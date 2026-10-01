package app.voxlibre;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Timon/URING wire protocol: connection lifecycle, AA55 framing, the nested
 * HMAC auth handshake and telemetry/device-status decoding.
 *
 * <p>Owns the {@link NordicTransport}. Recording-related control opcodes are handed to
 * {@link RingLibrary}; button input to {@link RingGestures}; device/telemetry/status and
 * logs go up through {@link Sink}. Also serves the library's outbound BLE commands
 * (list/read/delete).
 */
public class RingProtocol implements RingLibrary.Net {

    /** Device-status + UI outputs. */
    public interface Sink {
        void status(String text);
        void device(String model, String fw, String hw, String sn);
        void telem(int batt, int volt, int st, int rssi, long free, long tot);
        void event(String text);
        void toast(String text);
        void log(String m);
        void logRaw(String m, boolean unmapped);
    }

    static final UUID SVC = UUID.fromString("b75497db-806e-42b1-9e60-5871ca2e504b");
    static final UUID AUTH = UUID.fromString("b75497de-806e-42b1-9e60-5871ca2e504b");
    static final UUID CTRLW = UUID.fromString("b75497dc-806e-42b1-9e60-5871ca2e504b");
    static final UUID CTRLN = UUID.fromString("b75497dd-806e-42b1-9e60-5871ca2e504b");
    static final UUID DSVC = UUID.fromString("e49a3001-f69a-11e8-8eb2-f2801f1b9fd1");
    static final UUID DATAN = UUID.fromString("e49a3003-f69a-11e8-8eb2-f2801f1b9fd1");
    static final Set<Integer> KNOWN_CTRL = new HashSet<>(Arrays.asList(
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x25, 0x26, 0x2a, 0x2d, 0x30, 0xf0, 0xf2, 0xfa));

    private final Context ctx;
    private final RingConfig config;
    private final RingGestures gestures;
    private final RingLibrary library;
    private final Sink sink;
    private NordicTransport transport;

    private int seq = 1, step = 0;
    private boolean connected = false; // authenticated
    private boolean active = false;    // connection initiated (connecting or up)

    // device/telemetry state (decoded from 0xf2 / 0x14)
    private String model = "?", fwVer = "?", hwVer = "?";
    private int battPct = -1, battVolt = 0, battSt = 0, rssiHost = 0;
    private long storFree = -1, storTot = 0;

    public RingProtocol(Context ctx, RingConfig config, RingGestures gestures,
                        RingLibrary library, Sink sink) {
        this.ctx = ctx;
        this.config = config;
        this.gestures = gestures;
        this.library = library;
        this.sink = sink;
        transport = new NordicTransport(ctx, transportListener,
                SVC, AUTH, CTRLW, CTRLN, DSVC, DATAN);
    }

    public boolean isConnected() {
        return connected;
    }

    // ---------- connection ----------
    public void toggleConnect() {
        if (!active) {
            if (!config.isConfigured()) {
                sink.toast("select a ring first");
                sink.status("disconnected");
                return;
            }
            if (!config.hasCredentials()) {
                sink.toast("enter BIND KEY and USER ID in Settings");
                sink.status("disconnected");
                return;
            }
            active = true;
            sink.status("connecting…");
            sink.log("=== connecting " + config.mac() + " ===");
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothDevice dev = bm.getAdapter().getRemoteDevice(config.mac());
            sink.log("bondState=" + dev.getBondState() + " (12=BONDED)");
            transport.start(dev);
        } else {
            sink.log("disconnecting");
            transport.stop();
        }
    }

    // ---------- framing / crypto ----------
    private byte[] enc(int op, byte[] d) {
        seq = (seq + 1) & 0xFFFF;
        if (seq == 0)
            seq = 1;
        int ln = d.length + 1;
        byte[] f = new byte[7 + d.length + 3];
        f[0] = (byte) 0xAA;
        f[1] = 0x55;
        f[2] = (byte) (seq >> 8);
        f[3] = (byte) seq;
        f[4] = (byte) (ln >> 8);
        f[5] = (byte) ln;
        f[6] = (byte) op;
        System.arraycopy(d, 0, f, 7, d.length);
        f[7 + d.length] = 0;
        f[8 + d.length] = 0x0D;
        f[9 + d.length] = 0x0A;
        return f;
    }
    static String hex(byte[] a) {
        StringBuilder s = new StringBuilder();
        for (byte x : a) s.append(String.format("%02x", x));
        return s.toString();
    }
    static String hmacHex(String k, String m) {
        try {
            Mac x = Mac.getInstance("HmacSHA256");
            x.init(new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(x.doFinal(m.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private String kh() {
        return hex(config.bindKey().getBytes(StandardCharsets.UTF_8));
    }
    private String content(String n) {
        return config.fwid() + "_" + config.sn() + "_" + config.mac() + "_" + n;
    }

    private void wAuth(int op, byte[] d, String lb) {
        sink.log("TX [auth] op=0x" + Integer.toHexString(op) + " " + lb + " " + hex(d));
        transport.writeAuth(enc(op, d));
    }
    private void wCtrl(int op, byte[] d, String lb) {
        sink.log("TX [ctrl] op=0x" + Integer.toHexString(op) + " " + lb);
        transport.writeCtrl(enc(op, d));
    }
    private void startTelemetry() {
        wCtrl(0x30, new byte[] {0x01}, "init");
        wCtrl(0x25, new byte[0], "status");
        library.requestList();
        wCtrl(0x2a, new byte[] {0, 0}, "telemetry");
    }

    // ---------- RingLibrary.Net (outbound file commands) ----------
    @Override
    public void sendList() {
        wCtrl(0x11, new byte[0], "list");
    }
    @Override
    public void sendRead(String name) {
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[2 + nm.length + 5];
        d[0] = 1;
        d[1] = (byte) nm.length;
        System.arraycopy(nm, 0, d, 2, nm.length);
        wCtrl(0x12, d, "read " + name);
    }
    @Override
    public void sendDelete(String name) {
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[2 + nm.length];
        p[0] = 0x02;
        p[1] = (byte) nm.length;
        System.arraycopy(nm, 0, p, 2, nm.length);
        wCtrl(0x17, p, "delete " + name);
    }

    private void refreshInfo() {
        sink.device(has(model) ? model : "", has(fwVer) ? fwVer : "", has(hwVer) ? hwVer : "", config.sn());
    }
    private void refreshTelem() {
        sink.telem(battPct, battVolt, battSt, rssiHost, storFree, storTot);
    }
    static boolean has(String s) {
        return s != null && !s.equals("?") && !s.isEmpty();
    }

    // ---------- transport events ----------
    private final NordicTransport.Listener transportListener = new NordicTransport.Listener() {
        @Override
        public void onConnected() {
            sink.status("connected, negotiating…");
        }
        @Override
        public void onAuthReady() { // AUTH notifications enabled: begin the handshake
            step = 0;
            wAuth(0x82, new byte[] {0x05}, "proto");
        }
        @Override
        public void onAuthFrame(byte[] v) {
            handleAuth(frameOp(v), frameData(v));
        }
        @Override
        public void onCtrlFrame(byte[] v) {
            handleCtrl(frameOp(v), frameData(v));
        }
        @Override
        public void onData(byte[] v) {
            library.onFileData(v);
        }
        @Override
        public void onControlReady() {
            sink.log("ready: telemetry+list");
            startTelemetry();
        }
        @Override
        public void onDisconnected() {
            connected = false;
            active = false;
            sink.status("disconnected");
            gestures.reset();
            library.onDisconnected();
            library.refreshLibrary();
        }
        @Override
        public void log(String msg) {
            sink.log(msg);
        }
    };

    // AA55 frame: [AA][55][seqHi][seqLo][lenHi][lenLo][op][payload…][chk][0D][0A]
    static int frameOp(byte[] v) {
        return (v.length >= 7) ? (v[6] & 0xFF) : -1;
    }
    static byte[] frameData(byte[] v) {
        return (v.length >= 10)
                ? Arrays.copyOfRange(v, 7, 7 + ((v[4] & 0xFF) << 8 | (v[5] & 0xFF)) - 1)
                : new byte[0];
    }

    private void handleAuth(int op, byte[] data) {
        sink.log("RX [auth] op=0x" + Integer.toHexString(op) + " " + hex(data));
        if (op == 0x80 && data.length > 10) {
            config.parseDeviceInfo(data);
            refreshInfo();
        }
        if (op == 0x82 && data.length >= 1 && (data[0] & 0xFF) == 0x01) {
            String body = new String(data, 2, data.length - 2, StandardCharsets.UTF_8);
            String[] p = body.split("@");
            String nonce = p[0], dauth = p.length > 1 ? p[1] : "";
            boolean ok = hmacHex(kh(), content(nonce)).equals(dauth);
            sink.log("challenge nonce=" + nonce + " verifyRing=" + ok);
            String an = "app_" + nonce, inner = hmacHex(kh(), an), aa = hmacHex(inner, content(an));
            byte[] anb = an.getBytes(StandardCharsets.UTF_8), aab = aa.getBytes(StandardCharsets.UTF_8);
            byte[] pl = new byte[2 + anb.length + 1 + aab.length];
            pl[0] = 3;
            pl[1] = (byte) anb.length;
            System.arraycopy(anb, 0, pl, 2, anb.length);
            pl[2 + anb.length] = '@';
            System.arraycopy(aab, 0, pl, 3 + anb.length, aab.length);
            wAuth(0x82, pl, "app response");
            step = 99;
            return;
        }
        if (step == 99 && op == 0x82 && data.length >= 1 && (data[0] & 0xFF) == 0x03) {
            sink.log("APP-AUTH ACCEPTED");
            connected = true;
            sink.status("authenticated");
            refreshInfo();
            refreshTelem();
            library.refreshLibrary();
            transport.enableControlChannels();
            return;
        }
        if (step == 0) {
            step = 1;
            wAuth(0x80, new byte[0], "device info");
        } else if (step == 1) {
            byte[] uid = config.userId().getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[2 + uid.length];
            p[0] = 3;
            p[1] = (byte) uid.length;
            System.arraycopy(uid, 0, p, 2, uid.length);
            step = 2;
            wAuth(0x81, p, "userId");
        } else if (step == 2) {
            step = 3;
            wAuth(0x82, new byte[] {0x01}, "request challenge");
        }
    }

    private void handleCtrl(int op, byte[] d) {
        if (op == 0x26 && d.length >= 1) {
            gestures.feed26(d[0] & 0xFF);
            return;
        }
        sink.logRaw("RX [ctrl] op=0x" + Integer.toHexString(op) + " " + hex(d), !KNOWN_CTRL.contains(op));
        if (op == 0x14 && d.length >= 12) {
            storFree = ((long) (d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8)
                    | (d[3] & 0xFF);
            storTot = ((long) (d[8] & 0xFF) << 24) | ((d[9] & 0xFF) << 16) | ((d[10] & 0xFF) << 8)
                    | (d[11] & 0xFF);
            refreshTelem();
        } else if (op == 0xfa) {
            long t = d.length >= 4 ? (((long) (d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16)
                                             | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF))
                                   : 0;
            sink.log("ring RTC = " + t + (t > 1000000000L ? " (" + new Date(t * 1000L) + ")" : ""));
        } else if (op == 0x2a) { /* métrica; bateria vem do 0xf2 */
        } else if (op == 0xf2 && d.length >= 12) {
            hwVer = ((d[1] >> 4) & 15) + "." + (d[1] & 15) + "." + (d[2] & 0xFF);
            fwVer = ((d[3] >> 4) & 15) + "." + (d[3] & 15) + "."
                    + String.format(Locale.US, "%02d", d[4] & 0xFF);
            int battStatus = d[5] & 0xFF, batt = d[6] & 0xFF, slaveRssi = 256 - (d[8] & 0xFF),
                hostRssi = 256 - (d[9] & 0xFF);
            int volt = ((d[10] & 0xFF) << 8) | (d[11] & 0xFF);
            if (d.length > 14) {
                int ml = d[14] & 0xFF;
                if (ml > 0 && d.length >= 15 + ml)
                    model = new String(d, 15, ml, StandardCharsets.ISO_8859_1);
            }
            battPct = batt;
            battVolt = volt;
            battSt = battStatus;
            rssiHost = hostRssi;
            refreshInfo();
            refreshTelem();
        } else if (op == 0xf0) {
            boolean single = d.length >= 1 && (d[0] & 0xFF) == 0x01;
            final String eh = hex(d);
            sink.event((single ? "Single click" : "Gesture 0xf0") + ": " + eh);
            if (single)
                gestures.singleClick();
        } else if (op == 0x15 && d.length >= 2) {
            int code = d[0] & 0xFF;
            boolean pressed = (d[1] & 0xFF) != 0;
            sink.log("BUTTON(0x15) code=" + code + " pressed=" + pressed);
            if (code == 0x01 && pressed)
                gestures.singleClick();
        } else if (op == 0x16) {
            sink.log("heartbeat(0x16)");
        } else if (op == 0x18 && d.length >= 3) {
            int code = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            String msg = "";
            if (d.length > 5) {
                int ln = ((d[3] & 0xFF) << 8) | (d[4] & 0xFF);
                if (ln > 0 && d.length >= 5 + ln)
                    msg = new String(d, 5, ln, StandardCharsets.UTF_8);
            }
            sink.event("FAULT(0x18) code=" + code + (msg.isEmpty() ? "" : " " + msg));
            sink.log("deviceFault code=" + code + " " + msg);
        } else if (op == 0x11 || op == 0x13 || op == 0x17 || op == 0x12) {
            library.onCtrl(op, d); // recordings list / new recording / delete ack / transfer complete
        }
    }
}
