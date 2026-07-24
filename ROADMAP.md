# Roadmap

Directions and ideas for PwnCompanion — this is a personal/hobby project, so these are
things I'd like to do, **not promises or dates**. Issues and PRs welcome.

## On-phone cracking

- **EAPOL / `WPA*02` handshakes** — today only PMKID (`WPA*01`) captures are crackable on the
  phone; full 4-way EAPOL handshakes (the `eapol`-tagged captures) are cracked via the PTK + a
  MIC check rather than a PMKID. Adding that is the **next step** — it would make the large
  majority of captures crackable locally, not just the PMKID ones.
- **Faster cracking (multi-lane SIMD)** — the native cracker currently does one candidate per
  core using the ARMv8 hardware SHA-1 instructions (~3× the pure-Kotlin path). The big leap is
  hashing several candidates *in parallel across NEON lanes* (4–8-way PBKDF2), the way desktop
  crackers do — potentially another large multiplier on top.
- **Bigger / streaming wordlists** — today it's one in-memory list (pwncrack `default.gz`,
  ~655k WPA-valid candidates). A streaming loader (read the `.gz` line-by-line off disk instead
  of loading it all) would allow much larger lists (rockyou, HashMob) without the memory
  ceiling, behind a wordlist picker. Only really useful once cracking is faster, though.

## App & build

- **R8 / minify** for release builds, with tested keep-rules for kotlinx-serialization + Compose
  (currently off to avoid breaking reflection/serialization).
- Refreshed screenshots showing the on-phone cracking UI.

## Ideas / maybe

- Export cracked passwords.
- Open to suggestions — file an issue.
