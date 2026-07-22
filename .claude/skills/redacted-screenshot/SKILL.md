---
name: redacted-screenshot
description: Capture a PwnCompanion app screenshot from the phone (via adb), auto-redact sensitive data (IPs, GPS, MACs, device name, SSIDs, the e-ink screen), and optionally add it to the README. Use when the user says "screenshot for the readme", "grab a redacted screenshot", "add a screenshot", or wants to share the app publicly without leaking their network/location.
---

# Redacted screenshot

Produce a **safe-to-publish** screenshot of the PwnCompanion Android app: capture it,
black out anything that could pinpoint the user (their networks, location, IPs, device
name), and drop it into the README (or hand it back).

## Environment (this project)

- **adb**: adjust the path to your SDK's `platform-tools/adb.exe` (often not on PATH).
  Prefer PowerShell for adb (Git-bash mangles `/sdcard/...` paths). Over USB just use
  `adb` with the device serial from `adb devices`; for adb-over-WiFi use `<phone-ip>:5555`
  (`adb connect <phone-ip>:5555` — it drops when the phone sleeps).
- **Foldable gotcha**: on a foldable, `screencap`/`uiautomator`/`input` target a specific
  display id (list them with `adb shell dumpsys SurfaceFlinger --display-id`). If the
  active screen is folded shut, captures come back **all black** — unfold, or target the
  cover display's id. On a normal phone you can omit `-d <id>` entirely.
- **Redactor**: `python redact_screenshot.py` (Pillow ≥ 12). Lives beside this file.

## Steps

1. **Pick a screen.** Safest for public sharing (no sensitive data at all): the
   **`[ achievements ]`** or **`[ stats ]`** detail pages — pure terminal UI, milestone
   names + aggregate counts only. Riskier (need redaction): the main screen (IP, node
   name, GPS, the e-ink face, capture SSIDs) and the **`[ captures ]`** page (SSIDs +
   GPS coords). When in doubt, prefer a zero-sensitive screen.

2. **Wake + foreground the app** (PowerShell), then navigate to the target screen:
   ```powershell
   $adb="C:\Android\Sdk\platform-tools\adb.exe"; $dev="<phone-ip>:5555"   # or your USB serial
   & $adb -s $dev shell input keyevent KEYCODE_WAKEUP
   & $adb -s $dev shell am start -n com.wsvdmeer.pwncompanion/.presentation.MainActivity
   # scroll / tap to the screen you want …
   ```

3. **Capture the screenshot AND the uiautomator dump** (the dump drives redaction — grab
   both at the same UI state):
   ```powershell
   & $adb -s $dev shell screencap -p -d <display-id> /sdcard/shot.png   # drop -d <id> on a normal phone
   & $adb -s $dev pull /sdcard/shot.png shot.png
   & $adb -s $dev shell uiautomator dump /sdcard/ui.xml
   & $adb -s $dev pull /sdcard/ui.xml ui.xml
   ```

4. **Redact.** The tool auto-blacks IPs, GPS coords, MACs, the device name, image nodes
   tagged `device screen`, plus any `--extra` literals (SSIDs) you name:
   ```bash
   python .claude/skills/redacted-screenshot/redact_screenshot.py \
     --img shot.png --ui ui.xml --out docs/screenshot.png \
     --name <your-device-name> --extra "SSID_ONE" "SSID_TWO"
   ```
   - Add `--box x1,y1,x2,y2` for anything baked into a bitmap the dump can't see.
   - Pass `--expect-redactions` on sensitive screens so it fails loudly if it matched
     nothing (don't publish a "redacted" shot that redacted nothing).

5. **VERIFY before publishing.** Open the output PNG and read every pixel of text: no
   SSIDs, no lat/lon, no `10.x`/`192.168.x`, no MAC, no real device name, e-ink face
   covered. This manual check is mandatory — the regexes are a safety net, not a
   guarantee (arbitrary SSIDs can't be auto-detected without `--extra`).

6. **Add to README** (if asked): put the image under `docs/`, reference it with
   `![PwnCompanion](docs/screenshot.png)`, and commit both.

## Sensitive-data checklist
IPs (`10.x`, `192.168.x`, `bnep0` addr) · GPS lat/lon · BT MAC addresses · the
Pwnagotchi/device name · captured SSIDs · the e-ink screen bitmap (name + BT IP baked in).
