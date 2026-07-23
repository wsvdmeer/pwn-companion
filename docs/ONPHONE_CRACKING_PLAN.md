# On-phone WPA cracking — design plan

Feature branch: `feature/on-phone-cracking`. Goal: crack captured WPA2/PMKID handshakes
**locally on the phone**, offline, against a downloaded wordlist — no server (wpa-sec /
pwncrack) required. Fits the app's "compute lives on the phone" model; the Pi just supplies
the hash.

**Non-goals:** GPU cracking; brute-forcing HashMob-scale (billions / tens of GB) lists. This
targets *modest* lists against *specific* handshakes.

## Feasibility (measured)

`pwncrack.org/wordlists/default.gz` = **~655K words**, already WPA-shaped (min-8-char,
numeric/router-default heavy). HashMob offers everything from ~64 KB up to ~30 GB (billions).

The limit on-phone is **compute time**, not download size — WPA2 = PBKDF2-HMAC-SHA1 × 4096
iterations per candidate:

| wordlist        | ~words   | native (~10k/s) | pure-Kotlin (~800/s) |
|-----------------|----------|-----------------|----------------------|
| `default.gz`    | 655K     | ~1 min          | ~14 min              |
| small           | ~5M      | ~8 min          | ~1.7 h               |
| **medium**      | ~25M     | **~40 min**     | ~8.7 h               |
| large           | ~100M    | ~2.8 h          | ~35 h                |
| HashMob founder | billions | days            | weeks                |

(Rates optimistic; sustained is lower once the phone thermally throttles.) **Sweet spot:
≤ ~25–50M words (≈ ≤500 MB unzipped) → minutes to ~1 h native.** → go **native + streaming**.

## Architecture

1. **Hash acquisition (Pi → phone).** Cracker works on the hashcat `22000` hash, not the pcap.
   The plugin already runs `hcxpcapngtool` for grading — extend it to also emit the 22000 line
   per capturable pcap and send it over the WebSocket, keyed by BSSID. App stores `hash22000`
   on the `CaptureEntry`. **PMKID (`WPA*01`) first** — one HMAC after PBKDF2; EAPOL (`WPA*02`)
   adds PTK/MIC + message-pair/key-version handling.

2. **Native cracker (JNI).** Self-contained C (SHA1 + HMAC + PBKDF2, ~200 LOC, no OpenSSL
   dependency), multi-threaded over CPU cores. `PMK = PBKDF2(pass, ESSID, 4096, 32)` then
   PMKID/MIC verify. Early-exit on match, cancel flag, progress counter via JNI.
   NEON 4-way SHA1 is a later 2–4× optimization. Reintroduces NDK/CMake (arm64-v8a) — small,
   justified (the llama.cpp native lib we removed was far bigger).

3. **Wordlist management (streaming).**
   - Download by URL (`default.gz`, a chosen medium HashMob list), show progress.
   - **Stream + gunzip on the fly** — never unpack a 500 MB / 30 GB file to disk.
   - **Filter 8–63 chars inline** — skips candidates that can't be WPA keys (prunes generic lists).
   - **Size/time preview** before committing: "~N words → ~T min, ~S MB"; refuse the absurd ones.
   - Pause / resume / cancel + ETA for longer runs.

4. **UI.** `[ captures ]` detail → per-capture "crack on phone" (needs a hash + a wordlist).
   Cracking screen: wordlist picker, live progress (tried/total, rate, ETA), cancel, result.
   Wordlist manager: add-by-URL / download / delete. A found password flows into the existing
   "cracked" tagging (inline password, pet gloat, notification). Coexists with wpa-sec
   (online) as a second, offline crack source.

5. **Thermal / battery.** Run only on explicit action; show ETA; throttle/pause when hot;
   suggest charging for long runs.

## Phasing

1. **Plumbing** — plugin emits 22000 hash → app stores it. Verify hashes arrive (no cracking).
2. **Native PMKID cracker** — self-contained C, single-thread; prove a known password cracks.
3. **Scale** — multi-thread + EAPOL MIC verify + cancel/progress.
4. **Wordlists + UI** — download manager (default.gz + medium lists), crack action, progress,
   cracked-tagging integration.
5. **Optional** — NEON SHA1 optimization.

## Risks / unknowns

- EAPOL MIC correctness (key version, message-pair selection) → PMKID first mitigates.
- Sustained thermal throttling.
- Wordlist storage footprint (guarded by the size preview).
- `hcxpcapngtool` 22000 output parity across versions.

## Responsible use

Authorized networks only; compute-only over already-captured handshakes. Same disclaimer as
the rest of the app.
