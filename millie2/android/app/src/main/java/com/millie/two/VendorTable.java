package com.millie.two;

import java.util.LinkedHashMap;
import java.util.Locale;

/** Phase 3 (PASSIVE_RESEARCH.md §5): verified LE/gov/surveillance-associated vendor prefixes, each
 *  with a High/Medium/Low specificity weight. A match is "hardware made by company X," and NEVER
 *  proof that an agency is present. Matched longest-prefix-first (MA-S 9 hex -> MA-M 7 -> MA-L 6) on
 *  a globally-administered unicast MAC only; randomized / locally-administered / multicast addresses
 *  are never attributed. The deliberately-excluded false-friends (WatchGuard Technologies, Flock
 *  Audio, Motorola Mobility) simply aren't in the table, so they can't mis-match. */
final class VendorTable {
    enum Spec { HIGH, MEDIUM, LOW }
    static final class V {
        final String org, use; final Spec spec; final boolean camera;
        V(String org, String use, Spec spec, boolean camera) { this.org = org; this.use = use; this.spec = spec; this.camera = camera; }
    }

    static final LinkedHashMap<String, V> TABLE = new LinkedHashMap<>();
    private static void add(V v, String... prefixes) { for (String p : prefixes) TABLE.put(p, v); }
    static {
        // HIGH — gear that exists mainly to serve LE/gov (still not proof of an agency).
        add(new V("Axon Enterprise", "LE body cameras / TASER", Spec.HIGH, true), "0025DF");
        add(new V("WatchGuard Video", "in-car police video", Spec.HIGH, true), "001D96");       // not WatchGuard Technologies (firewalls)
        add(new V("Digital Ally", "police video", Spec.HIGH, true), "0023BD");
        add(new V("L-3 Mobile-Vision", "in-car police DVR (Flashback)", Spec.HIGH, true), "3873EA0");
        add(new V("Utility, Inc", "BodyWorn / Rocket IoT", Spec.HIGH, true), "0016ED", "0009BC");
        add(new V("Flock Safety", "ALPR (Falcon / Raven)", Spec.HIGH, true), "B41E52");          // not Flock Audio (pro audio)
        add(new V("Neology", "RFID / ALPR / tolling", Spec.HIGH, true), "00173D");
        add(new V("ELSAG North America", "MPH-900 ALPR", Spec.HIGH, true), "70B3D51C5");
        add(new V("Skydio", "police drone", Spec.HIGH, false), "381D14");
        add(new V("Cellebrite", "forensic extraction", Spec.HIGH, false), "001600");
        // MEDIUM — LE-heavy but sold broadly (down-weight).
        add(new V("Motorola Solutions", "public-safety radios", Spec.MEDIUM, false),
                "00047D", "001F92", "001885", "4CCC34", "10746F", "B8E28C", "9C862B");           // not Motorola Mobility (consumer phones)
        add(new V("Sierra Wireless", "in-vehicle routers (mostly commercial)", Spec.MEDIUM, false),
                "00A0D5", "50139D", "84DB2F", "64CE6E", "CC934A", "28A331");
        add(new V("Cradlepoint", "patrol-car routers (huge in retail)", Spec.MEDIUM, false), "003044", "00E01C");
        add(new V("Verkada", "surveillance cameras (mostly private)", Spec.MEDIUM, true), "E0A700");
        add(new V("Avigilon Alta", "CCTV (owned by Motorola Solutions)", Spec.MEDIUM, true), "701AD5");
        add(new V("Axis Communications", "city / transit / retail CCTV", Spec.MEDIUM, true), "00408C", "B8A44F", "E82725", "ACCC8E");
    }

    /** §5 lookup: null for randomized / locally-administered / multicast addresses, or no match. */
    static V lookup(String mac) {
        String h = mac == null ? "" : mac.replace(":", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (h.length() != 12 || !h.matches("[0-9A-F]{12}")) return null;
        if ((Integer.parseInt(h.substring(0, 2), 16) & 3) != 0) return null;   // U/L or multicast bit set -> not in the registry
        for (int n : new int[]{9, 7, 6}) { V v = TABLE.get(h.substring(0, n)); if (v != null) return v; }
        return null;
    }

    /** The mandatory §5 framing: a vendor match is hardware, never an agency. */
    static String framing(V v) {
        return (v.spec == Spec.HIGH ? "strong" : v.spec == Spec.MEDIUM ? "weak" : "very weak")
                + " · hardware made by " + v.org + " (commonly used in " + v.use
                + ") — this does NOT indicate any agency is present";
    }
}
