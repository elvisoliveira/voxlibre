package app.voxlibre;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-ring identity, persisted in SharedPreferences.
 *
 * <p>MAC comes from selecting a bonded device; SN/FWID are parsed from the 0x80
 * device-info frame. BIND_KEY / USERID are secrets minted by Vocci's servers at
 * pairing time — the user enters them in Settings (never hardcoded/shipped).
 */
public class RingConfig {

    public interface Sink {
        void log(String m);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Sink sink;
    private String mac, sn, fwid, bindKey, userId;

    public RingConfig(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
        prefs = ctx.getSharedPreferences("ring", Context.MODE_PRIVATE);
        mac = prefs.getString("mac", "");
        sn = prefs.getString("sn", "");
        fwid = prefs.getString("fwid", "");
        bindKey = prefs.getString("bindKey", "");
        userId = prefs.getString("userId", "");
    }

    /** A ring is selected. */
    public boolean isConfigured() {
        return mac != null && !mac.isEmpty();
    }
    /** Server-minted credentials are present (required to authenticate). */
    public boolean hasCredentials() {
        return bindKey != null && !bindKey.isEmpty() && userId != null && !userId.isEmpty();
    }
    public String mac() {
        return mac;
    }
    public String sn() {
        return sn;
    }
    public String fwid() {
        return fwid;
    }
    public String bindKey() {
        return bindKey;
    }
    public String userId() {
        return userId;
    }

    public void setMac(String m) {
        mac = m;
        prefs.edit().putString("mac", m).apply();
        sink.log("ring selected: " + m);
    }
    public void setBindKey(String v) {
        bindKey = v == null ? "" : v.trim();
        prefs.edit().putString("bindKey", bindKey).apply();
    }
    public void setUserId(String v) {
        userId = v == null ? "" : v.trim();
        prefs.edit().putString("userId", userId).apply();
    }

    /** rings paired on the phone (name, mac) — our discovery path (the ring is already bonded). */
    public List<String[]> bondedDevices() {
        List<String[]> out = new ArrayList<>();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            for (BluetoothDevice d : bm.getAdapter().getBondedDevices()) {
                out.add(new String[] {d.getName() != null ? d.getName() : "(no name)", d.getAddress()});
            }
        } catch (SecurityException e) {
            sink.log("no BT permission to list paired devices");
        }
        return out;
    }

    /** 0x80: [snLen][SN][macLen][MAC rev][fwLen][FWID] — updates SN/FWID and persists them. */
    public void parseDeviceInfo(byte[] d) {
        try {
            int o = 0;
            int snLen = d[o++] & 0xFF;
            if (o + snLen > d.length)
                return;
            sn = new String(d, o, snLen, StandardCharsets.US_ASCII);
            o += snLen;
            int macLen = d[o++] & 0xFF;
            o += macLen; // MAC (byte order flipped) — we already have it from connect
            if (o < d.length) {
                int fwLen = d[o++] & 0xFF;
                if (o + fwLen <= d.length)
                    fwid = new String(d, o, fwLen, StandardCharsets.US_ASCII);
            }
            sink.log("device-info: SN=" + sn + " FWID=" + fwid);
            prefs.edit().putString("sn", sn).putString("fwid", fwid).apply();
        } catch (Exception e) {
            sink.log("device-info parse failed: " + e);
        }
    }
}
