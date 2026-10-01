package app.voxlibre;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recordings library: the on-ring file list, downloads and the local, persisted
 * index that lets the list survive offline. Owns download orchestration (including the
 * automatic .json duration prefetch) and reconciles ring vs phone state.
 *
 * <p>Sends BLE reads/lists/deletes through {@link Net} (the protocol) and pushes results
 * through {@link Out} (the UI).
 */
public class RingLibrary {

    /** Outbound BLE commands, served by RingProtocol. */
    public interface Net {
        boolean isConnected();
        void sendList();
        void sendRead(String name);
        void sendDelete(String name);
    }

    /** UI-facing outputs. */
    public interface Out {
        void log(String m);
        void toast(String m);
        void event(String m);
        void library(List<RingEngine.Rec> items);
    }

    private final Context ctx;
    private final Net net;
    private final Out out;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ring state
    final LinkedHashMap<String, Integer> files = new LinkedHashMap<>();
    LinkedHashMap<String, Integer> collecting = null;
    // durations parsed from .json, cached by id
    final HashMap<String, Integer> durById = new HashMap<>();
    // automatic .json prefetch queue
    final ArrayDeque<String> jsonQ = new ArrayDeque<>();
    boolean autoJson = false;
    // in-flight download
    ByteArrayOutputStream fileBuf = new ByteArrayOutputStream();
    String curFile = null;
    boolean downloading = false;

    public RingLibrary(Context ctx, Net net, Out out) {
        this.ctx = ctx;
        this.net = net;
        this.out = out;
        prefs = ctx.getSharedPreferences("ring", Context.MODE_PRIVATE);
    }

    public boolean isDownloading() {
        return downloading;
    }

    static String recId(String n) {
        Matcher m = Pattern.compile("amsg_(\\d+)_mono").matcher(n);
        return m.find() ? m.group(1) : null;
    }

    // ---- persisted index ----
    private org.json.JSONObject loadIdx() {
        try {
            return new org.json.JSONObject(prefs.getString("lib", "{}"));
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }
    private void saveIdx(org.json.JSONObject o) {
        prefs.edit().putString("lib", o.toString()).apply();
    }

    public void refreshLibrary() {
        org.json.JSONObject idx = loadIdx();
        if (net.isConnected()) {
            try { // reconcile ring state: files = current list
                java.util.Iterator<String> it = idx.keys();
                List<String> keys = new ArrayList<>();
                while (it.hasNext()) keys.add(it.next());
                for (String k : keys) idx.getJSONObject(k).put("onRing", false);
                for (Map.Entry<String, Integer> e : files.entrySet()) {
                    String id = recId(e.getKey());
                    if (id == null)
                        continue;
                    org.json.JSONObject o = idx.optJSONObject(id);
                    if (o == null) {
                        o = new org.json.JSONObject();
                        idx.put(id, o);
                    }
                    o.put("onRing", true);
                    if (e.getKey().endsWith(".opus"))
                        o.put("ringSize", (long) (int) e.getValue());
                    Integer dur = durById.get(id);
                    if (dur != null)
                        o.put("dur", dur);
                }
                saveIdx(idx);
            } catch (Exception e) {
                out.log("lib idx error: " + e);
            }
        }
        // local scan
        HashMap<String, Long> localSz = new HashMap<>();
        File[] fs = ctx.getFilesDir().listFiles((d, n) -> n.endsWith("_mono.opus"));
        if (fs != null)
            for (File f : fs) {
                String id = recId(f.getName());
                if (id != null)
                    localSz.put(id, f.length());
            }
        // union (index + local), newest on top
        TreeMap<Long, String> ids = new TreeMap<>(Collections.reverseOrder());
        try {
            java.util.Iterator<String> it = idx.keys();
            while (it.hasNext()) {
                String k = it.next();
                ids.put(Long.parseLong(k), k);
            }
        } catch (Exception ignore) {}
        for (String id : localSz.keySet()) ids.put(Long.parseLong(id), id);
        final List<RingEngine.Rec> outList = new ArrayList<>();
        for (String id : ids.values()) {
            String name = "amsg_" + id + "_mono.opus";
            boolean saved = localSz.containsKey(id);
            org.json.JSONObject o = idx.optJSONObject(id);
            boolean onRing = o != null && o.optBoolean("onRing", false);
            if (!saved && !onRing) {
                idx.remove(id);
                continue;
            } // gone from ring and not on phone -> drop
            long ringSize = o != null ? o.optLong("ringSize", -1) : -1;
            Integer dur = durById.get(id);
            if (dur == null && o != null && o.has("dur")) {
                dur = o.optInt("dur");
                durById.put(id, dur);
            } // cache so auto-json skips it
            if (dur == null) {
                File wav = new File(ctx.getFilesDir(), "amsg_" + id + "_mono.wav");
                if (wav.exists() && wav.length() > 44)
                    dur = (int) ((wav.length() - 44) * 1000L / 2 / 48000);
            }
            long size = saved ? localSz.get(id) : ringSize;
            outList.add(new RingEngine.Rec(id, name, size, dur, onRing, saved));
        }
        saveIdx(idx); // persist the cleanup of removed entries
        out.library(outList);
        main.post(this::autoQueueMissing);
    }

    public void deleteLocal(String id) {
        new File(ctx.getFilesDir(), "amsg_" + id + "_mono.opus").delete();
        new File(ctx.getFilesDir(), "amsg_" + id + "_mono.wav").delete();
        out.log("deleted local " + id);
        out.toast("deleted from phone");
        refreshLibrary();
    }

    private void autoQueueMissing() {
        for (String n : files.keySet()) {
            if (n.endsWith(".json")) {
                String id = recId(n);
                if (id != null && !durById.containsKey(id) && !jsonQ.contains(n))
                    jsonQ.add(n);
            }
        }
        pumpJson();
    }
    private void pumpJson() {
        if (downloading || jsonQ.isEmpty() || !net.isConnected())
            return;
        autoJson = true;
        reqFile(jsonQ.poll());
    }
    private void parseDur(String name, byte[] c) {
        String id = recId(name);
        if (id == null)
            return;
        // The file is a stream of comma-separated objects (not a single object nor an array):
        //   {"record":[...]},{"duration":["<start>","<end>"]},{"record":[...]}
        // Wrap in [] and find the object carrying "duration"; take the end (index 1) as ms.
        try {
            org.json.JSONArray events =
                    new org.json.JSONArray("[" + new String(c, StandardCharsets.UTF_8) + "]");
            for (int i = 0; i < events.length(); i++) {
                org.json.JSONArray dur = events.getJSONObject(i).optJSONArray("duration");
                if (dur != null && dur.length() >= 2) {
                    int ms = Integer.parseInt(dur.getString(1));
                    durById.put(id, ms);
                    out.log("duration " + id + "=" + ms + "ms");
                    return;
                }
            }
        } catch (org.json.JSONException e) {
            out.log("duration parse failed for " + id);
        }
    }

    // ---- downloads ----
    public void download(String name) {
        reqFile(name);
    }
    private void reqFile(String name) {
        curFile = name;
        downloading = true;
        fileBuf.reset();
        out.log((autoJson ? "auto-json " : "DOWNLOAD ") + name);
        if (!autoJson)
            out.toast("downloading " + name);
        net.sendRead(name);
    }
    private void saveFile() {
        try {
            if (curFile.endsWith(".json"))
                parseDur(curFile, fileBuf.toByteArray());
            if (!autoJson) {
                File out2 = new File(ctx.getFilesDir(), curFile);
                FileOutputStream fo = new FileOutputStream(out2);
                fo.write(fileBuf.toByteArray());
                fo.close();
                out.log("SAVED " + out2.getName() + " (" + fileBuf.size() + "B)");
                out.toast("saved: " + out2.getName());
            }
        } catch (Exception e) {
            out.log("save error: " + e);
        }
        downloading = false;
    }

    private final Runnable recDebounce = () -> {
        if (collecting != null) {
            files.clear();
            files.putAll(collecting);
            collecting = null;
        }
        refreshLibrary();
    };
    public void requestList() {
        collecting = new LinkedHashMap<>();
        net.sendList();
    }
    public void manualRefresh() {
        if (!net.isConnected()) {
            out.toast("connect first");
            return;
        }
        if (downloading) {
            out.toast("download in progress");
            return;
        }
        out.log("manual list refresh");
        out.toast("refreshing…");
        requestList();
    }
    public void deleteRing(String id) {
        if (!net.isConnected()) {
            out.toast("connect first");
            return;
        }
        List<String> names = new ArrayList<>();
        for (String n : files.keySet())
            if (id.equals(recId(n)))
                names.add(n);
        if (names.isEmpty()) {
            out.toast("not in the ring list");
            return;
        }
        for (String n : names) {
            out.log("DELETE on ring: 0x17 [02] " + n);
            net.sendDelete(n);
        }
        out.toast("deleting " + id + "…");
        main.postDelayed(() -> {
            if (net.isConnected() && !downloading)
                requestList();
        }, 900);
    }

    /** Fed by the protocol when a download-stream chunk arrives (DATA characteristic). */
    public void onFileData(byte[] chunk) {
        if (downloading)
            fileBuf.write(chunk, 0, chunk.length);
    }

    /** Recording-related control opcodes, dispatched by the protocol (RX already logged). */
    public void onCtrl(int op, byte[] d) {
        if (op == 0x11) {
            String asc = new String(d, StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("[A-Za-z0-9_]+_mono\\.(json|opus)").matcher(asc);
            if (m.find()) {
                int size = 0;
                if (d.length >= 5)
                    size = ((d[3] & 0xFF) << 8) | (d[4] & 0xFF);
                (collecting != null ? collecting : files).put(m.group(), size);
                main.removeCallbacks(recDebounce);
                main.postDelayed(recDebounce, 500);
            }
        } else if (op == 0x13) {
            String asc = new String(d, StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("[A-Za-z0-9_]+_mono\\.opus").matcher(asc);
            String f = m.find() ? m.group() : "?";
            if (!f.equals("?"))
                files.put(f, 0);
            out.event("BUTTON → new recording: " + f);
            out.log("BUTTON/recording: " + f);
            refreshLibrary();
            if (!downloading)
                requestList();
        } else if (op == 0x17 && d.length >= 2 && (d[0] & 0xFF) == 0x02) {
            int nl = d[1] & 0xFF;
            String nm = (d.length >= 2 + nl) ? new String(d, 2, nl, StandardCharsets.ISO_8859_1) : "?";
            out.log("delete OK on ring: " + nm);
        } else if (op == 0x17 && d.length >= 2) {
            int n = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            StringBuilder sb = new StringBuilder("queue(" + n + "):");
            int o = 2;
            for (int i = 0; i < n && o + 5 <= d.length; i++, o += 6) {
                long id = ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8)
                        | (d[o + 3] & 0xFF);
                sb.append(" #" + id + "(st" + (d[o + 4] & 0xFF) + ")");
            }
            out.log(sb.toString());
        } else if (op == 0x12 && d.length >= 1 && (d[0] & 0xFF) == 0x02 && downloading) {
            out.log("transfer complete " + curFile + " (" + fileBuf.size() + "B)");
            boolean wasAuto = autoJson;
            saveFile();
            autoJson = false;
            if (wasAuto) {
                if (jsonQ.isEmpty())
                    refreshLibrary();
                else
                    pumpJson();
            } // auto-json: no per-item rebuild; refresh only when the queue drains
            else {
                refreshLibrary();
                if (net.isConnected())
                    requestList();
            }
        }
    }

    /** Reset transient download/prefetch state on disconnect. */
    public void onDisconnected() {
        jsonQ.clear();
        autoJson = false;
    }
}
