# PwnCompanion — Architecture & Logical Flow

## Overview

The system has two sides: the **Android app** (server) and the **Pwnagotchi plugin** (client). The app is the server; the Pwnagotchi is the client. All communication happens over the Bluetooth PAN network.

```
┌─────────────────────────────────────┐      Bluetooth PAN (bnep0/bt-pan)
│         ANDROID PHONE               │ ◄──────────────────────────────────►  PWNAGOTCHI
│                                     │
│  UDP broadcast → port 8888          │  Plugin listens on UDP:8888
│  WebSocket server on port 8081      │  Plugin connects to ws://phone-ip:8081
│  GPS data → sent on request         │  Requests GPS, sends screen/events
└─────────────────────────────────────┘
```

---

## Bluetooth Connection Flow

```
Android OS (Bluetooth Tethering enabled)
    │
    ├─ Pwnagotchi pairs & connects via BT PAN
    │
    ├─ Android creates bt-pan / bnep0 interface
    │      Phone IP:  e.g. 10.x.x.x
    │      Pwn IP:    e.g. 10.x.x.y  (assigned via DHCP)
    │
    ├─ BluetoothTetherMonitor detects bnep0 (via ConnectivityManager + NetworkInterface fallback)
    │      NetworkRequest uses TRANSPORT_BLUETOOTH — does NOT filter by NET_CAPABILITY_INTERNET
    │      (bt-pan never has internet capability — this was the original bug)
    │
    ├─ onCapabilitiesChanged fires after DHCP completes
    │      App retries getBnep0InterfaceIp() up to 10× / 2s each to wait for DHCP
    │      (without retry: app failed silently if DHCP hadn't assigned IP yet)
    │
    └─ NetworkService.start() called
           Gets phone's bnep0 IP
           Starts WebSocket server on 0.0.0.0:8081
           Starts UDP announcements → broadcasts ws://phone-ip:8081 every 5s
```

### Reconnection Handling
- `lastBnep0State` is set to `null` when a state change is throttled — prevents the reconnect event being treated as a duplicate
- After manual stop, `lastBnep0State` and `lastBnep0Ip` are reset so a new BT connect restarts everything
- `checkBnep0Interface` re-triggers `onBluetoothStateChanged(true)` when capabilities change on an already-known interface (catches DHCP completion)
- Duplicate-check bypass: if BT is detected but `serverStarted == false`, the "duplicate" guard is skipped to allow retry

---

## Plugin Flow (pwn-companion.py)

```
on_bt_tether_connected(agent, event_data)
    │
    ├─ Stores device name from event_data
    ├─ Stores agent reference (for auto-tune access)
    └─ _start_client_discovery(interface)
           │
           ├─ Binds UDP socket on port 8888 (SO_BINDTODEVICE to bnep0)
           ├─ Listens for {"type": "announcement", "serverIp": "...", "serverPort": 8081}
           └─ On announcement received:
                  _connect_to_app(ws://phone-ip:8081)
                      │
                      ├─ Sends {"type": "ready"}
                      ├─ Sends {"type": "status", "device_name": "bob", ...}
                      ├─ Sends {"type": "gps_request"}
                      └─ Starts periodic tasks:
                             _periodic_image_push()    — every push_image_interval seconds
                             _periodic_gps_request()   — every request_gps_interval seconds
                             _periodic_autotune_push() — every 30 seconds
```

### Plugin → App Message Types

| `type` | Direction | Description |
|--------|-----------|-------------|
| `image` | Plugin→App | Base64 PNG screenshot of Pwnagotchi display |
| `status` | Plugin→App | Device name, IP, mood, `wpa_sec_enabled` / `wpa_sec_online` (cracking status); sent on connect + on change |
| `gps_request` | Plugin→App | Requests current GPS from phone |
| `gps_received` | Plugin→App | Ack after receiving GPS |
| `network_event` | Plugin→App | WiFi events: handshakes, deauths, discoveries, anomalies (handshake/discovery carry real `channel` + `bssid`) |
| `autotune_stats` | Plugin→App | Per-channel handshake/deauth/assoc + AP/client density |
| `device_telemetry` | Plugin→App | Per-epoch vitals: temperature, cpu_load, mem_usage, reward, num_aps/num_sta/num_peers, mood counters (AUTO mode only) + a lightweight temp/cpu/mem push every ~12 s in any mode |
| `capture_history` | Plugin→App | Geolocated capture log + per-capture crackability (`quality`) — full set on connect, single entries live |
| `cracked` | Plugin→App | wpa-sec results (`bssid` → `password`), matched to captures in-app; on connect + ~2 min |
| `ready` | Plugin→App | Sent on connect/reconnect |
| `gps` / `gps_response` | App→Plugin | GPS coordinates response |
| `command` | App→Plugin | Control commands (scan, stop, message) |

### network_event Sub-types (event_type field)
- `handshakes_captured` — count, network, security, total_captures
- `network_discovered` — ssid, bssid, security, signal, channel, is_new
- `anomaly_detected` — anomaly_type, details
- `high_value_target` — network, reason
- `connection_success` — network, duration
- `connection_failure` — network, reason
- `scan_complete` — networks_found, duration

---

## Android App — Component Map

```
MainActivity
    │
    ├─ NetworkServiceSingleton.getInstance()   (shared across Activity + BackgroundService)
    │       └─ NetworkService
    │               ├─ BluetoothTetherMonitor   detects bnep0/bt-pan interface
    │               ├─ WebSocketServerService    Ktor CIO server on 0.0.0.0:8081
    │               ├─ UdpAnnouncementService    broadcasts ws://phone-ip:8081 on UDP:8888
    │               ├─ MessageHandler            routes all incoming WebSocket messages
    │               └─ OutgoingMessageQueue      queues GPS responses + commands
    │
    ├─ MainViewModel
    │       ├─ subscribeToDeviceStates()        → _deviceStates, _connectedDeviceCount
    │       │       └─ on empty: clears image, GPS, status, lastNetworkEvent
    │       ├─ subscribeToMessageUpdates()
    │       │       ├─ deviceImageUpdates        → _currentImageData
    │       │       ├─ deviceGpsUpdates          → _gpsData
    │       │       ├─ deviceStatusUpdates       → _currentStatusMessage
    │       │       └─ networkEventUpdates       → _lastNetworkEvent  ← feeds AI
    │       └─ stopServer()                     stops NetworkService + updates UI state
    │
    └─ Compose UI — single full-bleed terminal console (no Material cards/icons)
            └─ MainContentArea  (LazyColumn of console sections, separated by rules)
                    ├─ header                    pwncompanion v1  root@<name>
                    ├─ ConsoleStatusBlock        link / node / mode / gps
                    ├─ [ screen ]                live e-ink image (RawDeviceImage)
                    ├─ PwnagotchiPersonalityCard [ ai ] — emergent disposition + LLM response
                    ├─ ConsoleLearningBlock      [ learning ] — observations, busiest channel
                    ├─ ConsoleVitalsBlock        [ vitals ] — temp/cpu/mem/reward/env/peers/epoch
                    ├─ ConsoleGpsBlock           [ gps ] — lat/lon/acc/alt/fix
                    ├─ ConsoleCapturesBlock      [ captures ] — total / geolocated / recent
                    ├─ EventFeedCard             [ log ] — live terminal event feed
                    ├─ ConsoleCommandBar         [ go auto ] / [ go manual ] / [ stop ]
                    └─ ConsolePermissionBanner   [ permissions ] — shown only when missing

    LaunchedEffect wiring in MainContentArea:
        lastNetworkEvent → pwnagotchiVM.generatePersonality()
        telemetry        → pwnagotchiVM.applyTelemetry()
        deviceMood       → pwnagotchiVM.applyDeviceMood()
```

---

## Message Flow: Incoming WebSocket → UI

```
WebSocketServerService.handleWebSocketSession()
    │  deserializes JSON → ScreenData (ignoreUnknownKeys=true)
    │  extracts clientIp via call.request.local.remoteHost
    │
    ▼
NetworkService.onDataReceived(deviceId, screenData)
    │  updates DeviceState: pwnagotchiName, lastImage, GPS, autotune fields
    │
    ▼
MessageHandler.handleIncomingMessage(deviceId, screenData)
    │
    ├─ "image"         → _deviceImageUpdates  → MainViewModel → _currentImageData
    ├─ "gps"           → _deviceGpsUpdates    → MainViewModel → _gpsData
    ├─ "gps_request"   → logged (TODO: wire GpsService)
    ├─ "status"        → _deviceStatusUpdates → MainViewModel → _currentStatusMessage
    ├─ "network_event" → _networkEventUpdates → MainViewModel → _lastNetworkEvent
    │                                                         → LaunchedEffect
    │                                                         → PwnagotchiViewModel.generatePersonality()
    └─ "autotune_stats"→ logged; DeviceState already updated by NetworkService
```

---

## AI Personality System (emergent, learned)

There is **no fixed mood picker**. Personality emerges from a continuous trait vector and is learned over the device's lifetime.

```
PersonalityStateEngine
    │  PersonalityState(confidence, curiosity, frustration, energy, ego, boredom)
    │
    ├─ applyEvent(type)        — WiFi events nudge traits (handshake → +confidence/-boredom, …)
    ├─ applyDeviceMood(raw)    — device's REAL emotion events (on_grateful/bored/sad/angry/
    │                            excited/lonely, hooked in the plugin) folded into traits
    ├─ applyTelemetry(...)     — reward + *_for_epochs counters + temp/cpu stress (per-epoch);
    │                            a new session best/worst reward triggers the RL-brain narrator
    ├─ decay()                 — traits drift back toward the learned baseline
    └─ learn()                 — baseline slowly drifts toward lived state (long-term memory)

PwnagotchiViewModel
    │
    ├─ personality: EmergentPersonality   — dominant trait → disposition + accent colour + tier
    │
    ├─ generatePersonality(WifiEvent)
    │       _isGenerating claimed SYNCHRONOUSLY before launch (prevents concurrent inference)
    │       builds prompt: emergent traits + captures + event + compact memory
    │       streams tokens via LlamaClient.generateStreaming() (serialised by inferenceMutex)
    │
    ├─ applyTelemetry(DeviceTelemetry)     — feeds the engine, persists baseline
    │
    └─ baseline persisted via PersonalityRepository (DataStore) — survives restarts
```

> The learned baseline is the "learned, not fixed" core: a device that captures constantly
> trends permanently confident/cocky; one that idles trends bored/low-energy.

### Voice (blended, mood-driven — no picker)

The persona is fixed; what varies is **tone** and **franchise**, both derived, never chosen:

- **Tone** — the emergent disposition maps to `hyped / grumpy / weary / deadpan` (`BlendedVoice.toneFor`), injected as a directive into the prompt.
- **Franchise** — one of ten film worlds (Evil Dead, Star Wars, Matrix/Mr Robot, Harry Potter, Terminator, Tron, Jurassic Park, Alien, RoboCop, Blade Runner) is **pinned per line** (avoiding an immediate repeat) so a reply never blends two.
- The reliable fallback (`BuiltinPersonalityEngine`) picks canned lines keyed by `category × tone`, all single-franchise. A refusal gate keeps any "Sorry, I can't…" off-screen.

### Voice on the device's own screen (voice pool)

The app also drives the pwnagotchi's **own e-ink speech bubble** with these AI lines instead of its stock, repeating `voice.py` quips — no pwnagotchi core rework.

```
PwnagotchiViewModel
    ├─ voicePool: Map<category, List<line>>   — keyed by the device's OWN voice categories
    │      (normal/bored/sad/angry/excited/grateful/lonely/handshakes/deauth/assoc/
    │       motivated/demotivated + last_session = the MANUAL-mode recap, built from our
    │       data via buildRecapPrompt (rotating focus) instead of the stock kicked/handshakes
    │       tally; on_unread_messages is pointed at the same recap pool so the grid "N new
    │       messages" line stops stealing the manual screen)
    │  pre-seeded with curated short lines for every category (so `normal` — the device's
    │  dominant idle state, set each recon cycle — is never blank), then filled two ways:
    │    • passively — a real event reaction is folded into its matching category
    │    • actively  — a ~90s round-robin loop generates one line/category (generateQuick,
    │                  small token budget), only while connected + model ready; LLM lines
    │                  prepend and push the seeds out over time (→ more variety)
    │  cleanLine(): keeps ONE short clause, rejects rambles / "X" cue-parrots / dangling
    │               fragments, caps ~44 chars → matches the stock lines' length (~12–34c)
    ↓  (StateFlow) → MainViewModel.sendVoicePool()
Command  {type:"command", message:"set_voice_pool", data:<JSON {category:[line,…]}>}
    ↓  (existing OutgoingMessageQueue — no new wire type)
Plugin (pwn-companion.py)
    ├─ _apply_voice_pool()  — atomic dict swap + timestamp
    └─ on_ui_setup → _wrap_voice(ui._voice): monkeypatches Voice.on_<category>() so each
       returns a pooled line when fresh (app connected + < 20 min old), else defers to the
       ORIGINAL stock method. Native inference is serialised, so no e-ink/LLM contention.
```

Falls back to the device's own voice cleanly whenever the app is disconnected, the pool is stale, or a category has no clean line. Functional readouts (`on_free_channel`/`on_napping`/`on_waiting` countdowns) are deliberately left to the stock voice.

---

## Pwnagotchi Name Resolution

The Pwnagotchi's actual name (e.g. "bob") is sent in the `status` message as `device_name` (snake_case):

```
Plugin → {"type":"status", "device_name":"bob", ...}
ScreenData.deviceNameSnake = "bob"
DeviceState.pwnagotchiName = "bob"
DeviceCard displays "bob" instead of the BT device identifier
```

Fallback chain: `device_name` (snake_case) → `deviceName` (camelCase) → BT device name

---

## Auto-tune Integration

The plugin reads the `auto-tune` plugin's internal state every 30 seconds and sends:
```json
{
  "type": "autotune_stats",
  "autotune_channels": {"1": {"handshakes":5,"deauths":2,"associations":10}, "6": {...}},
  "autotune_best_channel": 6,
  "autotune_min_rssi": -80
}
```

Android side: `DeviceState` stores the data; `DeviceCard` shows best channel, min RSSI, and top 3 channels by handshake count. Data clears when the device disconnects.

---

## Handshake Cracking Pipeline (wpa-sec)

A handshake only counts once cracked, so the whole capture→crack→gloat loop is observable:

```
capture (.pcap)
    │
    ├─ Plugin: hcxpcapngtool grades it → quality = eapol | pmkid | partial
    │     cached in <pcap>.q sidecar; sent on the capture / capture_history
    │
    ├─ wpa-sec plugin (if enabled): uploads crackable handshakes → wpa-sec.stanev.org
    │     downloads results hourly → /home/pi/handshakes/wpa-sec.cracked.potfile
    │
    ├─ Plugin: reads the potfile → `cracked` message (bssid → password)
    │     + health-checks wpa-sec reachability → wpa_sec_online in status
    │
    └─ App (NetworkService): overlays passwords onto captures by BSSID
          (carried across reconnect merges); UI tags them cracked + shows the password;
          handleCracked() fires a grouped notification for NEW cracks (connect-seed
          suppressed); the pet announces it in-character (announceCracked)
```

- **crackable** = a PMKID or full EAPOL 4-way handshake (yields a hash); **partial** = incomplete (e.g. only M1), never crackable.
- Only weak/dictionary passwords fall (server-side wordlists). Only captures taken *after* wpa-sec is enabled auto-upload; a backlog is submitted once.

---

## Deauth Advisor & Steering

The headline mission feature — **all analysis is phone-side (Kotlin); the LLM only phrases it**, so the recommendation is always correct.

- `HuntAdvisor` ranks channels by the device's own `autotune_stats` (handshakes + client density), flags an untapped target (seen often, never caught), and raises mission alerts (`blind` / `running hot` / `APs but 0 clients` / dry spell).
- `SyncScheduler` sends the top channels back as `set_channel_priority` (→ bettercap `wifi.recon.channel`) every 45 s — a soft, reversible nudge, **skipped in MANUAL mode**. Prefers device-truth autotune, then the app's learned channel/time/location model.

---

## State Cleanup on Disconnect

When `deviceStates` becomes empty (Pwnagotchi disconnects/restarts):
- `_currentImageData` → null (image card hidden)
- `_gpsData` → null (GPS card hidden)
- `_currentStatusMessage` → null
- `_lastNetworkEvent` → null (AI card resets to "Waiting for network events...")

On reconnect everything repopulates automatically from incoming messages.

---

## Known Constraints

- **BT tethering must be enabled manually** in Android Settings — the app does not initiate tethering, it only observes the resulting network interface
- **One Pwnagotchi at a time** — BT PAN supports one PAN client per Android device
- **AI model (~491 MB, Qwen2.5 0.5B Instruct Q4_K_M GGUF)** — downloaded on first run; built via llama.cpp JNI (compiled from C++ source by CMake/NDK for arm64-v8a)
- **On-device inference is serialised** — one shared llama context; concurrent `llama_decode` calls corrupt ggml memory, so `GgufInference.inferenceMutex` guards all native calls
- **DHCP timing** — bnep0 interface appears before DHCP assigns the IP; app retries IP lookup up to 10× with 2s gaps

