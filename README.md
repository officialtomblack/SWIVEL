# SWIVEL

<p align="center">
  <img src="docs/assets/swivel-banner.svg" alt="SWIVEL — see what's broadcasting around you" width="100%">
</p>

<p align="center">
  <strong>Your phone can already hear every camera, tracker, and gadget broadcasting around you.</strong><br>
  SWIVEL just lets you <em>see</em> them — and keep the receipts. No hacking. No signups. Nothing ever leaves your phone.
</p>

<p align="center">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-55F5A0?style=flat-square">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-55F5A0?style=flat-square">
  <img alt="mode" src="https://img.shields.io/badge/mode-passive%20·%20no%20attacks-4DA6FF?style=flat-square">
  <img alt="accounts" src="https://img.shields.io/badge/accounts-none-4DA6FF?style=flat-square">
  <img alt="internet" src="https://img.shields.io/badge/app%20internet-never-4DA6FF?style=flat-square">
  <img alt="offline" src="https://img.shields.io/badge/data-stays%20on%20device-4DA6FF?style=flat-square">
  <img alt="root" src="https://img.shields.io/badge/root-not%20required-55F5A0?style=flat-square">
</p>

---

## 👁️ What SWIVEL does for you

Every Wi-Fi network, every AirTag, every security camera, every Bluetooth gadget nearby is **announcing its presence into the air all the time.** Your phone's radios pick up those announcements — it just never shows them to you.

SWIVEL turns that on. Open the app and it immediately starts **scanning** the Wi-Fi and Bluetooth signals around you, sorts them into plain categories — **cameras, trackers, surveillance gear, suspicious networks** — and quietly writes down *everything* it hears so you can look back later.

- 🛰️ **Passive — it never attacks.** SWIVEL doesn't deauth, inject, jam, join, or connect to anything. It uses the same ordinary Wi-Fi and Bluetooth scanning your phone already does every day — it just shows you the results and records them. (It sends the normal "discovery" pings any phone sends to find networks and devices, and nothing more.)
- 🔌 **No internet. Ever.** The app itself makes zero network calls — it has no internet permission at all. It figures out who made a device from a dictionary built right into the app. Nothing you see is ever sent anywhere.
- 🙅 **No account, no signup, no analytics.** Install it, open it, done. There is nothing to log into and nothing phoning home.
- 📱 **Just a normal phone.** Android 8.0 or newer. No "rooting," no Termux, no technical setup.

> You don't need to be a hacker, a programmer, or a security expert — you don't even need to know what a "MAC address" is. If you can install an app and tap a button, you can use SWIVEL.

<p align="center">
  <img src="docs/assets/screenshot-live.png" alt="SWIVEL live radar screen" width="300">
</p>

---

## 🚀 Quick start (literally 4 steps)

1. **Install it.** Put the app on your phone (see [Get the app](#-get-the-app) below). If Android warns about "installing unknown apps," allow it for your browser or file manager — that's normal for apps outside the Play Store.
2. **Open SWIVEL and say yes to two things.** It asks for **Location** and **Nearby devices / Bluetooth**. It needs these to *hear* radios — not to find *you*. (Your location only ever gets written into your own private log, and that log never leaves the phone.)
   - ⚠️ Keep Wi-Fi, Bluetooth, **and** your phone's Location switch turned **on**. Android hands back *nothing* while the system Location toggle is off — even after you've granted permission. That's an Android rule, not a SWIVEL one.
3. **That's it — scanning starts by itself.** No "start" button to hunt for. The **LIVE** screen fills up with blips: 🟡 amber is Wi-Fi, 🔵 blue is Bluetooth. The radar scope shows which signals are strong (close) and which are faint (far).
4. **Tap around.** Flip between the tabs across the bottom:

| Tab | What it's showing you |
|---|---|
| **LIVE** | Everything, as it comes in, on the radar scope. |
| **CAMERAS** | Devices made by known camera brands. |
| **TRACKERS** | AirTags, Tiles, SmartTags and friends — including a **"this one is following you"** alert. |
| **PUBLIC SAFETY** | Gear from surveillance and law-enforcement vendors. |
| **MORE** | Your controls: the capture toggle, the quiet button, EXPORT, and the panic wipe. |

**Something got flagged — now what?** Tap it. SWIVEL shows you *exactly why* — the signal history, the raw radio details, and the evidence behind the label. Nothing is hidden, and nothing is dressed up as more certain than it is (see [Evidence, not proof](#-evidence-not-proof)).

---

## 🔒 Where your stuff lives — and how to wipe it (plain English)

This is the part that matters, so here it is with no jargon.

### 📍 It all stays on your phone
Everything SWIVEL hears is written into **the app's own private folder** — a locked box that *no other app on your phone can open.* It is not in the cloud. It is not on a server. There is no "SWIVEL account" holding your data, because there is no account at all. **Nothing is ever uploaded.**

### 📤 Want a copy on your computer? Tap EXPORT
One tap on **EXPORT** makes a shareable copy of your session in four formats at once — a spreadsheet (CSV), a map file (KML, opens in Google Earth), a data file (JSON), and a `WigleCSV` that war-driving tools like Kismet and WiGLE understand. The copy lands in a folder you can reach from a computer:

```
Android/data/com.millie.civops2/files/
```

To get it onto a computer: plug the phone in with a USB cable and open that folder like a USB stick, or use your phone's **Files** app to copy it out. From there *you* decide where it goes — SWIVEL hands it to Android's normal share sheet and steps back. It never sends it for you.

### 🧨 Want it all gone? Tap DURESS · CLEAR ALL
One tap on the **DURESS · CLEAR ALL** panic button instantly:
- 🗑️ deletes **every** saved session log off the phone,
- 🗑️ deletes **every** export you've made, and
- 🧹 clears everything out of memory, then starts fresh.

No "are you sure," no recovery, no leftovers. *(Checked on a real phone during one session: one tap wiped 5 session logs and 4 exported files off the disk, instantly.)*

### 🤫 Why so careful about the cloud?
Because your log can include **your GPS location** on many of its lines — a record of where *you* were when you heard each signal. That is exactly why SWIVEL keeps it locked on your phone, and why this project deliberately keeps those files out of its public code (they're git-ignored). Your map is yours. Full stop.

---

## ✨ What it can spot

| 🎯 | Category | What SWIVEL flags |
|---|---|---|
| 📷 | **Cameras** | Devices made by known camera brands — Ring, Wyze, Axis, Hikvision, Dahua, Reolink, Nest, Arlo and more — matched against a built-in manufacturer registry. Common router/base-station names (Spectrum, TP-Link, Netgear, ASUS, Linksys, Eero, Arlo bases) are filtered out so they don't cry wolf. |
| 🏷️ | **Trackers following you** | Apple **Find My** / AirTag signatures, **Tile**, Samsung **SmartTag**, Chipolo, Pebblebee, and named trackers — plus a **"separated tracker is traveling with you"** alert for the classic AirTag-in-your-bag case. *(Implemented and wired up; not yet verified against a real separated tag on a device.)* |
| 🚔 | **Public-safety gear** | Hardware from **Flock Safety, Axon/TASER, Motorola Solutions, WatchGuard Video, Digital Ally** — matched by manufacturer and Bluetooth identifiers and graded **High / Medium / Low** by how specific the match is. Look-alike companies (Flock Audio, Motorola Mobility, WatchGuard Technologies) are deliberately kept out so they can't trip a false alarm. |
| ⚠️ | **Network threats** | **Evil-twin** fakes, **karma/pineapple** rogue access points, **security-downgrade** tricks, and **Bluetooth advertisement swarms** (Flipper / spam). With the optional [CYD board](#-optional-supercharge-it-with-a-cyd), also live **deauth / attack** detection. |
| 📝 | **Full-capture logging** | Not just the scary stuff — it writes down *everything* it hears, every network and every gadget, so you have a complete record to review later. |
| 📤 | **One-tap export** | CSV + KML + JSON + WigleCSV, all at once, streamed straight from the saved file so even huge captures never choke the phone. |
| 🧨 | **One-tap panic wipe** | DURESS · CLEAR ALL erases every log and every export off the disk in a single tap. |

### 🗃️ It keeps *everything* — and it's crash-proof

SWIVEL doesn't just show you blips and forget them. **Every** Wi-Fi access point and **every** Bluetooth advertisement — including ones with scrambled, randomized addresses, and every network name — gets written to a session file **the instant it arrives**, one line at a time.

- 🧱 **Nothing is collapsed, throttled, or capped.** It doesn't squish duplicates together or stop after some limit — it keeps the lot.
- 🛟 **It survives a crash.** A background writer flushes each batch of signals to disk as it goes, so if Android kills the app in the background, you lose at most the last split-second still in flight — never the session.
- 🔬 **Every line is rich.** Time, signal type, address, the real maker *(only when the address is genuinely traceable — see the rule below)*, the classification and *why* it was flagged, network name, signal strength, channel, security type, the raw Bluetooth and Wi-Fi fields, and your GPS position **when a fix is available.**
- 🎚️ **Your call on how much.** **LOG EVERYTHING** (the default) captures it all; flip to **FLAGGED ONLY** to keep just the devices that tripped an alert.
- 🤐 **A real "go quiet" button.** **PHONE SCAN · ON/OFF** genuinely stops every radio and the background service — not a fake label. *(Checked on a real phone: with it OFF the live counters freeze cold; flip it ON and they climb again.)*

---

## 🎖️ Evidence, not proof

Most "detector" apps overpromise. SWIVEL does the opposite — on purpose — and it's the thing that makes it worth trusting.

**The hard rule:** a device with a **randomized or scrambled address gets labeled as random, and *never* gets assigned a maker.** SWIVEL flat-out refuses to guess who built something from an address that doesn't carry that information. This rule is enforced the moment anything is written to the log, it's obeyed by every detector, and it's double-checked afterward by the desktop tool. *(Re-audited on one real session's capture of 1,478 rows: **zero** breaks of the rule.)*

And when SWIVEL *does* name a maker, here's what that means and what it doesn't:

> **A signature is evidence, not proof.** Matching a manufacturer tells you *"this is hardware made by company X."* It does **not** prove which product it is, that it's recording, or that any agency or person is present. Some camera brands also make routers. A Find My signature isn't proof of an AirTag. Silent devices get missed. The radar shows signal strength — it does not pinpoint a location.

That honesty *is* the feature. SWIVEL would rather tell you less and be right than hype you up and be wrong.

---

## 🖥️ Power-user tools (optional, for a computer)

Already exported a capture? The included desktop helper [`tools/swivel_crosscheck.py`](tools/README.md) checks and enriches it against **free public databases** — offline by default, online only when you explicitly ask:

- **`enrich`** *(offline)* — fills in device makers from the public registry and **re-audits the hard rule**.
- **`enhance`** *(offline)* — merges SWIVEL + Kismet captures into one clean dataset, fills blanks, drops unidentifiable noise.
- **`kismet-diff`** *(offline)* — compares coverage and network-name agreement against a Kismet export.
- **`wigle` / `deflock`** *(online, opt-in only)* — look up the Wi-Fi networks you saw on WiGLE, and check for known ALPR/Flock cameras near your route. `wigle` sends the Wi-Fi IDs you observed to WiGLE; `deflock` sends a bounding box around your capture (your rough area) to OpenStreetMap. Neither uploads your capture file or your full GPS track.

Full details and commands: **[tools/README.md](tools/README.md)**.

---

## 📡 Optional: supercharge it with a CYD

The phone works great entirely on its own. But a phone's radios physically *can't* see certain Wi-Fi traffic. Plug in a **CYD** (a cheap ESP32 board) over USB and SWIVEL gains what the phone can't hear by itself — think of it as x-ray vision for the **2.4 GHz Wi-Fi management/control frames** and live **deauth / attack detection** a phone is blind to, plus a second scanning radio.

Plug it in, tap **CYD LINK**, accept the USB prompt — the status shows **PHONE + CYD** and the two feeds merge into one clean record. Setup guide: **[docs/millie2-cyd.md](docs/millie2-cyd.md)**.

---

## 📥 Get the app

**Build it yourself** (below), or grab a signed build from the [Releases page](https://github.com/officialtomblack/SWIVEL/releases). An F-Droid listing is planned.

### 🛠️ Build from source

Needs **JDK 17** and the Android SDK (`ANDROID_SDK_ROOT` set, or `local.properties` → `sdk.dir`).

```bash
cd millie2/android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Full build notes: [millie2/android/README.md](millie2/android/README.md) · full feature map: [docs/FEATURES.md](docs/FEATURES.md).

---

## ✅ Verification status (the honest version)

Tested on a **Samsung Galaxy A12 (Android 12)** — these are results from field sessions on that one device, not guarantees for every phone:

- ✔️ The on-device instrumentation suite **passes** — dual-source merge, device classification, the randomized-MAC hard rule, evil-twin logic, Bluetooth↔Wi-Fi correlation, and the persistent-log round-trip.
- ✔️ **EXPORT** was driven on the real UI and produced a valid **WigleCSV** — 1,478 rows, 0 hard-rule breaks when independently re-audited by the desktop tool.
- ✔️ The **PHONE SCAN** toggle truly stops and resumes the radios (live counters freeze, then climb again).
- ✔️ **DURESS · CLEAR ALL** wiped every session log *and* every export off the disk, confirmed on the device.
- ⏳ **Not yet device-verified:** the Android 14/15 location-foreground-service path, screen-off (screen-locked) scanning, and the separated-tracker "following you" alert against a real tag. If you're on Android 14 or 15, treat background scanning as unconfirmed for now.

More detail: [feature map](docs/FEATURES.md) · [parallel scans & dedup](docs/millie2-verification.md) · [public-safety matching & sources](docs/millie2-public-safety.md) · [IEEE registry provenance](millie2/android/app/src/main/assets/IEEE_REGISTRY_SOURCE.txt).

---

## 📜 Credits & license

Maintained by [**@officialtomblack**](https://github.com/officialtomblack) — a privacy-focused, security-hardened fork.
Originally created as **MILLIE** by *idea grey* ([upstream](https://github.com/IDEAGREY/MILLIE)); see [docs/project-history.md](docs/project-history.md) for the lineage.

Licensed under the **MIT License** — © 2026 tcmidiservices. See [LICENSE](LICENSE).

<p align="center"><sub>SWIVEL watches the airwaves. It never attacks. Your data never leaves your hands.</sub></p>
