---
name: debug-stack
description: Debug the full PwnCompanion stack at once — the Android app (via adb/logcat) AND the Pwnagotchi plugin (via SSH/pwnlog) — and report a correlated health + error summary. Use when the user says "debug both", "check the stack", "why won't it connect", "check for errors", or is troubleshooting the phone↔Pwnagotchi link.
---

# Debug the PwnCompanion stack (phone app + Pwnagotchi)

Goal: in one pass, gather health + errors from **both** ends of the system and
correlate them, so connection/AI issues can be diagnosed without manual poking.

## Environment (this machine)

- **adb**: `/c/Android/Sdk/platform-tools/adb.exe` (not on PATH — use full path).
- **App package**: `com.wsvdmeer.pwncompanion`, launcher `.presentation.MainActivity`.
- **Pwnagotchi SSH**: `pi@<your-pwnagotchi>.local` with your SSH password (set these to
  your own device — the pwnagotchi hostname is in its `config.toml` `main.name`). No
  `sshpass`; use PuTTY plink: `/c/Program Files/PuTTY/plink.exe`. First connect prompts to
  cache the host key — pipe `echo y |` to accept.
- **Pwnagotchi log**: `/etc/pwnagotchi/log/pwnagotchi.log` (rotates; grep + tail).
- **Gotchas**:
  - Git-bash mangles `/sdcard/...` paths → prefix commands with `export MSYS_NO_PATHCONV=1`.
  - `screencap` to stdout on this foldable prints a "Multiple displays" warning that
    corrupts piped PNGs — capture to a device file then `adb pull`.
  - logcat is a ring buffer; pull soon after the event or it rotates out.

## Phone side (adb)

```bash
ADB="/c/Android/Sdk/platform-tools/adb.exe"
PKG="com.wsvdmeer.pwncompanion"
"$ADB" devices -l                                   # device present & authorized?
"$ADB" shell pidof "$PKG"                            # app running?
"$ADB" shell ip -4 addr show bt-pan | grep inet      # bt-pan link up + IP
# WebSocket server listening on 8081 (0x1F91)?
"$ADB" shell 'cat /proc/net/tcp6 /proc/net/tcp' | awk '{print $2}' | grep -i ':1F91'
# Announcer broadcasting? (should log every ~5s; absence == announcer stopped)
"$ADB" logcat -d 2>/dev/null | grep -iE "UdpAnnouncer.*broadcast" | tail -3
# Client connected?
"$ADB" logcat -d 2>/dev/null | grep -iE "CLIENT CONNECTED|Total Clients" | tail -2
# LLM health / hangs
"$ADB" logcat -d -v time 2>/dev/null | grep -iE "Using .* inference threads|Starting generation|Token 1:|Generation complete|Throttling" | tail -10
# Crashes / errors for our package
"$ADB" logcat -d 2>/dev/null | grep -iE " E |Exception|FATAL|ANR in $PKG" | grep -i pwncompanion | tail -15
```

## Pwnagotchi side (SSH)

```bash
PLINK="/c/Program Files/PuTTY/plink.exe"
H="pi@<your-pwnagotchi>.local"          # set to your device
echo y | "$PLINK" -ssh -pw "<ssh-password>" "$H" "
  echo '-- bnep0 + route to phone --'; ip -4 addr show bnep0 | grep inet;
  echo '-- plugin loaded / websockets --'; grep -a 'pwn-companion' /etc/pwnagotchi/log/pwnagotchi.log | grep -aiE 'plugin loaded|websockets|aborting' | tail -3;
  echo '-- bt-tether connect event + internet gate --'; grep -aiE 'bt-tether event received|Internet connectivity|no internet' /etc/pwnagotchi/log/pwnagotchi.log | tail -4;
  echo '-- discovery / connection --'; grep -a 'pwn-companion' /etc/pwnagotchi/log/pwnagotchi.log | grep -aiE 'Discovery started|UDP received|Connecting to|Connected to app|Network is unreachable|Discovery loop' | tail -8;
  echo '-- errors --'; grep -aiE 'pwn-companion|bt-tether' /etc/pwnagotchi/log/pwnagotchi.log | grep -aiE 'error|traceback|exception' | tail -10;
"
```
To watch live instead of a snapshot: `pwnlog` on the Pi tails the log (it follows —
run it with a timeout, e.g. `timeout 10 pwnlog`, so it doesn't block).

## Correlate & report

Walk the connection pipeline and pinpoint where it breaks:

1. **BT-PAN link** — phone `bt-pan` up *and* Pi `bnep0` has a `10.x` IP + route to the phone?
2. **bt-tether event** — did the Pi log `bt-tether event received`? If not, discovery
   never starts (the `on_epoch` bnep fallback should cover it). Note: the bt-tether
   plugin only emits the event after its **internet check passes** — a quiet phone
   (no internet) can suppress it.
3. **Announcer** — is the phone broadcasting on 8888 every ~5s? If it went silent,
   that's the announcer-stopped bug (the self-heal in `UdpAnnouncementService` should
   now recover it; if not, a force-stop+relaunch of the app restarts it).
4. **Connect** — Pi logs `Connecting to ws://…` then `✓ Connected to app!`?
   `Network is unreachable` = bnep0 had no usable IP yet when it tried.
5. **Client registered** — phone logs `CLIENT CONNECTED / Total Clients: N`.
6. **LLM** — generations complete and return to Ready (no perpetual "thinking")?

Report a short table: each stage ✓/✗, the first broken stage, the concrete evidence
(quote the log line), and the suggested fix. Don't change device state (e.g. don't
flip AUTO/MANUAL mode) unless the user asks.
```
