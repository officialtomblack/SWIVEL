package com.millie.two;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phase 2 (PASSIVE_RESEARCH.md §3): persistent per-device log. One SQLite row per device identity,
 *  carrying every captured field (as a JSON blob) plus GPS and derived aggregates — first/last seen,
 *  sighting count, RSSI min/max/mean/variance. Writes run on one background thread and are throttled
 *  per device; export to CSV / KML / JSON. Logs every device seen, not just flagged ones.
 *  shortcut: the device table is unbounded (bounded only by clear/duress) — add an LRU cap when storage matters. */
final class DetectionLog extends SQLiteOpenHelper {
    static final String DB = "swivel_detections.db";
    static final int VER = 1;
    static final long WRITE_THROTTLE_MS = 20000;  // at most one DB upsert per device per window

    final ExecutorService io = Executors.newSingleThreadExecutor();
    final HashMap<String, Long> lastWrite = new HashMap<>();
    volatile int count;

    DetectionLog(Context ctx) {
        super(ctx.getApplicationContext(), DB, null, VER);
        io.execute(() -> { try { count = (int) DatabaseUtils.queryNumEntries(getReadableDatabase(), "device"); } catch (Exception ignored) {} });
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE device(key TEXT PRIMARY KEY, kind TEXT, mac TEXT, vendor TEXT, classification TEXT, name TEXT, source TEXT,"
                + " first_seen INTEGER, last_seen INTEGER, count INTEGER, rssi_last INTEGER, rssi_min INTEGER, rssi_max INTEGER,"
                + " rssi_mean REAL, rssi_m2 REAL, lat REAL, lon REAL, fix_at INTEGER, fields TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { db.execSQL("DROP TABLE IF EXISTS device"); onCreate(db); }

    /** Record one sighting (async, throttled per device). lat/lon are NaN when there is no GPS fix. */
    void record(String key, String kind, String mac, String vendor, String classification, String name, String source,
                int rssi, double lat, double lon, long fixAt, String fieldsJson, long now) {
        if (key == null) return;
        lastWrite.values().removeIf(t -> now - t >= WRITE_THROTTLE_MS);  // keep the throttle map bounded (rotating MACs)
        Long prev = lastWrite.get(key);
        if (prev != null) return;                                        // within the throttle window — skip this advert
        lastWrite.put(key, now);
        io.execute(() -> { try { upsert(key, kind, mac, vendor, classification, name, source, rssi, lat, lon, fixAt, fieldsJson, now); } catch (Exception ignored) {} });
    }

    private void upsert(String key, String kind, String mac, String vendor, String classification, String name, String source,
                        int rssi, double lat, double lon, long fixAt, String fieldsJson, long now) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("kind", kind); v.put("mac", mac); v.put("vendor", vendor); v.put("classification", classification);
        v.put("name", name); v.put("source", source); v.put("last_seen", now); v.put("rssi_last", rssi); v.put("fields", fieldsJson);
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) { v.put("lat", lat); v.put("lon", lon); v.put("fix_at", fixAt); }
        Cursor c = db.rawQuery("SELECT count,rssi_min,rssi_max,rssi_mean,rssi_m2 FROM device WHERE key=?", new String[]{key});
        if (c.moveToFirst()) {
            long n = c.getLong(0) + 1;
            double mean = c.getDouble(3), d = rssi - mean; mean += d / n;
            double m2 = c.getDouble(4) + d * (rssi - mean);             // Welford variance accumulation
            v.put("count", n); v.put("rssi_min", Math.min(c.getInt(1), rssi)); v.put("rssi_max", Math.max(c.getInt(2), rssi));
            v.put("rssi_mean", mean); v.put("rssi_m2", m2);
            db.update("device", v, "key=?", new String[]{key});
        } else {
            v.put("key", key); v.put("first_seen", now); v.put("count", 1); v.put("rssi_min", rssi); v.put("rssi_max", rssi);
            v.put("rssi_mean", (double) rssi); v.put("rssi_m2", 0d);
            db.insert("device", null, v); count++;
        }
        c.close();
    }

    int size() { return count; }
    void clear() { lastWrite.clear(); count = 0; io.execute(() -> { try { getWritableDatabase().delete("device", null, null); } catch (Exception ignored) {} }); }

    // ---- export (call from a background thread) ----
    static String csv(String s) {
        if (s == null) return "";
        String v = (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) ? "'" + s : s;  // spreadsheet formula-injection guard
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    static String xml(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    static String iso(long t) { return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(t)); }
    private static void write(File f, String s) throws IOException { try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) { w.write(s); } }

    /** Writes CSV, KML and JSON into the app files dir; returns their paths. */
    String[] export(Context ctx) throws IOException {
        File dir = ctx.getExternalFilesDir(null); if (dir == null) dir = ctx.getFilesDir();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File csvF = new File(dir, "swivel_" + stamp + ".csv"), kmlF = new File(dir, "swivel_" + stamp + ".kml"), jsonF = new File(dir, "swivel_" + stamp + ".json");
        StringBuilder cb = new StringBuilder("key,kind,mac,vendor,classification,name,source,first_iso,last_iso,count,rssi_last,rssi_min,rssi_max,rssi_sd,lat,lon\n");
        StringBuilder kb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>SWIVEL log</name>\n");
        StringBuilder jb = new StringBuilder("[");
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM device ORDER BY last_seen", null);
        int iKey = c.getColumnIndex("key"), iKind = c.getColumnIndex("kind"), iMac = c.getColumnIndex("mac"), iVendor = c.getColumnIndex("vendor"),
            iClass = c.getColumnIndex("classification"), iName = c.getColumnIndex("name"), iSrc = c.getColumnIndex("source"),
            iFirst = c.getColumnIndex("first_seen"), iLast = c.getColumnIndex("last_seen"), iCount = c.getColumnIndex("count"),
            iRl = c.getColumnIndex("rssi_last"), iRmin = c.getColumnIndex("rssi_min"), iRmax = c.getColumnIndex("rssi_max"),
            iM2 = c.getColumnIndex("rssi_m2"), iLat = c.getColumnIndex("lat"), iLon = c.getColumnIndex("lon"), iFields = c.getColumnIndex("fields");
        boolean firstJson = true;
        while (c.moveToNext()) {
            long cnt = c.getLong(iCount);
            double sd = Math.round((cnt > 0 ? Math.sqrt(c.getDouble(iM2) / cnt) : 0) * 10) / 10.0;
            boolean hasLoc = !c.isNull(iLat) && !c.isNull(iLon);
            double lat = hasLoc ? c.getDouble(iLat) : 0, lon = hasLoc ? c.getDouble(iLon) : 0;
            cb.append(csv(c.getString(iKey))).append(',').append(csv(c.getString(iKind))).append(',').append(csv(c.getString(iMac))).append(',')
              .append(csv(c.getString(iVendor))).append(',').append(csv(c.getString(iClass))).append(',').append(csv(c.getString(iName))).append(',')
              .append(csv(c.getString(iSrc))).append(',').append(csv(iso(c.getLong(iFirst)))).append(',').append(csv(iso(c.getLong(iLast)))).append(',')
              .append(cnt).append(',').append(c.getInt(iRl)).append(',').append(c.getInt(iRmin)).append(',').append(c.getInt(iRmax)).append(',')
              .append(sd).append(',').append(hasLoc ? lat : "").append(',').append(hasLoc ? lon : "").append('\n');
            if (hasLoc) {
                String nm = c.getString(iName); String title = xml((nm != null && !nm.isEmpty() ? nm : c.getString(iVendor)) + " (" + c.getString(iKind) + ")");
                kb.append("<Placemark><name>").append(title).append("</name><description>")
                  .append(xml(c.getString(iMac) + " · " + c.getString(iClass) + " · " + c.getInt(iRl) + " dBm"))
                  .append("</description><Point><coordinates>").append(lon).append(',').append(lat).append(",0</coordinates></Point></Placemark>\n");
            }
            try {
                JSONObject o = new JSONObject();
                o.put("key", c.getString(iKey)); o.put("kind", c.getString(iKind)); o.put("mac", c.getString(iMac)); o.put("vendor", c.getString(iVendor));
                o.put("classification", c.getString(iClass)); o.put("name", c.getString(iName)); o.put("source", c.getString(iSrc));
                o.put("first_seen", c.getLong(iFirst)); o.put("last_seen", c.getLong(iLast)); o.put("count", cnt);
                o.put("rssi_last", c.getInt(iRl)); o.put("rssi_min", c.getInt(iRmin)); o.put("rssi_max", c.getInt(iRmax)); o.put("rssi_sd", sd);
                if (hasLoc) { o.put("lat", lat); o.put("lon", lon); }
                String f = c.getString(iFields);
                o.put("fields", f == null || f.isEmpty() ? JSONObject.NULL : new JSONObject(f));
                if (!firstJson) jb.append(','); firstJson = false; jb.append(o.toString());
            } catch (JSONException ignored) {}
        }
        c.close();
        kb.append("</Document></kml>\n"); jb.append("]");
        write(csvF, cb.toString()); write(kmlF, kb.toString()); write(jsonF, jb.toString());
        return new String[]{csvF.getAbsolutePath(), kmlF.getAbsolutePath(), jsonF.getAbsolutePath()};
    }

    /** Duress: delete every exported log file from the files dir. */
    void wipeExports(Context ctx) {
        File dir = ctx.getExternalFilesDir(null); if (dir == null) dir = ctx.getFilesDir();
        File[] fs = dir == null ? null : dir.listFiles((d, n) -> (n.startsWith("swivel_") || n.startsWith("millie_"))
                && (n.endsWith(".csv") || n.endsWith(".kml") || n.endsWith(".json")));
        if (fs != null) for (File f : fs) f.delete();
    }
}
