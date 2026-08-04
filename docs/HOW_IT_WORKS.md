# How It Works

← [Back to README](../README.md)

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
- **handshake crackability** (`hcxpcapngtool`) and **cracking** — server-side (`wpa-sec`) or **on-phone** (native PMKID cracker + Kotlin fallback, offline)

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

App → device commands (`type: command`): `restart_auto` / `restart_manual` (mode), `set_channel_priority` (focus bettercap recon on the app's learned-best channels), `set_param` (clamped auto-tuning knobs), `set_voice_pool` (fresh in-character e-ink lines), and `clear_captures` (delete the device's `.pcap` handshakes — the app's "wipe device handshakes" action).
