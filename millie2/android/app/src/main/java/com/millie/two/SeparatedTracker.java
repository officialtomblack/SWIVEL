package com.millie.two;

import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;

/** Phase 1 (PASSIVE_RESEARCH.md §6.1): detect a tracker broadcasting in its SEPARATED / offline
 *  state — the stalking case where it is away from its owner and possibly following you. Identity
 *  comes from the advertisement payload, NEVER the (random) MAC, and is emitted as a stable id for
 *  the repeated-sighting logic in SeparatedTrackerDetector. */
final class SeparatedTracker {
    static final class Hit {
        final String stableId, label, evidence;
        Hit(String stableId, String label, String evidence) { this.stableId = stableId; this.label = label; this.evidence = evidence; }
    }

    /** Returns a separated-tracker hit, or null. manufacturer = comma-joined hex blocks (company id
     *  little-endian first, as the scanners emit it); serviceData = {uuidString: hexValue}. */
    static Hit detect(String mac, String manufacturer, JSONObject serviceData) {
        // Apple Find My offline: company 4C00, type 0x12, len 0x19, status byte, then 22-byte EC key.
        // The full 0x12/0x19 payload IS the separated/offline broadcast — near-owner mode is shorter
        // and won't match. The MAC is static-random (never a vendor), so we key on the payload key.
        for (String b : split(manufacturer)) {
            String h = b.toUpperCase(Locale.ROOT);
            if (h.startsWith("4C001219") && h.length() >= 58 && h.matches("[0-9A-F]+")) {
                int battery = (Integer.parseInt(h.substring(8, 10), 16) >> 6) & 0x03;  // status byte top 2 bits
                String key = h.substring(10, 54);                                      // 22-byte key; stable while separated
                // TODO VERIFY ON DEVICE: genuine-AirTag separated key/rotation stability (~24h) vs ~15min near owner.
                return new Hit("airtag:" + key, "AIRTAG · SEPARATED FROM OWNER",
                        "strong · Find My offline payload 0x12/0x19 · battery " + battery + "/3");
            }
        }
        if (serviceData != null) {
            Iterator<String> it = serviceData.keys();
            while (it.hasNext()) {
                String u = it.next();
                String s16 = uuid16(u);
                String val = serviceData.optString(u, "").toUpperCase(Locale.ROOT);
                // Samsung SmartTag: FD59/FD5A, byte0 bits 5-7 = state (010 lost, 011 overmature-lost).
                // The privacy id is fixed ~24h in overmature-lost, so that state is the trackable one.
                if ((s16.equals("FD5A") || s16.equals("FD59")) && val.length() >= 2 && val.matches("[0-9A-F]+")) {
                    int state = (Integer.parseInt(val.substring(0, 2), 16) >> 5) & 0x07;
                    if (state == 2 || state == 3) {
                        String st = state == 3 ? "OVERMATURE-LOST" : "LOST";
                        return new Hit("smarttag:" + val, "SAMSUNG SMARTTAG · " + st,
                                "strong · service 0x" + s16 + " state " + st.toLowerCase(Locale.ROOT));
                    }
                }
                // Tile: static MAC that never rotates — one sighting fingerprints it for life.
                if (s16.equals("FEED") || s16.equals("FEEC")) {
                    return new Hit("tile:" + (mac == null ? "" : mac.toUpperCase(Locale.ROOT)),
                            "TILE · STATIC MAC", "strong · service 0x" + s16 + " · non-rotating MAC");
                }
            }
        }
        return null;
    }

    static String[] split(String v) { return v == null ? new String[0] : v.trim().split("[,;\\s]+"); }

    /** 16-bit UUID (uppercase) from a 128-bit base-UUID string or a bare 4-hex short form, else "". */
    static String uuid16(String u) {
        String s = u == null ? "" : u.toLowerCase(Locale.ROOT).replace("-", "");
        if (s.length() == 32 && s.endsWith("00001000800000805f9b34fb")) return s.substring(4, 8).toUpperCase(Locale.ROOT);
        if (s.length() == 4) return s.toUpperCase(Locale.ROOT);
        return "";
    }
}
