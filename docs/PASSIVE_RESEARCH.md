# MILLIE fork — passive capability & accuracy research (phone-only)

**Date:** 2026-10-29 · **Scope:** stock, UNROOTED Android 12+ phone (Galaxy A12), WiFi + BLE + Bluetooth Classic, passive only. No monitor mode, no root.
**Sourcing:** Verified 2026-10-29 against Android developer docs, the live IEEE Registration Authority CSVs, the Bluetooth SIG `assigned_numbers` repo, and peer-reviewed security research (cited inline; full list at end). Where a fact is reverse-engineered rather than vendor-published, it is flagged.

This is a build reference for the MILLIE fork. It assumes Phases 1–4 of the build plan are in progress.

---

## 0. The honest capability ceiling (read this first)

Three facts govern everything below. Put them in the app UI, not just here — they're accuracy, not disclaimers.

1. **You can only see devices that transmit: APs/beacons, BLE advertisers, and discoverable Classic devices.** You cannot see client probe requests, silent connected devices, or a phone that isn't advertising. That genuinely needs monitor mode, which a stock unrooted phone cannot do. This is an Android API limit, not a design flaw to fix.
2. **MAC reliability differs sharply by radio:** Bluetooth Classic = real factory MAC → reliable vendor OUI (best). WiFi AP BSSID = usually real → good. BLE = mostly rotating random addresses → weak/useless for identity. Build BLE identity on *advertisement contents*, not the MAC.
3. **The richest passive fingerprints are `ScanResult.getInformationElements()` (WiFi, API 30+) and BLE manufacturer/service data.** Both work unrooted. Most of the "outside-the-box" wins live here.

Permissions/throttling reality: WiFi needs `ACCESS_FINE_LOCATION` + `CHANGE_WIFI_STATE`/`ACCESS_WIFI_STATE` + Location Services ON, and is throttled to ~4 scans/2 min foreground, ~1/30 min background, suppressed under Doze/screen-off. BLE/Classic need `BLUETOOTH_SCAN` (+ `BLUETOOTH_CONNECT` for Class-of-Device / SDP). **Do NOT set `neverForLocation`** — Android then filters beacons (notably iBeacon-style) out of your results, and a max-detail detector wants them. Declare fine location for both subsystems.

---

## 1. Per-feature accuracy polish (existing MILLIE detectors)

**Cameras** — OUI alone is weak (Ring/Nest also make routers; Hikvision/Dahua OEM dozens of white-label brands). Improve precision by *combining* signals: OUI + an SSID pattern + a BLE service match before calling it a camera, and down-weight a bare OUI hit to "camera-maker hardware present," never "camera." US policy nuance: a Hikvision/Dahua hit argues *against* a federal operator (NDAA §889 ban) — surface that, don't treat Chinese-cam vendors as "official."

**Trackers** — biggest accuracy gain is detecting the *separated/offline* state (§6), not just "a tracker exists." Add the signatures Phase 1.4 is already pulling in (Google FMDN, Chipolo, Pebblebee-via-network) and parse the status byte so you report "AirTag, separated from owner" rather than a generic hit.

**Public-safety** — keep the strong/weak evidence tagging. The vendor table in §5 replaces guesswork with verified IEEE org names and a specificity weight. Watch the false-friend org names (§5) — several are currently at risk of mis-matching.

**Evil-twin / karma (Phase 2.5)** — accuracy comes from the WiFi Information Elements (API 30+): an evil twin usually can't perfectly clone the real AP's full IE set, so compare IE fingerprints across same-SSID BSSIDs, not just OUI. Baseline per-SSID security capabilities and flag downgrades (WPA3→WPA2→open) using the RSN element, not the loose `capabilities` string.

**BLE classification** — stop doing OUI lookups on random addresses (Phase 1.5). Classify address subtype by the top two bits of the first octet and label rotating addresses "private — vendor unknown." Lean on manufacturer/service data for identity.

---

## 2. Bluetooth Classic (new feature)

**Why it's worth adding:** Classic BR/EDR addresses are **not randomized** — they're the real factory BD_ADDR, so they give a reliable vendor OUI. That's the *strongest* identity signal of the three radios, and it's the one MILLIE doesn't use yet.

**How (unrooted):** `BluetoothAdapter.startDiscovery()` → `ACTION_FOUND` broadcast per device. Each yields:
- `BluetoothDevice.getAddress()` — real MAC → OUI → vendor (via the §5 lookup).
- `EXTRA_RSSI` — proximity (optional).
- `EXTRA_NAME` / `getName()` — friendly name, often model/owner.
- `getBluetoothClass()` — Class of Device; decode `getMajorDeviceClass()` (PHONE=512, COMPUTER=256, AUDIO_VIDEO=1024, WEARABLE=1792, HEALTH=2304, PERIPHERAL=1280, etc.) + minor class + `hasService()` for categories. Needs `BLUETOOTH_CONNECT`.
- `getType()` — CLASSIC/LE/DUAL.
- `getUuids()` (cached) and `fetchUuidsWithSdp()` → `ACTION_UUID` — enumerate service profiles (A2DP/HFP/HID…) **without pairing**. Needs `BLUETOOTH_CONNECT`.

**Honest limits:** discovery is an **active ~12 s inquiry** (the phone transmits — not purely passive) that degrades existing connections, so loop it (start → ~12 s → finished → restart) and always cancel before any connect. And it only finds devices that are **currently discoverable** — most phones (iPhones especially) and in-use headsets are not, so Classic catches far fewer devices than BLE. But the ones it catches give a real MAC, which is high value for fixed/surveillance hardware.

---

## 3. Logging — maximum passive detail per device (its own subsystem)

Log every field the APIs expose, timestamped and GPS-stamped, into a queryable store (SQLite), exportable to CSV / KML / JSON. Full field inventory, all readable unrooted:

**WiFi (`ScanResult`):** BSSID, SSID (`getWifiSsid()` on 33+), level/RSSI over time, frequency, channelWidth, centerFreq0/1, `capabilities` (and `getSecurityTypes()` on 33+), timestamp, `getWifiStandard()` (30+), `is80211mcResponder()`, Passpoint flags, and — the big one — **`getInformationElements()` (30+)**: raw 802.11 IEs. Parse by element ID: 221 vendor-specific (OUI+type; WPS under MS OUI 00:50:F2 can carry device name/manufacturer/model), 48 RSN (exact WPA2/WPA3-SAE/OWE + PMKID), 0/1/3/45/191/255 (capability bitmasks = durable firmware fingerprint).

**BLE (`ScanResult`+`ScanRecord`):** address + subtype (top-two-bits), rssi over time, `getTxPower()` (adv) vs `getTxPowerLevel()` (AD) for path-loss/distance estimate, primary/secondary PHY, advertisingSid, periodicAdvertisingInterval, dataStatus, isLegacy/isConnectable, **all manufacturer data by company ID**, all service UUIDs, service data, advertise flags, appearance (AD type 0x19), local name. `getAdvertisingDataMap()` (33+) or `getBytes()` for everything else.

**Classic:** MAC/OUI, RSSI, name, Class of Device (major/minor/service), type, SDP UUIDs.

**Derived (compute and store):** RSSI variance (movement), first/last seen, dwell time, reappearance count, co-location with GPS, and per-device "most stable identifier" (see §6) for cross-rotation tracking.

Note the WiFi throttle: register for `SCAN_RESULTS_AVAILABLE_ACTION` to also harvest scans other apps/the system trigger — those don't count against your 4-per-2-min quota ("piggybacking").

---

## 4. Display / user-facing info

- Per-device detail page: every logged field, with a plain-language "why flagged" evidence trail.
- A **specificity/confidence weight** (High/Medium/Low) per match — not a binary. §5 gives the weights.
- A prominent separate **"randomized / unknown"** bucket so a no-match isn't mistaken for "nothing there."
- "New to this location" banner (§6).
- Keep the strong/weak evidence line on every public-safety and camera result.

---

## 5. Vendor / OUI identification (the "prefixes for orgs" ask)

**The honest headline first:** there is **no state/federal law-enforcement MAC prefix.** The IEEE assigns prefixes to *manufacturers*, never to agencies or operators. A vendor match is NEVER proof police or government are present — the gear could belong to a private guard, a film crew, a reseller, or a surplus buyer. Surface every match as *"hardware made by a company whose products are commonly used in [LE video / ALPR / radio / surveillance] — this does not indicate any agency is present."*

**Lookup logic (mostly correct in MILLIE already — confirm it):**
1. Normalize to 12 hex, uppercase.
2. Check the **U/L bit first** (bit `0x02` of octet 1 — i.e. 2nd hex digit ∈ {2,6,A,E}). If set → locally administered / randomized → **not in the registry, do not attribute.** Also skip multicast (bit `0x01`).
3. Globally-administered only: **longest-prefix-first** — 36-bit (MA-S, 9 hex) → 28-bit (MA-M, 7 hex) → 24-bit (MA-L, 6 hex). This order is mandatory: MA-M/MA-S blocks are sub-delegated out of IEEE-held parent OUIs, so a 24-bit-only lookup mis-attributes every MA-M/MA-S device (classic example: `70:B3:D5` is the IEEE's own parent OUI shared by thousands of small vendors). Store an explicit prefix-length/mask per entry (Wireshark's `manuf` file is the model).

Authoritative files (re-pull on a schedule, don't hardcode): `standards-oui.ieee.org/oui/oui.csv`, `/oui28/mam.csv`, `/oui36/oui36.csv`. Snapshot sizes today: MA-L ≈40,420, MA-M ≈6,660, MA-S ≈7,250 records.

**Two accuracy caveats that undercut brand matching:** (a) the MAC belongs to the **radio chipset**, often not the device brand — a Getac/Toughbook/Cradlepoint box frequently emits its Intel/Qualcomm/Realtek WiFi OUI, so "no Getac OUI" ≠ "no Getac gear"; (b) randomized addresses dominate modern phones/BLE, so absence of a match means nothing.

### LE/gov/surveillance-associated vendors (all prefixes verified present 2026-10-29)

**HIGH brand specificity (gear exists mainly to serve LE/gov — still not proof of an agency):**
- **Axon Enterprise, Inc.** (ex-TASER) — `00:25:DF` (MA-L). Body cams, TASERs, Fleet.
- **WatchGuard Video** — `00:1D:96` (MA-L), Plano TX. ⚠️ NOT "WatchGuard Technologies" (`00:01:21`,`00:90:7F`) = unrelated firewall company.
- **Digital Ally, Inc.** — `00:23:BD` (MA-L).
- **L-3 Communications Mobile-Vision** — `3873EA0` (MA-M). "Flashback" in-car police DVR.
- **Utility, Inc / Utility Associates** — `00:16:ED`, `00:09:BC` (MA-L). BodyWorn / Rocket IoT.
- **Flock Safety** — `B4:1E:52` (MA-L). ALPR/Falcon/Raven. ⚠️ NOT "Flock Audio Inc." (`8C1F64574` MA-S) = pro-audio.
- **Neology** — `00:17:3D` (MA-L). RFID/ALPR (also tolling).
- **ELSAG North America** — `70B3D51C5` (MA-S). MPH-900 ALPR. (Industrial "ELSAG BAILEY" `00:C0:C9` / "Elsag Datamat" `00:40:DE` are different lines.)
- **Skydio Inc.** — `38:1D:14` (MA-L). Police "drone as first responder."
- **Cellebrite** — `00:16:00` (MA-L). Forensic extraction — but bench tools, rarely broadcasting in the field.

**MEDIUM — LE-heavy but sell broadly (down-weight):**
- **Motorola Solutions Inc.** — `00:04:7D`, `00:1F:92`, `00:18:85`, `4C:CC:34`; Malaysia `10:74:6F`, `B8:E2:8C`, `9C:86:2B` (MA-L). ⚠️ **CRITICAL:** key on "Motorola **Solutions**" NOT the word "Motorola" — "Motorola **Mobility** LLC (a Lenovo Company)" owns ~148 OUIs (`5016F4`, `C4A052`, `A470D6`, `E89120`…) that are **consumer Moto phones**, not public-safety.
- **Sierra Wireless** — `00:A0:D5`, `50:13:9D`, `84:DB:2F`, `64:CE:6E`, `CC:93:4A`, `28:A3:31`. In-vehicle routers, but mostly commercial M2M.
- **Cradlepoint** — `00:30:44`, `00:E0:1C`. Patrol-car routers, but huge in retail/enterprise.
- **Verkada** — `E0:A7:00`. Surveillance-specific brand, but customers mostly private enterprise/schools.
- **Avigilon Alta** — `70:1A:D5` (owned by Motorola Solutions).
- **Axis Communications AB** — `00:40:8C`, `B8:A4:4F`, `E8:27:25`, `AC:CC:8E`. Broad city/transit/retail CCTV.

**LOW / near-zero specificity:** Harris / L-3 / L3Harris (mostly defense/avionics, not patrol radios); Genetec (software vendor, little own hardware; ⚠️ not "GENETEC Corporation" Tokyo or "Netgenetech" Korea); **Hikvision** (80+ blocks) and **Zhejiang Dahua** (30+ blocks) — ubiquitous consumer/OEM, and §889-banned federally so a match argues *against* gov; FLIR/Teledyne (broad commercial).

**No own-name registration (will hide as chipset vendor):** Getac (MiTAC group — `MITAC` blocks `00:22:20`,`00:40:D0`,`14:84:30`… only weakly suggestive), Panasonic/Toughbook (no rugged-specific block), Coban police-video (⚠️ "COBAN SRL" `24A3F07`/`001BC50B4` is an unrelated Italian company), Vigilant Solutions.

### Bluetooth identifiers
Company IDs (in manufacturer data 0xFF, little-endian): Apple `0x004C`, Microsoft `0x0006`, Google `0x00E0`/`0x018E`, Samsung `0x0075`, Nordic `0x0059` (common in clone trackers), Tile `0x067C`, Chipolo `0x08C3`, Garmin `0x0087`, Amazon `0x0171`. **Pebblebee/Fitbit/Tractive have no own company ID** — Fitbit rides Google; Pebblebee rides Apple/Google find-networks (classify by network format, below).
Service UUIDs: Tile `0xFEED`/`0xFEEC`, Chipolo `0xFE33`/`0xFE65`, Google Fast Pair `0xFE2C`, Eddystone **and** Google Find My Device Network `0xFEAA` (disambiguate by frame-type byte: `0x40`/`0x41` = FMDN tracker; `0x00`/`0x10`/`0x20` = Eddystone), Exposure Notification `0xFD6F`, Samsung SmartThings/Galaxy `0xFD5A`/`0xFD59`/`0xFD6B`/others.
Source files (re-pull): `bluetooth.com/specifications/assigned-numbers/` + the SIG `assigned_numbers` YAML repo.

---

## 6. Outside-the-box passive features (ranked by value)

**6.1 Separated-tracker (stalking) detection — the core defensible feature.** Detect the state where a tracker is *away from its owner* and following *you*:
- **AirTag/Find My:** manufacturer data `4C 00` + type **`0x12`** + len `0x19` + status byte (top 2 bits = battery) + 22-byte rotating key. The MAC *is* the key (static-random, top bits `11`), so it never shows a vendor — detect by the payload. A genuine separated AirTag holds the **same key/MAC stable ~24 h** (vs ~15 min near its owner) — that stability is what lets repeated-sighting logic fire. (Heinrich PoPETs 2021; AirGuard WiSec 2022; FakeTag firmware for the status byte.)
- **Samsung SmartTag:** service data `0xFD59`/`0xFD5A`, byte 0 bits 5–7 = state (`010` lost / `011` overmature-lost = report-worthy); 8-byte privacy ID stays **fixed for 24 h** in overmature-lost (trackable window). (Yu et al., USENIX Sec 2023.)
- **Tile:** `0xFEED`/`0xFEEC`, **static MAC that never rotates** — one sighting fingerprints it for life. Easiest tracker to catch. (Georgia Tech 2025.)
- **Detection logic** (AirGuard's shipping thresholds): same identifier seen **≥3 times, across ≥400 m of your movement, over ≥30 min** → alert. The hard part is surviving rotation, which you beat using the stability windows above.

**6.2 Apple Continuity parsing — free device intel, fully passive.** Under company ID `0x004C`, concatenated `[type][len][payload]` messages, cleartext unless noted (Martin PoPETs 2019; Celosia & Cunche 2020):
- `0x07` Proximity Pairing (AirPods): model ID, color, in-ear/case state, per-bud+case battery, and a **lid-open counter** → battery×lidcount ≈160 states = a session-stable pseudo-ID that survives MAC rotation.
- `0x10` Nearby Info: an **activity-level** byte (idle/screen-on/video/driving/call) broadcast near-constantly.
- `0x0F` Nearby Action: iOS-setup actions leak device class/model/color/OS in cleartext; WiFi-password-sharing carries 24-bit hashes of email/phone/SSID.
- `0x0C` Handoff: a non-resetting **IV** that chains addresses across a MAC rotation.
- `0x05` AirDrop: truncated unsalted SHA-256 of the sender's email/phone (probabilistic recovery only).
- Net: you can passively classify Apple **device model + OS + current activity**, and link rotations short-horizon. You cannot decrypt payloads.

**6.3 Fast Pair / Swift Pair / Samsung / Amazon decode.** Fast Pair `0xFE2C` in pairing mode advertises a cleartext **24-bit Model ID** → exact make/model; the battery extension leaks L/R/case battery. Microsoft Swift Pair (CDP, company `0x0006`) leaks a cleartext **device class** (iPhone/Android/Windows laptop/Surface Hub…) + Nearby-Share "Everyone" flag. Samsung/Amazon: parse raw and fingerprint (thinner public field maps — budget your own capture work).

**6.4 BLE↔WiFi correlation — narrow but strong when it hits.** An iPhone with **Personal Hotspot ON becomes a visible WiFi AP**, and its beacon carries a vendor IE containing the device's Bluetooth address (WiFi MAC ≈ BT address +1 in ~95% of devices). On API 30+ read that via `getInformationElements()` and tie the hotspot to its Continuity BLE identity. Only works while actively hotspotting; otherwise fall back to co-presence timing/RSSI correlation (heuristic — label it as such).

**6.5 "New to this location" anomaly — pure app logic, fully feasible.** Define location by the stable set of visible WiFi BSSIDs (+ geofence), keep a rolling baseline of identifiers normally seen there (using each device's most-stable identifier: Tile static MAC, SmartTag 24 h privacy ID, AirTag 24 h key, Fast Pair model ID, AirPods lid-count pseudo-ID, AP IE fingerprint), and flag an identifier that's absent from the baseline AND co-moves with you. This is the "is something following me that doesn't belong here" primitive.

---

## 7. What NOT to build (honest no-gos)

- **De-randomizing other phones' WiFi probe MACs** (IE/sequence-number/WPS-UUID fingerprinting) — needs monitor mode; impossible on a stock phone. Don't market it. (The same IE-fingerprint idea *does* work on APs, which you can see — use it there.)
- **Decrypting Handoff/Find My payloads or recovering an AirTag's owner** — cryptographically out of reach.
- **Treating any vendor match as "agency present"** — §5.
- **`neverForLocation`** — filters beacons out of your scan.

---

## 8. Suggested priority

1. Separated-tracker detection (§6.1) — highest defensible value, pure BLE parsing.
2. Logging subsystem (§3) — everything else feeds it; do it early so no data is lost.
3. Vendor table + lookup hardening (§5).
4. Apple Continuity parsing (§6.2) and Fast Pair/Swift Pair (§6.3).
5. Bluetooth Classic (§2).
6. "New to this location" (§6.5) and WiFi IE evil-twin accuracy (§1).
7. BLE↔WiFi hotspot correlation (§6.4) — nice-to-have.

---

## Sources
Android: developer.android.com references for ScanResult (WiFi + BLE), ScanRecord, BluetoothAdapter/Device/Class, bt-permissions, wifi-scan. IEEE RA CSVs (standards-oui.ieee.org; RA at standards.ieee.org/products-programs/regauth). Bluetooth SIG assigned_numbers (bluetooth.com + SIG YAML repo). Google Fast Pair & FMDN specs (developers.google.com/nearby). MS-CDP beacon spec (learn.microsoft.com). Research: Martin et al. *Handoff All Your Privacy* PoPETs 2019; Celosia & Cunche *Discontinued Privacy* PoPETs 2020; Vanhoef et al. AsiaCCS 2016; Martin et al. *MAC Randomization…When it Fails* PoPETs 2017; Heinrich et al. *Who Can Find My Devices?* PoPETs 2021; Heinrich/Bittner/Hollick *AirGuard* WiSec 2022; Yu et al. *SmartThings Find* USENIX Sec 2023; Positive Security *Find You*; Georgia Tech Tile analysis 2025.

**Two parser details to validate against a live capture before shipping:** the MS-CDP spec's internal length inconsistency, and the exact genuine-AirTag separated-rotation interval (~24 h reported; the load-bearing fact is key *stability*, confirmed empirically by Positive Security).
