<p align="center">
  <img src="docs/assets/swivel-banner.svg" alt="SWIVEL — see what's broadcasting around you" width="100%">
</p>

<p align="center">
  <strong>See what's broadcasting around you — and keep the receipts.</strong><br>
  A passive, privacy-first Wi-Fi &amp; Bluetooth scanner for Android that flags nearby cameras, trackers, and surveillance gear, and logs <em>everything</em> it hears to disk for later analysis — using only your phone.
</p>

<p align="center">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-55F5A0?style=flat-square">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-55F5A0?style=flat-square">
  <img alt="privacy" src="https://img.shields.io/badge/radio-receive--only-4DA6FF?style=flat-square">
  <img alt="no tracking" src="https://img.shields.io/badge/accounts-none-4DA6FF?style=flat-square">
  <img alt="offline" src="https://img.shields.io/badge/data-stays%20on%20device-4DA6FF?style=flat-square">
</p>

---

## What it is

SWIVEL turns an ordinary Android phone into a passive radio scanner. It listens to the Wi-Fi and Bluetooth advertisements already in the air around you, classifies what it finds — surveillance cameras, item trackers (AirTag, Tile, SmartTag…), and public-safety gear (Flock, Axon/TASER, Motorola) against a bundled, offline device database — and **writes every observation to a crash-proof log on your phone** so you can analyse it later.

It is **receive-only**. It never transmits, never phones home, and never asks you to sign in. Everything stays on your phone until *you* choose to export it.

<p align="center">
  <img src="docs/assets/screenshot-live.png" alt="SWIVEL live radar screen" width="300">
</p>

## Why it's private by design

- **Receive-only.** SWIVEL only *listens*. It sends no packets, runs no attacks, and can't interfere with any network.
- **No internet.** The app makes no network calls; device lookups use a bundled offline IEEE registry.
- **No accounts, no analytics.** Nothing to sign up for, nothing phoned home.
- **Your data stays local.** The log lives in app-private storage; you choose if and when to export. A one-tap **DURESS · CLEAR ALL** wipes the session *and* deletes every export from disk.
- **Never a false vendor.** A randomized or locally-administered MAC carries **no** vendor attribution — the app refuses to guess a maker from an address that has none. (More in [the hard rule](#the-hard-rule).)

## What it detects

| View | What you get |
| --- | --- |
| **Live** | Wi-Fi, BLE and Classic observations, a signal-strength radar scope, and a distribution chart. Wi-Fi is amber; BLE is blue. |
| **Cameras** | IEEE manufacturer matches for camera makers — Ring, Wyze, Axis, Hikvision, Dahua, Reolink, Nest, Arlo, and more. Known router and base-station names are excluded. |
| **Trackers** | Apple Find My signatures, Tile and Samsung SmartTag service UUIDs, Chipolo/Pebblebee, and explicitly advertised tracker names — plus separated-tracker "following you" alerts. |
| **Public Safety** | Flock Safety, Axon/TASER, Motorola Solutions, WatchGuard Video, and Digital Ally — by IEEE vendor and Bluetooth SIG identifiers, graded by specificity. |
| **Threat** | Evil-twin and karma/pineapple heuristics, security-downgrade detection, BLE advertisement swarms, and (with a CYD) Wi-Fi deauth/attack detection. |
| **Signal details** | Full signal history, address, manufacturer, match evidence, source, observation ages, and the raw radio fields received. |

> **A signature is evidence, not proof.** An IEEE assignment identifies an *organization*, not a confirmed product or its state. Some camera makers also sell routers and hubs; a Find My signature doesn't prove an AirTag; a Flock or Axon match doesn't establish a model, recording state, or the presence of police. Silent devices can be missed. The radar shows signal strength — it does not locate or measure distance.

### Evil-twin, done honestly

The evil-twin check flags the same SSID broadcast from **more than one real (globally-administered) vendor**. It deliberately **ignores locally-administered BSSIDs** — so public/community hotspots broadcast with randomized BSSIDs (e.g. "Spectrum Mobile") and a single router's own multi-BSSID bands no longer masquerade as dozens of fake vendors. Even across two real vendors it stays **weak** unless the Wi-Fi Information-Element fingerprints *differ*, and it's meant to be corroborated by a **security downgrade** before you trust it. The result: far fewer false alarms in dense housing.

## Logging — it keeps everything

Every AP, every BLE and Classic advert — **including randomized-address devices and every SSID** — is appended to a per-session file as it arrives, one JSON object per line (NDJSON) in app-private storage. No per-device collapse, no throttle, no row cap.

- **Crash-proof.** A background writer flushes each scan batch to disk, so a background-app kill loses at most the sub-second in-flight burst.
- **Rich.** Each row carries time, source, kind, MAC, address type, vendor (only when real), classification, threat flag + reason, SSID/name, RSSI, frequency/channel, security, raw BLE manufacturer data, service UUIDs/data, the raw **Wi-Fi Information Elements**, BLE **txPower/PHY/advertising** metadata, and `lat`/`lon`/`accuracy` when a fix is available.
- **Capture toggle.** **LOG EVERYTHING** (default) ⇄ **FLAGGED ONLY**.
- **Scan toggle.** **PHONE SCAN · ON/OFF** — a real "go quiet" control that stops every radio and the foreground service.
- **Export.** **CSV · KML · JSON**, plus a **WigleCSV** for Kismet/WiGLE — all streamed from the persisted file (multi-MB captures don't OOM) and shared via the system sheet.

> The log holds GPS in many rows. It lives only on your phone, and the repo's `.gitignore` keeps logs and exports out of version control.

## Analyse your logs (desktop)

[`tools/swivel_crosscheck.py`](tools/README.md) validates, enriches and merges a capture against **free public databases** — offline by default, online only when you ask:

- **`enrich`** (offline) — decode OUI vendor + BLE company IDs and audit the hard rule.
- **`enhance`** (offline) — merge SWIVEL + Kismet captures from one folder, fill in blank vendors/names, drop unidentifiable noise, and write one clean dataset for Kismet and other databases.
- **`kismet-diff`** (offline) — coverage and SSID agreement vs a Kismet wiglecsv.
- **`wigle`** / **`deflock`** (online, opt-in) — WiGLE BSSID lookups and DeFlock/OSM ALPR-camera proximity. Neither ever uploads your GPS track.

## The hard rule

Attribution must never outrun the evidence. SWIVEL enforces one rule everywhere it names a maker: **a randomized or locally-administered MAC gets an address type and no vendor.** The log enforces it at write time, the evil-twin detector obeys it, and the desktop tools re-audit it. A vendor match means *hardware made by X* — never *an agency is present*.

## Install

**Build it yourself** (works today, see below), or grab a signed build from the [Releases page](https://github.com/officialtomblack/SWIVEL/releases). An F-Droid listing is planned.

1. Install the APK (enable "install unknown apps" for your browser/file manager if prompted).
2. Open **SWIVEL** and grant **Location** and **Nearby devices / Bluetooth**. Keep Wi-Fi, Bluetooth, and location services on — Android returns **no** scan results while the system Location toggle is off, even with permission granted.
3. Scanning starts on its own; tap any signal for its full details. **PHONE SCAN · OFF** stops it.

> Requires **Android 8.0+**. Wi-Fi scan cadence and caching are controlled by Android. No Termux, no root.

## Build from source

Needs **JDK 17** and the Android SDK (`ANDROID_SDK_ROOT` set, or `local.properties` → `sdk.dir`).

```bash
cd millie2/android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Full build notes: [millie2/android/README.md](millie2/android/README.md).

## Optional: add a CYD (ESP32)

The phone works entirely on its own. For more reach, a [CYD](docs/millie2-cyd.md) (an ESP32 board) plugs in over USB and adds what a phone's radios physically can't see: **2.4 GHz Wi-Fi management/control frames** and **deauth / attack detection**, plus a second scanning radio.

1. Flash the firmware (see the [hardware guide](docs/millie2-cyd.md) — it's an app-partition image, not a full-flash).
2. Connect the CYD to a USB-OTG port with a **data cable**.
3. Open SWIVEL, tap **CYD LINK**, accept the USB prompt. **PHONE + CYD** means both are active; shared devices merge into one record.

The Android app owns all classification; the firmware only streams raw observations.

## Verification

Verified on a **Samsung Galaxy A12 (Android 12)**: the on-device instrumentation suite passes — dual-source merge, device classification, the randomized-MAC hard rule, evil-twin logic, BLE↔Wi-Fi correlation, and the persistent-log round-trip. Live Wi-Fi + BLE + Classic capture with GPS was confirmed against a real multi-thousand-row session (0 hard-rule breaks, independently re-audited by the desktop tool). The EXPORT / scan-toggle / duress **UI** paths are code-complete and build-clean, with final on-device UI verification in progress. The Android 14/15 location-foreground-service path and screen-off scanning are not yet device-verified.

More: [feature map](docs/FEATURES.md) · [parallel scans & dedup](docs/millie2-verification.md) · [public-safety matching & sources](docs/millie2-public-safety.md) · [IEEE registry provenance](millie2/android/app/src/main/assets/IEEE_REGISTRY_SOURCE.txt).

## Credits & license

Maintained by [**@officialtomblack**](https://github.com/officialtomblack) — a privacy-focused, security-hardened fork.
Originally created as **MILLIE** by *idea grey* ([upstream](https://github.com/IDEAGREY/MILLIE)); see [docs/project-history.md](docs/project-history.md) for lineage.

Licensed under the **MIT License** — © 2026 tcmidiservices. See [LICENSE](LICENSE).
