# Features

← [Back to README](../README.md)

## Capture History

On connect, the plugin scans the handshake directory and pairs each `<ssid>_<bssid>.pcap` with the `.gps.json` sidecar it wrote at capture time, sending the app a **geolocated capture log** (newest first). New captures are pushed live as they happen. The app shows a `[ captures ]` console section: total handshakes, how many are geolocated (`⌖`), and the most recent networks with time-ago.

Tapping in opens a full **`[ captures ]` detail** with a searchable list and a **pixel map** of where handshakes were caught. It fetches dark [CARTO](https://carto.com/) basemap tiles for the capture area and renders them as a **pixel-perfect square grid** (drawn in a Compose `Canvas` at integer cell sizes, auto-contrast-normalised): the street network shows as muted **grey** squares, each catch as a bright **green** square, and your current position as an **orange** one. Tiles are cached on disk; with no network it falls back to a pure-ASCII block heatmap so there's always a map. © OpenStreetMap © CARTO.

## Handshake cracking (wpa-sec + on-phone)

A handshake only *counts* once it's cracked, so each capture is graded on-device by **`hcxpcapngtool`**:

- **crackable** — a PMKID or a full EAPOL 4-way handshake (yields a hash)
- **partial** — an incomplete grab (e.g. only an M1 frame); can never be cracked

The `[ captures ]` screen tags each catch and shows a `cracked · crackable · partial` split. A **`[ filters ]`** button opens a bottom sheet with the view filters (geo / crackable / cracked) and the cracking-power controls, so the list stays uncluttered. There are two ways to actually crack — a free server, or the phone itself.

### wpa-sec (server-side)

If **wpa-sec** is enabled, the plugin uploads crackable handshakes and downloads results hourly to `wpa-sec.cracked.potfile`; the app matches passwords to captures by BSSID, tags them **cracked** with the password inline, and the pet gloats in-character when a *new* one lands. A **cracking** status row shows whether wpa-sec is on **and whether the service is reachable** (the Pi health-checks it — wpa-sec goes down sometimes). Cracked/connect events also fire **notifications**.

> Cracking is server-side against wordlists, so only weak/dictionary passwords fall — strong random ones won't. Only handshakes captured *after* wpa-sec is enabled auto-upload; an existing backlog has to be submitted once.

### On-phone (offline, no server)

You can also crack **on the phone itself** — no server, no account, works offline. The plugin distills each capture to a hashcat-`22000` line (via `hcxpcapngtool`) and sends it up alongside the capture; PMKID captures then show a bright **`crack ▸`** tag. Tap one and the phone cracks it directly:

- **Native-accelerated** — `PMK = PBKDF2-HMAC-SHA1(passphrase, ESSID, 4096)` → `PMKID = HMAC-SHA1(PMK, "PMK Name" ‖ AP ‖ STA)`, computed in a small C library (`libwpacrack.so`) that uses the **ARMv8 hardware SHA-1 extension** when present (~3× the pure-Kotlin path). Self-validated against a reference vector at load; falls back to a pure-Kotlin cracker on any device without the lib (x86, etc.), so it always works.
- **Wordlist** — pwncrack's `default.gz` (**~655 K** WPA-valid, 8–63-char candidates), downloaded once to app storage and reused offline. It's WPA-tuned, so its hit-rate per candidate is far higher than a general dump like rockyou.
- **Speed** — roughly **~8 min (plugged, all cores)** to **~30 min (easy-cpu)** for the whole list on a modern phone. A **quick** toggle (in the filters sheet) tries only the top ~25 k first, catching weak passwords in ~1–3 min; a quick miss is *not* recorded as "no match" (a full run may still get it).
- **Live progress** — a banner shows `tried / total · rate · ETA` over a real progress bar, across your CPU cores.
- **Queue** — tap several `crack ▸` rows and they run one after another (`cracking X · N queued`, with skip / stop). Serial by design: cracking is pure PBKDF2 already fanned across cores, so two at once would only halve each other's speed.
- **Survives lock** — a foreground service keeps a long crack alive with the screen off (Doze / background-CPU throttling would otherwise stall it), showing progress in its notification.
- **Resume** — the position is checkpointed continuously, so an interrupted crack (process kill, reboot, unplug, cancel) picks up where it left off instead of restarting from candidate 0.
- **Lasting results** — a hit shows `pw: <password>` on the row and **persists across restarts**; a fully-searched miss is remembered as **`no match`** so it isn't re-offered.
- **Gentle power controls** (on by default, in the filters sheet) — **easy cpu** (cap workers at 2), **charger only** (pause while unplugged, auto-resume on replug), **stop <15%** (pause on low battery). Cracking is heavy — a hot phone, real battery draw — so these keep it civil; it's best run plugged in with the screen off.

#### How the crack actually works (PMKID + PBKDF2)

The attack targets the **PMKID** — a value some APs place in the very first handshake frame, derived purely from the network's password and the two MAC addresses, with **no client needed**. That's what makes it crackable *offline*: grab one frame, then guess passwords locally. WPA2 derives it in two steps:

1. **PMK** (pairwise master key) = `PBKDF2-HMAC-SHA1(passphrase, ESSID, 4096, 32)` — the passphrase stretched over the network name, 4096 iterations, 32 bytes out.
2. **PMKID** = `HMAC-SHA1(PMK, "PMK Name" ‖ AP_MAC ‖ STA_MAC)`, first 16 bytes.

So the crack is a straightforward **dictionary attack**: for each candidate passphrase, compute its PMK, then its PMKID, and compare to the captured one — a match means that passphrase *is* the network's password. Nothing is reversed or decrypted; it's guess-and-check, which is exactly why only weak/dictionary passwords fall and strong random ones never will.

**Why it's slow, and how we speed it up.** The entire cost is step 1 — PBKDF2's 4096 iterations are *deliberately* expensive (that's the job of a key-derivation function), ~8k SHA-1 compressions per candidate. We attack that three ways:

- **Native** — the whole per-candidate computation runs in a small C library (`libwpacrack.so`): one JNI call per *batch* of candidates instead of thousands of JVM crypto calls each.
- **Hardware SHA-1** — on arm64 it uses the **ARMv8 crypto-extension SHA-1 instructions** when the CPU has them (self-validated against a known digest at load, else it falls back to a software transform), for ~3× the pure-Kotlin path.
- **All cores** — candidates are handed out from a single shared cursor, one worker per core. That shared, monotonic cursor is also what makes a crack **resumable**: everything below `cursor − workers` is guaranteed done, so that floor is checkpointed and a killed/paused crack picks up from it.

**What it can't do (yet).** The big leap — hashing many candidates in parallel across SIMD lanes (or a GPU), the way desktop crackers do — is a much larger job left for later; today it's one candidate per core at a time.

> On-phone cracking handles **both PMKID (`WPA*01`) and EAPOL 4-way handshakes (`WPA*02`)** — the same PBKDF2 PMK step, with EAPOL adding the PTK + MIC key-confirmation check (verified against the hashcat mode-22000 reference vector). Like wpa-sec it's a dictionary attack — weak/common passwords fall, strong random ones won't — but it's fully local, private, and needs no internet or wpa-sec account.

> ⚠️ **Only crack handshakes from networks you own or are explicitly authorized to test.** Cracking others' Wi-Fi is illegal in most jurisdictions — see the [responsible-use note](../README.md#pwncompanion) at the top.

> 🔥 **On-phone cracking is hard on your phone** — it pins the CPU at 100% for a long time (minutes to hours), so expect real heat, heavy battery drain, and the wear that comes with sustained load. The power controls (easy cpu / charger only / stop <15%) keep it in check; run it plugged in with the screen off. **At your own risk.**

**Testing the cracker.** The crypto is pinned to a reference vector (ESSID `pwn-test-net`, passphrase `12345678`) in `WpaCrackerTest` — run with `./gradlew testDebugUnitTest`. The same vector self-checks the native library at load (and gates the hardware SHA-1 path). And **debug builds** show a `[ +test ]` button on the captures screen that injects a known-crackable capture, so the full tap → crack → cracked-password → persist → notify flow can be exercised without waiting on a real weak network (compiled out of release builds).

## Device Vitals

The `[ vitals ]` section surfaces live telemetry as **block-bar gauges** (`cpu ██████···· 40%`): temperature, CPU load, memory, the RL `reward` (green when positive), plus environment density (APs / clients / peers). `temperature`/`cpu`/`mem` are pushed **every ~12 s regardless of mode** (a plugin timer), so the gauges stay live even while paused; `reward` and the mood counters only exist while hunting in AUTO. The same per-channel AP/client density also enriches the channel-learning model — so "best channel" reflects where the targets actually are, not just where handshakes happened to land.

## Live-pet notification

The two always-on foreground services (WebSocket + GPS) share a **single** ongoing notification (kept at MIN importance so it stays quiet). Expand it and it becomes a glanceable widget: the Pwnagotchi's **e-ink face** as the image plus a live stats line — `pwnagotchi · 169 caught · 4 clients · 49°C · gps 0.00,0.00` — tinted phosphor green.

While an **on-phone crack** is running, a third (transient) foreground service shows its own low-priority progress notification — `Cracking <ssid> · N queued` over a progress bar, with a **Stop** action — and clears itself when the queue drains.

Separately, an **Alerts** channel (normal importance) fires event notifications: **link up** to the Pwnagotchi (throttled against reconnect churn) and one per **newly cracked** network showing its SSID + recovered password. The connect-time backlog is seeded silently so it never floods.

## Look & feel

Fixed dark phosphor-terminal theme (no white launch flash): a terminal app **icon** (the pwnagotchi "smart" `(✜‿‿✜)` face in phosphor green on a CRT-grid background), a matching dark **splash**, the bundled Share Tech Mono font everywhere, and uppercase `[ SECTION ]` headers. When no pwnagotchi is linked the console shows a `[ standby ]` panel (idle face + a rotating in-character line) instead of empty sections.
