# Troubleshooting

### Nothing shows up / the LIVE screen is empty
Almost always the phone's **Location switch is off**. Android hands back *zero* scan results while the system Location toggle is off — even after you've granted SWIVEL permission. Turn on **Wi-Fi, Bluetooth, and Location** (all three), then reopen the app. Also confirm you said **yes** to both Location and Nearby devices / Bluetooth permissions.

### It never beeps
- Check **MORE → LE / CAMERA ALERTS** is on.
- Check **MORE → ALERT SENSITIVITY** — on *Low* it only fires on the highest-confidence gear, rarely.
- The beep uses your **alarm** volume (so it works on silent) — make sure alarm volume isn't at zero.
- Real-world: high-confidence gear simply may not be near you. No beep is often correct.

### Where do my exports go?
After you tap **EXPORT**, the files land in:
```
Android/data/com.millie.civops2/files/
```
Plug the phone into a computer with a USB cable and open that folder like a USB stick, or use your phone's **Files** app to copy them out. SWIVEL never sends them for you.

### The sweep shows IPs but the MAC column is blank
That's an Android version limit, not a bug. **SWEEP SUBNET** reads MAC addresses out of the system on **Android 8–12**. On Android 13–15 the OS blocks that read for a modern-target app, so you get the IP list but MACs show blank. The IP sweep itself always works.

### It can't hear a camera I can see on a pole
Fixed cameras (Flock/ALPR poles, traffic cams) usually transmit **no Wi-Fi or Bluetooth** — they run on solar + cellular. A passive scanner can't hear a silent device. SWIVEL catches gear that transmits (body cams, in-car systems, patrol-car Wi-Fi). See the [FAQ](FAQ).

### Battery is draining faster than expected
Turn off **CAMERA LOCATION ALERTS** if you had it on (it tightens the GPS poll). To fully stop everything, use **PHONE SCAN · OFF** — it halts the radios and the background service.

### Android warns "can't install / unknown apps"
Normal for any app outside the Play Store. On that prompt tap **Settings**, switch on **Allow from this source** for your browser or Files app, press back, then **Install**. It's not a warning about SWIVEL specifically.

### An update won't install over the old one
Every release is signed with the same key (`CN=SWIVEL`), which is what lets Android update in place. If you ever installed a build signed with a *different* key, Android will refuse the update — uninstall first (you'll lose local logs), then install the current release. Using [Obtainium](https://github.com/ImranR98/Obtainium) avoids this by always pulling the official signed release.

### Still stuck?
Open an issue or email **theredwake.sh@protonmail.com**. Please don't paste raw capture rows (they can contain MAC addresses, network names and your GPS track) — describe what happened instead.
