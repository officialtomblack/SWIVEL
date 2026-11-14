# FAQ

### What does SWIVEL actually do?
Your phone's Wi-Fi and Bluetooth radios are constantly hearing announcements from nearby networks, gadgets, trackers and cameras — it just never shows them to you. SWIVEL shows them, sorts them into plain categories (cameras, trackers, surveillance gear, suspicious networks), and writes everything down so you can look back later.

### Is it "hacking"? Will it get me in trouble?
No. SWIVEL is **passive** — it never attacks, jams, injects, deauths, joins or logs into anything. It only *listens* to the signals already in the air, which is the same scanning your phone does every day to find networks. It sends nothing but the ordinary "discovery" pings any phone sends.
*This isn't legal advice and laws differ by place,* but receiving public radio broadcasts is generally fine. The one feature that actively reaches out — **SWEEP SUBNET** — only touches the Wi-Fi network your phone is already joined to; use it only on networks you own or are allowed to test.

### Can the police — or anyone — tell I'm running it?
No. Because it's passive, a phone running SWIVEL looks exactly like any other phone scanning for Wi-Fi and Bluetooth. It doesn't broadcast "SWIVEL is here" and it doesn't announce itself to the devices it hears.

### Why does it ask for Location permission? Is it tracking me?
Android **requires** Location permission before any app is allowed to see Wi-Fi/Bluetooth scan results — it's an OS rule, not a SWIVEL choice. SWIVEL needs it to *hear radios*, not to find you. Your location is only ever written into your own private log on the phone, and that log never leaves the device.
⚠️ You must keep your phone's **Location switch turned on** — Android returns *nothing* while it's off, even after you grant permission.

### Does it upload anything? Is there an account?
Never, and no. There's no account, no signup, no analytics, and the app never contacts a remote server. It figures out who made a device from a dictionary built into the app. Everything stays in the app's private folder until *you* choose to EXPORT it.

### Is sideloading the APK safe?
The whole source code is public in this repo, the app never phones home, and every release is signed with one fixed key you can verify yourself (`apksigner verify --print-certs`). Android's "unknown apps" warning simply means "installed outside the Play Store" — it is not a warning that something is wrong with the file.

### Why didn't it detect a Flock camera / the camera on that pole?
Most **fixed** cameras — Flock/ALPR poles, traffic and pole cams — run on solar + cellular and put out **no Wi-Fi or Bluetooth at all**, so a passive scanner literally cannot hear them. SWIVEL alerts on gear that *does* transmit: body cams, in-car systems, and a patrol car's own Wi-Fi. (Warning about fixed cameras by map location is an optional, you-provide-the-map feature.)

### It beeped and said PUBLIC SAFETY — does that mean a cop is here?
No. A match means *a device made by that vendor is nearby.* The same routers and radios ride in utility trucks, ambulances and delivery vans. SWIVEL says "probable," never "a cop." Tap the alert to see the exact evidence.

### What's the difference between three beeps and one beep?
**Three beeps** = a high-confidence hit (body-cam, in-car-video and ALPR makers like Axon, Flock, WatchGuard). **One beep** = lower-confidence, dual-use gear that's also sold widely (Motorola radios, in-car routers, business CCTV) — expect more false alarms there. Tune it under **MORE → ALERT SENSITIVITY**.

### Does it drain my battery?
Normal scanning is light. Two optional features use more: **CAMERA LOCATION ALERTS** (tightens the GPS poll) and heavy continuous capture. The **PHONE SCAN · ON/OFF** button genuinely stops every radio when you want to go quiet.

### How is this different from other "detector" apps?
Most overpromise. SWIVEL does the opposite on purpose: it refuses to name a maker for a scrambled address, grades its public-safety matches by how specific they are, and shows you the raw evidence behind every label. Honesty is the feature.

### What's DEBRIEF?
A free desktop companion. SWIVEL gives you a live radar and a log for one outing; [DEBRIEF](https://github.com/officialtomblack/DEBRIEF) merges your exports across *many* outings and flags the trackers or gadgets that kept turning up at more than one of your places.
