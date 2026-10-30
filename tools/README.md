# SWIVEL cross-check tools

Desktop-side validation and enrichment for a SWIVEL capture, against **free public databases**.
Offline by default; the online paths are opt-in because your log carries GPS in every row.

## Get a capture onto Linux

Plug the phone in (USB debugging on) and pull the live session log:

```bash
adb exec-out run-as com.millie.civops2 \
  sh -c 'cat files/logs/$(ls -t files/logs | head -1)' > session.ndjson
```

Or use the app's **EXPORT** button, which writes `swivel_<stamp>.json` and `swivel_<stamp>_wigle.csv`
to `/sdcard/Android/data/com.millie.civops2/files/` (pull with `adb pull`).

## Subcommands

| command | network | what it does |
|---|---|---|
| `enrich <file>` | **none** | Decode OUI vendor (IEEE registry) + BLE company IDs, and audit SWIVEL's own attributions — confirms the hard rule (locally-administered MAC ⇒ no vendor) and flags any disagreement with IEEE. |
| `enhance <files/dir>` | **none** | **Merge** SWIVEL + Kismet captures from one folder into a single clean dataset: one row per device, **blanks filled** (vendor from OUI, BLE company from the manufacturer id, best SSID/channel/security across sources, RSSI range, first/last seen, position, `sources`), and **unidentifiable random MACs dropped** (keep them with `--keep-noise`). This is the "for me, to use with Kismet and other databases" output. |
| `kismet-diff <swivel_wigle.csv> <kismet_wigle.csv>` | **none** | Coverage (who saw which BSSIDs) and SSID agreement on shared ones. Kismet exports wiglecsv natively. |
| `wigle <file> --auth NAME:TOKEN` | **online, opt-in** | Look up each Wi-Fi BSSID in WiGLE → known SSID/location and a public-hotspot flag (catches "Spectrum Mobile" etc. as hotspots, not evil twins). Sends your BSSIDs to wigle.net. |
| `deflock <file> [--radius 100]` | **online, opt-in** | Pull known ALPR/Flock camera locations near your track (DeFlock data via OpenStreetMap Overpass) and flag points within range. Sends your bounding box out. |
| `selftest` | none | Built-in asserts. |

```bash
python3 swivel_crosscheck.py selftest
python3 swivel_crosscheck.py enrich session.ndjson
# drop every SWIVEL + Kismet file for one outing in a folder, get one merged, de-noised dataset:
python3 swivel_crosscheck.py enhance ./outing_2026-10-29/      # or: enhance session.ndjson Kismet-xxx.wiglecsv
python3 swivel_crosscheck.py kismet-diff swivel_xxx_wigle.csv Kismet-xxx.wiglecsv
WIGLE_AUTH="apiname:token" python3 swivel_crosscheck.py wigle session.ndjson --limit 50
python3 swivel_crosscheck.py deflock session.ndjson --radius 150
```

## Reference databases

- **IEEE OUI registry** — vendor by MAC prefix. Auto-located at `/usr/share/ieee-data/oui.txt`
  (`apt install ieee-data`), `/usr/share/nmap/nmap-mac-prefixes`, or Wireshark's `manuf`; or pass `--oui`.
  A fresher file gives better coverage (the shipped one lags newer assignments).
- **Bluetooth SIG company identifiers** — decode BLE manufacturer-data company IDs. A confident common
  subset is bundled; drop the full official list next to the script as `company_ids.json`
  (`{"76": "Apple, Inc.", ...}`, decimal keys) to cover everything.
- **WiGLE** — free account + API token at <https://wigle.net/account>. Rate-limited on the free tier.
- **DeFlock / OpenStreetMap** — ALPR camera locations, queried live via the public Overpass API.

## Privacy

`enrich` and `kismet-diff` never touch the network. `wigle` and `deflock` do, and they reveal what you
observed and roughly where — so they are separate opt-in subcommands. This tool never uploads your capture
or GPS track anywhere; sharing your wardrive (e.g. uploading to WiGLE) is a manual step you take yourself.
