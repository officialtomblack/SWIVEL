# Glossary

Plain-English definitions. You never need to know any of this to use SWIVEL — it's here for when you're curious about something you tapped.

**MAC address** — a hardware ID every Wi-Fi/Bluetooth radio has, like `A4:83:E7:…`. SWIVEL uses it to tell one device from another. The first half can reveal who *made* the device (see OUI).

**Randomized / scrambled address** — modern phones and many gadgets deliberately fake their MAC address for privacy, changing it often. Because a fake address carries no real maker information, **SWIVEL never assigns a maker to one** — that's its hard rule. It's labeled "random" and left at that.

**OUI (manufacturer prefix)** — the first portion of a MAC address is assigned by the IEEE to a specific company. SWIVEL carries an offline copy of that registry and uses "longest-prefix matching" to look up the maker — no internet needed.

**RSSI** — signal strength, in dBm (a negative number). Closer to 0 (e.g. −30) = strong = the device is near. More negative (e.g. −100) = weak = far. The radar scope plots this; it shows strength, **never direction**.

**SSID** — a Wi-Fi network's name (e.g. "HOME-5G"). Note: a name your phone is *looking for* isn't proof of what's transmitting — only access-point/beacon names become a record's network name.

**BSSID** — the MAC address of a specific Wi-Fi access point. Two routers can share an SSID (name) but have different BSSIDs, so SWIVEL keeps them separate.

**BLE (Bluetooth Low Energy)** — the low-power Bluetooth that trackers, wearables and beacons use to advertise themselves constantly. Most of what the TRACKERS tab sees is BLE.

**Find My / AirTag signature** — the specific BLE advertisement Apple's trackers broadcast. A match suggests an Apple tracker, but a signature isn't proof of a physical AirTag.

**Evil-twin** — a fake Wi-Fi access point that copies a real network's name to trick devices into connecting. SWIVEL flags the pattern.

**Karma / Pineapple** — a rogue access point that answers to *any* network name a phone asks for, to lure devices in. A classic attack tool.

**ALPR** — Automatic License-Plate Reader. Camera systems (e.g. Flock) that photograph plates. Fixed ALPR poles usually transmit nothing, so they can't be heard passively.

**OUI vs. product** — knowing the maker is not knowing the product. "Made by Axon" doesn't tell you *which* Axon device, or that it's recording. This is the heart of "evidence, not proof."

**WigleCSV** — a standard capture format that war-driving tools like **Kismet** and **WiGLE** understand. SWIVEL can export to it so your captures line up with those tools.

**Kismet** — a popular wireless-capture program for laptops. If you run one, DEBRIEF can merge its captures with your SWIVEL exports.

**Subnet sweep** — the one active tool. It lists the live devices on the Wi-Fi network *your phone is currently joined to*, and (where Android allows) their MAC addresses. Use it only on networks you own or are authorized to test.

**DURESS · CLEAR ALL** — the panic button. One tap deletes every saved log and every export off the phone, instantly, with no recovery.
