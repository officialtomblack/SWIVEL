# SWIVEL — features: what they are and how they work

A map of every capability in the Android app, grouped by feature, with the mechanism and the
classes that implement it. Everything is **phone-only, passive, receive-only** on a stock
unrooted phone. Code lives under `millie2/android/app/src/main/java/com/millie/two/`.

---

## 1. Signal capture (the radios)

**What:** Continuously listen to nearby Wi-Fi and Bluetooth, on the phone alone or with an
optional CYD (ESP32) sensor over USB.

**How:**
- **Phone Wi-Fi** — `MainActivity.scanWifi()` calls `WifiManager.startScan()` and reads
  `getScanResults()` on `SCAN_RESULTS_AVAILABLE_ACTION`. Yields BSSID, SSID, RSSI, frequency,
  channel, security capabilities. Android throttles scans (~4 / 2 min foreground) and needs
  Location services on.
- **Phone BLE** — `MainActivity.startBleScan()` runs a low-latency `BluetoothLeScanner` scan
  (null filter while the screen is on); `ScanService` runs a filtered foreground-service scan so
  tracker/camera signatures keep coming while the screen is off. Reads advertised name, service
  UUIDs, manufacturer data, service data, RSSI, PHY.
- **Optional CYD** — `CydLinkService` owns a USB-serial link to the ESP32, which streams raw
  Wi-Fi management/control frames and BLE adverts the phone can't see (plus deauth/attack counts).
- **Merge** — `ObservationState` keeps each radio identity's observations from each source
  (PHONE / CYD) separately and de-dupes by sample id/order, so the same device seen by both
  merges into one record without double-counting. Status shows `PHONE`, `CYD`, or `PHONE + CYD`.

---

## 2. Device classification (what a signal is)

**What:** Label each device — camera maker, item tracker, public-safety/surveillance vendor, or
a plain Wi-Fi/BLE device — with a strong/weak evidence line, never a certainty claim.

**How:**
- **`SignalClassifier`** matches BLE manufacturer data and service UUIDs (Apple Find My, Tile,
  Samsung SmartTag, Chipolo, Pebblebee, Google Find My Device) and camera-maker / public-safety
  vendor names.
- **`PublicSafetyMatcher`** attributes IEEE vendor and Bluetooth-SIG company/service IDs for
  Axon/TASER, Motorola Solutions, Flock, WatchGuard, Digital Ally.
- **`VendorTable` (§5)** — a verified table of LE/gov/surveillance-associated vendor prefixes
  (Axon, Flock, Neology, ELSAG, Skydio, Cellebrite, Motorola Solutions, Sierra Wireless, Cradlepoint,
  Verkada, Avigilon, Axis…) each with a **High / Medium / Low** specificity weight, matched
  longest-prefix-first (MA-S→MA-M→MA-L) on a real MAC only. The false-friends (WatchGuard
  Technologies, Flock Audio, Motorola Mobility) are deliberately absent so they can't mis-match.
  Every hit carries the "hardware made by X — this does not indicate any agency is present" framing.
- **`ContinuityParser` (§6.2/§6.3)** — reads cleartext device intel from **Apple Continuity**
  (AirPods, the Nearby-Info activity byte, and Handoff/AirDrop by *presence only* — never decrypted),
  **Google Fast Pair** (24-bit cleartext Model ID), and **Microsoft Swift Pair** (device class).
  Surfaced as `DEVICE INTEL · …` on a BLE device. Continuity model/activity maps are reverse-
  engineered, so decodes stay conservative.
- **Vendor lookup** uses the bundled offline IEEE registry (`assets/ieee_mac_registry.csv`),
  longest-prefix-first. Randomized/locally-administered MACs (U/L bit set) are labelled
  `RANDOM / PRIVATE` or `LOCAL / RANDOMIZED` and **never** attributed to a vendor.
- A match means "hardware made by company X," not "an agency is present."

---

## 3. Separated-tracker (stalking) detection — Phase 1

**What:** Detect a tracker that is *away from its owner* and staying with *you* — the AirTag-in-
your-bag case — and alert on it.

**How:**
- **`SeparatedTracker`** parses the offline/separated advertisement and emits a **stable
  identifier from the payload, never the random MAC**:
  - **AirTag / Find My** — manufacturer `4C00` + type `0x12` + len `0x19`; the full payload is the
    offline broadcast (near-owner mode is shorter and ignored). Status byte → battery; the 22-byte
    key is the id (stable while separated).
  - **Samsung SmartTag** — service `FD59`/`FD5A`, byte-0 bits 5–7 give the state
    (`010` lost / `011` overmature-lost); the service-data value is the id.
  - **Tile** — service `FEED`/`FEEC`; its MAC never rotates, so the MAC is the lifetime id.
- **`SeparatedTrackerDetector`** fires on AirGuard's thresholds: the same id across **≥3 location
  samples, ≥400 m of your travel, over ≥30 min** → one alert, with a 30-min re-alert cooldown. A
  50 m per-sample floor rejects GPS jitter and standing still.
- Hits are labelled precisely (`AIRTAG · SEPARATED FROM OWNER`) and routed here instead of the
  generic follow path (§4). Alerts come through `MainActivity.notifyFollow()`.
- *Limit:* an AirTag's key rotates (~24 h separated, ~15 min near its owner), so a tag next to its
  own phone reads as "at home" and won't trip — by design. `// TODO VERIFY ON DEVICE` marks the
  rotation-timing assumption.

---

## 4. Generic follow detection

**What:** The older "this tracker has been near you at several places" alert, kept as a fallback
for trackers the Phase-1 parser doesn't cover.

**How:** `FollowDetector` keys on a service-data payload where one exists, else the MAC, and
alerts after ≥3 distinct ~80 m places over ≥30 min. Weaker than §3 because rotating identities
(AirTags) defeat the key; separated-tracker hits are handled by §3 instead.

---

## 5. Threat heuristics (networks under attack / spoofing)

**What:** Flag suspicious Wi-Fi and BLE environments.

**How — `ThreatRules`:**
- **Evil-twin** — the same SSID seen on more than one vendor prefix (weak evidence; common
  carrier/ISP names like `xfinitywifi` legitimately do this).
- **Karma / Pineapple** — one vendor prefix presenting ≥6 SSIDs.
- **Security downgrade** — an SSID whose security tier drops vs its baseline (WPA3→WPA2→open).
- **BLE advertisement swarm** — ≥25 new BLE addresses in 60 s (Flipper / Fast-Pair spam).

**Attack detection (needs the CYD):** the ESP32 counts deauth / disassoc / probe-request frames;
`MainActivity.handleAttackReport()` raises an **UNDER ATTACK** / probe-harvest banner. The phone
can't see these frames on its own.

---

## 6. Logging and export

**What:** Persistently log **every** device seen (not just flagged) and export the record.

**How — `DetectionLog` (SQLite):** each device is written to a SQLite table, one row per device
(`kind|mac`): every captured field as a JSON blob, plus GPS and derived aggregates — first/last
seen, sighting count, and RSSI min/max/mean/variance (Welford). Writes run on a background thread,
throttled to one upsert per device per 20 s. **EXPORT CSV · KML · JSON** writes all three to the app
files dir and opens a share sheet; the KML maps where each device was seen. **DURESS · CLEAR ALL**
clears the table and deletes the export files. The table persists across restarts and is bounded
only by clear/duress (a storage cap is a TODO).

---

## 7. Display

**What:** Make the data readable and the reasoning transparent.

**How — `MainActivity`:** a **SIGNAL SCOPE** radar (Wi-Fi amber / BLE blue gauges, peak RSSI, an
RSSI-distribution chart); tabs **LIVE / CAMERAS / TRACKERS / PUBLIC SAFETY / MORE** (filtered
views); a per-device **detail page** with the full signal history graph and every raw radio field
plus the classification evidence; and a **FINDER** hot/cold proximity meter (`FinderView`) with
MARK SAFE / MUTE.

---

## 8. Privacy posture

**What:** The whole point — and it's accuracy, not a disclaimer.

**How:** The app **only receives** — it transmits nothing and runs no attacks. Vendor lookups use
the bundled offline registry, so there are **no network calls, no accounts, no analytics**.
Detections stay on the phone; export and the duress wipe are the only data paths, both user-driven.

---

*Phase status: §3 (separated-tracker) is implemented but build-verified only — the alert path and
the AirTag key-stability timing are unverified until tested on a phone. Items 2–7 of the build plan
(logging subsystem, vendor-table hardening, Apple Continuity / Fast Pair, Bluetooth Classic, "new
to this location", BLE↔Wi-Fi correlation) are not built yet — see `docs/PASSIVE_RESEARCH.md §8`.*
