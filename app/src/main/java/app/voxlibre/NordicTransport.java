package app.voxlibre;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;
import java.util.UUID;
import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

/**
 * BLE transport backed by the Nordic Android-BLE-Library.
 *
 * Owns all the GATT plumbing that used to live in RingEngine: connection, MTU,
 * notification subscriptions and the serialized write queue (the library
 * serializes operations internally, so there is no manual queue here).
 *
 * RingEngine stays the protocol brain: it builds/parses the AA55 frames and
 * drives the auth handshake through the callbacks below. Frames arrive raw and
 * writes take an already-encoded frame.
 *
 * The previous raw-GATT implementation is kept in reference/RingEngine.rawgatt.java.bak.
 */
public class NordicTransport extends BleManager {

    /** Events raised back to the protocol layer (RingEngine). Called on the main thread. */
    public interface Listener {
        void onConnected();          // link up, negotiating
        void onAuthReady();          // AUTH notifications enabled: start the handshake
        void onAuthFrame(byte[] raw);
        void onCtrlFrame(byte[] raw);
        void onData(byte[] raw);     // download stream (DATA characteristic)
        void onControlReady();       // CTRL+DATA notifications enabled: telemetry can start
        void onDisconnected();
        void log(String msg);
    }

    private final Listener l;
    private final UUID svc, auth, ctrlW, ctrlN, dsvc, dataN;
    private BluetoothGattCharacteristic authCh, ctrlWCh, ctrlNCh, dataNCh;

    public NordicTransport(Context ctx, Listener l,
                           UUID svc, UUID auth, UUID ctrlW, UUID ctrlN, UUID dsvc, UUID dataN) {
        super(ctx);
        this.l = l;
        this.svc = svc; this.auth = auth; this.ctrlW = ctrlW; this.ctrlN = ctrlN;
        this.dsvc = dsvc; this.dataN = dataN;
        setConnectionObserver(connObs);
    }

    // ---------- connection ----------
    public void start(BluetoothDevice dev) {
        connect(dev).useAutoConnect(false).timeout(15000).retry(2, 100).enqueue();
    }
    public void stop() {
        disconnect().enqueue();
    }

    // ---------- writes (frame already encoded by RingEngine) ----------
    public void writeAuth(byte[] frame) {
        if (authCh != null)
            writeCharacteristic(authCh, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
    }
    public void writeCtrl(byte[] frame) {
        if (ctrlWCh != null)
            writeCharacteristic(ctrlWCh, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
    }

    /** After auth: subscribe CTRL-notify (+ DATA when present), then signal onControlReady. */
    public void enableControlChannels() {
        setNotificationCallback(ctrlNCh).with((d, data) -> l.onCtrlFrame(data.getValue()));
        if (dataNCh != null) {
            enableNotifications(ctrlNCh).enqueue();
            setNotificationCallback(dataNCh).with((d, data) -> l.onData(data.getValue()));
            enableNotifications(dataNCh).done(d -> l.onControlReady()).enqueue();
        } else {
            enableNotifications(ctrlNCh).done(d -> l.onControlReady()).enqueue();
        }
    }

    // ---------- library callbacks ----------
    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new BleManagerGattCallback() {
            @Override
            protected boolean isRequiredServiceSupported(BluetoothGatt gatt) {
                BluetoothGattService s = gatt.getService(svc);
                if (s == null) { l.log("service not found"); return false; }
                authCh = s.getCharacteristic(auth);
                ctrlWCh = s.getCharacteristic(ctrlW);
                ctrlNCh = s.getCharacteristic(ctrlN);
                BluetoothGattService ds = gatt.getService(dsvc);
                dataNCh = (ds != null) ? ds.getCharacteristic(dataN) : null;
                return authCh != null && ctrlWCh != null && ctrlNCh != null;
            }
            @Override
            protected void initialize() {
                requestMtu(247).enqueue();
                setNotificationCallback(authCh).with((d, data) -> l.onAuthFrame(data.getValue()));
                enableNotifications(authCh).done(d -> l.onAuthReady()).enqueue();
            }
            @Override
            protected void onServicesInvalidated() {
                authCh = ctrlWCh = ctrlNCh = dataNCh = null;
            }
        };
    }

    private final ConnectionObserver connObs = new ConnectionObserver() {
        @Override public void onDeviceConnecting(BluetoothDevice d) {}
        @Override public void onDeviceConnected(BluetoothDevice d) { l.onConnected(); }
        @Override public void onDeviceFailedToConnect(BluetoothDevice d, int r) {
            l.log("connect failed reason=" + r);
            l.onDisconnected();
        }
        @Override public void onDeviceReady(BluetoothDevice d) {}   // handshake driven by onAuthReady
        @Override public void onDeviceDisconnecting(BluetoothDevice d) {}
        @Override public void onDeviceDisconnected(BluetoothDevice d, int r) { l.onDisconnected(); }
    };

    // ---------- forward the library's internal logs into our log panel ----------
    @Override public int getMinLogPriority() { return Log.DEBUG; }
    @Override public void log(int priority, String message) { l.log("[ble] " + message); }
}
