# The six tabs

SWIVEL's whole interface is six tabs across the bottom. Here's what each one shows and how to read it. ([One-glance version](https://github.com/officialtomblack/SWIVEL/blob/main/docs/assets/infographic-how-to-read.svg).)

Two colors run through everything: **🟡 amber = Wi-Fi**, **🔵 blue = Bluetooth / BLE**.

## LIVE
Everything the phone hears, the moment it arrives, on a radar scope. The scope shows **signal strength, not direction**: a bright dot near the middle is strong (close); a faint dot near the edge is weak (far). It does **not** point at where a device is. The stacked amber/blue histogram shows how many contacts fall at each strength. Scanning starts by itself — there's no "start" button.

## CAMERAS
Devices made by known camera brands — Ring, Wyze, Nest, Arlo, Axis, Hikvision, Dahua, Reolink and more — matched against the built-in manufacturer registry. Common router and base-station names (Spectrum, TP-Link, Netgear, ASUS, Eero, Arlo bases) are deliberately filtered out so they don't cry wolf. A match identifies the *maker*, not a confirmed camera model.

## TRACKERS
Apple Find My / AirTag signatures, Tile, Samsung SmartTag, Chipolo, Pebblebee and named trackers. It also carries a **"this one is separated and traveling with you"** alert — the classic case of a tag slipped into a bag. *(That following-you alert is wired up but not yet verified against a real separated tag on a device — treat it as unconfirmed for now.)*

## PUBLIC SAFETY
Hardware from surveillance and law-enforcement vendors — Flock Safety, Axon/TASER, Motorola Solutions, WatchGuard Video, Digital Ally — matched by manufacturer and Bluetooth identifiers and **graded High / Medium / Low** by how specific the match is. Look-alike companies (Flock Audio, Motorola Mobility) are kept out so they can't trip a false alarm.
**Read the label, not your fears:** a match means a device *made by* that vendor is nearby — not that police, any agency, or any person is present.

## ALERTS
A running list of everything that set off an **audible alert**, newest first. Three beeps = high-confidence (body-cam / ALPR / in-car makers). One beep = lower-confidence dual-use gear (expect more false alarms). The same device won't re-beep for ~90 seconds, so one car beside you doesn't nag — but a *different* vehicle still alerts on its own. Tap any entry for the signal, the vendor and the evidence. Turn alerts on/off and tune sensitivity under MORE.

## MORE
Your controls:
- **PHONE SCAN · ON/OFF** — genuinely stops every radio and the background service (a real "go quiet," not a fake label).
- **LOG EVERYTHING / FLAGGED ONLY** — capture it all (default) or only devices that tripped an alert.
- **LE / CAMERA ALERTS** and **ALERT SENSITIVITY** (Low / Medium / High).
- **CAMERA LOCATION ALERTS** — optional GPS warning near fixed cameras *you've mapped* (uses more battery).
- **SWEEP SUBNET** — the one active tool; lists live hosts on the Wi-Fi you're joined to.
- **EXPORT** — one tap writes CSV + KML + JSON + WigleCSV.
- **DURESS · CLEAR ALL** — the panic wipe: deletes every log and export instantly, no recovery.
