# Roadmap

Directions and ideas for PwnCompanion — this is a personal/hobby project, so these are
things I'd like to do, **not promises or dates**. Issues and PRs welcome.

## Prioritized next

The current focus, in no strict order:

- **ISP default-key generators** ⭐ — many EU/NL home routers (Ziggo, KPN, Telfort, …) ship WPA
  keys that are *algorithmically derived* from the ESSID/BSSID/serial, not random. A per-ISP
  generator produces a tiny candidate set from the capture's own ESSID+MAC and cracks a large
  slice of real networks a generic wordlist never gets — on-phone, in seconds. (Needs the per-ISP
  algorithms; several are publicly documented/reversed.) The single biggest hit-rate win.
- **Faster cracking — multi-lane SIMD** — hash several candidates in parallel across NEON lanes
  (4–8-way PBKDF2) on top of the ARMv8 hardware SHA-1 already in use. A large speed multiplier,
  now especially worthwhile since EAPOL (heavier per candidate) is in play.
- **Tappable map** — tap a map dot → open that capture; filter the map by cracked/crackable. Makes
  the geolocation view interactive instead of display-only.
- **Settings screen** — consolidate the scattered options (intervals, wordlist, notifications,
  cracking-power policy) into one place.
- **More tests** — the capture merge/normalize, HuntAdvisor, and CrackEngine queue/resume are
  untested and have regressed before (timestamp units, connect-race capture drop, advisor/bandit
  divergence). A safety net for the parts that keep breaking.

## Backlog / ideas

- **Wordlist picker + streaming** — choose among multiple wordlists and stream a `.gz`
  line-by-line (rockyou / HashMob) without the in-memory ceiling. Most worthwhile once cracking
  is faster.
- **ESSID-based candidates** — try the ESSID as the password, ESSID + common suffixes, and simple
  local patterns — near-zero cost, catches lazy real-world keys the generic list misses.
- **"Deauth this AP" button** — let the operator target a specific untapped AP on demand, not just
  steer recon. High impact for the deauth mission; must stay behind the authorized-use guardrails.
- **Connection-health polish** for the flaky Pi Zero BT link (clearer reconnect state).
- **Multi-pwnagotchi** support (more than one device at once).
- **R8 / minify** for release builds, with tested keep-rules for kotlinx-serialization + Compose.
- Refreshed screenshots showing the current on-phone cracking + captures UI.

## Recently shipped

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
