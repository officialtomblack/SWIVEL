package com.millie.two;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Phase 6 (PASSIVE_RESEARCH.md §6.5): "new to this location / is something following me". A place is
 *  the stable set of visible Wi-Fi BSSIDs — no GPS needed, so this works indoors where the Phase-2 log
 *  showed no fix is available, and it is the GPS-free complement to the Phase-1 separated-tracker detector.
 *  A stable device identifier that turns up with you at >= 2 distinct places doesn't belong to any one of
 *  them, and is flagged. Places are matched by BSSID-set overlap (Jaccard), not exact equality, so RSSI
 *  jitter around the collection threshold doesn't split one spot into many false "locations". */
final class LocationTracker {
    static final int MIN_BSSIDS = 3;        // too few APs to characterise a place -> no location context
    static final double SAME = 0.5;         // BSSID-set overlap >= this counts as the same place
    static final int MIN_PLACES = 2;        // a stable id at >= this many places = co-moving with you

    final ArrayList<HashSet<String>> places = new ArrayList<>();          // known place fingerprints
    final HashMap<String, HashSet<Integer>> seenAt = new HashMap<>();     // stable id -> place indices
    final HashSet<String> alerted = new HashSet<>();
    int current = -1;

    /** Resolve the current scan's BSSID set to a known place, registering a new one when nothing matches. */
    void setLocation(List<String> bssids) {
        if (bssids.size() < MIN_BSSIDS) { current = -1; return; }
        HashSet<String> now = new HashSet<>(bssids);
        int best = -1; double bestJ = 0;
        for (int i = 0; i < places.size(); i++) {
            double j = jaccard(now, places.get(i));
            if (j > bestJ) { bestJ = j; best = i; }
        }
        // shortcut: a place's fingerprint is frozen at first sight; over a long session, slow AP turnover
        // can split one spot into two places. Upgrade: periodically re-cluster the place list.
        if (bestJ >= SAME) current = best;
        else { places.add(now); current = places.size() - 1; }
    }

    /** Record a sighting of a stable id at the current place; returns an alert the first time that id has
     *  been seen at >= MIN_PLACES distinct places (it followed you), else null. */
    String observe(String stableId, String label) {
        if (stableId == null || current < 0) return null;
        HashSet<Integer> locs = seenAt.computeIfAbsent(stableId, k -> new HashSet<>());
        locs.add(current);
        if (locs.size() >= MIN_PLACES && alerted.add(stableId))
            return (label == null || label.isEmpty() ? "A device" : label)
                    + " doesn't belong here — it has been with you at " + locs.size() + " different places.";
        return null;
    }

    static double jaccard(HashSet<String> a, HashSet<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String x : a) if (b.contains(x)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    void clear() { places.clear(); seenAt.clear(); alerted.clear(); current = -1; }
}
