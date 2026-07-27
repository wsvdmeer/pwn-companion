# Roadmap

Directions and ideas for PwnCompanion — this is a personal/hobby project, so these are
things I'd like to do, **not promises or dates**. Issues and PRs welcome.

## Cracking — hit-rate first (bigger wins than a bigger wordlist)

- **ISP default-key generators** ⭐ — many EU/NL home routers (Ziggo, KPN, Telfort, …) ship WPA
  keys that are *algorithmically derived* from the ESSID/BSSID/serial, not random. A per-ISP
  generator produces a tiny candidate set from the capture's own ESSID+MAC and cracks a large
  slice of real networks a generic wordlist never gets — on-phone, in seconds. (Needs the
  per-ISP algorithms; several are publicly documented/reversed.)
- **Wordlist mangling rules** — hashcat-style rules (append `0-9`/year, capitalize, l33t,
  ESSID-as-password) multiply the existing list cheaply and catch "Welkom2024"-style keys.
- **EAPOL / `WPA*02`** — today on-phone cracking is PMKID-only; most captures are `eapol`.
  Cracking those (PTK + MIC) is the biggest *coverage* step.
- **Faster cracking (multi-lane SIMD)** — hash several candidates in parallel across NEON lanes
  (4–8-way PBKDF2), the desktop-cracker approach — another large speed multiplier on top of the
  ARMv8 hardware SHA-1 we already use.
- **Bigger / streaming wordlists** — a streaming loader (read the `.gz` line-by-line) would allow
  rockyou / HashMob without the in-memory ceiling, behind a wordlist picker. Only worth it once
  cracking is faster.

## App features & UX

- **Capture detail view** — tap a capture → BSSID, channel, signal, first/last seen, crack
  status + actions (crack / re-hunt / copy password), and "focus on map." Rows are display-only now.
- **Tappable map** — tap a map dot → its capture; filter the map by cracked/crackable.
- **Export** — cracked passwords + capture log to CSV, and the map to KML/GPX (opens in Google Earth).
- **Settings screen** — consolidate the scattered options (intervals, wordlist, notifications,
  cracking power controls) into one place.
- **"Deauth this AP" button** — let the operator target a specific untapped AP on demand, not just
  steer recon. High impact for the deauth mission; must stay behind the authorized-use guardrails.

## Plugin / device

- **Connection-health polish** for the flaky Pi Zero BT link (clearer reconnect state).
- **Multi-pwnagotchi** support (more than one device at once).

## Dev infra

- **CI (GitHub Actions)** — build + run unit tests on every push, and on a `v*` tag build and
  attach the (signed) APK to the Release. Reproducible builds + no more stale release assets.
- **More tests** — only `WpaCrackerTest` + `BanditCoreTest` exist; the capture merge/normalize,
  HuntAdvisor, and CrackEngine queue/resume/persistence are untested and have regressed before
  (timestamp units, advisor/bandit divergence, connect-race capture drop).
- **R8 / minify** for release builds, with tested keep-rules for kotlinx-serialization + Compose.
- Refreshed screenshots showing the on-phone cracking UI.
