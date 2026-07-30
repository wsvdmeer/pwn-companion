# ISP default-key generators — design note

Status: **framework + first generator landed.** Step 1 (framework) and step 2's first generator
(**Thomson/SpeedTouch**, `ThomsonKeygen.kt`, reference-vector verified) are in. This is the scoping
+ how-to for extending it (more router families) — the highest-hit-rate cracking feature.

> Scope reminder: PwnCompanion is for **authorized** Wi-Fi security research on networks you own or
> have explicit permission to test. Default-key generators just move candidates the operator could
> already type by hand into the on-phone cracker — same guardrails as the rest of the app.

## Why

Many ISP routers ship WPA keys that are **algorithmically derived** from the ESSID/BSSID (and
sometimes a serial), not random. Where that's true, a handful of candidates derived from the
capture's own identifiers crack the network in **seconds**, before the wordlist is ever touched — a
far bigger hit-rate win than any larger wordlist. This has been studied and reversed publicly for
years (see Sources).

## The shortcut: RouterKeygen (GPL)

[RouterKeygen](https://github.com/routerkeygen/routerkeygenAndroid) is an open-source Android app
that already implements generators for dozens of router families (Thomson/SpeedTouch, UPC/Ubee,
Technicolor, …). It's **GPL — same license as PwnCompanion — so its algorithms can be ported
directly** rather than reverse-engineered from scratch. That turns most of this from "research each
ISP" into "port + verify the generators matching networks we actually see."

## What the app has vs. needs

The make-or-break constraint is **inputs**. Every capture gives us **ESSID + BSSID** over the air.

- ✅ Algorithms that derive the key from **ESSID + BSSID** are directly implementable (e.g. UPC's
  `UPC1234567` ESSID → candidate set; Thomson from BSSID+ESSID).
- ❌ Algorithms that need a **serial number** (not broadcast) are **out of scope** — a clean line.

Output is tiny (often a few hundred candidates, sometimes < 10), which is why it's seconds on the
native cracker.

## Best targets for NL / EU

- **Ziggo / UPC** (Ubee / Technicolor cable modems) — `UPC`/`Ziggo`-style ESSIDs; documented
  ([UPC UBEE reversing](https://deadcode.me/blog/2016/07/01/UPC-UBEE-EVW3226-WPA2-Reversing.html),
  [upcKeygen](https://github.com/yolosec/upcKeygen)). Highest local prevalence → **do this first**.
- **Thomson / Technicolor** (older KPN/Experia and many EU ISPs) — the classic
  [Kevin Devine algorithm](https://www.gnucitizen.org/blog/default-key-algorithm-in-thomson-and-bt-home-hub-routers/)
  ([thomson-key](https://github.com/renatomartins/thomson-key)).

## Architecture (already in the tree)

Step 1 is implemented and inert:

- **`crack/KeyGenerator.kt`** — the interface: `id`, `matches(essid, bssid)`,
  `candidates(essid, bssid)`.
- **`crack/KeyGenerators.kt`** — registry. `candidatesFor(essid, bssid)` runs every matching
  generator, length-filters to 8..63, de-dups preserving priority order, and isolates a throwing
  generator. The `generators` list is empty for now; add implementations there.
- **`crack/CrackEngine.kt`** — the crack loop prepends generated candidates to the wordlist space:
  total = `ispCount + wordCount × mult`; `candidateAt` returns the ISP candidate for the first
  `ispCount` indices, then the wordlist (with mangling) after. The resume checkpoint id and the
  progress-banner `mode` (`… · isp …`) pick up an `isp` tag when present.
- **`test/.../KeyGeneratorTest.kt`** — covers the registry (match gating, length filter, dedup,
  throw isolation).

With no generators registered, `ispCount == 0` and a crack behaves exactly as a plain wordlist run.

## How to add a generator (step 2)

`ThomsonKeygen.kt` (+ its test in `KeyGeneratorTest`) is the worked example — copy its shape. Next
highest-value family: **UPC/Ziggo** (messier algorithm, but highest local prevalence).

### UPC/Ziggo — notes for the native port

Scoped but **not yet implemented** — it needs native code, not a Kotlin loop:

- **Algorithm:** Peter "blasty" Geissler's `upc_keys.c` (UPC UBEE EVW3226). From a `UPC%07d` SSID,
  brute-force serials `SAAP%d%02d%d%04d`, MD5 → derive SSID (match) → mangle → second MD5 → 8-char
  password (charset excludes `I`,`L`,`O`). Magic constants: `MAGIC_24GHZ=0xffd9da60`,
  `MAGIC_5GHZ=0xff8d8f20`, `MAGIC0=0xb21642c9`, `MAGIC1=0x68de3af`, `MAGIC2=0x6b5fca6b`.
- **Why native:** the serial space is `10×100×10×10000 = 100,000,000` — far too slow for Kotlin on a
  phone. Port blasty's C into `wpa_crack.c` (add MD5 + a JNI entry `upcCandidates(essid)`), and have
  a Kotlin `KeyGenerator` call it, self-checked like `NativeWpaCracker.verified`.
- **Verify:** gate on a published `UPC…→password` vector before registering (blasty's own example
  output, or [upcwifikeys.com](https://upcwifikeys.com/) / the deadcode.me writeup).
- **Ziggo caveat:** blasty targets `UPC` SSIDs; `Ziggo…` is the same hardware but the SSID→serial
  link is unconfirmed — don't claim Ziggo until there's a Ziggo vector.
- **Get the exact source** (do NOT port from a summary — the mangle/charset steps must be exact):
  [igi64/upc_keys](https://github.com/igi64/upc_keys/blob/master/upc_keys.c) ·
  [spaze/upc_keys-lambda](https://github.com/spaze/upc_keys-lambda) ·
  [yolosec/upcKeygen](https://github.com/yolosec/upcKeygen).

1. Implement `KeyGenerator`:
   - `matches`: gate on the ESSID pattern and/or BSSID OUI (first 3 octets) the family uses.
   - `candidates`: derive the passphrases from `essid`/`bssid`. Port the algorithm from the GPL
     source; keep candidates in most-likely-first order.
2. **Write a reference test first** (like `WpaCrackerTest`'s EAPOL vector): a known
   `(ESSID, BSSID) → key`, asserting the generator's output contains that key. A generator without a
   passing reference test **must not** be registered — a wrong one just injects garbage candidates.
3. Add it to the `generators` list in `KeyGenerators`.
4. Everything else — trying them first, progress/ETA, banner, resume — is automatic.

## Caveats (be honest about hit-rate)

- **Patched firmware.** Many of these vulns are fixed in newer models/firmware, so a generator hits
  *older* deployed routers, not new ones. Real but declining coverage.
- **Verification is mandatory** (see step 2.2). This is the same discipline that gated EAPOL.
- **Attribution / license.** Ported algorithms come from GPL sources — fine for this GPL project;
  credit them in the generator's KDoc.

## Sources

- [WOOT'15 — Scrutinizing WPA2 Password Generating Algorithms in Wireless Routers](https://www.usenix.org/system/files/conference/woot15/woot15-paper-lorente.pdf) (the canonical survey)
- [HITB'16 — In Stickers We Trust: Breaking Naive ESSID/WPA2 Key Generation Algorithms](https://archive.conference.hitb.org/hitbsecconf2016ams/sessions/in-stickers-we-trust-breaking-naive-essidwpa2-key-generation-algorithms/)
- [RouterKeygen (Android, GPL)](https://github.com/routerkeygen/routerkeygenAndroid) · [upcKeygen](https://github.com/yolosec/upcKeygen) · [thomson-key](https://github.com/renatomartins/thomson-key)
- [Wifi-WPA-Keyspace-List (catalog)](https://github.com/sheimo/Wifi-WPA-Keyspace-List)
- [UPC UBEE EVW3226 WPA2 reversing](https://deadcode.me/blog/2016/07/01/UPC-UBEE-EVW3226-WPA2-Reversing.html)
- [Default key algorithm in Thomson & BT Home Hub](https://www.gnucitizen.org/blog/default-key-algorithm-in-thomson-and-bt-home-hub-routers/)
