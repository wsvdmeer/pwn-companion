# PwnCompanion

Android companion app for [Pwnagotchi](https://github.com/jayofelony/pwnagotchi). Connects to the Pwnagotchi over Bluetooth PAN, receives live screen captures, provides GPS location, and runs a fully on-device, deterministic "AI" that reacts to WiFi hunting events in real time — and, crucially, **advises where to hunt next** so the device catches more handshakes.

The whole UI is a single monospace **terminal console** (phosphor-green on black, ASCII block-bars, scanlines) — no Material chrome, everything on one scrolling screen.

### What it is, in plain terms

A [Pwnagotchi](https://github.com/jayofelony/pwnagotchi) is a pocket gadget (usually a Raspberry Pi Zero) that passively sniffs Wi-Fi and collects **WPA handshakes** for security research, showing its status as a moody ASCII face on a tiny e-ink screen. On its own it's **headless, has no GPS, and runs on a Pi too weak** to do anything clever with what it captures.

**PwnCompanion turns your phone into its screen and its brain.** It:
- **mirrors** the pet's live e-ink display on your phone;
- **geotags** every captured handshake and plots them on a **map**;
- **decides where to hunt next** — a phone-side advisor ranks Wi-Fi channels by real yield + live client density and steers the device there, so it catches *more* handshakes;
- gives the pet a genuine **personality voice** — a curated, franchise-flavored corpus with live data woven in, spliced onto the pwnagotchi's own e-ink screen;
- closes the loop by **cracking** captured handshakes — server-side via wpa-sec, or **on-phone** (offline, pure-Kotlin PMKID) — and showing the recovered passwords.

The guiding idea: **move the thinking to the phone** (which has compute, GPS, and storage) and leave the Pi lean, just hunting. See ["Why the brain lives on the phone"](#why-the-brain-lives-on-the-phone-not-the-pi) below.

**Requires Android 10+ (API 29)** on an arm64 device, plus a [Pwnagotchi](https://github.com/jayofelony/pwnagotchi) running the [`bt-tether`](https://github.com/wsvdmeer/pwnagotchi-plugins) + `pwn-companion.py` plugins.

> ⚠️ **Responsible use.** PwnCompanion is a companion for authorized Wi-Fi security research and education, paired with a [Pwnagotchi](https://github.com/jayofelony/pwnagotchi). Capturing handshakes, sending deauthentication frames, and cracking passwords may be illegal without permission — only use it on networks you **own or have explicit authorization to test**. You are responsible for complying with your local laws. Licensed under **GPL-3.0** (see [`LICENSE`](LICENSE)).

> 🚀 New here? Start with **[GETTING_STARTED.md](GETTING_STARTED.md)** — it walks through both halves (the `bt-tether` + `pwn-companion.py` plugins on the Pi, and the app on your phone).

## Screenshots

<p>
  <img src="docs/screenshot-console.png" width="32%" alt="Connected console: the pwnagotchi e-ink face, vitals, history channels, captures, log" />
  <img src="docs/screenshot-learning.png" width="32%" alt="Learning detail: per-channel activity bars + activity-by-hour" />
  <img src="docs/screenshot-standby.png" width="32%" alt="Standby screen shown when no pwnagotchi is linked" />
</p>

*Left: the live console — the pwnagotchi's mirrored e-ink face (with the current film-world captioned, `‹ back to the future ›`), vitals, per-channel history, captures, and log. Middle: the `[ history ]` learning detail — channels by activity + activity-by-hour. Right: the `[ standby ]` screen when nothing's linked. (SSIDs / node name / IP redacted for publishing.)*

---

## How It Works (30-Second Summary)

1. Enable **Bluetooth Tethering** on Android (Settings → Connections → Mobile Hotspot and Tethering → Bluetooth Tethering)
2. Pair and connect the Pwnagotchi via Bluetooth — Android creates a `bt-pan`/`bnep0` network interface
3. The app detects the interface, starts a **WebSocket server** on the phone's bt-pan IP (port 8081)
4. The app broadcasts **UDP announcements** (port 8888) so the Pwnagotchi plugin discovers the server
5. The Pwnagotchi plugin connects via WebSocket and starts sending screen captures, WiFi events, and GPS requests
6. The app sends GPS back, the AI reacts to every handshake capture / deauth / network discovery

### What syncs (both directions)

Everything rides the one Bluetooth PAN link. The phone also shares its **internet** to the Pi over that link (via `bt-tether`), which is what lets wpa-sec upload/download.

**Pwnagotchi → App** (what the pet streams up):

| | Message | What it carries |
|---|---|---|
| 🖥 Screen | `image` | the live e-ink frame (~1/s) |
| ℹ️ Status | `status` | device name, IP, current **mood**, mode (AUTO/MANUAL), and **wpa-sec** status (enabled + service reachable) |
| 📶 WiFi events | `network_event` | handshake captured / association / deauth / idle — each tagged with the real **channel + BSSID** |
| 📊 Per-channel stats | `autotune_stats` | handshakes / deauths / associations + peak AP & client density per channel (the steering ground truth) |
| 🌡 Telemetry | `device_telemetry` | temperature, CPU, memory, RL **reward**, AP/client/peer counts, mood counters — full per-epoch in AUTO, plus a lightweight temp/cpu/mem push every ~12 s in any mode |
| 📍 Capture log | `capture_history` | geolocated handshakes (paired with `.gps.json`), each graded for **crackability** (eapol/pmkid/partial) with its hashcat-**22000 hash** (for on-phone cracking), plus the raw file count |
| 🔓 Cracked | `cracked` | wpa-sec results (BSSID → password), on connect + every ~2 min |
| 🙂 Emotions | (in `status`) | the device's own mood events — grateful / bored / sad / angry / excited / lonely |

**App → Pwnagotchi** (what the phone sends down):

| | Message | What it does |
|---|---|---|
| 📡 Discovery | UDP `announcement` | broadcasts `ws://phone-ip:8081` every 5 s so the plugin finds the app |
| 📍 GPS | `gps` | the phone's fix (lat/lon/accuracy/altitude) on request (~5 s) → geotags captures + feeds location learning (the Pi has no GPS of its own) |
| 🎯 Channel steering | `set_channel_priority` | the advisor's top channels → bettercap recon, every 45 s **while hunting** (AUTO only), soft + reversible |
| 🎛 Auto-tuning | `set_param` | clamped `min_rssi` / `ap_ttl` / `sta_ttl` / `recon_time` / hop timing — re-implements the removed RL param-tuner on the phone |
| 🗣 Device voice | `set_voice_pool` | fresh in-character lines per pwnagotchi voice category; the plugin speaks them in the e-ink bubble instead of the stock repeating quips (falls back to stock when disconnected) |
| ⏯ Mode control | `restart_auto` / `restart_manual` | flip the pet between hunting and paused |

---

## Setup

Two halves: the **Android app** (on your phone) and the **`pwn-companion` plugin** (on the Pwnagotchi). They meet over Bluetooth PAN.

### 1. Build & install the Android app

**Quickest — prebuilt APK:** grab the APK from the [**Releases** page](../../releases) and sideload it (enable "install unknown apps"). Note it's a **debug build** (debug-signed, for testing — not the Play Store); requires **Android 10+ on an arm64 device**. The voice engine is fully on-device — **no model download, no network needed on first launch** — so it just opens straight to the console. To build it yourself instead:

**Prerequisites**
- **JDK 17** — required by the Android Gradle Plugin (9.2).
- **Android SDK** — `compileSdk`/`targetSdk` **36**, `minSdk` **29** (Android 10+). Install SDK Platform 36 + build-tools via Android Studio or `sdkmanager`.
- Gradle itself is handled by the wrapper (Gradle 9.4.1) — no separate install. Kotlin 2.2.10. *(No NDK/CMake needed — the voice is pure Kotlin now; there's no native code to compile, so the build is quick.)*

**Build & install** (CLI; or just open the project in Android Studio and Run):
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app opens **straight to the console** — the voice is on-device and deterministic, so there's nothing to download and it works fully offline.

**Runtime permissions** (requested on first launch):
| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | the Bluetooth PAN link to the Pwnagotchi |
| `ACCESS_FINE_LOCATION` | geotag captures for the map (and Android requires it for BT scanning) |
| `POST_NOTIFICATIONS` | the foreground-service notice + cracked/connect alerts |

### 2. Pwnagotchi side (`pwn-companion.py`)

Requires a **Pwnagotchi** (the [jayofelony](https://github.com/jayofelony/pwnagotchi) fork) with the **[`bt-tether` plugin](https://github.com/wsvdmeer/pwnagotchi-plugins)** configured — that's the transport: your phone shares internet to the Pi *and* the app link rides the same Bluetooth PAN. (`bt-tether` is a reworked plugin — with a web UI for managing the phone link — from [wsvdmeer/pwnagotchi-plugins](https://github.com/wsvdmeer/pwnagotchi-plugins).)

```bash
# Python dependency
sudo pip3 install websockets

# Handshake crackability grading uses hcxpcapngtool (ships on jayofelony images;
# install if missing):
sudo apt install -y hcxtools

# Deploy the plugin
sudo cp pwn-companion.py /usr/local/share/pwnagotchi/custom-plugins/
sudo systemctl restart pwnagotchi
```

**`config.toml`** — enable the companion plugin:
```toml
[main.plugins.pwn-companion]
enabled = true
show_on_screen = true
```

**Optional — cracking via [wpa-sec](https://wpa-sec.stanev.org)** (free community cracking; needs an API key):
```toml
[main.plugins.wpa-sec]
enabled = true
api_key = "<your wpa-sec key>"
api_url = "https://wpa-sec.stanev.org"
download_results = true          # pull cracked passwords back (hourly)
```

---

## Compatibility

Built and **tested on the [jayofelony](https://github.com/jayofelony/pwnagotchi) fork** (with the [`bt-tether` plugin](https://github.com/wsvdmeer/pwnagotchi-plugins) as the transport). Other pwnagotchi forks are **untested** — the app is fork-agnostic (it only speaks WebSocket/UDP to the plugin) and the plugin uses standard pwnagotchi APIs and degrades gracefully, so it may work elsewhere, but jayofelony is the only version I've verified.

## Emergent AI Personality

The on-device AI has **no fixed, selectable mood** — its personality *emerges* and is *learned*. There is no mood picker; the app shows a live readout of whatever disposition the companion currently has.

It is driven by three things:

- **Live events** — every handshake, new network, deauth/anomaly, and idle period nudges a continuous trait vector (confidence, curiosity, frustration, energy, ego, boredom).
- **The Pwnagotchi's own emotions** — the plugin hooks the device's real mood events (`on_grateful`, `on_bored`, `on_sad`, `on_angry`, `on_excited`, `on_lonely`) and sends them over WebSocket, where they're folded into those same traits in real time (e.g. `EXCITED` raises energy/curiosity, `ANGRY` raises frustration).
- **History** — total captures set an experience tier (Rookie → Seasoned → Veteran → Jaded → Apex → Phantom → Singularity) that shapes the voice, and a **learned baseline** slowly drifts toward how the device usually behaves.

The strongest trait at any moment is surfaced as the current **disposition** (e.g. Confident, Cocky, Curious, Restless, Drained, Frustrated, Composed), each with its own neon accent color.

### The voice speaks on the pet's own screen

The pet reacts on its own to real events, and its lines are spoken **on the pwnagotchi's own e-ink display** — the app pushes a fresh pool of in-character lines per voice category (`set_voice_pool`), and the plugin speaks them in the e-ink bubble instead of the stock repeating quips (falling back to stock when disconnected). There's no phone-side chat box: the voice belongs to the device, so that's where it lives.

Some lines are **data-grounded**, not just flavor — a recap folds in real numbers (caught this session, crackable/cracked counts, best channel, the AP that keeps escaping, how it feels), composed from the corpus + live capture stats via slot templates. Because the numbers come from Kotlin, the pet **can never invent a wrong count or channel** — it only picks the words around correct facts, and while hunting it volunteers its best spot on its own.

### Proactive life

Beyond reacting, the pet speaks up on its own for real events — all throttled/deduped so it never spams:

- **Tier promotions** (Rookie → Seasoned → Veteran → Jaded → Apex → Phantom → Singularity) and **capture milestones** (every 25 catches) — announced once, only for catches gained this session.
- **Alert recovery** — when a `blind` / `running hot` / dry-spell condition clears ("antenna's back — I can see again").
- **Untapped-target nag** — occasionally reminds you about an AP it keeps seeing but never caught.
- **RL-brain narrator** — when the device's own reinforcement-learning agent sets a new session best/worst epoch reward, the pet narrates what its AI is learning (two AIs talking).

### One blended, mood-driven voice

There's no voice picker. The companion speaks in a **single hacker-gremlin persona** that draws on **21 cult-film & game worlds** — Evil Dead, Star Wars, Matrix/Mr Robot, Harry Potter, Terminator, Tron, Jurassic Park, Alien, RoboCop, Blade Runner, WarGames, Hackers, Portal/GLaDOS, Predator, HAL 9000, Cyberpunk 2077, SHODAN, Mad Max, Ghostbusters, Back to the Future, The Thing — but commits to **exactly one franchise per line** (a world is pinned per utterance, never blended mid-sentence, so you get "Hail to the king, baby." *or* "I find your lack of security disturbing." — never a jarring mash of both). The film-world the pet is currently in is shown as a caption under the mirrored e-ink (`‹ jurassic park ›`).

What changes is the **tone**, chosen live by the emergent disposition (no user input):

- **hyped** (Cocky / Confident) — triumphant, swaggering, after a good run of catches
- **grumpy** (Frustrated) — snarling and dark when it's running hot, blind, or getting nothing
- **weary** (Restless / Drained) — flat and unimpressed during a dry spell or long idle
- **deadpan** (Composed / Curious) — dry and level the rest of the time

The mood/tier decide *what* the line carries and *how it's coloured*; a **persistent franchise** (pinned for a whole mood-stretch, rotating only when the disposition flips) keeps every line — in the app and on the e-ink — set in one coherent world. Line selection is a **curated per-franchise corpus** keyed by `reaction-category × franchise` (handshake / assoc / deauth / idle / excited / weary / normal / recap), with the recap/status lines carrying live data slots. Nothing is generated, so it's instant, offline, and never off-character — no refusals, no invented numbers, no garbled blends. Adding worlds later is just extending the franchise list.

---

## Deauth Advisor — where to hunt next

The whole point of a Pwnagotchi is to **deauth clients and capture handshakes**, so the app's headline feature is a hunt advisor that tells you where to point it. **All the analysis is done on the phone (Kotlin), deterministically** — so the recommendation is always correct. The live steering it's doing right now shows in `[ steering ]`; genuine problems (blind / hot / dry / nothing-to-deauth) surface as `[ alerts ]`, and the pet voices them in-character on its e-ink.

- **Where to park** — ranks channels by the device's *own* per-channel capture stats (`autotune_stats`: handshakes + client density) and surfaces e.g. `» park on ch6 · 4 clients · 12 caught here`.
- **Untapped targets** — tracks APs the device keeps *seeing* (association events, by BSSID) but has *never captured*, and flags the best one: `» chase: CorpNet (-55dBm, ch6) seen 8× but never caught`.
- **Mission alerts** — deauth-blocking problems, in the error color: `blind` (monitor interface / antenna dead), `running hot` (thermal throttle), `APs but 0 clients` (nothing to deauth here — move), and dry-spell detection. These are the only thing the `[ alerts ]` section shows (it stays hidden when there's nothing wrong), and new alerts are also voiced proactively by the pet.

### It steers the hunt

The advisor's ranking is also sent back to the device (`set_channel_priority` → bettercap's `wifi.recon.channel`), so the Pwnagotchi focuses recon on the channels that actually yield handshakes. Channels are scored by a **weighted blend** — device handshakes + **live client density** (deauth targets) dominate, with all-time yield / here / this-hour bonuses and a chase bonus for the untapped target.

Channel choice is an **explore/exploit bandit** (UCB1): it exploits the productive channels but keeps sampling under-explored ones (including the whole 2.4GHz floor) so it never tunnel-visions on a self-reinforcing top-N. The exploration counts **decay over time** — the recency term — so a channel that goes cold gets re-explored (WiFi is non-stationary).

It's also **motion-aware**: pinning a learned set only helps while stationary, so when you're *moving* it stops pinning and hops the wide 2.4GHz band (1/6/11) to keep discovering as the environment changes. Motion is read from the phone's **hardware GPS speed** (Doppler) when available — falling back to an accuracy-gated position difference, then to AP-churn indoors — all with hysteresis, so GPS jitter or a stationary scan can't false-trip "moving" (and send it hopping too fast). Motion also drives **dwell steering** — the app sets the device's `recon_time` longer when you're still (sit and wait out handshakes on the good channels) and shorter when moving (cover ground, hop faster). It's a soft, reversible nudge with idle back-off (an unchanged set isn't re-sent every 45s). The live `[ steering ]` section shows the active `recon → ch …`; the aggregate learning readout lives behind the `[ history ]` link.

### The bandit protocol, step by step

Every steering cycle (~45 s, AUTO only) the app scores each candidate channel as **exploit value + exploration bonus** and sends the top 3 back as `set_channel_priority`.

**1. Exploit value** — a weighted blend of every signal we have for that channel (a channel strong across several beats one strong in just one):

| signal | weight | meaning |
|---|---|---|
| device handshakes here | ×1.0 (normalised) | proven captures on this channel |
| live clients here (`sta`) | ×1.0 (normalised) | deauth targets *right now* |
| all-time yield | ×0.8 | long-run productivity (0–1) |
| productive near here (GPS) | +0.4 | good at this location |
| productive this hour | +0.3 | good at this time of day |
| the untapped target's channel | +0.6 | chase the AP that keeps escaping |

**2. Exploration bonus (UCB1)** — `EXPLORE_C · √(ln(totalPulls) / (pulls[ch] + 1))`, with `EXPLORE_C = 0.6`. It's large for channels sampled little and shrinks as they're sampled, so the bandit keeps trying under-explored channels (the whole 2.4 GHz floor 1–11 is always a candidate, for discovery) instead of tunnel-visioning on a self-reinforcing top-N.

Final score = `exploit(ch) / maxExploit + bonus`. The 3 chosen channels get their `pulls` incremented; then **all** pulls decay by `×0.97` each cycle (~15–20 min half-life). That decay is the **recency** term: a channel that goes cold has its exploitation forgotten and gets re-explored, because Wi-Fi is non-stationary. Motion overrides the whole thing (pin when still, hop 1/6/11 when moving), and `deauth` is never touched — the device keeps attacking on the channels it's told to watch.

### How the voice works — fully on-device, no model

There is **no language model**. The voice is a **curated per-franchise corpus** (21 cult-film/game worlds × eight reaction categories) selected deterministically by the emergent mood + a persistent franchise, with live-data slots (`[SESSION]`/`[CRACKED]`/`[BESTCH]`/…) filled in from real capture stats. It's instant, offline, weighs nothing, and can never invent a wrong channel or number or drift off-character.

> **Earlier versions shipped a ~491 MB on-device LLM** (Qwen2.5-0.5B via llama.cpp) whose only job was phrasing live data. Once the personality moved into the curated corpus, that job shrank to filling in a few numbers — so the model (and its download, RAM/battery cost, native build, and refusal-handling) was **removed entirely** in favor of deterministic slot templates. Same voice, none of the weight.

**The voice (personality):**
- the pet's spoken lines on the **pwnagotchi's own e-ink screen** (the voice pool, incl. the MANUAL recap)
- data-grounded lines (session recap, status) that weave *pre-decided* facts into the corpus — it only picks the words around correct numbers

**The decisions (plain Kotlin / analytics):**
- **channel steering** — the UCB1 bandit above
- **the personality tuner** — context policy + hill-climb on `min_rssi` / TTLs / recon
- **the advisor ranking** — `HuntAdvisor` scores the channels; the voice only reflects the winner
- **handshake crackability** (`hcxpcapngtool`) and **cracking** — server-side (`wpa-sec`) or **on-phone** (pure-Kotlin PMKID, offline)

### It re-implements the AI jayofelony removed

The 64-bit pwnagotchi image drops the original's reinforcement-learning param-tuner (no `torch`/`stable-baselines3` on the device — just the reward scalar + mood faces). So the app **re-creates that job on the phone**, where there's compute + GPS + a cross-session learning DB. A small **personality tuner** auto-adjusts the same knobs the old RL did — `min_rssi`, `ap_ttl`, `sta_ttl`, `recon_time`, hop timing, channels — and pushes them live via a clamped `set_param` command (`agent.run("set wifi.*")` for rssi/TTLs, `personality` config for recon/hop). It's deliberately **not** a neural net: a **context policy** sets the TTLs/hop from motion, and a slow **feedback hill-climb** nudges `min_rssi` every ~10 min toward whatever raises the capture rate (using captures/min + the device's own reward), holding when there's no signal so it doesn't wander. `deauth` is never touched (the device keeps attacking). The live `[ steering ]` `tuning :` line shows the values, e.g. `rssi -80 · ttl 180/300 · recon 45s · hop 10s`.

> Handshake captures now carry their real **channel** (and BSSID) end-to-end. Previously every capture was recorded on channel 0, which made per-channel "best channel" stats meaningless — that's fixed, so yield numbers and steering are now trustworthy.

### Why the brain lives on the phone, not the Pi

The core idea of PwnCompanion is to **move the "thinking" off the Pwnagotchi and onto the phone**, and leave the Pi doing only what it's good at — hunting. The reasons:

- **The Pi Zero 2 W has nothing to spare.** It's CPU/RAM/thermal-limited, and its combo chip shares **one 2.4 GHz radio between Wi-Fi and Bluetooth** — running a neural net or an LLM on it steals cycles and heat from the capture loop and worsens the BT-tether contention. The phone has real compute, RAM, and an NPU sitting idle.
- **The original brain was already gone.** The jayofelony image ships **without** the reinforcement-learning tuner (no `torch`/`stable-baselines3`) — just a reward scalar and mood faces. So the adaptive "AI" had to live *somewhere*; the phone is the obvious host.
- **The phone has senses the Pi doesn't** — **GPS** (location-aware steering + geotagged captures), a **persistent cross-session database** (learning that survives reboots), and a **big interactive screen**. The Pi has none of these.
- **Small, reversible nudges go down; nothing heavy goes up.** The phone decides (bandit + tuner) and only sends clamped `set_*` hints and short voice lines to the Pi. If the phone disconnects, the Pi just keeps hunting on its own — no dependency, no risk.

Net: a richer, adaptive brain (and a full personality voice) **without** adding a gram of load, heat, or battery drain to the device whose radio is already stretched thin.

### How our "AI" compares to evilsocket's original

The original Pwnagotchi (evilsocket) genuinely learned; ours is deliberately simpler and transparent, but does things the original couldn't:

| | **evilsocket original** | **PwnCompanion (ours)** |
|---|---|---|
| What "AI" is | **A2C reinforcement learning** — a neural net (`MlpLstmPolicy`, TensorFlow/stable-baselines) | **UCB1 bandit** + heuristic hill-climb (classic online algorithms), plus a curated corpus for voice — **fully deterministic** |
| Runs on | the Pi | the phone |
| Learns by | gradient descent on a reward over many epochs | online explore/exploit + a slow feedback hill-climb, backed by a cross-session DB |
| Tunes | personality params (`recon_time`, `min_rssi`, TTLs, channels) to maximise handshakes | the **same knobs**, re-implemented via clamped `set_param` |
| Transparency | black-box learned policy | fully interpretable / deterministic / debuggable |
| Voice | static `voice.py` templates | curated corpus, mood- & franchise-driven with live-data slots — spoken on the pet's own e-ink |
| Footprint | heavy (torch/TF) — **removed** in the jayofelony fork | **zero** extra load on the Pi; all compute lives on the phone (and no model at all — pure Kotlin) |
| Failure mode | can converge oddly, opaque | can't "go rogue"; falls back to safe defaults, voice degrades to canned lines |

Honest take: evilsocket's was "more AI" in the machine-learning sense — a real learned policy that could, in principle, discover non-obvious strategies. Ours trades that for **transparency, off-device compute, GPS-awareness, cross-session memory, and an actual personality voice** — and, unlike the original, it actually runs on the current RL-stripped image.

### It learns over its lifetime

The learned baseline is **persisted to disk** (DataStore), so the companion's disposition survives app restarts and genuinely develops over time: a Pwnagotchi that captures constantly trends permanently more confident and cocky, while one that mostly idles trends bored and low-energy. Recent activity, best channel, and time-since-last-capture also feed the disposition and the data-slot recap/status lines, so what the pet says reflects what it has actually learned.

### Fed by the device's own telemetry

Every epoch, the Pwnagotchi reports rich telemetry the AI folds into its disposition — the strongest "learned, not fixed" signal:

- **`reward`** — the device's own reinforcement-learning self-score → confident/proud when high, frustrated when negative.
- **Mood counters** (`bored_for_epochs`, `sad_for_epochs`, `active_for_epochs`, …) — how long it's been in each emotional state → drifts the learned baseline.
- **Thermal/CPU stress** (`temperature`, `cpu_load`) — a hot or pegged Pi reads as irritable and drained.

> ⚠️ `reward` and the mood counters only flow while the device is **hunting in AUTO mode** — pwnagotchi pauses its epoch loop in manual, so they aren't produced there. Basic vitals (`temperature`/`cpu`/`mem`) are pushed on a ~12 s timer regardless of mode, so the `[ vitals ]` gauges stay live even while paused.

---

## Capture History

On connect, the plugin scans the handshake directory and pairs each `<ssid>_<bssid>.pcap` with the `.gps.json` sidecar it wrote at capture time, sending the app a **geolocated capture log** (newest first). New captures are pushed live as they happen. The app shows a `[ captures ]` console section: total handshakes, how many are geolocated (`⌖`), and the most recent networks with time-ago.

Tapping in opens a full **`[ captures ]` detail** with a searchable list and a **pixel map** of where handshakes were caught. It fetches dark [CARTO](https://carto.com/) basemap tiles for the capture area and renders them as a **pixel-perfect square grid** (drawn in a Compose `Canvas` at integer cell sizes, auto-contrast-normalised): the street network shows as muted **grey** squares, each catch as a bright **green** square, and your current position as an **orange** one. Tiles are cached on disk; with no network it falls back to a pure-ASCII block heatmap so there's always a map. © OpenStreetMap © CARTO.

## Handshake cracking (wpa-sec + on-phone)

A handshake only *counts* once it's cracked, so each capture is graded on-device by **`hcxpcapngtool`**:

- **crackable** — a PMKID or a full EAPOL 4-way handshake (yields a hash)
- **partial** — an incomplete grab (e.g. only an M1 frame); can never be cracked

The `[ captures ]` screen tags each catch and shows a `cracked · crackable · partial` split (filter with `[ ] crackable` / `[ ] cracked`). There are two ways to actually crack — a free server, or the phone itself.

### wpa-sec (server-side)

If **wpa-sec** is enabled, the plugin uploads crackable handshakes and downloads results hourly to `wpa-sec.cracked.potfile`; the app matches passwords to captures by BSSID, tags them **cracked** with the password inline, and the pet gloats in-character when a *new* one lands. A **cracking** status row shows whether wpa-sec is on **and whether the service is reachable** (the Pi health-checks it — wpa-sec goes down sometimes). Cracked/connect events also fire **notifications**.

> Cracking is server-side against wordlists, so only weak/dictionary passwords fall — strong random ones won't. Only handshakes captured *after* wpa-sec is enabled auto-upload; an existing backlog has to be submitted once.

### On-phone (offline, no server)

You can also crack **on the phone itself** — no server, no account, works offline. The plugin distills each capture to a hashcat-`22000` line (via `hcxpcapngtool`) and sends it up alongside the capture; PMKID captures then show a bright **`crack ▸`** tag. Tap one and the phone cracks it directly:

- **Pure-Kotlin WPA2** — `PMK = PBKDF2-HMAC-SHA1(passphrase, ESSID, 4096)` → `PMKID = HMAC-SHA1(PMK, "PMK Name" ‖ AP ‖ STA)`, checked against each candidate. No native code, no model, verified against a reference vector in unit tests.
- **Wordlist** — pwncrack's `default.gz` (**~655 K** WPA-valid, 8–63-char candidates), downloaded once to app storage and reused offline. It's WPA-tuned, so its hit-rate per candidate is far higher than a general dump like rockyou — which is the whole reason it stays small and finishes in hours, not days.
- **Live progress** — a banner shows `tried / total · rate · ETA` over a real progress bar; the crack runs across your CPU cores.
- **Queue** — tap several `crack ▸` rows and they run one after another (`cracking X · N queued`, with skip / stop). Serial by design: cracking is pure PBKDF2 already fanned across cores, so two at once would only halve each other's speed.
- **Survives lock** — a foreground service keeps a multi-hour crack alive with the screen off (Doze / background-CPU throttling would otherwise stall it), showing progress in its notification.
- **Resume** — the position is checkpointed continuously, so an interrupted crack (process kill, reboot, unplug, cancel) picks up where it left off instead of restarting from candidate 0.
- **Gentle power knobs** (on by default, in a `power` chip row) — **easy cpu** (cap workers at 2), **charger only** (pause while unplugged, auto-resume on replug), **stop <15%** (pause on low battery). Cracking is genuinely heavy — a hot phone, real battery draw, ~4 h for the full list — so these keep it civil; it's best run plugged in with the screen off.

> On-phone cracking is **PMKID-only for now** (EAPOL / `WPA*02` is the next step). Like wpa-sec it's a dictionary attack — weak/common passwords fall, strong random ones won't — but it's fully local, private, and needs no internet or wpa-sec account.

## Device Vitals

The `[ vitals ]` section surfaces live telemetry as **block-bar gauges** (`cpu ██████···· 40%`): temperature, CPU load, memory, the RL `reward` (green when positive), plus environment density (APs / clients / peers). `temperature`/`cpu`/`mem` are pushed **every ~12 s regardless of mode** (a plugin timer), so the gauges stay live even while paused; `reward` and the mood counters only exist while hunting in AUTO. The same per-channel AP/client density also enriches the channel-learning model — so "best channel" reflects where the targets actually are, not just where handshakes happened to land.

## Live-pet notification

The two always-on foreground services (WebSocket + GPS) share a **single** ongoing notification (kept at MIN importance so it stays quiet). Expand it and it becomes a glanceable widget: the Pwnagotchi's **e-ink face** as the image plus a live stats line — `pwnagotchi · 169 caught · 4 clients · 49°C · gps 0.00,0.00` — tinted phosphor green.

While an **on-phone crack** is running, a third (transient) foreground service shows its own low-priority progress notification — `Cracking <ssid> · N queued` over a progress bar, with a **Stop** action — and clears itself when the queue drains.

Separately, an **Alerts** channel (normal importance) fires event notifications: **link up** to the Pwnagotchi (throttled against reconnect churn) and one per **newly cracked** network showing its SSID + recovered password. The connect-time backlog is seeded silently so it never floods.

## Look & feel

Fixed dark phosphor-terminal theme (no white launch flash): a terminal app **icon** (the pwnagotchi "smart" `(✜‿‿✜)` face in phosphor green on a CRT-grid background), a matching dark **splash**, the bundled Share Tech Mono font everywhere, and uppercase `[ SECTION ]` headers. When no pwnagotchi is linked the console shows a `[ standby ]` panel (idle face + a rotating in-character line) instead of empty sections.

---

## Plugin Hooks

The `pwn-companion.py` plugin implements the following pwnagotchi event hooks:

| Hook | Purpose |
|------|---------|
| `on_loaded` | Load config options |
| `on_ui_setup` / `on_ui_update` | Show connection status + GPS on e-ink |
| `on_bt_tether_connected` | Start UDP discovery + WebSocket client |
| `on_bt_tether_disconnected` | Stop services **non-blocking** (fire-and-forget) so a BT drop never freezes the pwnagotchi main loop |
| `on_handshake` | Save GPS `.gps.json` sidecar, write the `.22000` hash sidecar (for on-phone cracking), push live capture entry, fire handshake AI event **with the capture's real channel + BSSID** |
| `on_association` | Fire network_discovered AI event |
| `on_deauthentication` | Fire anomaly AI event |
| `on_epoch` | Accumulate channel stats (+ AP/client density), push `autotune_stats` + `device_telemetry` |
| `on_grateful` / `on_bored` / `on_sad` / `on_angry` / `on_excited` / `on_lonely` | Push the device's real emotion to the app (folded into the AI's traits) |

On connect, the plugin also scans the handshake directory and sends a `capture_history` message (geolocated capture log).

### Plugin → App message types

| `type` | Description |
|--------|-------------|
| `image` | Base64 PNG of the e-ink screen |
| `status` | Device name / connection status / mood + `wpa_sec_enabled` · `wpa_sec_online` (cracking status) |
| `gps_request` | Asks the phone for its current GPS fix |
| `network_event` | Handshake / association / deauth / idle events (drive the AI); handshake + discovery events carry `channel` + `bssid` |
| `autotune_stats` | Per-channel handshake/deauth/assoc + AP/client density |
| `device_telemetry` | Vitals + `reward` + density + mood counters (per epoch in AUTO); also a lightweight `temperature`/`cpu_load`/`mem_usage` push every ~12 s in any mode |
| `capture_history` | Geolocated capture log + per-capture crackability (`quality`: eapol/pmkid/partial) + hashcat-`22000` hash (on-phone cracking) |
| `cracked` | wpa-sec results (`bssid` → `password`), matched to captures in-app |

App → device commands (`type: command`): `restart_auto` / `restart_manual` (mode), `set_channel_priority` (focus bettercap recon on the app's learned-best channels), `set_param` (clamped auto-tuning knobs), and `set_voice_pool` (fresh in-character e-ink lines).

---

## Troubleshooting — connection drops / needing frequent reboots

On the Pi Zero 2 W, **Bluetooth and WiFi share one combo radio chip**. When both are active they contend for the antenna, and the bt-tether link can wedge — often only recovered by a **hard reboot** of the Pwnagotchi. This is a hardware limitation, not a plugin bug.

- **Symptom:** the app shows `link : ○ listening…` and never connects even though the device is paired, or the link dies mid-session.
- **Recovery:** hard-reboot the Pwnagotchi.
- **Durable fix:** add a **USB WiFi adapter** so WiFi hunting and the BT tether stop sharing the built-in radio.
- **Softer recovery:** resetting `hciuart` (not just the `bluetooth` service) tends to bring BT back without a full reboot.

The plugin is hardened for this flaky link: sends are bounded by a timeout, WebSocket keepalive detects a silently-dropped tether in seconds, the socket is always closed cleanly on disconnect, and BT-drop teardown is non-blocking so a drop never freezes the pwnagotchi's main loop. These reduce how often the link wedges but can't remove the underlying radio contention.

---

## License

**GPL-3.0** — see [`LICENSE`](LICENSE). You may use, modify, and redistribute this software under the terms of the GNU General Public License v3.0; derivative works must remain open-source under the same license.

Built on / credits: [Pwnagotchi (jayofelony fork)](https://github.com/jayofelony/pwnagotchi).

### Trademarks & parody

The pet's voice riffs on cult films and games (Evil Dead, Star Wars, The Matrix / Mr. Robot, Harry Potter, Terminator, Tron, Jurassic Park, Alien, RoboCop, Blade Runner, WarGames, Hackers, Portal, Predator, 2001: A Space Odyssey, Cyberpunk 2077, System Shock, Mad Max, Ghostbusters, Back to the Future, The Thing). PwnCompanion is an **unofficial, non-commercial parody/homage** — it is **not affiliated with, endorsed by, or sponsored by** any of those rights holders. All titles, characters, and quoted phrases are the trademarks and copyrights of their respective owners, referenced here for parody and commentary only.

> Reminder: authorized / educational use only — see the responsible-use note at the top.
