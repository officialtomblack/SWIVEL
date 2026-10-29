package com.millie.two;

import java.util.List;
import java.util.Locale;

/** Phase 7 (PASSIVE_RESEARCH.md §6.4): BLE<->WiFi hotspot correlation — phone-only, passive, no new
 *  permissions, no active scanning. Pure post-processing of data MainActivity already captures.
 *
 *  DETERMINISTIC half (sourced — Martin et al. 2017, arXiv:1703.02874 §6.7.1, already in the doc's
 *  Sources): an active iOS Personal Hotspot beacons an Apple vendor IE — element 221 (0xDD), OUI
 *  00:17:F2, vendor type 0x06 — whose 6-byte payload is the device's globally-administered Bluetooth
 *  MAC. The hotspot's own BSSID is randomised and rotates on every hotspot restart, so that embedded
 *  BT MAC is a STABLE identity that survives BSSID rotation; we surface it in the device's
 *  classification label. We extract it, gate out any non-global / non-unicast value (which rejects
 *  the Tetherbug spoofer's constant 0x01... payload), and corroborate against the BSSID (WiFi MAC is
 *  BT +/-1 on most devices). An Apple OUI means "hardware made by Apple" (Spec LOW) — NEVER that an
 *  agency or person is present. Identity confidence and agency specificity are two separate axes.
 *  (Deliberately NOT wired into the §6.5 follow/location tracker: an active hotspot is dominated by the
 *  user's OWN phone, which is present at every location they visit, so "following you across places"
 *  would be near-all false positives — the identity is surfaced as classification, not a follow alert.)
 *
 *  HEURISTIC half (the spec's explicit fallback, WEAK only): co-presence + RSSI co-movement between a
 *  hotspot and an Apple Continuity BLE advert. iOS Continuity advertises from rotating RANDOM addresses,
 *  so this can NEVER be a deterministic identity link — it is timing/signal only, capped at weak/LOW,
 *  and never raises an alert.
 *
 *  // TODO VERIFY ON DEVICE: that current iOS (17/18+) still emits the type-6 BT MAC; the exact framing
 *  inside a multi-part 221 body; that ScanResult.InformationElement.getBytes() returns the IE body only
 *  (no id/len octets, as the WiFi capture path assumes); and the co-presence thresholds below. Every one
 *  of these fails SAFE — a mismatch makes detect()/coPresence() return null, never a false identity. */
final class HotspotCorrelator {
    enum Consistency { STRONG, WEAK }
    static final class Identity {
        final String btMac; final Consistency consistency;        // BT MAC survives BSSID rotation; shown in the row's classification label
        Identity(String bt, Consistency c) { btMac = bt; consistency = c; }
    }
    static final class Link {
        final String label, evidence; final boolean strong;
        Link(String label, String evidence, boolean strong) { this.label = label; this.evidence = evidence; this.strong = strong; }
    }

    static final long PAIR_TOL_MS = 3_000, APPEAR_TOL_MS = 20_000, LIVE_TOL_MS = 15_000;
    static final int MIN_PAIRS = 3, MIN_SWING_DB = 8;
    static final double R_MIN = 0.7, GAP_SD_MAX = 6.0;

    /** Deterministic §6.4: the iOS-hotspot BT identity from a WiFi row's IEs, or null. Only the phone's
     *  API-30+ scan carries IEs — a CYD/ESP32 WiFi frame has none, so it yields no 221 element and
     *  returns null (no explicit source check needed, and a PHONE+CYD-merged row still works). */
    static Identity detect(String bssid, String informationElements) {
        if (informationElements == null) return null;
        for (String e : informationElements.split(",")) {
            int c = e.indexOf(':');
            if (c <= 0 || !e.substring(0, c).equals("221")) continue;
            String h = e.substring(c + 1).toUpperCase(Locale.ROOT);
            // IE body = OUI(3B) + vendor type(1B) + payload; the Apple hotspot marker is 0017F2 type 06.
            if (!h.startsWith("0017F2") || h.length() < 20 || !h.substring(6, 8).equals("06")) continue;
            String cand = h.substring(8, 20);                       // the 6-byte field after OUI+type
            int o0;
            try { o0 = Integer.parseInt(cand.substring(0, 2), 16); } catch (Exception x) { return null; }
            if ((o0 & 0x03) != 0) return null;                      // not globally-administered unicast -> reject (kills the Tetherbug 0x01 spoof); hard rule
            String bs = hex(bssid);
            Consistency cons = bs.length() == 12
                    && cand.substring(2, 10).equals(bs.substring(2, 10))
                    && close(cand.substring(10, 12), bs.substring(10, 12)) ? Consistency.STRONG : Consistency.WEAK;
            return new Identity(colon(cand), cons);
        }
        return null;
    }

    /** Deterministic bonus: a WiFi BSSID and a BLE address that are consecutive FACTORY (globally-
     *  administered unicast) MACs are one hardware module. Refuses randomised/local MACs (hard rule),
     *  so it effectively never fires for a modern iPhone (random BSSID + BLE RPA) — it catches an old
     *  Android SoftAP / MiFi. HIGH specificity when it does fire. */
    static Link consecutiveMac(String wifiMac, String bleMac) {
        String w = hex(wifiMac), b = hex(bleMac);
        if (w.length() != 12 || b.length() != 12) return null;
        try {
            if ((Integer.parseInt(w.substring(0, 2), 16) & 0x03) != 0) return null;
            if ((Integer.parseInt(b.substring(0, 2), 16) & 0x03) != 0) return null;
            long d = Math.abs(Long.parseLong(w, 16) - Long.parseLong(b, 16));
            if (d >= 1 && d <= 2) return new Link("HOTSPOT LINK · SAME HARDWARE [HIGH]",
                    "strong · WiFi BSSID and BLE address are consecutive factory MACs (differ by " + d + ") — same physical hardware, NOT proof a person is present", true);
        } catch (Exception ignored) {}
        return null;
    }

    /** Heuristic §6.4 fallback, WEAK only. wifi/ble are time-ordered {timeMs, rssi} samples drawn from
     *  signalHistory. Pearson tests co-DIRECTION (shape); the RSSI-gap stdev tests same-distance
     *  (proximity) — Pearson alone IS the two-co-located-devices false positive, so both must pass.
     *  strong is always false; this can never be more than weak timing/signal evidence. */
    static Link coPresence(List<long[]> wifi, List<long[]> ble, long wifiFirstSeen, long bleFirstSeen, long now, boolean bleRpa) {
        if (wifi.isEmpty() || ble.isEmpty()) return null;
        if (now - wifi.get(wifi.size() - 1)[0] > LIVE_TOL_MS) return null;        // stale / rotated-away -> bail
        if (now - ble.get(ble.size() - 1)[0] > LIVE_TOL_MS) return null;
        if (Math.abs(wifiFirstSeen - bleFirstSeen) > APPEAR_TOL_MS) return null;  // didn't power on together -> not one device
        double[] wr = new double[wifi.size()], br = new double[wifi.size()]; int n = 0;
        for (long[] ws : wifi) {                                                  // WiFi is the sparse side; pair each to the nearest BLE sample
            long[] best = null; long bestDt = PAIR_TOL_MS + 1;
            for (long[] bsamp : ble) { long dt = Math.abs(bsamp[0] - ws[0]); if (dt < bestDt) { bestDt = dt; best = bsamp; } }
            if (best != null && bestDt <= PAIR_TOL_MS) { wr[n] = ws[1]; br[n] = best[1]; n++; }
        }
        if (n < MIN_PAIRS) return null;                                          // WiFi scan throttling often starves this — declining is correct, not a failure
        if (range(wr, n) < MIN_SWING_DB || range(br, n) < MIN_SWING_DB) return null;  // flat window -> nothing to correlate
        double r = pearson(wr, br, n), gapSd = diffStdev(wr, br, n);
        if (r < R_MIN || gapSd > GAP_SD_MAX) return null;
        long span = (wifi.get(wifi.size() - 1)[0] - wifi.get(0)[0]) / 1000;
        String ev = String.format(Locale.US,
                "weak · heuristic co-presence (NOT identity): an Apple hotspot and an Apple Continuity advert co-vary (Pearson r=%.2f, n=%d over %ds, RSSI gap sd=%.1f dB) and appeared together — consistent with one iPhone, but any co-located device co-varies with your own motion; not proof a person is present.%s",
                r, n, span, gapSd, bleRpa ? " The BLE address is a rotating RPA, so this holds only for the current advertising window." : "");
        return new Link("HOTSPOT LINK? · POSSIBLE APPLE DEVICE [LOW]", ev, false);
    }

    static boolean close(String a, String b) { try { return Math.abs(Integer.parseInt(a, 16) - Integer.parseInt(b, 16)) <= 1; } catch (Exception e) { return false; } }
    static String hex(String mac) { return mac == null ? "" : mac.replace(":", "").replace("-", "").toUpperCase(Locale.ROOT); }
    static String colon(String h) { StringBuilder s = new StringBuilder(); for (int i = 0; i + 2 <= h.length(); i += 2) { if (i > 0) s.append(':'); s.append(h, i, i + 2); } return s.toString(); }
    static double range(double[] x, int n) { double mn = x[0], mx = x[0]; for (int i = 1; i < n; i++) { mn = Math.min(mn, x[i]); mx = Math.max(mx, x[i]); } return mx - mn; }
    static double pearson(double[] x, double[] y, int n) { double sx=0,sy=0,sxx=0,syy=0,sxy=0; for(int i=0;i<n;i++){sx+=x[i];sy+=y[i];sxx+=x[i]*x[i];syy+=y[i]*y[i];sxy+=x[i]*y[i];} double den=Math.sqrt((n*sxx-sx*sx)*(n*syy-sy*sy)); return den==0?0:(n*sxy-sx*sy)/den; }
    static double diffStdev(double[] x, double[] y, int n) { double m=0; for(int i=0;i<n;i++)m+=x[i]-y[i]; m/=n; double v=0; for(int i=0;i<n;i++){double d=(x[i]-y[i])-m;v+=d*d;} return Math.sqrt(v/n); }
}
