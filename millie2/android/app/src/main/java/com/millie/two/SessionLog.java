package com.millie.two;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Logging subsystem (PASSIVE_RESEARCH.md §3) — full-capture, crash-proof, per-session.
 *
 *  Every observation (every AP, every BLE and Classic advert — including randomized-address devices and
 *  every SSID) is appended as one JSON object per line (NDJSON) to a per-session file in
 *  {@code filesDir/logs/session_<id>.ndjson} as it arrives, and flushed per scan batch on a background
 *  writer thread. There is no in-memory buffer of the session: every flushed line is already in the
 *  kernel's file and survives a background-app kill (routine on the A12); at most the sub-second
 *  in-flight burst is lost. A write error (e.g. a full disk) sets {@link #failed()} and freezes the
 *  counter instead of silently dropping — the writer thread never dies, and recovers if the error
 *  clears. flush() is not fsync(): data survives a process kill, not a sudden power loss (out of scope).
 *
 *  Export (CSV / KML / JSON) is streamed FROM the persisted file one line at a time (no whole-file
 *  buffering), so a multi-MB capture exports without OOM. A "log everything" toggle (default on) vs
 *  "flagged only" filters at capture time. The hard rule holds upstream in
 *  MainActivity.buildObservation: a randomized / locally-administered MAC gets an address_type and NO
 *  vendor attribution.
 *
 *  Duress ({@link #wipe}) deletes the on-disk session NDJSON file(s) AND every exported file, and marks
 *  the log wiped so an export racing the wipe writes nothing (or deletes what it just wrote). The log
 *  lives in internal app-private storage and carries GPS in many rows; the repo .gitignore excludes
 *  logs/, *.ndjson, *.db and the export patterns as a second line of defence.
 *
 *  shortcut: one session file per app launch (append mode is only defensive); files accumulate, bounded
 *  only by duress. Add an age/size cap if device storage becomes a concern. */
final class SessionLog {
    static final int QUEUE_CAP = 10000;   // transient hand-off to the writer; drops (never grows) if the disk stalls
    final File dir, file;
    final String sessionId;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAP);
    private final Thread writer;
    volatile boolean logEverything;
    private volatile boolean running = true;
    private volatile boolean failed = false;
    private volatile boolean wiped = false;
    private volatile long written = 0;

    SessionLog(Context ctx, boolean logEverything) {
        this.logEverything = logEverything;
        File base = new File(ctx.getFilesDir(), "logs");
        base.mkdirs();
        this.dir = base;
        this.sessionId = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        this.file = new File(base, "session_" + sessionId + ".ndjson");
        writer = new Thread(this::drain, "session-log");
        writer.setDaemon(true);
        writer.start();
    }

    /** Append one observation. {@code flagged} = the device carries any threat/classification flag.
     *  Returns immediately; the write happens on the background writer thread. */
    void record(JSONObject obs, boolean flagged) {
        if (obs == null || !running) return;
        if (!logEverything && !flagged) return;
        queue.offer(obs.toString());   // bounded: a stalled writer (disk full) drops here rather than growing memory
    }

    /** true once a write has failed and not yet recovered — surfaced in the UI so silent loss is visible. */
    boolean failed() { return failed; }

    private BufferedWriter open() { try { return new BufferedWriter(new FileWriter(file, true)); } catch (Exception e) { return null; } }

    private void drain() {
        BufferedWriter w = open();
        ArrayList<String> batch = new ArrayList<>();
        while (running || !queue.isEmpty()) {
            String first;
            try { first = queue.poll(1, TimeUnit.SECONDS); } catch (InterruptedException ie) { continue; }
            if (first == null) continue;
            batch.clear(); batch.add(first); queue.drainTo(batch);       // take the rest of this scan burst
            if (w == null) w = open();                                   // reopen after a prior failure
            if (w == null) { failed = true; continue; }                  // still cannot open (e.g. disk full) — flag and drop; the queue is bounded
            try {
                for (String line : batch) { w.write(line); w.write('\n'); written++; }
                w.flush();                                               // per-batch flush: durable against a background-app kill
                failed = false;                                          // a write succeeded — recovered
            } catch (Exception e) {
                failed = true;                                           // signal, do NOT die: one bad write can't stop the whole session's logging
                try { w.close(); } catch (Exception ignored) {}
                w = null;                                                // force a fresh open on the next batch
            }
        }
        if (w != null) try { w.close(); } catch (Exception ignored) {}
    }

    long size() { return written; }

    /** Stop the writer, draining whatever is queued, and close the file before returning. */
    void stop() { running = false; try { writer.join(3000); } catch (InterruptedException ignored) {} }

    // ---- export: streamed FROM the persisted NDJSON file; call on a background thread ----
    static final String[] COLS = {"iso_time", "session_id", "source", "kind", "mac", "address_type", "vendor",
            "classification", "evidence", "threat", "threat_evidence", "ssid_or_name", "rssi", "frequency", "channel", "security",
            "manufacturer", "services", "service_data", "lat", "lon", "gps_accuracy_m"};

    // WigleWifi-1.4 interchange (Kismet imports/exports it; WiGLE reads it). Text fields are RFC4180-quoted so
    // commas inside an SSID can't shift columns; numbers are bare. No formula-injection guard here — this file
    // is parsed by tools, never opened in a spreadsheet, so SSID fidelity matters more than Excel safety.
    // AltitudeMeters is always blank (the phone API gives us no altitude). Type: WIFI / BLE / BT (Classic).
    static final String WIGLE_HEADER =
            "WigleWifi-1.4,appRelease=SWIVEL,model=,release=,device=,display=,board=,brand=\n"
            + "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n";
    static String wigle(JSONObject o) {
        String kind = o.optString("kind", "");
        String type = kind.equals("WIFI") ? "WIFI" : kind.equals("BLE") ? "BLE" : kind.equals("CLASSIC") ? "BT" : kind;
        String iso = o.optString("iso_time", "");
        String first = (iso.length() >= 19 ? iso.substring(0, 19) : iso).replace('T', ' ');   // ISO -> "yyyy-MM-dd HH:mm:ss"
        String lat = (o.has("lat") && !o.isNull("lat")) ? String.valueOf(o.optDouble("lat")) : "";
        String lon = (o.has("lon") && !o.isNull("lon")) ? String.valueOf(o.optDouble("lon")) : "";
        String acc = (o.has("gps_accuracy_m") && !o.isNull("gps_accuracy_m")) ? String.valueOf(o.optDouble("gps_accuracy_m")) : "";
        return q(o.opt("mac")) + "," + q(o.opt("ssid_or_name")) + "," + q(o.opt("security")) + "," + q(first)
                + "," + o.optInt("channel", 0) + "," + o.optInt("rssi", 0) + "," + lat + "," + lon + ",," + acc + "," + q(type) + "\n";
    }
    static String q(Object v) { if (v == null || v == JSONObject.NULL) return "\"\""; return "\"" + String.valueOf(v).replace("\"", "\"\"") + "\""; }

    String[] export(Context ctx) throws IOException {
        if (wiped) return null;                          // a duress wipe already happened — never write a fresh GPS export
        File out = ctx.getExternalFilesDir(null); if (out == null) out = ctx.getFilesDir();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File csvF = new File(out, "swivel_" + stamp + ".csv"), kmlF = new File(out, "swivel_" + stamp + ".kml"), jsonF = new File(out, "swivel_" + stamp + ".json");
        File wigleF = new File(out, "swivel_" + stamp + "_wigle.csv");   // Kismet / WiGLE interchange format
        try (Writer cw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvF)));
             Writer kw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(kmlF)));
             Writer jw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(jsonF)));
             Writer ww = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(wigleF)))) {
            for (int i = 0; i < COLS.length; i++) { if (i > 0) cw.write(","); cw.write(COLS[i]); }
            cw.write("\n");
            ww.write(WIGLE_HEADER);
            kw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>SWIVEL log</name>\n");
            jw.write("[");
            boolean firstJson = true;
            if (file.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim(); if (line.isEmpty()) continue;
                        JSONObject o;
                        try { o = new JSONObject(line); } catch (Exception bad) { continue; }   // skip a torn last line
                        for (int i = 0; i < COLS.length; i++) { if (i > 0) cw.write(","); cw.write(csv(o.opt(COLS[i]))); }
                        cw.write("\n");
                        ww.write(wigle(o));
                        if (o.has("lat") && o.has("lon") && !o.isNull("lat") && !o.isNull("lon")) {
                            String nm = o.optString("ssid_or_name", "");
                            String disp = !nm.isEmpty() ? nm : o.optString("vendor", "");
                            if (disp.isEmpty()) disp = o.optString("mac", "device");
                            kw.write("<Placemark><name>" + xml(disp + " (" + o.optString("kind", "") + ")") + "</name><description>"
                                    + xml(o.optString("mac", "") + " · " + o.optString("classification", "") + " · " + o.optInt("rssi", 0) + " dBm · " + o.optString("iso_time", ""))
                                    + "</description><Point><coordinates>" + o.optDouble("lon", 0) + "," + o.optDouble("lat", 0) + ",0</coordinates></Point></Placemark>\n");
                        }
                        if (!firstJson) jw.write(","); firstJson = false; jw.write(o.toString());
                    }
                }
            }
            kw.write("</Document></kml>\n"); jw.write("]");
        }
        if (wiped) { csvF.delete(); kmlF.delete(); jsonF.delete(); wigleF.delete(); return null; }   // duress raced the export — destroy the just-written GPS files
        return new String[]{csvF.getAbsolutePath(), kmlF.getAbsolutePath(), jsonF.getAbsolutePath(), wigleF.getAbsolutePath()};
    }

    /** Duress: delete the on-disk session NDJSON file(s) AND every exported log file. */
    void wipe(Context ctx) {
        wiped = true;
        File[] logs = dir == null ? null : dir.listFiles((d, n) -> n.startsWith("session_") && n.endsWith(".ndjson"));
        if (logs != null) for (File f : logs) f.delete();
        written = 0;
        File out = ctx.getExternalFilesDir(null); if (out == null) out = ctx.getFilesDir();
        File[] exps = out == null ? null : out.listFiles((d, n) -> (n.startsWith("swivel_") || n.startsWith("millie_")) && (n.endsWith(".csv") || n.endsWith(".kml") || n.endsWith(".json")));
        if (exps != null) for (File f : exps) f.delete();
    }

    static String csv(Object val) {
        if (val == null || val == JSONObject.NULL) return "";
        String s = String.valueOf(val);
        boolean number = s.matches("-?\\d+(\\.\\d+)?");   // keep plain numbers (rssi, lat/lon) numeric in spreadsheets
        String v = (!number && (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@"))) ? "'" + s : s;  // formula-injection guard
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    static String xml(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
