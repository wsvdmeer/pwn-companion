# Changelog

All significant changes to PwnCompanion, most recent first.

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

