# Troubleshooting

← [Back to README](../README.md)

## Troubleshooting — connection drops / needing frequent reboots

On the Pi Zero 2 W, **Bluetooth and WiFi share one combo radio chip**. When both are active they contend for the antenna, and the bt-tether link can wedge — often only recovered by a **hard reboot** of the Pwnagotchi. This is a hardware limitation, not a plugin bug.

- **Symptom:** the app shows `link : ○ listening…` and never connects even though the device is paired, or the link dies mid-session.
- **Recovery:** hard-reboot the Pwnagotchi.
- **Durable fix:** add a **USB WiFi adapter** so WiFi hunting and the BT tether stop sharing the built-in radio.
- **Softer recovery:** resetting `hciuart` (not just the `bluetooth` service) tends to bring BT back without a full reboot.

The plugin is hardened for this flaky link: sends are bounded by a timeout, WebSocket keepalive detects a silently-dropped tether in seconds, the socket is always closed cleanly on disconnect, and BT-drop teardown is non-blocking so a drop never freezes the pwnagotchi's main loop. These reduce how often the link wedges but can't remove the underlying radio contention.

## Privacy — everything stays on your phone

No capture, location, or password data ever leaves your device. The app has **no analytics, no crash reporting, and no cloud sync** — captured networks, GPS fixes, and cracked passwords live only on your phone (and travel only to your Pi over the local Bluetooth link). Its **only** outbound network calls fetch public resources: the on-phone cracking wordlist (pwncrack) and the dark map tiles (CARTO). The single exception is entirely opt-in and Pi-side — if *you* enable **wpa-sec**, the Pi uploads handshakes to that service for cracking.
