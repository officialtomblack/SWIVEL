package com.millie.two;

import java.util.HashMap;

/** Phase 1 (PASSIVE_RESEARCH.md §6.1): fire once a separated-tracker identifier has stayed with the
 *  user across real movement. AirGuard's shipping thresholds: same id seen >=3 times, across >=400 m
 *  of your movement, over >=30 min. A per-sample movement floor filters GPS jitter / standing still. */
final class SeparatedTrackerDetector {
    static final int MIN_SIGHTINGS = 3;
    static final double MIN_METERS = 400, MIN_STEP = 50;
    static final long MIN_SPAN_MS = 30 * 60 * 1000L;

    static final class Track { String label; long firstSeen, lastSeen, alertedAt; int samples; double meters, lat, lon; }
    final HashMap<String, Track> tracks = new HashMap<>();

    /** Record a sighting with the user's current location; returns an alert the first time the
     *  thresholds are crossed (then not again for MIN_SPAN_MS), else null. */
    String observe(String id, String label, double lat, double lon, long now) {
        if (id == null) return null;
        Track t = tracks.get(id);
        if (t == null) { t = new Track(); t.firstSeen = now; t.lat = lat; t.lon = lon; t.samples = 1; tracks.put(id, t); }
        else {
            double d = FollowDetector.haversine(t.lat, t.lon, lat, lon);
            if (d >= MIN_STEP) { t.meters += d; t.lat = lat; t.lon = lon; t.samples++; }  // count a sample only after real movement
        }
        t.label = label; t.lastSeen = now;
        long span = t.lastSeen - t.firstSeen;
        if (t.samples >= MIN_SIGHTINGS && t.meters >= MIN_METERS && span >= MIN_SPAN_MS && now - t.alertedAt > MIN_SPAN_MS) {
            t.alertedAt = now;
            return (label == null || label.isEmpty() ? "A tracker" : label)
                    + " has stayed with you: " + t.samples + " sightings over " + (span / 60000) + " min, " + Math.round(t.meters) + " m of travel.";
        }
        return null;
    }
}
