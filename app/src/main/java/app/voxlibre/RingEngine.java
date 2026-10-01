package app.voxlibre;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Facade over the ring modules, headless (no Views). Wires them together and exposes a
 * flat API to the UI; state is emitted through the {@link Ui} callbacks.
 *
 * <ul>
 *   <li>{@link RingConfig} — per-ring identity (mac/sn/fwid)
 *   <li>{@link RingProtocol} — transport, framing, auth, telemetry
 *   <li>{@link RingLibrary} — recordings list, downloads, local index
 *   <li>{@link RingGestures} — button input (0x26 stream + single-click media)
 *   <li>{@link OpusAudio} — recording playback
 * </ul>
 */
public class RingEngine implements RingProtocol.Sink, RingLibrary.Net, RingLibrary.Out {
    static final String TAG = "VoxLibre";

    /** Model of a recording (on ring and/or downloaded). */
    public static class Rec {
        public final String id, name;
        public final long sizeOpus;
        public final Integer durationMs;
        public final boolean onRing, savedLocal;
        public Rec(String id, String name, long sizeOpus, Integer durationMs, boolean onRing,
                boolean savedLocal) {
            this.id = id;
            this.name = name;
            this.sizeOpus = sizeOpus;
            this.durationMs = durationMs;
            this.onRing = onRing;
            this.savedLocal = savedLocal;
        }
    }
    /** Callbacks for the UI (called on the main thread). */
    public interface Ui {
        void status(String text, boolean connected);
        void device(String model, String fw, String hw, String sn);
        void telem(int battPct, int battVolt, int battStatus, int rssiHost, long storFree, long storTot);
        void event(String text);
        void gesture(String text);
        void library(List<Rec> items); // unified list (ring + local)
        void logLine(String line, boolean unmapped);
        void toast(String text);
    }

    private final Context ctx;
    private final Ui ui;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final RingConfig config;
    private final OpusAudio audio;
    private final RingGestures gestures;
    private final RingLibrary library;
    private final RingProtocol protocol;

    public RingEngine(Context context, Ui ui) {
        this.ctx = context.getApplicationContext();
        this.ui = ui;
        config = new RingConfig(ctx, this::log);
        audio = new OpusAudio(ctx, new OpusAudio.Sink() {
            public void log(String m) { RingEngine.this.log(m); }
            public void toast(String m) { RingEngine.this.toast(m); }
        });
        gestures = new RingGestures(ctx, new RingGestures.Sink() {
            public void log(String m) { RingEngine.this.log(m); }
            public void gesture(String t) { ui(() -> RingEngine.this.ui.gesture(t)); }
        });
        library = new RingLibrary(ctx, this, this);                        // Net + Out
        protocol = new RingProtocol(ctx, config, gestures, library, this); // Sink
    }

    // ---------- public API (used by the ViewModel/UI) ----------
    public boolean isConfigured() { return config.isConfigured(); }
    public boolean hasCredentials() { return config.hasCredentials(); }
    public String getMac() { return config.mac(); }
    public void setMac(String m) { config.setMac(m); }
    public String getBindKey() { return config.bindKey(); }
    public void setBindKey(String v) { config.setBindKey(v); }
    public String getUserId() { return config.userId(); }
    public void setUserId(String v) { config.setUserId(v); }
    public List<String[]> bondedDevices() { return config.bondedDevices(); }

    public boolean isDownloading() { return library.isDownloading(); }
    public void toggleConnect() { protocol.toggleConnect(); }
    public void setMediaToggle(boolean on) { gestures.setMediaToggle(on); }

    public void refreshLibrary() { library.refreshLibrary(); }
    public void manualRefresh() { library.manualRefresh(); }
    public void download(String name) { library.download(name); }
    public void deleteRing(String id) { library.deleteRing(id); }
    public void deleteLocal(String id) { library.deleteLocal(id); }

    public void play(String name) { audio.play(name); }
    public void stopPlay() { audio.stop(); }

    // ---------- RingProtocol.Sink ----------
    @Override public void status(String text) {
        final boolean c = protocol.isConnected();
        ui(() -> ui.status(text, c));
    }
    @Override public void device(String model, String fw, String hw, String sn) {
        ui(() -> ui.device(model, fw, hw, sn));
    }
    @Override public void telem(int batt, int volt, int st, int rssi, long free, long tot) {
        ui(() -> ui.telem(batt, volt, st, rssi, free, tot));
    }

    // ---------- RingLibrary.Net (delegates to the protocol) ----------
    // isConnected() below serves both the public API and RingLibrary.Net.
    @Override public boolean isConnected() { return protocol.isConnected(); }
    @Override public void sendList() { protocol.sendList(); }
    @Override public void sendRead(String name) { protocol.sendRead(name); }
    @Override public void sendDelete(String name) { protocol.sendDelete(name); }

    // ---------- shared outputs (Sink + Out) ----------
    @Override public void library(List<Rec> items) {
        ui(() -> ui.library(items));
    }
    @Override public void event(String text) {
        ui(() -> ui.event(text));
    }
    @Override public void toast(final String s) {
        ui(() -> ui.toast(s));
    }
    @Override public void log(String m) {
        logRaw(m, false);
    }
    @Override public void logRaw(final String m, final boolean unmapped) {
        Log.i(TAG, m);
        final String line = ts.format(new Date()) + "  " + m;
        ui(() -> ui.logLine(line, unmapped));
    }

    private void ui(final Runnable r) {
        mainHandler.post(r);
    }
}
