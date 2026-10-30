#!/usr/bin/env python3
"""SWIVEL cross-check — validate/enrich a SWIVEL capture against free public databases.

Pull a session off the phone first, e.g.:
  adb exec-out run-as com.millie.civops2 cat files/logs/session_XXXX.ndjson > session.ndjson
or use the EXPORT button's swivel_*.json / swivel_*_wigle.csv.

Subcommands
  enrich       OFFLINE. Decode OUI vendor (IEEE registry) + BLE company IDs, and audit SWIVEL's own
               attributions — confirms the hard rule (locally-administered MAC => NO vendor) and flags
               any OUI disagreement. No network.
  kismet-diff  OFFLINE. Diff a SWIVEL wiglecsv against a Kismet wiglecsv: coverage (who saw what) and
               field agreement on shared BSSIDs. No network.
  wigle        ONLINE, opt-in. Look up each BSSID in WiGLE (known SSID/location/type). SENDS YOUR
               BSSIDs to wigle.net — a deliberate choice, never automatic. Needs a free API token.
  deflock      ONLINE, opt-in. Pull known ALPR/Flock camera locations (DeFlock data via OpenStreetMap
               Overpass) near your capture and flag points within range. SENDS YOUR BOUNDING BOX out.

Privacy: enrich and kismet-diff never touch the network. wigle/deflock do, and your log carries GPS in
every row, so the online paths are opt-in subcommands — this tool never uploads your location anywhere.
"""
import argparse, csv, json, os, sys, time, math, urllib.request, urllib.parse, urllib.error

# ---- loading a SWIVEL capture (ndjson, exported .json array, or _wigle.csv) ----

def load_records(path):
    """Return a list of dict records with at least: mac, kind, vendor, address_type, ssid, manufacturer."""
    low = path.lower()
    if low.endswith(".ndjson"):
        out = []
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line:
                    try: out.append(json.loads(line))
                    except ValueError: pass
        return out
    if low.endswith(".json"):
        with open(path, encoding="utf-8", errors="replace") as f:
            data = json.load(f)
        return data if isinstance(data, list) else [data]
    if low.endswith(".csv"):
        return _load_wigle(path)
    sys.exit(f"unrecognised file type: {path} (want .ndjson, .json, or _wigle.csv)")


def _load_wigle(path):
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        rows = list(csv.reader(f))
    # WigleWifi files start with a preheader line, then the column header, then data.
    start = 0
    for i, r in enumerate(rows):
        if r and r[0].strip().upper() == "MAC":
            start = i + 1; break
    for r in rows[start:]:
        if len(r) < 11: continue
        out.append({"mac": r[0], "ssid": r[1], "security": r[2], "iso_time": r[3],
                    "channel": r[4], "rssi": r[5], "lat": _f(r[6]), "lon": _f(r[7]),
                    "kind": {"WIFI": "WIFI", "BLE": "BLE", "BT": "CLASSIC"}.get(r[10].strip(), r[10].strip())})
    return out


def _f(s):
    try: return float(s)
    except (TypeError, ValueError): return None


def ssid_of(rec):
    return rec.get("ssid_or_name", rec.get("ssid", "")) or ""

# ---- MAC helpers (the same hard rule the app enforces) ----

def norm_mac(mac):
    return "".join(c for c in (mac or "") if c in "0123456789abcdefABCDEF").upper()

def local_admin(mac):
    h = norm_mac(mac)
    return len(h) >= 2 and (int(h[:2], 16) & 0x02) != 0

def oui6(mac):
    h = norm_mac(mac)
    return h[:6] if len(h) >= 6 else ""

# ---- IEEE OUI registry (offline) ----

OUI_PATHS = ["/usr/share/ieee-data/oui.txt", "/usr/share/nmap/nmap-mac-prefixes",
             "/usr/share/wireshark/manuf", os.path.expanduser("~/.cache/oui.txt")]

def load_oui(explicit=None):
    """Parse an IEEE oui.txt / nmap-mac-prefixes / wireshark manuf into {OUI6: vendor}. Offline."""
    path = explicit or next((p for p in OUI_PATHS if os.path.exists(p)), None)
    if not path:
        return {}, None
    table = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "(hex)" in line:                       # IEEE oui.txt: "00-11-22   (hex)\t\tVendor"
                left, _, right = line.partition("(hex)")
                key = norm_mac(left)
                if len(key) == 6 and right.strip():
                    table[key] = right.strip()
            else:                                     # nmap / manuf: "001122\tVendor"  or  "00:11:22\tVendor"
                parts = line.split("\t") if "\t" in line else line.split(None, 1)
                if len(parts) >= 2:
                    key = norm_mac(parts[0])
                    if len(key) == 6 and parts[1].strip() and not parts[0].startswith("#"):
                        table.setdefault(key, parts[1].strip())
    return table, path

# ---- Bluetooth SIG company identifiers (bundled common subset; offline) ----
# 16-bit IDs, little-endian in advertising data. This is a confident subset covering the vast majority of
# consumer traffic; drop the full official YAML (bluetooth.com assigned-numbers) next to this file as
# company_ids.json ({"76":"Apple, Inc.", ...} decimal keys) to cover everything.
BLE_COMPANY = {
    0x004C: "Apple, Inc.", 0x0006: "Microsoft", 0x0075: "Samsung Electronics Co. Ltd.",
    0x00E0: "Google", 0x0059: "Nordic Semiconductor ASA", 0x0087: "Garmin International",
    0x00C4: "LG Electronics", 0x000F: "Broadcom", 0x0001: "Nokia Mobile Phones",
    0x0078: "Nike", 0x0157: "Anhui Huami (Amazfit)", 0x0171: "Amazon.com Services LLC",
    0x038F: "Xiaomi Inc.", 0x00D2: "Qualcomm", 0x0049: "Plantronics", 0x0131: "Cypress Semiconductor",
    0x0499: "Ruuvi Innovations", 0x0085: "BlueRadios", 0x0310: "SGL Italia (iBeacon)",
    0x004F: "APT Licensing (aptX)", 0x0180: "Dialog Semiconductor", 0x02E0: "Sonos",
}

def load_company_ids():
    sidecar = os.path.join(os.path.dirname(os.path.abspath(__file__)), "company_ids.json")
    table = dict(BLE_COMPANY)
    if os.path.exists(sidecar):
        try:
            with open(sidecar, encoding="utf-8") as f:
                for k, v in json.load(f).items():
                    table[int(k)] = v
        except (ValueError, OSError):
            pass
    return table

def ble_company_id(manuf_hex):
    """First 2 bytes of BLE manufacturer data, little-endian, -> company id int (or None)."""
    h = (manuf_hex or "").split(",")[0].strip()
    if len(h) < 4: return None
    try: return int(h[2:4] + h[0:2], 16)
    except ValueError: return None

# ---- enrich (offline) ----

def cmd_enrich(args):
    recs = load_records(args.file)
    oui, oui_src = load_oui(args.oui)
    comp = load_company_ids()
    print(f"loaded {len(recs)} records from {args.file}")
    print(f"OUI registry: {oui_src or 'NOT FOUND — install ieee-data or pass --oui'} ({len(oui)} prefixes)")
    print(f"BLE company ids: {len(comp)} known\n")

    hard_rule_breaks, oui_hits, oui_miss, oui_disagree, ble_named = [], 0, 0, [], 0
    for r in recs:
        mac = r.get("mac", "")
        vendor = (r.get("vendor", "") or "").strip()
        kind = (r.get("kind", "") or "").strip()
        # Hard rule: a locally-administered MAC must carry NO vendor.
        if local_admin(mac) and vendor:
            hard_rule_breaks.append((mac, vendor))
        # OUI validation for globally-administered addresses.
        if mac and not local_admin(mac):
            ieee = oui.get(oui6(mac))
            if ieee:
                oui_hits += 1
                if vendor and not _vendor_match(vendor, ieee):
                    oui_disagree.append((mac, vendor, ieee))
            else:
                oui_miss += 1
        # BLE company-id decode.
        if kind == "BLE":
            cid = ble_company_id(r.get("manufacturer", ""))
            if cid in comp:
                ble_named += 1

    print(f"HARD RULE: locally-administered MACs carrying a vendor (should be 0): {len(hard_rule_breaks)}")
    for mac, v in hard_rule_breaks[:10]:
        print(f"   !! {mac}  vendor={v!r}")
    print(f"\nOUI (global MACs): {oui_hits} resolved in IEEE, {oui_miss} not in registry")
    print(f"OUI disagreements SWIVEL vs IEEE: {len(oui_disagree)}")
    for mac, v, ieee in oui_disagree[:15]:
        print(f"   ~ {mac}  SWIVEL={v!r}  IEEE={ieee!r}")
    print(f"\nBLE rows whose company id decodes to a known vendor: {ble_named}")
    if hard_rule_breaks:
        print("\nFAIL: hard-rule breaks present.")
        return 1
    print("\nOK: no hard-rule breaks.")
    return 0

def _vendor_match(a, b):
    """Loose vendor-name agreement (both DBs spell vendors differently)."""
    def toks(s): return {t for t in "".join(c.lower() if c.isalnum() else " " for c in s).split() if len(t) > 2}
    ta, tb = toks(a), toks(b)
    return bool(ta & tb) if ta and tb else a.lower()[:4] == b.lower()[:4]

# ---- kismet-diff (offline) ----

def cmd_kismet_diff(args):
    a = {norm_mac(r["mac"]): r for r in load_records(args.swivel) if r.get("mac")}
    b = {norm_mac(r["mac"]): r for r in load_records(args.kismet) if r.get("mac")}
    only_a, only_b, shared = set(a) - set(b), set(b) - set(a), set(a) & set(b)
    print(f"SWIVEL:  {len(a)} unique BSSIDs ({args.swivel})")
    print(f"Kismet:  {len(b)} unique BSSIDs ({args.kismet})")
    print(f"shared:  {len(shared)}")
    print(f"only SWIVEL saw: {len(only_a)}   only Kismet saw: {len(only_b)}")
    if only_b:
        cov = 100 * len(shared) // (len(shared) + len(only_b)) if (shared or only_b) else 100
        print(f"\nSWIVEL coverage of Kismet's APs: {cov}%  (missed {len(only_b)})")
        for m in list(only_b)[:15]:
            print(f"   MISSED {m}  {ssid_of(b[m])!r}")
    disagree = [m for m in shared if ssid_of(a[m]).strip() and ssid_of(b[m]).strip()
                and ssid_of(a[m]).strip() != ssid_of(b[m]).strip()]
    print(f"\nSSID disagreements on shared BSSIDs: {len(disagree)}")
    for m in disagree[:15]:
        print(f"   ~ {m}  SWIVEL={ssid_of(a[m])!r}  Kismet={ssid_of(b[m])!r}")
    return 0

# ---- wigle (online, opt-in) ----

def cmd_wigle(args):
    auth = args.auth or os.environ.get("WIGLE_AUTH", "")
    if not auth or ":" not in auth:
        sys.exit("wigle needs --auth 'APIName:APIToken' (free at wigle.net/account) or $WIGLE_AUTH")
    recs = load_records(args.file)
    bssids = sorted({norm_mac(r["mac"]) for r in recs
                     if r.get("mac") and (r.get("kind", "").strip() == "WIFI") and not local_admin(r["mac"])})
    bssids = bssids[: args.limit]
    print(f"!! ONLINE: sending {len(bssids)} BSSIDs to wigle.net (this reveals what you observed).")
    tok = __import__("base64").b64encode(auth.encode()).decode()
    found = hotspots = 0
    for i, m in enumerate(bssids):
        colon = ":".join(m[j:j+2] for j in range(0, 12, 2))
        url = "https://api.wigle.net/api/v2/network/search?" + urllib.parse.urlencode({"netid": colon})
        req = urllib.request.Request(url, headers={"Authorization": "Basic " + tok})
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.load(resp)
        except urllib.error.HTTPError as e:
            print(f"   {colon}: HTTP {e.code} ({'rate-limited; stopping' if e.code==429 else e.reason})")
            if e.code == 429: break
            continue
        except Exception as e:
            print(f"   {colon}: {e}"); continue
        results = data.get("results") or []
        if results:
            found += 1
            net = results[0]
            name = net.get("ssid") or "(hidden)"
            public = "PUBLIC-HOTSPOT" if (net.get("ssid") or "").lower() in PUBLIC_HOTSPOTS else ""
            if public: hotspots += 1
            print(f"   {colon}: {name!r}  {net.get('trilat')},{net.get('trilong')}  {public}")
        time.sleep(args.sleep)
    print(f"\n{found}/{len(bssids)} known to WiGLE; {hotspots} flagged as public hotspots "
          f"(those are NOT evil twins).")
    return 0

PUBLIC_HOTSPOTS = {"xfinitywifi", "spectrum mobile", "spectrumwifi", "attwifi", "optimumwifi",
                   "cablewifi", "eduroam", "google starbucks", "xfinity"}

# ---- deflock / ALPR (online, opt-in; DeFlock data via OpenStreetMap Overpass) ----

def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * r * math.asin(math.sqrt(a))

def cmd_deflock(args):
    recs = load_records(args.file)
    pts = [(r["lat"], r["lon"]) for r in recs
           if isinstance(r.get("lat"), (int, float)) and isinstance(r.get("lon"), (int, float))]
    if not pts:
        sys.exit("no GPS points in the capture — nothing to cross-reference")
    lats = [p[0] for p in pts]; lons = [p[1] for p in pts]
    pad = 0.01
    s, w, n, e = min(lats)-pad, min(lons)-pad, max(lats)+pad, max(lons)+pad
    print(f"!! ONLINE: querying OpenStreetMap/DeFlock ALPR cameras in bbox "
          f"{s:.4f},{w:.4f},{n:.4f},{e:.4f} (reveals roughly where you were).")
    q = (f'[out:json][timeout:40];node["man_made"="surveillance"]["surveillance:type"="ALPR"]'
         f'({s},{w},{n},{e});out;')
    try:
        req = urllib.request.Request("https://overpass-api.de/api/interpreter",
                                     data=urllib.parse.urlencode({"data": q}).encode())
        with urllib.request.urlopen(req, timeout=60) as resp:
            cams = json.load(resp).get("elements", [])
    except Exception as e:
        sys.exit(f"Overpass query failed: {e}")
    print(f"known ALPR cameras in area: {len(cams)}")
    hits = 0
    for c in cams:
        clat, clon = c.get("lat"), c.get("lon")
        if clat is None: continue
        near = min((haversine_m(clat, clon, la, lo) for la, lo in pts), default=1e9)
        if near <= args.radius:
            hits += 1
            op = (c.get("tags") or {}).get("operator", "")
            print(f"   CAMERA within {near:.0f} m  ({clat:.5f},{clon:.5f})  {op}")
    print(f"\n{hits} known ALPR camera(s) within {args.radius} m of your track.")
    return 0

# ---- enhance: merge SWIVEL + Kismet, fill blanks, drop noise (offline) ----

def _colon(h): return ":".join(h[i:i+2] for i in range(0, 12, 2))
def _s(x): return "" if x is None else str(x)
def _i(x): return "" if x is None else str(int(x))
def _fill(d, key, val):
    if val and not d.get(key): d[key] = val

def _is_random(addr_type, h):
    return (addr_type or "").startswith(("random", "local")) or local_admin(h)

def _addr_type(kind, h):
    """Fallback address_type when a source (e.g. Kismet) didn't log one."""
    if len(h) < 2: return "unknown"
    b = int(h[:2], 16)
    if kind == "BLE":
        return {0: "random_nrpa", 1: "random_rpa", 3: "random_static"}.get((b >> 6) & 3, "public")
    return "local" if (b & 0x02) else "public"

def _wigle_source(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return "swivel" if "swivel" in fh.readline().lower() else "kismet"
    except OSError:
        return "kismet"

def _iter_inputs(paths):
    import glob
    files = []
    for p in paths:
        if os.path.isdir(p):
            for pat in ("*.ndjson", "*.json", "*.csv", "*.wiglecsv"):
                files += glob.glob(os.path.join(p, pat))
        else:
            files.append(p)
    tagged = []
    for f in sorted(set(files)):
        src = "swivel" if f.lower().endswith((".ndjson", ".json")) else _wigle_source(f)
        tagged.append((f, src))
    return tagged

def cmd_enhance(args):
    oui, oui_src = load_oui(args.oui)
    comp = load_company_ids()
    inputs = _iter_inputs(args.paths)
    if not inputs:
        sys.exit("no inputs found (want .ndjson/.json SWIVEL captures and/or Kismet wiglecsv)")
    devices, total = {}, 0
    for path, src in inputs:
        for r in load_records(path):
            h = norm_mac(r.get("mac", ""))
            if len(h) != 12: continue
            total += 1
            d = devices.get(h)
            if d is None:
                d = devices[h] = {"mac": _colon(h), "sources": set(), "count": 0, "rssi_min": None,
                                  "rssi_max": None, "first_seen": None, "last_seen": None, "kind": "",
                                  "name": "", "vendor": "", "security": "", "channel": "", "frequency": "",
                                  "classification": "", "threat": "", "manufacturer": "", "services": "",
                                  "address_type": "", "lat": None, "lon": None, "acc": None}
            d["sources"].add(src); d["count"] += 1
            for k, v in (("kind", (r.get("kind") or "").strip()), ("name", ssid_of(r).strip()),
                         ("security", (r.get("security") or "").strip()), ("channel", r.get("channel")),
                         ("frequency", r.get("frequency")), ("classification", (r.get("classification") or "").strip()),
                         ("threat", (r.get("threat") or "").strip()), ("manufacturer", (r.get("manufacturer") or "").strip()),
                         ("services", (r.get("services") or "").strip()), ("address_type", (r.get("address_type") or "").strip())):
                _fill(d, k, v)
            if not _is_random(r.get("address_type", ""), h):        # hard rule: never carry a vendor for a random MAC
                _fill(d, "vendor", (r.get("vendor") or "").strip())
            rssi = _f(r.get("rssi"))
            if rssi is not None:
                d["rssi_min"] = rssi if d["rssi_min"] is None else min(d["rssi_min"], rssi)
                d["rssi_max"] = rssi if d["rssi_max"] is None else max(d["rssi_max"], rssi)
            t = r.get("iso_time") or ""
            if t:
                d["first_seen"] = t if d["first_seen"] is None else min(d["first_seen"], t)
                d["last_seen"] = t if d["last_seen"] is None else max(d["last_seen"], t)
            lat, lon = r.get("lat"), r.get("lon")
            if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
                acc = _f(r.get("gps_accuracy_m"))
                if d["lat"] is None or (acc is not None and (d["acc"] is None or acc < d["acc"])):
                    d["lat"], d["lon"], d["acc"] = lat, lon, acc
    # enrich: fill blank vendor/company + address_type, hard-rule-safe
    for h, d in devices.items():
        if not d["address_type"]: d["address_type"] = _addr_type(d["kind"], h)
        d["manufacturer_company"] = ""
        if not d["vendor"] and not _is_random(d["address_type"], h):
            d["vendor"] = oui.get(h[:6], "")
        if d["kind"] == "BLE" and d["manufacturer"]:
            cid = ble_company_id(d["manufacturer"])
            if cid in comp: d["manufacturer_company"] = comp[cid]
    # clean: drop the "nullshit" — a random MAC with nothing that identifies it
    kept = [d for d in devices.values()
            if args.keep_noise or d["name"] or d["vendor"] or d["manufacturer_company"]
            or d["manufacturer"] or d["services"] or d["classification"]]
    dropped = len(devices) - len(kept)
    out_dir = args.out or (os.path.dirname(os.path.abspath(inputs[0][0])) or ".")
    base = os.path.join(out_dir, "enhanced_" + _stamp())
    out_csv, n = base + ".csv", 1
    while os.path.exists(out_csv):                     # don't clobber a run from the same second
        out_csv, n = f"{base}_{n}.csv", n + 1
    cols = ["mac", "kind", "address_type", "vendor", "manufacturer_company", "name", "channel", "frequency",
            "security", "classification", "threat", "rssi_min", "rssi_max", "count", "first_seen", "last_seen",
            "lat", "lon", "sources"]
    with open(out_csv, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh); w.writerow(cols)
        for d in sorted(kept, key=lambda d: (d["kind"], d["mac"])):
            w.writerow([d["mac"], d["kind"], d["address_type"], d["vendor"], d["manufacturer_company"],
                        d["name"], _s(d["channel"]), _s(d["frequency"]), d["security"], d["classification"],
                        d["threat"], _i(d["rssi_min"]), _i(d["rssi_max"]), d["count"], d["first_seen"] or "",
                        d["last_seen"] or "", _s(d["lat"]), _s(d["lon"]), "+".join(sorted(d["sources"]))])
    both = sum(1 for d in kept if len(d["sources"]) > 1)
    filled = sum(1 for d in kept if d["vendor"] or d["manufacturer_company"])
    print(f"inputs: {len(inputs)} file(s) ({', '.join(src for _, src in inputs)}), {total} observations")
    print(f"unique devices: {len(devices)}  |  kept: {len(kept)}  |  dropped as noise: {dropped}")
    print(f"seen by both SWIVEL and Kismet: {both}")
    print(f"vendor/company resolved: {filled}/{len(kept)}  (OUI db: {oui_src or 'none'})")
    print(f"\nwrote {out_csv}")
    return 0

def _stamp():
    return time.strftime("%Y%m%d_%H%M%S", time.localtime())

# ---- selftest ----

def cmd_selftest(_):
    assert local_admin("06:53:D2:00:00:01") and local_admin("AA:BB:CC:00:00:01")
    assert not local_admin("3C:5A:B4:00:00:01") and not local_admin("00:11:22:33:44:55")
    assert oui6("3c:5a:b4:11:22:33") == "3C5AB4"
    assert ble_company_id("4C0010063A") == 0x004C          # little-endian 4C,00 -> 0x004C Apple
    assert ble_company_id("0600010920") == 0x0006          # Microsoft
    assert BLE_COMPANY[ble_company_id("75004204")] == "Samsung Electronics Co. Ltd."
    assert _vendor_match("Samsung Electronics Co.,Ltd", "Samsung Electronics Co. Ltd.")
    assert not _vendor_match("Apple, Inc.", "Sagemcom Broadband SAS")
    assert abs(haversine_m(40.0, -75.0, 40.0, -75.0)) < 1
    assert 10 < haversine_m(40.0000, -75.0, 40.0001, -75.0) < 15   # ~11 m per 0.0001 deg lat
    assert _addr_type("BLE", "0BDBFC70BFFD") == "random_nrpa" and _addr_type("BLE", "47CB4B08B268") == "random_rpa"
    assert _addr_type("WIFI", "3C5AB4112233") == "public" and _addr_type("WIFI", "0653D2000001") == "local"
    assert _is_random("random_rpa", "00112233AABB") and _is_random("", "0653D2000001") and not _is_random("public", "3C5AB4112233")
    assert _colon("3C5AB4112233") == "3C:5A:B4:11:22:33"
    print("selftest OK")
    return 0

def main():
    ap = argparse.ArgumentParser(description="SWIVEL cross-check against free public databases.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("enrich", help="offline: decode OUI + BLE company ids, audit hard rule")
    p.add_argument("file"); p.add_argument("--oui", help="path to an IEEE oui.txt / manuf file")
    p.set_defaults(func=cmd_enrich)
    p = sub.add_parser("kismet-diff", help="offline: compare a SWIVEL wiglecsv to a Kismet wiglecsv")
    p.add_argument("swivel"); p.add_argument("kismet"); p.set_defaults(func=cmd_kismet_diff)
    p = sub.add_parser("wigle", help="ONLINE opt-in: look up BSSIDs in WiGLE")
    p.add_argument("file"); p.add_argument("--auth", help="'APIName:APIToken' (or $WIGLE_AUTH)")
    p.add_argument("--limit", type=int, default=50); p.add_argument("--sleep", type=float, default=1.0)
    p.set_defaults(func=cmd_wigle)
    p = sub.add_parser("deflock", help="ONLINE opt-in: ALPR cameras near your track (DeFlock/OSM)")
    p.add_argument("file"); p.add_argument("--radius", type=float, default=100.0)
    p.set_defaults(func=cmd_deflock)
    p = sub.add_parser("enhance", help="offline: merge SWIVEL+Kismet captures, fill blanks, drop noise")
    p.add_argument("paths", nargs="+", help="capture files and/or a directory holding them")
    p.add_argument("--oui", help="path to an IEEE oui.txt / manuf file")
    p.add_argument("--out", help="output directory (default: the first input's directory)")
    p.add_argument("--keep-noise", action="store_true", help="keep unidentifiable random MACs instead of dropping them")
    p.set_defaults(func=cmd_enhance)
    sub.add_parser("selftest", help="run built-in asserts").set_defaults(func=cmd_selftest)
    args = ap.parse_args()
    sys.exit(args.func(args) or 0)

if __name__ == "__main__":
    main()
