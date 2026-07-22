# PwnCompanion — Features

An Android companion for [Pwnagotchi](https://github.com/jayofelony/pwnagotchi): a single-screen **terminal console** that turns your phone into the Pwnagotchi's brain-on-the-go — live screen, GPS, an on-device AI pet, and a deauth "where to hunt" advisor that helps it catch **more handshakes**.

**Requires Android 10+ (API 29)** on an arm64 device.

## Connectivity
- Connects over **Bluetooth PAN**, using the **[`bt-tether` plugin](https://github.com/wsvdmeer/pwnagotchi-plugins)** (from [wsvdmeer/pwnagotchi-plugins](https://github.com/wsvdmeer/pwnagotchi-plugins)) as the connector/transport — the app runs a WebSocket server on the phone and the `pwn-companion` plugin auto-discovers it via UDP over the bt-tether link.
- Streams the Pwnagotchi's **live e-ink screen**, WiFi events, and telemetry; sends the **phone's GPS** back so captures are geolocated.
- Hardened for the Pi Zero 2 W's shared BT/WiFi radio: send timeouts, keepalive, clean socket teardown, and non-blocking BT-drop handling so a dropped link never freezes the device.

## On-device AI pet (fully local — no cloud)
- Runs **Qwen2.5-0.5B** locally via llama.cpp (JNI); nothing leaves the phone.
- **Real token streaming** — replies type out as the model generates them, with a refusal gate so it never shows "Sorry, I can't…".
- **Emergent, learned personality** — no mood picker; a live trait vector (confidence, curiosity, frustration, energy, ego, boredom) driven by real events **and the device's own emotions** (it hooks the pwnagotchi's `grateful` / `bored` / `sad` / `angry` / `excited` / `lonely` states), with a persisted baseline that develops over its lifetime.
- **Evolution stages** (Rookie → Seasoned → Veteran → Jaded → Apex → Phantom → Singularity) tied to real handshake count — they change the pet's voice.
- **One blended cult-movie voice** — no picker; a hacker-gremlin persona drawing on **ten film worlds** (Evil Dead, Star Wars, Matrix/Mr Robot, Harry Potter, Terminator, Tron, Jurassic Park, Alien, RoboCop, Blade Runner), but committing to **exactly one franchise per line** (pinned per utterance, never blended mid-sentence). Its tone (hyped · grumpy · weary · deadpan) is chosen live by the emergent mood — happy after a catch, grumpy when it's hot/blind/dry, weary when idle.
- **Fact-backed intents**: `hunt` (voice the advisor), `recap` (session digest), `status` (check-in), `poke` (manual).
- **Grounded recap + auto-insights** (no free-text box — a 0.5B model is weak at open Q&A): `recap` folds in the useful queries (crackable/cracked counts, best spot, the AP that keeps escaping), and the pet volunteers its best hunting spot on its own while hunting.
- **AI feed** — a rolling log of the pet's recent lines + what triggered each (reaction, emotion, RL-reward, milestone), so you can see the AI features firing at a glance.
- **Proactive life** — speaks up on its own for tier promotions, capture milestones, alert recovery, and untapped targets (all throttled).
- **RL-brain narrator** — the pwnagotchi runs its own reinforcement-learning agent; when its epoch reward sets a new session best/worst, the pet narrates what its own AI is learning (two AIs talking).
- **Speaks on the device's own screen** — instead of the pwnagotchi's stock, repeating `voice.py` quips, the app streams a rolling pool of fresh AI lines keyed to the device's native voice moments (normal · bored · sad · angry · excited · grateful · lonely · handshakes · deauth · assoc · motivated · demotivated). The plugin splices them straight into the e-ink speech bubble — filled both by real event reactions and a round-robin refresh while connected (weighted toward `normal`, the state shown most) — so the pet says something different every time. In **MANUAL mode** it even replaces the stock "Kicked N stations / Got N handshakes" recap screen with an AI recap built from the app's richer data (catches this session, cracked/crackable, best channel, mood). Lines are **length-calibrated to the tiny e-ink** (one short clause, ~a dozen–40 chars, matching the stock voice): the generator rejects rambles, chopped fragments and quote-parroting so nothing overflows. Falls back to its own stock voice cleanly whenever the app is disconnected or quiet; nothing to configure on the pwnagotchi.

## Deauth advisor — catch more handshakes
- `[ advisor ]` line tells you **where to hunt now** — ranks channels by the device's own per-channel captures + live client density (clients = deauth targets). All analysis is phone-side; the AI only phrases it.
- **Untapped-target spotting** — flags APs seen often but never captured.
- **Mission alerts** — dead antenna (`blind`), thermal throttle, "APs but no clients", dry spells — plus recovery lines when they clear.
- **Steers the hunt** — an explore/exploit **UCB bandit** picks channels (exploit yield + live clients, keep sampling under-explored ones, recency decay), motion-aware (pin when still, hop the wide band when moving), plus motion-driven **dwell** — sent to bettercap as soft, reversible, idle-backed-off nudges.
- **Re-implements the removed AI** — jayofelony's image strips the original RL param-tuner, so the app rebuilds it **on the phone**: a lightweight personality tuner auto-adjusts `min_rssi` / `ap_ttl` / `sta_ttl` / `recon_time` / hop timing (context policy from motion + a slow capture-rate hill-climb on `min_rssi`) and applies them live via a clamped `set_param` command. Deterministic, interpretable, `deauth` never touched. Shown as the `[ learning ] tuning :` line.

## Learning & captures
- `[ learning ]` — per-channel yield bars + an hourly activity sparkline; learns best channel / time / location.
- `[ captures ]` — geolocated handshake log, searchable, with a **pixel-perfect map**: dark basemap tiles rendered as a green-on-grey square grid, catches in green, you in orange. Opens **centred on your location**; pinch to zoom, drag to pan, double-tap to reset — the squares stay crisp at any zoom.
- **Handshake crackability** — each capture is graded by `hcxpcapngtool` on the device (PMKID / full EAPOL = crackable, or an uncrackable partial grab). The list tags each catch and shows a `real vs partial` split, so you know which captures are actual wins and which APs to re-hunt.
- **Cracked-password loop** — when `wpa-sec` is enabled (upload + `download_results`), the plugin reads the returned `wpa-sec.cracked.potfile`, matches passwords to captures by BSSID, tags them **cracked** (with the password, filterable via `[ ] cracked`), counts them across the app, and the pet gloats in-character the moment a new one lands. A **cracking** status row shows whether `wpa-sec` is on **and whether the wpa-sec service is reachable** (the Pi health-checks it periodically — it goes down sometimes), so it's obvious if captures aren't being cracked or the service is offline.
- `[ stats ]` — expanded aggregate view: crackable/partial split, unique APs, last-24h / last-7d counts, catch cadence per day, busiest hour, best channel, and more.

## Vitals & at-a-glance
- `[ vitals ]` — temperature / CPU / memory as **block-bar gauges** + the device's RL reward and environment density; basic vitals stay live (~12s) even while paused.
- **One live-pet notification** — the e-ink face + a stats line (caught · clients · temp · GPS) in a single, quiet foreground notice.
- **Alert notifications** — a heads-up when the Bluetooth link to the Pwnagotchi comes up (throttled against reconnect churn), and one per **newly cracked** network showing its SSID + recovered password. The connect-time backlog is seeded silently so it never floods.

## Look & feel
- Fixed **dark phosphor-terminal** theme, monospace throughout, ASCII block-bars, scanlines — everything on one scrolling screen (no progressive hiding).
- Terminal app icon (green `^_^` face) and matching dark splash — no white launch flash.

## Privacy
- All AI runs on-device. Map tiles are the only network call for the map (cached, attributed). Screenshots for sharing are redaction-gated; raw captures are never committed.
