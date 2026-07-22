# Getting Started with PwnCompanion

This walks you from zero to a connected pet. **PwnCompanion is two halves** that meet over a
single **Bluetooth PAN** link — get both in place and they find each other automatically.

```
   ANDROID PHONE                         PWNAGOTCHI (Pi)
 ┌────────────────────┐   Bluetooth   ┌────────────────────────┐
 │ PwnCompanion app   │◀───  PAN  ───▶│ bt-tether plugin        │ ← the transport
 │  · WebSocket server│   (bnep0 /    │   (+ shares phone's net)│
 │  · AI brain + LLM  │    bt-pan)    │ pwn-companion.py plugin  │ ← our bridge
 │  · GPS             │               │ bettercap / hunting      │
 └────────────────────┘               └────────────────────────┘
```

> ⚠️ **Authorized use only.** Only run this against networks you own or have explicit
> permission to test. See the responsible-use note in the [README](README.md).

---

## What you need

- A **Pwnagotchi** on the [jayofelony fork](https://github.com/jayofelony/pwnagotchi) (e.g. Pi Zero 2 W), that you can SSH into.
- An **Android phone**, Android 10+ (SDK 29+), with Bluetooth.
- A dev machine with the **Android SDK + NDK + CMake** (to build the app) — or grab a prebuilt APK if you have one.
- *(Optional, for password cracking)* a free [wpa-sec](https://wpa-sec.stanev.org) API key.

---

## Step 1 — Pi: set up the `bt-tether` plugin (REQUIRED transport)

PwnCompanion does **not** create its own Bluetooth link — it rides on a
**[`bt-tether` plugin](https://github.com/wsvdmeer/pwnagotchi-plugins)** (a reworked version
with a web UI for managing the phone link, from
[wsvdmeer/pwnagotchi-plugins](https://github.com/wsvdmeer/pwnagotchi-plugins)). It pairs with
your phone, brings up the `bnep0`/`bt-pan` interface, and shares your phone's internet to the
Pi. **Without it, nothing else works.**

Install it like any custom plugin:
```bash
# On the Pi, from a clone of https://github.com/wsvdmeer/pwnagotchi-plugins
sudo cp bt-tether.py /usr/local/share/pwnagotchi/custom-plugins/
```
Enable + configure it in `/etc/pwnagotchi/config.toml`:
```toml
[main.plugins.bt-tether]
enabled = true
phone-name = "<your phone's Bluetooth name>"
phone = "android"            # or "ios"
mac = "<your phone's Bluetooth MAC>"
ip = "192.168.44.44"         # default android subnet; leave as-is unless you know better
```
See the [plugin's README](https://github.com/wsvdmeer/pwnagotchi-plugins) for pairing + its web UI. Confirm it works: after pairing, the Pi should get a `bnep0` IP and have internet.

## Step 2 — Pi: install the `pwn-companion.py` plugin (this project)

```bash
# On the Pi:
sudo pip3 install websockets                 # required
sudo apt install -y hcxtools                 # for handshake crackability grading (usually preinstalled)

sudo cp pwn-companion.py /usr/local/share/pwnagotchi/custom-plugins/
```
Enable it in `config.toml`:
```toml
[main.plugins.pwn-companion]
enabled = true
show_on_screen = true
```
Then restart: `sudo systemctl restart pwnagotchi`.

## Step 3 — Phone: install the app

**Easiest — prebuilt APK:** download it from the [**Releases** page](../../releases) and sideload it (enable "install unknown apps" for your browser/file manager, then open the `.apk`). It's a **debug build** for testing — Android 10+ on an arm64 device.

**Or build it yourself:**
```bash
# On your dev machine (needs JDK 17, Android SDK 36, NDK + CMake ≥ 3.22.1):
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(Or open the project in Android Studio and hit **Run**.) See the [README setup section](README.md#setup) for the full prerequisite list. On **first launch** the app downloads its AI voice model (Qwen2.5-0.5B, ~491 MB) over WiFi.

**Grant the permissions** it asks for: Bluetooth (the link), Location (geotag captures + required for BT scanning), Notifications (the foreground notice + alerts).

## Step 4 — Pair & connect

1. On Android: **Settings → Connections → Mobile Hotspot and Tethering → Bluetooth tethering → ON**.
2. Pair the Pwnagotchi from your phone's Bluetooth settings (and accept on the Pi if prompted).
3. Open the PwnCompanion app. It starts a WebSocket server on the `bt-pan` IP and broadcasts UDP announcements; the Pi's `pwn-companion` plugin discovers it and connects — **no IP typing needed**.

## Step 5 — Verify it's working

- App: the top line shows **`link : ● … online`** and the live e-ink screen appears.
- The **AI pet** starts talking (and its lines now show on the pwnagotchi's own screen too).
- `[ captures ]` fills with your geolocated handshake history; `[ learning ]` shows per-channel bars.

---

## Optional — password cracking (wpa-sec)

Add your key on the Pi and PwnCompanion will show cracked passwords + let the pet gloat:
```toml
[main.plugins.wpa-sec]
enabled = true
api_key = "<your wpa-sec key>"
api_url = "https://wpa-sec.stanev.org"
download_results = true
```

## Troubleshooting

The Pi Zero 2 W shares one radio between Wi-Fi and Bluetooth, so the tether can drop under
heavy hunting. If the link won't come up or dies mid-session, see
[README → Troubleshooting](README.md#troubleshooting--connection-drops--needing-frequent-reboots)
(short version: hard-reboot the Pi; the durable fix is a USB Wi-Fi adapter).
