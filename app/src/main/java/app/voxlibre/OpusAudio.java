package app.voxlibre;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Plays the ring's raw-Opus recordings: decode (.opus → PCM via MediaCodec) → WAV
 * cache → MediaPlayer. Self-contained; talks back only through {@link Sink}.
 *
 * Opus framing: [seq:u8][len:u8][opus packet], 48 kHz mono, 60 ms packets.
 */
public class OpusAudio {

    public interface Sink {
        void log(String m);
        void toast(String m);
    }

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;

    public OpusAudio(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
    }

    /** Decode (off the main thread) if needed, then play. {@code name} is the .opus file in filesDir. */
    public void play(String name) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) {
            sink.toast("download first");
            return;
        }
        final File wav = new File(ctx.getFilesDir(), name.replaceFirst("\\.opus$", ".wav"));
        if (wav.exists()) {
            playWav(wav);
            return;
        }
        sink.toast("decoding…");
        new Thread(() -> {
            try {
                byte[] pcm = decodeOpus(f);
                writeWav(wav, pcm);
                sink.log("decoded " + name + " (" + pcm.length + "B PCM / "
                        + String.format(Locale.US, "%.2f", pcm.length / 2 / 48000.0) + "s)");
                main.post(() -> playWav(wav));
            } catch (Exception e) {
                sink.log("decode failed: " + e);
                sink.toast("decode failed");
            }
        }).start();
    }

    public void stop() {
        if (mp != null) {
            mp.release();
            mp = null;
        }
    }

    private void playWav(File wav) {
        try {
            stop();
            mp = new MediaPlayer();
            mp.setDataSource(wav.getAbsolutePath());
            mp.setOnCompletionListener(m -> sink.log("play end"));
            mp.prepare();
            mp.start();
            sink.log("playing " + wav.getName());
            sink.toast("playing…");
        } catch (Exception e) {
            sink.log("play failed: " + e);
            sink.toast("not playable");
        }
    }

    private byte[] decodeOpus(File f) throws Exception {
        ByteArrayOutputStream fb = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int r;
        FileInputStream fi = new FileInputStream(f);
        while ((r = fi.read(tmp)) > 0) fb.write(tmp, 0, r);
        fi.close();
        byte[] d = fb.toByteArray();
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1)
                .put((byte) 1)
                .putShort((short) 120)
                .putInt(48000)
                .putShort((short) 0)
                .put((byte) 0);
        head.rewind();
        ByteBuffer delay = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        delay.putLong(2500000L);
        delay.rewind();
        ByteBuffer preroll = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        preroll.putLong(80000000L);
        preroll.rewind();
        MediaFormat fmt = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
        fmt.setByteBuffer("csd-0", head);
        fmt.setByteBuffer("csd-1", delay);
        fmt.setByteBuffer("csd-2", preroll);
        MediaCodec codec = MediaCodec.createDecoderByType("audio/opus");
        codec.configure(fmt, null, null, 0);
        codec.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        int off = 0;
        boolean inDone = false;
        long pts = 0;
        while (true) {
            if (!inDone) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    if (off + 2 <= d.length && off + 2 + (d[off + 1] & 0xFF) <= d.length) {
                        int len = d[off + 1] & 0xFF;
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        ib.clear();
                        ib.put(d, off + 2, len);
                        codec.queueInputBuffer(ii, 0, len, pts, 0);
                        pts += 60000;
                        off += 2 + len;
                    } else {
                        inDone = true;
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }
            }
            int oi = codec.dequeueOutputBuffer(bi, 10000);
            if (oi >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(oi);
                byte[] ch = new byte[bi.size];
                ob.position(bi.offset);
                ob.get(ch);
                out.write(ch);
                codec.releaseOutputBuffer(oi, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    break;
            }
        }
        codec.stop();
        codec.release();
        return out.toByteArray();
    }

    private void writeWav(File out, byte[] pcm) throws Exception {
        int rate = 48000, ch = 1, dataLen = pcm.length;
        ByteBuffer hh = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hh.put("RIFF".getBytes())
                .putInt(36 + dataLen)
                .put("WAVE".getBytes())
                .put("fmt ".getBytes())
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) ch)
                .putInt(rate)
                .putInt(rate * ch * 2)
                .putShort((short) (ch * 2))
                .putShort((short) 16)
                .put("data".getBytes())
                .putInt(dataLen);
        FileOutputStream fo = new FileOutputStream(out);
        fo.write(hh.array());
        fo.write(pcm);
        fo.close();
    }
}
