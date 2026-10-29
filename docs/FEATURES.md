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
- **Phone Bluetooth Classic** (§2) — `startClassicDiscovery()` loops `BluetoothAdapter.startDiscovery()`
  (restarts on `ACTION_DISCOVERY_FINISHED`); each `ACTION_FOUND` gives a device with a **real factory
  BD_ADDR** (so the vendor OUI and the §5 `VendorTable` are reliable, unlike BLE), RSSI, name,
  **Class of Device** (major class decoded), device type, and cached **SDP service UUIDs**. Note:
  discovery is an **active ~12 s inquiry** — the phone transmits — so it's the one non-passive
  feature, and it only finds currently-*discoverable* devices (most phones/headsets aren't).
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
- **`HotspotCorrelator` (§6.4) — Phase 7** — flags an **active iOS Personal Hotspot** from its Apple
  vendor IE (element 221, OUI `00:17:F2`, vendor type `0x06`), whose 6-byte payload is the device's
  globally-administered **Bluetooth MAC** (Martin et al. 2017). That BT MAC is a stable identity that
  survives the hotspot's rotating BSSID, surfaced as `iOS PERSONAL HOTSPOT (active) · BT …`. A U/L-bit
  gate rejects locally-administered values (which kills the Tetherbug spoofer's constant payload), and
  the BSSID is cross-checked (WiFi MAC ≈ BT ±1) for strong/weak confidence. Attribution is **"hardware
  made by Apple" (Spec LOW)** — never an agency or person — and it never overwrites a higher-value
  public-safety/camera label. A second, **weak-only** path runs a co-presence + RSSI co-movement
  (Pearson shape + constant-gap proximity) heuristic between a hotspot and an Apple Continuity BLE
  advert, labelled *timing-only, not identity*; it never raises an alert and never becomes deterministic
  (iOS Continuity uses rotating random BLE addresses). It is **deliberately not** wired into the §6.5
  "following me" tracker: an active hotspot is usually the user's own phone, present everywhere they go.
  `// TODO VERIFY ON DEVICE`: that current iOS still emits the type-6 BT MAC and the exact 221-body framing.
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

**"New to this location" — Phase 6 (§6.5), `LocationTracker`:** the GPS-free complement to §3. A
*place* is the stable set of visible Wi-Fi BSSIDs, matched across scans by set overlap (Jaccard ≥0.5)
rather than exact equality, so RSSI jitter around the collection threshold doesn't split one spot into
many false locations. A stable device identifier (a separated-tracker payload id — never its rotating
MAC) that turns up with you at **≥2 distinct places** is flagged "doesn't belong here — followed you."
This is the "is something following me that doesn't belong here" primitive, and it works indoors where
there's no GPS fix. Alerts come through `MainActivity.notifyFollow()`. *Limit (shortcut):* only
separated-tracker ids feed it so far; Fast Pair model id / AirPods pseudo-id aren't plumbed onto the
device record yet, and it has no own-device safe-list, so a device of yours will trip it once.

---

## 5. Threat heuristics (networks under attack / spoofing)

**What:** Flag suspicious Wi-Fi and BLE environments.

**How — `ThreatRules`:**
- **Evil-twin (§1)** — the same SSID on more than one vendor prefix. Evidence is graded by the Wi-Fi
  Information Elements (API 30+): if the same-SSID BSSIDs show **different IE fingerprints** (the sorted
  set of IE `id:length`) the evidence is **strong** — a clone rarely reproduces the real AP's full IE
  set; if the IE sets are identical it stays **weak** (likely one ISP gateway plus its mesh/extender).
- **Karma / Pineapple** — one vendor prefix presenting ≥6 SSIDs.
- **Security downgrade (§1)** — an SSID whose security tier drops vs its baseline (WPA3→WPA2→OWE→open).
  The tier is read from the **RSN element's AKM suite** (SAE→WPA3, OWE→enhanced-open, others→WPA2), not
  the loose `capabilities` string, and only the AKM list is read so a GCMP-128 cipher (…08) isn't
  mistaken for an SAE AKM (…08).
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

*Phase status: all seven build-plan phases are implemented but **build-verified only** — §3
separated-tracker, the §6 logging subsystem, §5 vendor table, §6.2/§6.3 Continuity / Fast Pair / Swift
Pair, §2 Bluetooth Classic, §6.5 "new to this location" + §1 Wi-Fi IE evil-twin accuracy, and §6.4
BLE↔Wi-Fi hotspot correlation. The alert paths, the AirTag key-stability timing, the MS-CDP length quirk,
the live IE/RSN parsing, and the Apple hotspot type-6 BT-MAC / 221-body framing are all unverified until
tested on a phone (`// TODO VERIFY ON DEVICE`). Nothing remains on the `docs/PASSIVE_RESEARCH.md §8`
priority list; the §7 no-gos were deliberately not built.*
