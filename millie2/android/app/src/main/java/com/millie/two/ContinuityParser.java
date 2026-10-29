package com.millie.two;

import java.util.Locale;

/** Phase 4 (PASSIVE_RESEARCH.md §6.2 / §6.3): passively read cleartext device intel from Apple
 *  Continuity, Google Fast Pair, and Microsoft Swift Pair advertisements — device class / model /
 *  activity presence only. Handoff and AirDrop are read for PRESENCE, never decrypted or recovered
 *  (§7 no-go). Exact model/activity field maps are reverse-engineered, so decodes stay conservative. */
final class ContinuityParser {

    /** Apple Continuity (company 0x004C): concatenated [type][len][payload] messages. Returns a
     *  summary of the message types present (and the Nearby-Info activity byte), or "". */
    static String apple(String manufacturerHex) {
        for (String b : split(manufacturerHex)) {
            String h = b.toUpperCase(Locale.ROOT);
            if (!h.startsWith("4C00") || h.length() < 8 || !h.matches("[0-9A-F]+")) continue;
            StringBuilder out = new StringBuilder();
            int i = 4;                                           // skip the 4C00 company id
            while (i + 4 <= h.length()) {
                int type, len;
                try { type = Integer.parseInt(h.substring(i, i + 2), 16); len = Integer.parseInt(h.substring(i + 2, i + 4), 16); }
                catch (NumberFormatException e) { break; }
                int start = i + 4, end = Math.min(start + len * 2, h.length());
                String p = h.substring(start, end);
                String tag = null;
                switch (type) {
                    case 0x07: tag = "AirPods (proximity pairing)"; break;
                    case 0x10: tag = "Nearby Info (activity byte 0x" + (p.length() >= 2 ? p.substring(0, 2) : "?") + ")"; break;
                    case 0x0F: tag = "Nearby Action (setup intel)"; break;
                    case 0x0C: tag = "Handoff"; break;           // IV present — links rotations short-horizon; NOT decrypted (§7)
                    case 0x05: tag = "AirDrop"; break;           // presence only — no email/phone hash recovery (§7)
                    case 0x02: tag = "iBeacon"; break;
                }
                if (tag != null) { if (out.length() > 0) out.append(" · "); out.append(tag); }
                if (len == 0) break;
                i = end;
            }
            if (out.length() > 0) return out.toString();
        }
        return "";
    }

    /** Google Fast Pair (service data 0xFE2C): in pairing mode the first 3 bytes are a cleartext
     *  24-bit Model ID (otherwise part of the encrypted account-key filter). */
    static String fastPair(String serviceDataHex) {
        String h = serviceDataHex == null ? "" : serviceDataHex.toUpperCase(Locale.ROOT);
        if (h.length() >= 6 && h.matches("[0-9A-F]+")) return "Fast Pair · 0x" + h.substring(0, 6) + " (model id in pairing mode)";
        return "";
    }

    /** Microsoft Swift Pair (company 0x0006, CDP beacon): cleartext device-type byte.
     *  // TODO VERIFY ON DEVICE: the MS-CDP beacon has a documented internal length inconsistency. */
    static String swiftPair(String manufacturerHex) {
        for (String b : split(manufacturerHex)) {
            String h = b.toUpperCase(Locale.ROOT);
            if (h.startsWith("0600") && h.length() >= 8 && h.matches("[0-9A-F]+"))   // company 0x0006, little-endian
                return "MS Swift Pair · device type 0x" + h.substring(6, 8);
        }
        return "";
    }

    static String[] split(String v) { return v == null ? new String[0] : v.trim().split("[,;\\s]+"); }
}
