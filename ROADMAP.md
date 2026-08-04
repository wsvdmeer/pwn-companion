# Roadmap

Directions and ideas for PwnCompanion — this is a personal/hobby project, so these are
things I'd like to do, **not promises or dates**. Issues and PRs welcome.

## Prioritized next

The current focus, in no strict order:

- **More tests (partly done)** — capture merge/normalize, HuntAdvisor, and the CrackEngine
  candidate-space math (`CrackSpace`) are now covered. What's left is CrackEngine's **queue +
  checkpoint I/O** (enqueue/dedup, resume position), which needs Robolectric or a Context fake — a
  bigger lift than the pure-logic tests.
- **Connection-health polish** — clearer reconnect state for the flaky Pi Zero BT link. Drops
  mid-session are common; the real fix is a USB Wi-Fi adapter, but the app can surface the state better.

## Backlog / ideas

- **UPC / Ziggo default-key generator** — the highest-prevalence NL family, but **parked pending a
  real-router vector** (2026-08). "UPC" turned out to be *several* device variants (blasty's `upc_keys`
  ≠ upcwifikeys.com's algorithm), so reproducing one only proves the port is faithful — not that it
  matches the routers you'll actually see. Blocked on confirming a generated candidate is the real key
  of a live UPC/Ziggo capture. When unblocked: native JNI port of blasty's C into `wpa_crack.c` (add
  MD5 + `upcCandidates(essid)`), gated on that vector. Notes on `feature/isp-keygen` + `ISP_KEYGEN.md`.
- **GPU-offload bridge (`pwnbridge`)** — a small cross-platform CLI (single self-contained binary for
  Windows/Linux/macOS) that runs on a PC with a GPU: the phone sends a capture's `22000` hash over the
  LAN, the PC cracks it with hashcat (millions/s vs the phone's hundreds) using the operator's full
  wordlist + rules, and the result auto-returns to the app and marks the network cracked. Batch mode
  fires the whole uncracked backlog in one hashcat run. Designed + parked (a reference Python agent
  lives on `feature/pc-bridge`); the "copy hash" button is the zero-setup manual precursor.
- **Wordlist picker + streaming** — choose among multiple wordlists and stream a `.gz`
  line-by-line (rockyou / HashMob) without the in-memory ceiling. Most worthwhile once cracking
  is faster.
- **Multi-lane SIMD cracking (low priority — probably not worth it)** — 4–8-way NEON SHA-1 to hash
  several candidates per pass. Deprioritized: we already use the ARMv8 SHA-1 **crypto extension**, and
  a single-lane hardware hash typically matches or beats 4-way *software* NEON on modern cores (which
  is why hashcat prefers SHA-NI/crypto-ext over SIMD where present) — so the expected gain on the
  phones we target is small for a lot of fiddly native code. The real speed levers are cheaper:
  **using more cores** (the "easy cpu" toggle caps at 2 for a cool phone) and the **GPU-offload bridge**
  above. Revisit only if targeting cores *without* hardware SHA-1.
- **Multi-pwnagotchi** support (more than one device at once).
- **R8 / minify** for release builds, with tested keep-rules for kotlinx-serialization + Compose.
- Refreshed screenshots showing the current on-phone cracking + captures UI.

*Considered and dropped: a manual "deauth this AP" button — it only makes sense for untapped APs, and
the pwnagotchi already auto-deauths everything it sees while the app steers channel priority, so a
per-AP button just duplicates the autonomy.*

## Recently shipped

- **Offline starter wordlist + auto-update** — a compact bundled list (~21K WPA-relevant words) loads
  with no network, so the first crack works out of the box; the full pwncrack list downloads in the
  background and refreshes via a throttled conditional GET (~once a day) when it changes.
- **ISP / ESSID default-key candidates** — Thomson/SpeedTouch default-key derivation
  (reference-vector verified: `SpeedTouchF8A3D0 → 742DA831D2`) + universal ESSID name-guesses, tried
  before the wordlist (the "targeted" phase). Cracks a slice of real networks a generic list never
  gets, on-phone in seconds. (UPC/Ziggo — the native-scale family — is still on the roadmap.)
- **Slippy pixel map** — a real OSM tile map with smooth panning, discrete stepped zoom (stable
  pixels), a phosphor pixel shader, and tap-to-open catch clusters (replaces the display-only view).
- **Voice + settings** — 34 selectable film-world franchises (a rotation pool in Settings) and
  per-notification toggles.
- **On-phone EAPOL (`WPA*02`) cracking** — native (ARMv8 SHA-1) + Kotlin fallback, verified
  against the hashcat mode-22000 reference vector. Most captures are EAPOL, so this roughly
  doubles what's actually crackable on-phone.
- **Word-mangling rules** — hashcat-style variants (append digits/years/bangs, capitalize, leet),
  chosen per-crack.
- **Offline capture persistence** — captures + their 22000 hashes cached on the phone, so browsing
  and cracking work with the Pi disconnected or after a restart.
- **Capture management** — full capture detail sheet, per-capture forget / delete-on-device,
  clear phone cache / wipe device handshakes, an in-detail stop button, and an "already tried"
  marker.
- **Cracking UX** — crack-time wordlist options (quick/mangle), split filters/options sheets, and
  a crack banner that shows the network + all options in effect (`eapol · native · mangle · …`).
- **CI (GitHub Actions)** — build + unit tests on every push; on a `v*` tag, build and attach a
  **signed** APK to the Release automatically.
- **Live captures without a GPS fix** — a handshake caught during a GPS dropout reaches the app
  list immediately instead of waiting for the next re-scan.
