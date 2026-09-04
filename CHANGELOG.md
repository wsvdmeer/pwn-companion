# Changelog

All significant changes to PwnCompanion, most recent first.

---

## Session — 2026-09-04 (clean dark basemap · unified confirm sheet)

App `1.2.7` (build 20)

### Map — clean dark basemap (drop the phosphor pixel shader)
| Area | Detail |
|------|--------|
| Fix the "shape on every tile" | The map fetched CARTO `dark_nolabels` **without an API key**, which CARTO now returns stamped with a diagonal "API KEY REQUIRED" watermark. The phosphor pixel-shader amplified that watermark into a pale ghost shape repeating on every tile. Switched `TileMapLoader` to Esri **Dark Gray Canvas** (keyless; note z/y/x order + JPEG) and bumped the tile-cache prefix so the old watermarked tiles aren't served |
| Drop the pixel shader | Removed the `RuntimeShader` phosphor post-effect from `SlippyPixelMap` — a plain dark basemap reads far clearer than the pixelated version. Catch/you markers still draw on top (green / orange). (The API<33 `PixelBasemap` fallback still pixelates — lower priority) |
| Fix "Map data not available" on zoom-in | Esri Dark Gray Canvas only has tiles to **z16**; past that its MapServer returns a placeholder tile whose image literally reads "Map data not available" (HTTP 200, so it drew on screen). The FETCH level is now clamped to the source max and deeper view zoom **over-zooms** (scales up) those tiles instead; view zoom capped at 18 (~4× over-zoom) |
| Pixelated look, no shader | Rebuilt the pixel aesthetic cheaply and on every API level: nearest-neighbour tile scaling (`FilterQuality.None`) so over-zoomed tiles are crisp chunky pixels, plus a **map-anchored pixel-grid overlay** on the same cells the catch pixels snap to |

### App — one confirm sheet for all disruptive actions
| Area | Detail |
|------|--------|
| Unified `ConfirmSheet` | `go auto` / `go manual`, `reboot pi`, `shutdown pi`, and now `stop service` all route through a single reusable `ConfirmSheet`/`ConfirmSpec` instead of separate `ModeSwitchSheet` / `PowerActionSheet`. Consistent look + behaviour, action-specific confirm labels (`reboot` / `shutdown` / `stop`); `stop service` gained a confirm it previously lacked |

---

## Session — 2026-09-04 (clean partials)

plugin `2.4.0`

### Clean uncrackable partial captures
| Area | Detail |
|------|--------|
| Why | A **partial** grab is one `hcxpcapngtool` can't distil a WPA\*01/WPA\*02 hash from — no `.22000`, so it can never be cracked. They just accumulate on the device. |
| Plugin — `clean_partials` command | New `_clean_partials()` deletes settled partial captures + their sidecars. Two safety rails: it re-classifies each grab with `hcxpcapngtool` (authoritative) and deletes **only** an exact `partial` verdict — a crackable or *unknown* result is kept; and it skips any capture written to within `PARTIAL_SETTLE_S` (300 s), since bettercap appends frames to a `.pcapng` and a fresh partial may still grow into a full handshake. Bumped to `2.4.0`. |
| App — manage sheet | The captures **manage** sheet gains a confirm-gated **clean partials** action (shown only when partials exist), stating how many it will remove. It drops partials from the phone cache immediately and the plugin resends its trimmed history so the count reconciles. Crackable captures and cracked passwords are untouched. |

---

## Session — 2026-09-04 (decouple capture band-tag from GPS · geo backfill)

plugin `2.3.0` · no app change

### Plugin — captures keep their band even without a fix
| Area | Detail |
|------|--------|
| Band/geo decoupled | The `.gps.json` sidecar — the only thing that persists a capture's channel (which the app turns into the 2.4/5 GHz band) — was written **only when a GPS fix existed at grab time**. So a handshake caught with no fix (typically while the app was disconnected and the pet hunted on its own) lost its **band** forever, not just its location. Live data showed the coupling exactly: only the geolocated captures had a band tag (53/272). The sidecar is now written whenever a **channel or a fix** is known, via a shared `_write_gps_sidecar()`, so every future capture is band-tagged regardless of GPS |
| Geo backfill | A fix arriving within `GEO_BACKFILL_WINDOW_S` (30 s) of a fix-less grab now backfills that capture's coordinates (`_backfill_pending_geo()`), recovering the common "GPS warm-up / brief dropout" case. Bounded window so a moving pet never gets a wildly stale location pinned onto an old grab; the pending list is pruned to the window so an offline pet can't grow it unbounded |

> Note: only affects **new** captures — the ~219 existing sidecar-less grabs never recorded a channel, so they can't be retroactively band-tagged; captures made while disconnected still can't be geolocated (the phone is the only GPS source and must be tethered), but they now at least get a band.

---

## Session — 2026-09-02 (manual-mode discovery watchdog · power confirm sheet)

App `1.2.6` (build 19) · plugin `2.2.0`

### Plugin — bootstrap discovery via a watchdog
| Area | Detail |
|------|--------|
| Manual-mode fix | Discovery was started only from the bt-tether connect event, with an `on_epoch` fallback. MANUAL mode never epochs, so that fallback was dead — a missed one-shot connect event left the plugin idle forever. A 30s background watchdog now re-checks and starts discovery whenever a BT-PAN interface is up but we're neither discovering nor connected. Shared `_ensure_discovery()` behaves identically with or without epochs; `_start_client_discovery` is lock-guarded/idempotent so the extra caller can't double-start |

### App
| Area | Detail |
|------|--------|
| Power confirm sheet | `reboot pi` / `shutdown pi` in the command bar now confirm via a terminal-styled bottom sheet (matching the mode-switch sheet) instead of the inline two-tap. Shutdown is called out as unrecoverable without a physical power-cycle |

---

## Session — 5 GHz channel steering (dual-band adapters)

App `1.2.5` (build 18) · plugin `2.1.0`

Merged via PR #1. The hunt advisor was 2.4 GHz-only; on a dual-band adapter that left the entire 5 GHz spectrum unhunted.

### App — steering
| Area | Detail |
|------|--------|
| 5 GHz candidate universe | Steering now uses the device-reported **supported channels** as the candidate set instead of assuming 2.4 GHz — so 5 GHz channels become steerable targets |
| Exploration slot | A reserved exploration slot keeps 5 GHz getting hunted rather than being starved by 2.4 GHz yield; the slot rotates onto whichever band is currently **uncovered** |
| DFS excluded | DFS 5 GHz channels are held out of steering — dwelling on a radar-restricted channel produced "blind" stalls |
| Cold-start steer | Discovery can steer at cold start; the dead autotune gate that blocked early steering was dropped, and `supported_channels` is sent even when autotune is empty |
| Ground truth | Per-channel ground truth (autotune) is reconstructed from AP channel + live scan when the device doesn't report it directly |

### App — UI / captures
| Area | Detail |
|------|--------|
| Dual-band recon spectrum | The recon spectrum in the UI shows both bands; duplicate "recon" labels renamed; AI-event log spam trimmed; duplicate-node / image-flip on reconnect fixed |
| Band-tagged captures | Captures are tagged with their **2.4 / 5 GHz band** |

---

## Session — 2026-08-20 (new-pwnagotchi pcapng · mode-switch guard)

App `1.2.4` (build 10) · plugin `2.1.0`

### Plugin — support the new pwnagotchi (v2.9.5.5+)
| Area | Detail |
|------|--------|
| pcapng captures | bettercap now writes `.pcapng` (not `.pcap`). Capture discovery, sidecar naming, and cleanup are format-agnostic via `CAPTURE_EXTS`/`capture_base()`/`is_capture_file()` — pcapng grabs were previously invisible to the plugin |
| Append-aware cache | pcapng files grow as bettercap appends frames, so a "partial" grab can become a full crackable handshake. `_classify_pcap` re-runs `hcxpcapngtool` when the capture is newer than its `.q`/`.22000` sidecar |
| auto-tune → strategy | auto-tune moved to core and was renamed "strategy"; `_autotune_min_rssi()` probes both plugin keys (dedupes two copies of the lookup) |

### App
| Area | Detail |
|------|--------|
| Mode-switch guard | `go auto` / `go manual` in the command bar now confirms via a terminal-styled bottom sheet before restarting the device — it sits by the service/power controls and was getting fat-fingered |
| Update check | Sideloaded app has no store auto-update, so on launch it checks the GitHub `releases/latest` tag; if newer, the `version` row flips to `update → v1.2.x` and links to the releases page. Best-effort + silent on failure; version-compare unit-tested |

---

## Session — 2026-07-22 (UI polish · motion · franchises)

### Console UI
| Area | Detail |
|------|--------|
| `[ standby ]` | When no pwnagotchi is linked, a "waiting" panel (idle face + rotating in-character line) instead of empty sections |
| `[ gps ]` | Only while linked (not on standby); always rendered when connected, showing `acquiring…` until a fix — never vanishes mid-session |
| `[ history ]` | Top-3 channels shown inline as vitals-style bar gauges (bar = activity, value = yield %) |
| `[ captures ]` | Decluttered from 4 stat rows to 2 (counts folded into `total`; crack result + wpa-sec state folded into `crack`, shown only when relevant) |
| `[ log ]` | Main console ellipsizes; log detail wraps full lines (no more mid-line clip) |
| Franchise caption | The current film-world is captioned under the mirrored e-ink (`‹ world ›`) |
| Icon | Launcher icon → the pwnagotchi "smart" `(✜‿‿✜)` face, phosphor green on the CRT grid |
| Service on launch | Foreground/WebSocket service now starts in `onCreate` (no longer deferred to the BT receiver) |

### Voice — 21 franchises
| Area | Detail |
|------|--------|
| +7 worlds | Added HAL 9000, Cyberpunk 2077, SHODAN, Mad Max, Ghostbusters, Back to the Future, The Thing (full 8-category corpus each) → 21 total |

### Steering — motion & the bandit, extracted + tested
| Area | Detail |
|------|--------|
| Motion (hardware speed) | Motion now reads the OS `Location.speed` (Doppler) when present, plumbed GpsService→ScreenData→GpsData; falls back to an **accuracy-gated** position difference, then AP-churn indoors — all hysteresis'd, so GPS jitter / a stationary scan can't false-trip "moving" (the "hops too fast" fix) |
| Pure cores | Extracted `ChannelBandit` (UCB1) + `MotionHeuristic` into a dependency-free `BanditCore.kt`; SyncScheduler/MainViewModel delegate (identical math) |
| Unit tests | `BanditCoreTest` — 11 tests (bandit exploit/explore/decay + motion churn/speed/accuracy-gate). Verified live in AUTO too (steering stays stationary, explores the tail) |

---

## Session — 2026-07-22

### Removed the on-device LLM — voice is now fully deterministic

| Area | Detail |
|------|--------|
| Why | After the curated-first shift, the ~491 MB Qwen2.5-0.5B's only job was phrasing a few live numbers. Not worth the download + 800 MB free-space + RAM/battery + native build + refusal-handling. **Removed entirely.** |
| Voice engine | `BuiltinPersonalityEngine` is now THE voice: selects from `BlendedVoice.corpus[franchise][reaction-category]` (10 franchises × 8 categories), fills live-data slots (`[SESSION]`/`[CRACKED]`/`[BESTCH]`/`[CHANNEL]`/`[TEMP]`/`[SINCE]`) from real capture stats. Instant, offline, never off-character |
| Persistent franchise | One film-world pinned per mood-stretch (`currentFranchise()`), rotating only on disposition flip — unified across the app **and** the e-ink voice pool |
| Deleted | `GgufInference.kt`, `ModelManager.kt`, `ModelDownloadViewModel.kt`, `ModelDownloadScreen.kt`, `ModelDownloadService.kt`, `app/src/main/cpp/` (CMake + llama_jni). `LlamaClient` gutted to a thin deterministic wrapper (kept signatures) |
| Build | Dropped `ndk.abiFilters`, `externalNativeBuild { cmake }`, `noCompress "gguf"`, okhttp; removed `ModelDownloadService` from the manifest. App opens straight to the console — no download screen |
| Fixed | Init-order NPE (`fillSlots` read `_captureStats` before it was assigned during the init-time reseed) |

### UI streamlining (no LLM → no "AI" chrome)

| Area | Detail |
|------|--------|
| Pet card removed | The phone-side personality card is gone — the voice lives on the pwnagotchi's own e-ink now. Also removed the `voice:` line and every "AI" label |
| Advisor → `[ alerts ]` | Dropped the misleading "try chX" headline; the section shows **only** genuine problems (blind/hot/dry/no-clients) + the untapped-target chase, and stays **hidden when there's nothing to warn about** |
| Learning → `[ history ]` | The full learning block became a one-line link to the detail screen; `[ steering ]` is its own live section on the main console |
| Learning detail | Activity-by-hour graph rebuilt full-width with a correct `0·4·8·12·16·20·24` axis (was cut off); channel bars widened |
| Manual mode | Distinct lines (was falsely showing "patrolling" while paused) |

### Deauth/anomaly log data

| Area | Detail |
|------|--------|
| Real station/channel | `on_deauthentication` now threads the station MAC + channel + bssid; `on_anomaly_detected` emits them top-level. Log line shows the actual target instead of "spectrum" |

---

## Session — 2026-07-20

### Handshake cracking pipeline

| Area | Detail |
|------|--------|
| Crackability grading | Plugin grades every capture with `hcxpcapngtool`: `eapol`/`pmkid` = crackable, `partial` = uncrackable grab. Cached in a `<pcap>.q` sidecar; sent as `quality` on the live capture + history scan |
| App display | `CaptureEntry.quality`/`password`; captures list tags each row + shows a `cracked · crackable · partial` split, a `[ ] cracked` filter, and counts on the main block + `[ stats ]` |
| wpa-sec cracked loop | Plugin reads `wpa-sec.cracked.potfile`, matches passwords to captures by BSSID (`cracked` message, on connect + ~2 min); app overlays them (preserved across reconnect merges), tags **cracked** with the password inline; pet gloats on each *new* crack (connect-seed suppressed) |
| Service status | Plugin reports `wpa_sec_enabled` + health-checks `wpa_sec_online` (fresh on connect, re-checked ~5 min); captures **cracking** row shows on / online / service OFFLINE |
| Backlog upload | One-time bulk-upload of the 59 crackable pcaps to wpa-sec (the plugin only auto-uploads captures taken *after* it's enabled) |

### AI — use the model on more

| Area | Detail |
|------|--------|
| Blended voice | Replaced the 5-theme picker with ONE mood-driven persona; tone (hyped/grumpy/weary/deadpan) chosen live by the emergent disposition. Later: **one franchise per line** (pinned per utterance, never blended) across **ten** film worlds (Evil Dead, Star Wars, Matrix/Mr Robot, Harry Potter, Terminator, Tron, Jurassic Park, Alien, RoboCop, Blade Runner) |
| Device emotions | Plugin hooks the *real* pwnagotchi mood events (`on_grateful`/`on_bored`/`on_sad`/`on_angry`/`on_excited`/`on_lonely`) — the old `on_mood`/`agent._mood` path never fired. App maps `GRATEFUL`/`ANGRY` to traits |
| RL-brain narrator | New session best/worst epoch reward → the pet narrates what the device's own RL agent is learning (throttled) |
| Ask box + AI feed | Grounded natural-language question box (answers only from a live facts block); an `ai feed` log of recent lines + triggers |

### UX / notifications

| Area | Detail |
|------|--------|
| Alerts | Cracked + connected notifications on a DEFAULT-importance "Alerts" channel, **grouped** under one bundled summary; connect throttled 2 min, cracked seed suppressed |
| Waiting-for-link | `networkingDesired` exposed as state; the start-service button + link row show "waiting for Bluetooth link" (armed) instead of a silent no-op |
| Steering | `SyncScheduler` no longer steers channel priority (or logs) in MANUAL mode |
| Map | `[ captures ]` pixel map opens centred on your location; pinch-zoom / drag-pan / double-tap reset, crisp at any zoom; height-capped so the list scrolls |
| Crisp visuals | e-ink image renders nearest-neighbour (`FilterQuality.None`); learning + energy bars switched from blurry block glyphs to uniform `Box` cells; app icon eyes `+_+` → `^_^` |
| Stats | `[ stats ]` adds crackable/partial split, unique APs, last-24h/7d, catch cadence, busiest hour |

### Docs

- README rewritten with a full **setup/onboarding** section (JDK 17, SDK 36, NDK + CMake, build/install, runtime-permissions table, plugin packages incl. `hcxtools`, optional wpa-sec config); documented cracking, notifications, the ten-franchise voice; fixed stale claims (real emotion hooks, ask box exists, RL narrator).

---

## Session — 2026-06-17

### Reliability Hardening (audit fixes)

Found via a subsystem audit; all verified live on-device.

#### Connection (root cause of "app doesn't see the Pwnagotchi")

| File | Fix |
|------|-----|
| `WebSocketServerService.kt` | Ktor CIO binds asynchronously, so `.start()` returning and `server != null` proved nothing — a failed bind (stale socket after reinstall) was invisible. Now a real loopback TCP **probe** confirms the bind; `start()` returns `Boolean`; `isRunning()` reflects the confirmed bind |
| `NetworkService.kt` | UDP announcer is now **gated on the server actually binding** (was unconditional → broadcast a dead port the Pi could never reach) |
| `NetworkService.kt` | Added a 12 s **health check** that rebinds when bnep is up but the port isn't listening — recovers the "no down→up transition after force-stop/reinstall, so nothing retries" wedge (`networkingDesired` intent flag) |

#### Native LLM (use-after-free + leak)

| File | Fix |
|------|-----|
| `GgufInference.kt` | `close()` now runs under `inferenceMutex`, so `nativeFree` can never race an in-flight `nativeGenerate` (use-after-free → SIGSEGV) |
| `LlamaClient.kt` | The ~350 MB model is now actually freed — on reload (`initializeModel` closes the old handle first) and on ViewModel clear via an app-lifetime scope (`viewModelScope` is already cancelled in `onCleared`) |

#### Plugin (`pwn-companion.py`)

| Fix |
|-----|
| Guard `_channel_stats` mutation + serialization with the lock — kills the "dictionary changed size during iteration" crash (epoch thread vs sender thread) |
| Run `execute_command` via `run_in_executor` so `agent.run()` / `pwnagotchi.restart()` never block the asyncio loop (was freezing image/GPS heartbeats). Verified: command now runs on an executor thread (`asyncio_1`) |
| Snapshot `_agent` under the lock before use |

#### Learning → steering signal

| File | Fix |
|------|-----|
| `SyncScheduler.kt` | Rank steered channels by **handshake success rate** (with a per-channel observation floor), falling back to density — was ranking by raw observation count (the noisiest channels) |

> Audited timestamp seconds-vs-millis concern was a **false positive**: captures are seconds-consistent, observations millis-consistent, and they never mix — no change needed.

### New Features

#### Selectable AI voice themes

| File | Detail |
|------|--------|
| `PersonalityThemes.kt` (new) | 5 themes — dry sardonic / evil dead / star wars / harry potter / hacker cinema. Each supplies an LLM persona + few-shot examples, curated canned quote pools, and "thinking…" phrases |
| `PwnagotchiViewModel.kt` | `theme` StateFlow, `selectTheme()`, persisted via DataStore; few-shot + persona injected into `buildPrompt` |
| `PersonalityRepository.kt` | persist/restore the selected theme id |
| `PwnagotchiPersonalityCard.kt` | tappable `voice :` chip row (active = filled green) |
| `LlamaClient.kt` | franchise themes lead with the curated canned voice (the 0.5B model can't reproduce quotes); `dry` uses the LLM |

#### Learning → attack feedback loop (soft channel priority)

| File | Detail |
|------|--------|
| `SyncScheduler.kt` | real `sendStrategyCommand` (was a stub): sends top-3 learned channels as `set_channel_priority` to the live session ids every 45s (gated on ≥12 observations) |
| `pwn-companion.py` | `set_channel_priority` handler applies `wifi.recon.channel <list>` to bettercap (live, reversible) |
| `MainViewModel.kt` / `Composables.kt` | `channelPriority` flow + `[ LEARNING ] steering : recon → ch …` readout + log line |

### Bug Fixes

| File | Fix |
|------|-----|
| `LlamaClient.kt` | LLM safety refusals ("Sorry, I can't assist…") are detected (`sanitize`/`refusalMarkers`) and replaced with the in-character voice; system prompt softened (fictional game, never refuse) |
| `LlamaClient.kt` | response cut-off fixed earlier (maxTokens 20→64); full output vetted before streaming |
| `Composables.kt` | device screen keeps the last good frame instead of blanking on a bad/partial decode |

### UI / Polish

| Change | Detail |
|--------|--------|
| Headers | `[ AI ]` / `[ LOG ]` uppercased to match the rest |
| Prompt | dropped redundant `root@` from header + AI shell prompt |
| Command bar | stop button is now a service toggle: `[ stop service ]` / `[ start service ]` |
| Tofu fix | removed emojis (🧠🛑📡📶📍) from the AI card — they rendered as boxes in the monospace font |

---

## Session — 2026-06-15

### Bug Fixes

#### Native crash — concurrent LLM inference (SIGSEGV)

| File | Fix |
|------|-----|
| `PwnagotchiViewModel.kt` | `generatePersonality()` set its `_isGenerating` guard flag *inside* the launched coroutine (after a 2s delay), so rapid AUTO-mode events slipped past the guard and launched concurrent generations. Flag is now claimed **synchronously** before the launch |
| `GgufInference.kt` | Added a `Mutex` (`inferenceMutex`) serialising every native call — two coroutines sharing the single llama context corrupted ggml compute buffers → `SIGSEGV` in `ggml_compute_forward_mul_mat`. This is the definitive fix |

#### Telemetry / autotune never sent

| File | Fix |
|------|-----|
| `pwn-companion.py` | `on_epoch` did `if ch is None: return`, but this pwnagotchi's `epoch_data` has no `channel` key — so it bailed every epoch *before* sending `autotune_stats` / `device_telemetry`. Channel logic is now conditional; telemetry always sends when connected |

### New Features

#### Capture History (geolocated handshakes)

| File | Detail |
|------|--------|
| `pwn-companion.py` | `_scan_capture_history()` pairs each `<ssid>_<bssid>.pcap` with its `.gps.json` sidecar; sends full log as `capture_history` on connect + single live entries from `on_handshake` |
| `ScreenData.kt` | `CaptureEntry` model + `captures` field + `TYPE_CAPTURE_HISTORY` |
| `DeviceState.kt` / `NetworkService.kt` | `captures` merged + deduped by BSSID (newest first) in `mergeCaptures()` |
| `MainViewModel.kt` | `captures` StateFlow aggregated across devices |
| `Composables.kt` | `ConsoleCapturesBlock` → `[ captures ]` section (total / geolocated `⌖` / recent list with time-ago) |

#### Device Telemetry → Vitals + Emergent AI

| File | Detail |
|------|--------|
| `pwn-companion.py` | `on_epoch` sends `device_telemetry`: `temperature`, `cpu_load`, `mem_usage`, `reward`, `num_aps`/`num_sta`/`num_peers`, and `*_for_epochs` mood counters. Channel stats also track peak AP/client density |
| `ScreenData.kt` | Telemetry fields + `DeviceTelemetry` model + `toTelemetry()`; `AutotuneChannelStat` gains `aps`/`sta` |
| `DeviceState.kt` / `NetworkService.kt` | `telemetry` stored from `device_telemetry` messages |
| `MainViewModel.kt` | `telemetry` StateFlow |
| `Composables.kt` | `ConsoleVitalsBlock` → `[ vitals ]` section (temp/cpu/mem/reward/env/peers/epoch) |
| `PersonalityStateEngine.kt` | `applyTelemetry()` — folds reward + mood counters + thermal/CPU stress into the continuous trait vector (tiny per-epoch deltas → learned baseline) |
| `PwnagotchiViewModel.kt` | `applyTelemetry(DeviceTelemetry)` wrapper, persists baseline |

---

## Session — 2026-04-20

### Bug Fixes

#### Bluetooth / Connection

| File | Fix |
|------|-----|
| `BluetoothTetherMonitor.kt` | Changed `NetworkRequest` from `NET_CAPABILITY_INTERNET` to `TRANSPORT_BLUETOOTH`. bt-pan never has internet capability — the monitor was never firing |
| `BluetoothTetherMonitor.kt` | Added `java.net.NetworkInterface` fallback in `getBnep0InterfaceIp()` for devices where `ConnectivityManager.allNetworks` misses the bt-pan interface |
| `BluetoothTetherMonitor.kt` | `checkBnep0Interface` now re-triggers `onBluetoothStateChanged(true)` when capabilities change on an already-detected interface with a new IPv4 address (catches DHCP completion) |
| `NetworkService.kt` | Added DHCP retry: `getBnep0InterfaceIp()` is retried up to 10× with 2s gaps. Previously, if the interface appeared before DHCP assigned an IP, start() failed silently and never recovered |
| `NetworkService.kt` | Throttle deadlock fix: when a state change is throttled, `lastBnep0State` is set to `null` instead of being left unchanged — prevents a throttled disconnect from making the subsequent reconnect look like a duplicate |
| `NetworkService.kt` | Duplicate-check bypass: if BT is still detected but `serverStarted == false`, the duplicate guard is skipped to allow a retry |
| `NetworkService.kt` | `stop()` now resets `lastBnep0State = null` and `lastBnep0Ip = null` so a new BT connect can restart the service after a manual stop |
| `CompanionBackgroundService.kt` | Removed direct `networkService.start()` call — only `initialize()` is called; `BluetoothTetherMonitor` is now the sole trigger, eliminating the double-start race |
| `AndroidManifest.xml` | Fixed `BluetoothConnectionReceiver` intent-filter: was `CONNECTION_STATE_CHANGED` + `BOND_STATE_CHANGED`, now correctly `ACL_CONNECTED` + `ACL_DISCONNECTED` — the receiver was never firing |

#### IP Address Display

| File | Fix |
|------|-----|
| `WebSocketServerService.kt` | Reverted `origin.remoteHost` (not available in Ktor 2.3.x) back to `local.remoteHost` which correctly returns the remote client's IP in Ktor CIO |
| `NetworkService.kt` (IP monitor) | Removed code that overwrote `DeviceState.ipAddress` with the phone's own `bnep0` IP — the device state stores the Pwnagotchi's IP (remote client), not the phone's IP |

#### Stop Server

| File | Fix |
|------|-----|
| `MainViewModel.kt` | `stopServer()` now sets `_isServerRunning.value = false` immediately — previously the UI never reflected the stopped state |

#### Event → AI Pipeline (was completely broken)

| File | Fix |
|------|-----|
| `ScreenData.kt` | Added all `network_event` fields: `eventType`, `eventDescription`, `network`, `count`, `signal`, `channel`, `security`, `reason`, `totalCaptures` — these were silently ignored before |
| `MessageHandler.kt` | Registered handler for `"network_event"` — messages were received but dropped with "No handler registered" |
| `MessageHandler.kt` | Added `NetworkEventUpdate` data class + `networkEventUpdates` SharedFlow |
| `MessageHandler.kt` | Registered handler for `"autotune_stats"` (acknowledged, not dropped) |
| `MainViewModel.kt` | Subscribes to `networkEventUpdates`, exposes `lastNetworkEvent` StateFlow |
| `Composables.kt` | `LaunchedEffect(lastNetworkEvent)` in `MainContentArea` calls `pwnagotchiVM.generatePersonality()` and `recordCapture()` — the AI now actually reacts to Pwnagotchi events |

#### Disconnect / Screen Clear

| File | Fix |
|------|-----|
| `MainViewModel.subscribeToDeviceStates()` | When `deviceStates` becomes empty, clears: image, imageDeviceId, imageTimestamp, statusMessage, gpsData, lastNetworkEvent — screen was previously frozen on last state |

---

### New Features

#### AI Moods (attacker-themed)

Replaced the generic moods (Curious/Confident/Aggressive/Focused/Suspicious/Frustrated) with six hacker-personality moods:

- **Predator** 🦈 — relentless hunter, short hungry bursts
- **Reaper** ☠️ — machine-like, counts handshakes as trophies
- **Ghost** 👻 — stealth-focused, beauty of invisibility
- **Berserker** ⚡ — reckless fury, max aggression
- **Tactician** 🎖️ — cold strategist, clinical RSSI/channel analysis
- **Phantom** 🌑 — patient, ominous, waits for perfect moment

Attack moods (Predator/Reaper/Berserker/Ghost) are sticky — events won't auto-override them.

Each mood has a `promptPersonality` string that fully rewrites the LLM's character in the prompt.

#### AI Model Name Display

`PwnagotchiViewModel.modelName` is now exposed (via `llamaClient.modelManager.modelName`) and shown in the personality card subtitle: `🦈 Predator · phi-2.Q3_K_M.gguf`

#### Mood Selector UI

Horizontally scrollable `FilterChip` row in `PwnagotchiPersonalityCard` — one chip per mood, selected chip highlights with `primaryContainer` colour.

#### Pwnagotchi Name

Plugin sends `device_name` (snake_case) in its status message. `ScreenData` now maps `@SerialName("device_name")` to `deviceNameSnake`. `DeviceState` stores `pwnagotchiName`. `DeviceCard` shows the Pwnagotchi's own name (e.g. "bob") instead of the BT session identifier.

Resolution priority: `device_name` (snake_case) → `deviceName` (camelCase) → BT device name

#### Auto-tune Integration

Plugin reads the `auto-tune` plugin's state every 30 seconds and sends `autotune_stats` with per-channel handshake/deauth/association counts, best channel, and min RSSI. `DeviceCard` displays this section when data is available.

#### Connection Status Dot Label

The coloured dot in `DeviceCard` now has an "Online"/"Offline" text label next to it.

---

### UI / Design

| Change | Detail |
|--------|--------|
| Icons | All `Icons.Filled.*` replaced with `Icons.Outlined.*` throughout — M3 design uses outlined icons for non-primary actions |
| AI Learning card icon | Replaced `🧠` emoji with `Icons.Outlined.BarChart` |
| Pwnagotchi card icon | Replaced `🎭` emoji with `Icons.Outlined.Android` |
| Footer Info icon | `Icons.Filled.Info` → `Icons.Outlined.Info` |
| Device card icon | `Icons.Filled.Phone` → `Icons.Outlined.PhoneAndroid` |

---

### Plugin (`pwn-companion.py`)

| Change | Detail |
|--------|--------|
| `_agent` reference | Stored when `on_bt_tether_connected` fires — used for auto-tune access |
| `_send_autotune_stats(agent)` | Reads auto-tune plugin state, sends `autotune_stats` message |
| `_periodic_autotune_push()` | Coroutine that runs `_send_autotune_stats` every 30s while connected |
| `_start_periodic_tasks()` | Now also starts the auto-tune push task |

---

### Model / Data

| File | Change |
|------|--------|
| `ScreenData.kt` | Added `device_name` (snake_case), all `network_event` sub-fields, `AutotuneChannelStat` nested class, `TYPE_NETWORK_EVENT` + `TYPE_AUTOTUNE` constants, `resolvedDeviceName` computed property |
| `DeviceState.kt` | Added `pwnagotchiName`, `autotuneChannels`, `autotuneBestChannel`, `autotuneMinRssi` |
| `ModelManager.kt` | `modelName` changed from `private` to `val` (public) |
| `LlamaClient.kt` | `modelManager` changed from `private` to `val` (public) |

