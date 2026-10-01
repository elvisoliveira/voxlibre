package app.voxlibre;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import java.util.Locale;

/**
 * Ring button input.
 *
 * <p>Two independent paths:
 * <ul>
 *   <li>the 0x26 press stream (a repeating counter while the button is held) is
 *       turned into PRESSED / SINGLE / DOUBLE / HOLD gestures — a monitor only;
 *   <li>a single click (from 0xf0 / 0x15) optionally emits a media Play/Pause key.
 * </ul>
 */
public class RingGestures {

    public interface Sink {
        void log(String m);
        void gesture(String text);
    }

    private static final long HOLD_MS = 700, DOUBLE_MS = 400, RELEASE_MS = 300;

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 0x26 state machine
    private int lastC26 = -1;
    private long lastT26 = 0;
    private boolean pressing = false, pendingSingle = false;
    private long pressStart = 0, lastTickT = 0;
    private int pressTicks = 0;
    private final Runnable releaseTask = this::onRelease;
    private final Runnable singleTask = () -> {
        if (pendingSingle) {
            pendingSingle = false;
            onGesture("SINGLE", null);
        }
    };

    // media Play/Pause (single click)
    private boolean mediaToggle = false;
    private long lastMediaKey = 0;

    public RingGestures(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
    }

    public void setMediaToggle(boolean on) {
        mediaToggle = on;
        sink.log("play/pause " + (on ? "ON" : "OFF"));
    }

    /** Reset transient state on disconnect. */
    public void reset() {
        lastC26 = -1;
        pressing = false;
        pendingSingle = false;
        main.removeCallbacks(releaseTask);
        main.removeCallbacks(singleTask);
    }

    /** Feed one 0x26 button-pressed tick (payload byte). */
    public void feed26(int val) {
        long now = System.currentTimeMillis();
        String extra = "";
        if (lastC26 >= 0) {
            int dv = val - lastC26;
            if (dv < 0)
                dv += 256;
            long dt = now - lastT26;
            double hz = dt > 0 ? 1000.0 / dt : 0;
            extra = " Δ=" + dv + " dt=" + dt + "ms ~" + String.format(Locale.US, "%.1f", hz) + "Hz";
        }
        lastC26 = val;
        lastT26 = now;
        sink.log("RX [ctrl] op=0x26 val=" + val + " (0x" + Integer.toHexString(val) + ")" + extra);
        final long t = now;
        main.post(() -> trackPress(t));
    }

    private void trackPress(long t) {
        if (!pressing) {
            pressing = true;
            pressStart = t;
            pressTicks = 0;
            sink.gesture("button: PRESSED…");
        }
        pressTicks++;
        lastTickT = t;
        main.removeCallbacks(releaseTask);
        main.postDelayed(releaseTask, RELEASE_MS);
    }

    private void onRelease() {
        pressing = false;
        long dur = lastTickT - pressStart;
        if (dur >= HOLD_MS) {
            pendingSingle = false;
            onGesture("HOLD", "(" + dur + "ms, " + pressTicks + " ticks)");
        } else if (pendingSingle) {
            pendingSingle = false;
            main.removeCallbacks(singleTask);
            onGesture("DOUBLE", null);
        } else {
            pendingSingle = true;
            main.postDelayed(singleTask, DOUBLE_MS);
        }
    }

    private void onGesture(final String type, final String detail) {
        sink.log("GESTURE(0x26): " + type + (detail != null ? " " + detail : ""));
        sink.gesture("Last gesture: " + type + (detail != null ? "  " + detail : ""));
    }

    /** A single click (0xf0 / 0x15): fire Play/Pause when the feature is enabled. */
    public void singleClick() {
        if (!mediaToggle) {
            sink.log("single click (play/pause feature off)");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMediaKey < 400) {
            sink.log("click ignored (debounce)");
            return;
        }
        lastMediaKey = now;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        sink.log("PLAY/PAUSE sent (single click)");
    }
}
