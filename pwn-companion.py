"""
Pwn Companion Plugin for Pwnagotchi

Enables real-time bidirectional communication with mobile app via WebSocket.
Connects as client to app's WebSocket server discovered via UDP announcements.

Required System Packages:
    sudo pip3 install websockets

Features:
- Automatic app discovery via UDP announcements on port 8888
- WebSocket client connection (lightweight, single connection)
- Custom command execution
- GPS coordinate support with periodic requests
- Real-time connection status on screen
- Periodic screenshot push (configurable interval)
- Async/await architecture (non-blocking)

Setup:
1. Install: sudo pip3 install websockets
2. Copy plugin to /usr/local/share/pwnagotchi/custom-plugins/
3. Enable in config.toml: [main.plugins.pwn-companion] enabled = true
4. Tether with Android device; app broadcasts endpoint via UDP:8888
5. Plugin auto-connects via WebSocket

Configuration (config.toml):

    [main.plugins.pwn-companion]
    enabled = true
    show_on_screen = true                       # Show status on display
    status_position = [0, 0]                    # Position for status display [x, y]
    show_latitude = true                        # Show latitude on display
    latitude_position = [0, 72]                 # Position for latitude display [x, y]
    show_longitude = true                       # Show longitude on display
    longitude_position = [0, 82]                # Position for longitude display [x, y]
    show_accuracy = true                        # Show GPS accuracy on display
    accuracy_position = [0, 92]                 # Position for accuracy display [x, y]
    show_altitude = true                        # Show GPS altitude on display
    altitude_position = [0, 102]                # Position for altitude display [x, y]
    push_image_interval = 1                     # Push screenshot every N seconds (0 = disabled)
    request_gps_interval = 5                    # Request GPS every N seconds (0 = disabled)

Mobile App Protocol:

    App announces via UDP:8888:
    {"type": "announce", "endpoint": "ws://192.168.x.x:8081", ...}

    Send Custom Command:
    {"type": "command", "action": "do_something", "params": {"key": "value"}}

    Send GPS:
    {"type": "gps", "latitude": 37.7749, "longitude": -122.4194, "accuracy": 10, "altitude": 50}

    Request Status:
    {"type": "status_request"}

    Request GPS (sent by plugin):
    {"type": "gps_request"}

    Image Response (sent by plugin):
    {"type": "image", "data": "<base64>", "content_type": "image/png", "timestamp": 1234567890.5}

GPS Data Persistence:
- GPS coordinates are automatically saved to .gps.json files alongside captured .pcap files
- Each handshake capture includes location data from the last GPS update
"""

import logging
import json
import os
import asyncio
import threading
import socket
import time
import base64
import subprocess
import random
import requests

try:
    import websockets
except ImportError:
    websockets = None

from pwnagotchi.plugins import Plugin
from flask import jsonify
from pwnagotchi.ui.components import LabeledValue
from pwnagotchi.ui.view import BLACK
import pwnagotchi.ui.fonts as fonts
import pwnagotchi


log = logging.getLogger(__name__)


# ============================================================
#  AI Event Broadcaster - Sends WiFi Events to App for LLM
# ============================================================

class PwnagotchiEventBroadcaster:
    """
    Sends rich WiFi event data to the companion app for LLM personality responses
    Enables the Pwnagotchi AI to react to WiFi events in real-time
    """

    def __init__(self, websocket_send_func):
        """
        Args:
            websocket_send_func: Reference to plugin's _send_to_app method
        """
        self.send_to_app = websocket_send_func
        self.handshake_count = 0
        self.networks_discovered = set()
        log.info("[pwn-companion]  Event broadcaster initialized")

    async def on_handshakes_captured(self, count: int, network_name: str, security: str = "WPA2",
                                     channel: int = None, bssid: str = None):
        """
        Called when Pwnagotchi captures handshakes

        Args:
            count: Number of handshakes captured in this burst
            network_name: SSID of the network
            security: Security type (WPA2, WPA3, Open, etc.)
            channel: WiFi channel the handshake was captured on (from the access point).
                     Critical: without it the app records every capture on channel 0,
                     which poisons its per-channel yield stats and "where to hunt" steering.
            bssid: AP MAC — lets the app attribute captures to specific APs.
        """
        try:
            self.handshake_count += count

            event = {
                "type": "network_event",
                "event_type": "handshakes_captured",
                "count": count,
                "network": network_name,
                "security": security,
                "channel": channel,
                "bssid": bssid or "unknown",
                "total_captures": self.handshake_count,
                "timestamp": int(time.time()),
                "description": f"Captured {count} handshake{'s' if count != 1 else ''} from {network_name} ({security})"
            }

            log.info(f"[pwn-companion]  AI Event: {event['description']}")
            await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_handshakes_captured: {e}")

    async def on_network_discovered(self, ssid: str, bssid: str = None, security: str = "Unknown",
                                   signal_strength: int = -50, channel: int = 1):
        """
        Called when a new network is discovered

        Args:
            ssid: Network name
            bssid: MAC address of access point
            security: Security type
            signal_strength: Signal strength in dBm
            channel: WiFi channel
        """
        try:
            is_new = ssid not in self.networks_discovered
            self.networks_discovered.add(ssid)

            event = {
                "type": "network_event",
                "event_type": "network_discovered",
                "network": ssid,
                "bssid": bssid or "unknown",
                "security": security,
                "signal": signal_strength,
                "channel": channel,
                "is_new": is_new,
                "timestamp": int(time.time()),
                "description": f"Found network: {ssid} on CH{channel} ({security}, {signal_strength}dBm)"
            }

            if is_new:
                log.info(f"[pwn-companion]  AI Event: {event['description']}")
                await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_network_discovered: {e}")

    async def on_connection_success(self, network_name: str, duration: float = 0.0):
        """
        Called when successfully connected to a network

        Args:
            network_name: SSID
            duration: Connection time in seconds
        """
        try:
            event = {
                "type": "network_event",
                "event_type": "connection_success",
                "network": network_name,
                "duration": duration,
                "timestamp": int(time.time()),
                "description": f"Successfully connected to {network_name}" + (f" in {duration:.1f}s" if duration > 0 else "")
            }

            log.info(f"[pwn-companion]  AI Event: {event['description']}")
            await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_connection_success: {e}")

    async def on_connection_failure(self, network_name: str, reason: str = "Unknown"):
        """
        Called when connection fails

        Args:
            network_name: SSID
            reason: Failure reason
        """
        try:
            event = {
                "type": "network_event",
                "event_type": "connection_failure",
                "network": network_name,
                "reason": reason,
                "timestamp": int(time.time()),
                "description": f"Failed to connect to {network_name}: {reason}"
            }

            log.warning(f"[pwn-companion]  AI Event: {event['description']}")
            await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_connection_failure: {e}")

    async def on_anomaly_detected(self, anomaly_type: str, details: dict = None,
                                  network: str = None, channel=None, bssid: str = None,
                                  station: str = None):
        """
        Called when an anomaly is detected in network activity

        Args:
            anomaly_type: Type of anomaly (deauthentication, unusual_probe, etc.)
            network/channel/bssid: the AP involved; station: the client MAC targeted
                (for a deauth). These are sent TOP-LEVEL — the app's ScreenData only
                deserializes top-level keys, so nesting them in `details` would drop them.
        """
        try:
            event = {
                "type": "network_event",
                "event_type": "anomaly_detected",
                "anomaly_type": anomaly_type,
                "network": network,
                "channel": channel,
                "bssid": bssid,
                "station": station,     # deauth TARGET (client MAC)
                "details": details or {},
                "timestamp": int(time.time()),
                "description": (
                    f"Deauth {station or network or bssid or ''}"
                    + (f" CH{channel}" if channel else "")
                ).strip() if anomaly_type == "deauthentication" else f"Anomaly detected: {anomaly_type}",
            }

            log.warning(f"[pwn-companion]  AI Event: {event['description']}")
            await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_anomaly_detected: {e}")

    async def on_high_value_target(self, network_name: str, reason: str = ""):
        """
        Called when a high-value target (WPA3, Enterprise, etc.) is detected

        Args:
            network_name: SSID
            reason: Why it's considered high-value
        """
        try:
            event = {
                "type": "network_event",
                "event_type": "high_value_target",
                "network": network_name,
                "reason": reason,
                "timestamp": int(time.time()),
                "description": f"High-value target found: {network_name}" + (f" ({reason})" if reason else "")
            }

            log.info(f"[pwn-companion]  AI Event: {event['description']}")
            await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_high_value_target: {e}")

    async def on_scan_complete(self, networks_found: int, duration: float = 0.0):
        """
        Called when a WiFi scan completes

        Args:
            networks_found: Number of networks discovered
            duration: Scan duration in seconds
        """
        try:
            event = {
                "type": "network_event",
                "event_type": "scan_complete",
                "networks_found": networks_found,
                "duration": duration,
                "total_unique_networks": len(self.networks_discovered),
                "timestamp": int(time.time()),
                "description": f"Scan complete: Found {networks_found} networks" + (f" in {duration:.1f}s" if duration > 0 else "")
            }

            log.info(f"[pwn-companion]  AI Event: {event['description']}")
            await self.send_to_app(event)
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_scan_complete: {e}")

# Suppress verbose websockets handshake errors
logging.getLogger("websockets").setLevel(logging.WARNING)
logging.getLogger("websockets.server").setLevel(logging.WARNING)
logging.getLogger("websockets.protocol").setLevel(logging.WARNING)
logging.getLogger("websockets.asyncio").setLevel(logging.WARNING)
logging.getLogger("websockets.asyncio.server").setLevel(logging.WARNING)


# Custom filter to suppress only specific noisy errors
class HandshakeErrorFilter(logging.Filter):
    def filter(self, record):
        msg = record.getMessage().lower()
        # Suppress only the specific "stream ends after 0 bytes" noise, keep other errors
        if (
            "stream ends after 0 bytes" in msg
            or "connection closed while reading" in msg
        ):
            return False
        return True


# Apply filter to websockets loggers
for logger_name in [
    "websockets",
    "websockets.server",
    "websockets.protocol",
    "websockets.asyncio",
    "websockets.asyncio.server",
]:
    logging.getLogger(logger_name).addFilter(HandshakeErrorFilter())

# ============================================================
#  Constants
# ============================================================

# Network configuration
UDP_DISCOVERY_PORT = 8888
UDP_BUFFER_SIZE = 1024
UDP_RECEIVE_TIMEOUT = 2.0

PWNAGOTCHI_UI_HOST = "127.0.0.1"
PWNAGOTCHI_UI_PORT = 8080
PWNAGOTCHI_UI_URL = f"http://{PWNAGOTCHI_UI_HOST}:{PWNAGOTCHI_UI_PORT}/ui"

# WebSocket configuration
WEBSOCKET_CONNECT_TIMEOUT = 5.0
# Max time a single send() may block before we declare the link dead. On a flaky
# shared BT/WiFi radio a half-open socket fills the OS buffer and send() would
# otherwise hang until the library ping-timeout fires (tens of seconds).
WEBSOCKET_SEND_TIMEOUT = 5.0
# Keepalive so a silently-dropped BT link is detected in seconds, not by default.
WEBSOCKET_PING_INTERVAL = 15.0
WEBSOCKET_PING_TIMEOUT = 10.0
WEBSOCKET_CLOSE_TIMEOUT = 3.0

# Connection retry configuration
INITIAL_RETRY_DELAY = 1  # seconds
MAX_RETRY_DELAY = 30  # seconds
RETRY_BACKOFF_FACTOR = 1.5
DISCOVERY_LOOP_SLEEP = 0.1  # seconds

# Request timeouts
IMAGE_REQUEST_TIMEOUT = 10  # seconds
PERIODIC_TASK_RETRY_SLEEP = 1  # seconds
# Lightweight vitals (temp/cpu/mem) push cadence. Runs regardless of AUTO/MANUAL so the
# app's [ vitals ] gauges stay live even while paused (on_epoch only fires in AUTO).
VITALS_PUSH_INTERVAL = 12  # seconds

# How long an app-pushed voice pool stays usable before we fall back to the device's
# own stock voice. The app re-pushes on every per-category refresh (~90s) while
# connected, so this only trips well after the app has gone quiet / disconnected.
VOICE_POOL_STALE_SECONDS = 1200  # 20 minutes

# String formatting
LOG_STRING_TRUNCATE_LENGTH = 80
HANDLER_ERROR_TRUNCATE_LENGTH = 100
GPS_COORD_PRECISION = 6  # decimal places
LAT_LON_FORMAT_PRECISION = 8  # decimal places
ACCURACY_FORMAT_PRECISION = 1  # decimal place

# UI display configuration
DEFAULT_STATUS_POSITION = [0, 0]
DEFAULT_LAT_POSITION = [0, 72]
DEFAULT_LNG_POSITION = [0, 82]
DEFAULT_ACC_POSITION = [0, 92]
DEFAULT_ALT_POSITION = [0, 102]

# Default periodic request intervals
SESSION_REQUEST_GPS_INTERVAL = 5  # Request GPS every 5 seconds by default

# Thread timeout
THREAD_SHUTDOWN_TIMEOUT = 5  # seconds

# Socket constants
SO_BINDTODEVICE = 25  # Linux-specific socket option

# ============================================================
#  Pwn Companion Plugin
# ============================================================


class PwnCompanion(Plugin):
    __author__ = "wsvdmeer"
    __version__ = "2.0.0"
    __description__ = "WebSocket client for communication with pwn-companion android app (app-hosted server)"

    csrf_exempt = True

    def __init__(self):
        # UI config
        self.show_on_screen = True
        self.status_position = DEFAULT_STATUS_POSITION
        self.gps_position = DEFAULT_LAT_POSITION     # single compact GPS indicator position
        self.show_latitude = True
        self.latitude_position = DEFAULT_LAT_POSITION
        self.show_longitude = True
        self.longitude_position = DEFAULT_LNG_POSITION
        self.show_accuracy = True
        self.accuracy_position = DEFAULT_ACC_POSITION
        self.show_altitude = True
        self.altitude_position = DEFAULT_ALT_POSITION

        # Periodic sync config
        self.push_image_interval = 1  # 0 = disabled, >0 = seconds between pushes
        self.request_gps_interval = (
            SESSION_REQUEST_GPS_INTERVAL  # Request GPS every N seconds by default
        )

        # State
        self.discovering = False
        self.app_websocket = None
        self.app_connected = False
        self.app_endpoint = None
        self.discovery_task = None
        self.listen_task = None
        self.periodic_tasks = []
        self.lock = threading.Lock()

        # Data storage
        self.last_gps = None
        self.last_command = None
        self.start_time = time.time()
        # Where pwnagotchi stores captured handshakes (+ our .gps.json sidecars).
        # Scanned on connect to seed the app with the historical capture log.
        self.handshakes_dir = "/home/pi/handshakes"

        # Device info — pwnagotchi.name() returns the name from config.toml main.name
        try:
            self.device_name = pwnagotchi.name()
        except Exception:
            self.device_name = socket.gethostname()  # fallback to system hostname
        self.bt_peer_name = None   # BT name of the tethered phone (set on connect)
        self.device_status = "initialized"
        self._agent = None  # Set when first agent event fires

        # Network info
        self.current_ip = "unknown"
        self.tether_interface = None

        # Per-channel stats collected from on_epoch — used for autotune_stats message.
        # Structure: {channel_int: {"handshakes": int, "deauths": int, "associations": int}}
        self._channel_stats = {}
        self._best_channel = None
        self._total_handshakes = 0  # running total across all epochs

        # App-driven voice: the companion app streams fresh, in-character lines per
        # pwnagotchi voice category; we splice them into the device's own speech bubble
        # (see _wrap_voice / _voiced_line). Empty/stale → the stock voice.py is used.
        self._voice_pool = {}            # category -> list[str], newest first
        self._voice_pool_ts = 0.0        # wall-clock of the last pool push
        self._wrapped_voice_id = None    # id() of the Voice object we've patched

        # Operating mode — "AUTO" (scanning) or "MANUAL" (user-controlled, no scanning)
        self._current_mode = "AUTO"
        # Last mode value actually pushed to the app, so on_ui_update can push a
        # correction when the real (View) mode differs from what the app was told.
        self._last_sent_mode = None

        # UI components
        self.status_label = None
        self.gps_label = None          # single compact GPS indicator (icon + fix age)
        self.latitude_label = None
        self.longitude_label = None
        self.accuracy_label = None
        self.altitude_label = None

        # Event loop for async operations
        self.loop = None
        self.ws_thread = None

        # AI Event Broadcaster - for personality responses
        self.event_broadcaster = None

        if websockets is None:
            log.warning(
                "[pwn-companion] websockets not installed. Install with: pip3 install websockets"
            )

        log.info("[pwn-companion] Plugin initialized")

    def on_loaded(self):
        """Plugin loaded"""
        # Load UI config options
        if "show_on_screen" in self.options:
            self.show_on_screen = self.options["show_on_screen"]
        if "status_position" in self.options:
            self.status_position = self.options["status_position"]
        # GPS indicator position — prefer the new gps_position, fall back to the
        # legacy latitude_position so old configs keep working.
        if "gps_position" in self.options:
            self.gps_position = self.options["gps_position"]
        elif "latitude_position" in self.options:
            self.gps_position = self.options["latitude_position"]
        if "show_latitude" in self.options:
            self.show_latitude = self.options["show_latitude"]
        if "latitude_position" in self.options:
            self.latitude_position = self.options["latitude_position"]
        if "show_longitude" in self.options:
            self.show_longitude = self.options["show_longitude"]
        if "longitude_position" in self.options:
            self.longitude_position = self.options["longitude_position"]
        if "show_accuracy" in self.options:
            self.show_accuracy = self.options["show_accuracy"]
        if "accuracy_position" in self.options:
            self.accuracy_position = self.options["accuracy_position"]
        if "show_altitude" in self.options:
            self.show_altitude = self.options["show_altitude"]
        if "altitude_position" in self.options:
            self.altitude_position = self.options["altitude_position"]

        # Where pwnagotchi writes captured handshakes (+ our .gps.json sidecars).
        # Scanned on connect to seed the app's capture history.
        if "handshakes_dir" in self.options:
            self.handshakes_dir = self.options["handshakes_dir"]

        # Load periodic sync config
        if "push_image_interval" in self.options:
            self.push_image_interval = self.options["push_image_interval"]
        if "request_gps_interval" in self.options:
            self.request_gps_interval = self.options["request_gps_interval"]

        if websockets is None:
            log.error("[pwn-companion] websockets library not installed, aborting")
            return

        log.info(
            f"[pwn-companion] Plugin loaded. Image push: {self.push_image_interval}s, "
            f"GPS request: {self.request_gps_interval}s"
        )

    def on_unloaded(self):
        """Plugin unloaded"""
        log.info("[pwn-companion] Plugin unloading")
        self._stop_client_discovery()

    def on_ui_setup(self, ui):
        """Setup UI components"""
        # Splice the app's AI voice into the device's own speech bubble. `ui` here is
        # pwnagotchi's View, which owns the Voice object (view._voice) that view.py
        # calls as self._voice.on_<category>() to fill the 'status' element. We wrap
        # those methods so our fresh lines show at the exact native voice moments,
        # with a transparent fall-back to the stock voice when we have nothing.
        try:
            self._wrap_voice(ui)
        except Exception as e:
            log.debug(f"[pwn-companion] voice wrap skipped: {e}")

        if self.show_on_screen:
            self.status_label = LabeledValue(
                label="PWN:",
                value="offline",
                position=tuple(self.status_position),
                label_font=fonts.Small,
                text_font=fonts.Small,
                color=BLACK,
            )
            ui.add_element("pwn_companion_status", self.status_label)

            # Single compact GPS indicator — the full lat/lng/acc/alt now lives in
            # the companion app's [ gps ] section, not crammed onto the tiny e-ink.
            # Shows a location glyph + how long ago the fix updated (e.g. "+ 5s").
            self.gps_label = LabeledValue(
                color=BLACK,
                label="GPS:",
                value="--",
                position=tuple(self.gps_position),
                label_font=fonts.Small,
                text_font=fonts.Small,
            )
            ui.add_element("pwn_companion_gps", self.gps_label)

    def on_ui_update(self, ui):
        """Update UI display"""
        with self.lock:
            gps_snapshot = self.last_gps
            app_connected = self.app_connected

        # Source of truth for AUTO/MANUAL: pwnagotchi's own View 'mode' element
        # ('AUTO' or 'MANU'). The transition hooks (on_manual_mode/on_auto_mode)
        # miss the initial state and reset on restart, which made the app show AUTO
        # while the device was actually MANUAL. Reading the live View mode each tick
        # keeps _current_mode (sent as pwnagotchi_mode) correct.
        try:
            view_mode = ui.get("mode")
            if view_mode:
                new_mode = "MANUAL" if str(view_mode).upper().startswith("MAN") else "AUTO"
                self._current_mode = new_mode
                # Push the authoritative mode whenever it differs from what the app
                # was last told. Fixes the app showing AUTO after a boot-into-MANUAL:
                # the connect-time status carried the default AUTO and the transition
                # hook fired before the app connected, so the correction never shipped.
                if app_connected and self.loop and new_mode != self._last_sent_mode:
                    # _last_sent_mode is advanced inside _send_mode_update only on a
                    # confirmed send, so a dropped push is retried next tick.
                    self._schedule_on_loop(self._send_mode_update(new_mode), self.loop)
        except Exception:
            pass

        if self.status_label:
            # Plain, unambiguous connection word (clearer than a tiny dot / "ok").
            self.status_label.value = "online" if app_connected else "offline"

        if self.gps_label:
            if gps_snapshot and app_connected:
                ts = gps_snapshot.get("timestamp", 0) or 0
                age = int(time.time() - ts) if ts else -1
                # Compact age only — no marker. "live" covers the normal 5s refresh.
                self.gps_label.value = (
                    "--" if age < 0
                    else "live" if age < 4
                    else f"{age}s" if age < 120
                    else f"{age // 60}m"
                )
            else:
                self.gps_label.value = "--"

    # Voice methods we let the app override, each mapped to the pool category it serves
    # (many methods may share one category). Expressive/recurring only — the functional
    # countdown methods (on_free_channel/on_napping/on_waiting) stay on the stock voice.
    # In MANUAL mode both on_last_session_data (the recap, re-rendered every 5s) AND
    # on_unread_messages (the grid "N new messages" line that otherwise keeps stealing the
    # slot) draw from the 'last_session' recap pool, so the manual screen shows our recap.
    _VOICE_METHODS = {
        "on_normal": "normal", "on_bored": "bored", "on_sad": "sad",
        "on_angry": "angry", "on_excited": "excited", "on_grateful": "grateful",
        "on_lonely": "lonely", "on_handshakes": "handshakes", "on_deauth": "deauth",
        "on_assoc": "assoc", "on_motivated": "motivated", "on_demotivated": "demotivated",
        "on_last_session_data": "last_session", "on_unread_messages": "last_session",
    }

    def _wrap_voice(self, ui):
        """Monkeypatch the device's Voice.on_<category>() methods to serve our lines.

        view.py fills the speech bubble via self._voice.on_<category>(); by replacing
        those bound methods on the live Voice instance we hook every native voice
        moment without touching pwnagotchi core. Each wrapper returns our pooled line
        when one is fresh, else defers to the original (stock) method — so the device
        never blanks and simply speaks its own voice whenever we're quiet/disconnected.
        """
        voice = getattr(ui, "_voice", None)
        if voice is None:
            # Fall back to the agent's view if this UI object doesn't expose the Voice.
            try:
                voice = self._agent.view()._voice  # type: ignore[attr-defined]
            except Exception:
                voice = None
        if voice is None:
            return

        # Re-wrap only if this is a Voice we haven't patched (survives plugin reloads
        # that rebuild the View/Voice, without double-wrapping the same instance).
        if self._wrapped_voice_id == id(voice):
            return

        wrapped = 0
        for method_name, cat in self._VOICE_METHODS.items():
            orig = getattr(voice, method_name, None)
            if not callable(orig):
                continue

            def make(orig=orig, cat=cat):
                def wrapped_method(*args, **kwargs):
                    line = self._voiced_line(cat)
                    if line is not None:
                        return line
                    return orig(*args, **kwargs)
                return wrapped_method

            setattr(voice, method_name, make())
            wrapped += 1

        self._wrapped_voice_id = id(voice)
        log.info(
            f"[pwn-companion] Voice hijacked — app AI can now speak on the device "
            f"screen ({wrapped} categories)"
        )

    def _voiced_line(self, category):
        """Return a fresh app-supplied line for a voice category, or None to fall back.

        None (→ stock voice) when the app is disconnected, the pool is stale, or the
        category is empty. We random-pick among the recent lines the app pushed so a
        repeated state (e.g. bored) doesn't say the same thing every frame.
        """
        try:
            if not self.app_connected:
                return None
            if (time.time() - self._voice_pool_ts) > VOICE_POOL_STALE_SECONDS:
                return None
            lines = self._voice_pool.get(category)
            if not lines:
                return None
            return random.choice(lines)
        except Exception:
            return None

    def _apply_voice_pool(self, raw):
        """Store a voice pool pushed by the app: {category: [line, ...], ...}.

        Swaps in a whole new dict (atomic for the UI-thread reader) and stamps the
        time so _voiced_line can age it out. Values may be a list or a bare string.
        """
        try:
            if not raw:
                return
            data = json.loads(raw) if isinstance(raw, str) else raw
            if not isinstance(data, dict):
                return
            pool = {}
            for key, val in data.items():
                if isinstance(val, (list, tuple)):
                    lines = [str(x).strip() for x in val if str(x).strip()]
                elif val:
                    lines = [str(val).strip()]
                else:
                    lines = []
                if lines:
                    pool[str(key)] = lines[:6]
            self._voice_pool = pool          # atomic reference swap for _voiced_line
            self._voice_pool_ts = time.time()
            log.debug(f"[pwn-companion] Voice pool updated: {list(pool.keys())}")
        except Exception as e:
            log.debug(f"[pwn-companion] Bad voice pool payload: {e}")

    def on_bt_tether_connected(self, agent, event_data):
        """Handle bt-tether connected event - start discovery"""
        self._agent = agent  # Store for auto-tune access
        try:
            ip = event_data.get("ip")
            iface = event_data.get("interface")
            device = event_data.get("device", "unknown")

            log.info(f"[pwn-companion]  bt-tether event received: ip={ip}, interface={iface}, device={device}")

            if ip and ip != "unknown":
                self.current_ip = ip
                self.tether_interface = iface
                self.bt_peer_name = device  # Phone BT name — stored separately, not our own name
                log.info(
                    f"[pwn-companion] ✅ bt-tether connected: {self.current_ip} via {iface} ({device})"
                )
                log.info(
                    f"[pwn-companion]  Starting UDP discovery on port {UDP_DISCOVERY_PORT}..."
                )
                # Start discovery (pass the interface for UDP listening)
                self._start_client_discovery(iface)
            else:
                log.warning(
                    f"[pwn-companion] ⚠️ bt-tether event missing required data: ip={ip}"
                )
        except Exception as e:
            log.error(f"[pwn-companion] Error in bt_tether_connected: {e}")

    def on_bt_tether_disconnected(self, agent, event_data):
        """Handle bt-tether disconnected event - stop discovery"""
        self.tether_interface = None
        self.current_ip = "unknown"
        log.info("[pwn-companion] bt-tether disconnected")
        # Non-blocking: this runs on the pwnagotchi main loop thread and BT drops are
        # frequent (shared radio). Blocking here would freeze UI/epoch/recon each drop.
        self._stop_client_discovery(wait=False)

    def on_handshake(self, agent, filename, access_point, client_station):
        """Save GPS data and fire AI event when a handshake is captured"""
        self._agent = agent
        try:
            ssid = access_point.get("hostname", "") or access_point.get("essid", "unknown")
            security = "WPA2"  # pwnagotchi primarily captures WPA2
            # Attribute the capture to its real channel/AP. bettercap's access_point dict
            # exposes "channel" and "mac"; without the channel the app defaults captures to
            # channel 0 and its "best hunting channel" yield becomes meaningless.
            ap_channel = access_point.get("channel")
            ap_bssid = access_point.get("mac", "") or access_point.get("bssid", "") or ""

            # Increment running total
            self._total_handshakes += 1

            # Fire AI network_event immediately — this is the main driver of AI responses
            if self.event_broadcaster and self.app_connected:
                self._schedule_on_loop(
                    self.event_broadcaster.on_handshakes_captured(
                        count=1,
                        network_name=ssid,
                        security=security,
                        channel=ap_channel,
                        bssid=ap_bssid,
                    ),
                    self.loop
                )

            # Save GPS data alongside the pcap
            if not self.last_gps:
                return

            lat = self.last_gps.get("latitude", 0)
            lon = self.last_gps.get("longitude", 0)
            if lat == 0 and lon == 0:
                log.debug("[pwn-companion] Skipping GPS save - no valid coordinates")
                return

            # Only rewrite the extension, not every ".pcap" substring in the path
            # (a dir/SSID containing ".pcap" would otherwise get a misnamed sidecar
            # that _scan_capture_history — which strips only the suffix — can't pair).
            gps_filename = (
                filename[:-len(".pcap")] + ".gps.json"
                if filename.endswith(".pcap")
                else filename + ".gps.json"
            )
            gps_data = {
                "latitude": self.last_gps.get("latitude"),
                "longitude": self.last_gps.get("longitude"),
                "accuracy": self.last_gps.get("accuracy"),
                "altitude": self.last_gps.get("altitude"),
                "timestamp": self.last_gps.get("timestamp"),
            }
            with open(gps_filename, "w") as fp:
                json.dump(gps_data, fp, indent=2)
            log.info(
                f"[pwn-companion] ✓ GPS saved to {gps_filename} "
                f"(lat: {lat:.{GPS_COORD_PRECISION}f}, lon: {lon:.{GPS_COORD_PRECISION}f})"
            )

            # Push this single geolocated capture to the app so its list grows live
            # (no need to wait for a reconnect to re-scan the whole dir).
            if self.app_connected:
                base = os.path.basename(filename)
                if base.endswith(".pcap"):
                    base = base[:-len(".pcap")]
                cap_ssid, cap_bssid = (base.rsplit("_", 1) + [""])[:2] if "_" in base else (base, "")
                entry = {
                    "ssid": cap_ssid or "unknown",
                    "bssid": cap_bssid,
                    "latitude": self.last_gps.get("latitude"),
                    "longitude": self.last_gps.get("longitude"),
                    "accuracy": self.last_gps.get("accuracy"),
                    "timestamp": self.last_gps.get("timestamp") or int(time.time()),
                }
                # Fresh capture: recompute crackability (bettercap may have just
                # appended the frames that complete/upgrade it) and refresh the cache.
                q = self._classify_pcap(filename, use_cache=False)
                if q:
                    entry["quality"] = q
                self._schedule_on_loop(
                    self._send_to_app({"type": "capture_history", "captures": [entry]}),
                    self.loop,
                )
        except Exception as e:
            log.error(f"[pwn-companion] Error in on_handshake: {e}")

    def _classify_pcap(self, pcap_path, use_cache=True):
        """Handshake quality/crackability: 'pmkid' | 'eapol' | 'partial' | None.

        A capture only counts as a real win if it's actually crackable. hcxpcapngtool
        is the authority: if it can distil a WPA*01 (PMKID) or WPA*02 (EAPOL) hash from
        the pcap, it's crackable; if it writes nothing, it's a partial grab (e.g. only
        M1 frames) and won't crack. Result is cached in a <base>.q sidecar so history
        re-scans are instant. Returns None if hcxpcapngtool is unavailable (unknown).
        """
        try:
            base = pcap_path[:-len(".pcap")] if pcap_path.endswith(".pcap") else pcap_path
            cache = base + ".q"
            if use_cache and os.path.isfile(cache):
                try:
                    with open(cache) as fp:
                        v = fp.read().strip()
                    if v:
                        return v
                except OSError:
                    pass

            quality = "partial"
            out = base + ".22000.tmp"
            try:
                subprocess.run(
                    ["hcxpcapngtool", "-o", out, pcap_path],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
                )
            except FileNotFoundError:
                return None   # tool missing → quality unknown, don't cache
            except Exception as e:
                log.debug(f"[pwn-companion] classify failed for {pcap_path}: {e}")
                return None

            try:
                if os.path.isfile(out) and os.path.getsize(out) > 0:
                    with open(out) as fp:
                        data = fp.read()
                    if "WPA*02*" in data:
                        quality = "eapol"     # full EAPOL 4-way handshake
                    elif "WPA*01*" in data:
                        quality = "pmkid"     # PMKID — also crackable
                    else:
                        quality = "partial"
            finally:
                try:
                    os.remove(out)
                except OSError:
                    pass

            try:
                with open(cache, "w") as fp:
                    fp.write(quality)
            except OSError:
                pass
            return quality
        except Exception:
            return None

    def _scan_capture_history(self, limit=300):
        """Scan the handshakes dir and build a capture log for the app.

        Pwnagotchi writes one <SSID>_<BSSID>.pcap per captured handshake; our
        on_handshake() drops a matching <base>.gps.json sidecar with the GPS fix.
        We pair them up so the app can show a map/list of where things were caught.
        Newest captures first; capped at `limit` to keep the payload small.
        """
        captures = []
        try:
            directory = self.handshakes_dir
            if not directory or not os.path.isdir(directory):
                log.info(f"[pwn-companion] No handshakes dir at {directory}, skipping history scan")
                return captures

            pcaps = [f for f in os.listdir(directory) if f.endswith(".pcap")]
            # Raw handshake-file count (what the pwnagotchi screen shows) — the app
            # dedupes captures by BSSID, so this lets it display "N networks · M
            # handshakes" and reconcile with the device's own count.
            self._last_scan_file_count = len(pcaps)
            # Newest first by mtime.
            pcaps.sort(
                key=lambda f: os.path.getmtime(os.path.join(directory, f)),
                reverse=True,
            )

            for name in pcaps[:limit]:
                base = name[:-len(".pcap")]
                # pwnagotchi names files <ssid>_<bssid>.pcap; the BSSID is the
                # last underscore-separated chunk (SSIDs may contain underscores).
                if "_" in base:
                    ssid, bssid = base.rsplit("_", 1)
                else:
                    ssid, bssid = base, ""

                path = os.path.join(directory, name)
                entry = {
                    "ssid": ssid or "unknown",
                    "bssid": bssid,
                    "timestamp": int(os.path.getmtime(path)),
                }
                q = self._classify_pcap(path)   # cached in <base>.q sidecar
                if q:
                    entry["quality"] = q

                gps_path = os.path.join(directory, base + ".gps.json")
                if os.path.isfile(gps_path):
                    try:
                        with open(gps_path) as fp:
                            gps = json.load(fp)
                        lat = gps.get("latitude")
                        lon = gps.get("longitude")
                        if lat and lon:
                            entry["latitude"] = lat
                            entry["longitude"] = lon
                            entry["accuracy"] = gps.get("accuracy")
                            if gps.get("timestamp"):
                                entry["timestamp"] = int(gps["timestamp"])
                    except Exception as e:
                        log.debug(f"[pwn-companion] Bad gps sidecar {gps_path}: {e}")

                captures.append(entry)

            geo = sum(1 for c in captures if "latitude" in c)
            log.info(
                f"[pwn-companion] Capture history: {len(captures)} handshakes "
                f"({geo} geolocated) from {directory}"
            )
        except Exception as e:
            log.error(f"[pwn-companion] Error scanning capture history: {e}")
        return captures

    def _read_cracked(self, limit=1000):
        """Read wpa-sec's downloaded cracked results, if the plugin has fetched any.

        wpa-sec writes <handshakes>/wpa-sec.cracked.potfile once `download_results`
        is on; each line is `<apmac>:<stamac>:<essid>:<password>` with plain 12-hex
        MACs (essid may itself contain ':', so we keep the FIRST field as the BSSID
        and the LAST as the password). Returns [{bssid, ssid, password}] — empty if
        the file doesn't exist yet (nothing cracked, or download not enabled).
        """
        results = []
        try:
            path = os.path.join(self.handshakes_dir, "wpa-sec.cracked.potfile")
            if not os.path.isfile(path):
                return results
            with open(path, "r", errors="replace") as fp:
                for line in fp:
                    line = line.strip()
                    if not line or ":" not in line:
                        continue
                    parts = line.split(":")
                    if len(parts) < 4:
                        continue
                    ap = parts[0].strip().lower().replace("-", "")
                    pw = parts[-1]
                    essid = ":".join(parts[2:-1])
                    if ap and pw:
                        results.append({"bssid": ap, "ssid": essid, "password": pw})
            if len(results) > limit:
                results = results[-limit:]
        except Exception as e:
            log.debug(f"[pwn-companion] read cracked failed: {e}")
        return results

    async def _send_cracked(self):
        """Push the cracked-password map to the app (matched to captures by BSSID)."""
        try:
            results = await asyncio.get_event_loop().run_in_executor(None, self._read_cracked)
            if results and self.app_connected:
                await self._send_to_app({"type": "cracked", "results": results})
                log.info(f"[pwn-companion] Sent {len(results)} cracked results to app")
        except Exception as e:
            log.debug(f"[pwn-companion] send cracked failed: {e}")

    def on_association(self, agent, ap):
        """Fire network_event when Pwnagotchi associates with an AP"""
        self._agent = agent
        try:
            ssid = ap.get("hostname", "") or ap.get("essid", "unknown")
            channel = ap.get("channel", 0)
            rssi = ap.get("rssi", -100)
            security = ap.get("encryption", "Unknown")
            bssid = ap.get("mac", None)

            log.info(f"[pwn-companion]  Association: {ssid} CH{channel} ({rssi}dBm)")

            if self.event_broadcaster and self.app_connected:
                self._schedule_on_loop(
                    self.event_broadcaster.on_network_discovered(
                        ssid=ssid,
                        bssid=bssid,
                        security=security,
                        signal_strength=rssi,
                        channel=channel,
                    ),
                    self.loop
                )
        except Exception as e:
            log.debug(f"[pwn-companion] Error in on_association: {e}")

    def on_deauthentication(self, agent, ap, station):
        """Fire AI event when Pwnagotchi sends a deauth packet"""
        self._agent = agent
        try:
            channel = ap.get("channel", 0) if isinstance(ap, dict) else 0
            ssid = (ap.get("hostname", "") or ap.get("essid", "")) if isinstance(ap, dict) else str(ap)
            bssid = (ap.get("mac", "") or ap.get("bssid", "")) if isinstance(ap, dict) else ""
            # The deauth TARGET is a client station MAC — previously ignored, which is why
            # the app's log line had nothing to show and fell back to "spectrum".
            sta_mac = station.get("mac", "") if isinstance(station, dict) else ""
            log.debug(f"[pwn-companion]  Deauth: sta={sta_mac} ap={ssid} CH{channel}")
            if self.event_broadcaster and self.app_connected:
                self._schedule_on_loop(
                    self.event_broadcaster.on_anomaly_detected(
                        anomaly_type="deauthentication",
                        network=ssid or None,
                        channel=channel or None,
                        bssid=bssid or None,
                        station=sta_mac or None,
                        details={"channel": channel, "network": ssid},
                    ),
                    self.loop
                )
        except Exception as e:
            log.debug(f"[pwn-companion] Error in on_deauthentication: {e}")

    def on_epoch(self, agent, epoch, epoch_data):
        """
        Collect per-channel stats from each epoch and send autotune_stats to app.
        epoch_data contains: channel, num_deauths, num_associations, num_handshakes, etc.
        This runs every epoch (~60s) and is the most reliable source of channel efficiency.
        """
        self._agent = agent
        try:
            # Fallback discovery start: if no bt-tether event ever started us but a
            # Bluetooth-PAN interface is up, begin discovery anyway so we can connect.
            if not self.discovering and not self.app_connected:
                iface = self._detect_bnep_interface()
                if iface:
                    log.info(
                        f"[pwn-companion] bnep interface '{iface}' detected without a "
                        f"bt-tether event — starting discovery"
                    )
                    self._start_client_discovery(iface)

            # Per-channel stats — only when this epoch reports a channel. (This
            # pwnagotchi's epoch_data often has no 'channel' key; we must NOT bail
            # here, or the telemetry/autotune sends below would never run.)
            ch = epoch_data.get("channel") or epoch_data.get("current_channel")
            if ch is not None:
                ch = int(ch)
                # Guard with the lock: this runs on the pwnagotchi epoch thread while
                # _send_autotune_stats iterates the same dict on the loop thread —
                # without the lock that races into "dict changed size during iteration".
                with self.lock:
                    if ch not in self._channel_stats:
                        self._channel_stats[ch] = {"handshakes": 0, "deauths": 0, "associations": 0, "aps": 0, "sta": 0}

                    self._channel_stats[ch]["handshakes"]   += int(epoch_data.get("num_handshakes", 0))
                    self._channel_stats[ch]["deauths"]       += int(epoch_data.get("num_deauths", 0))
                    self._channel_stats[ch]["associations"]  += int(epoch_data.get("num_associations", 0))
                    # Target density: peak APs / clients observed while hopping on this channel.
                    self._channel_stats[ch]["aps"] = max(self._channel_stats[ch].get("aps", 0), int(epoch_data.get("num_aps", 0)))
                    self._channel_stats[ch]["sta"] = max(self._channel_stats[ch].get("sta", 0), int(epoch_data.get("num_sta", 0)))

                    # Determine best channel by total handshakes
                    self._best_channel = max(
                        self._channel_stats,
                        key=lambda c: self._channel_stats[c]["handshakes"]
                    )

                log.debug(
                    f"[pwn-companion] Epoch {epoch}: CH{ch} "
                    f"hs={epoch_data.get('num_handshakes',0)} "
                    f"da={epoch_data.get('num_deauths',0)} "
                    f"as={epoch_data.get('num_associations',0)}"
                )

            # Send autotune_stats to app if connected
            if self.app_connected and self._channel_stats:
                # Snapshot under the lock (deep-copy inner dicts) so serialisation
                # can't race the epoch-thread mutation above.
                with self.lock:
                    channels_payload = {
                        str(c): dict(v) for c, v in self._channel_stats.items()
                    }
                msg = {
                    "type": "autotune_stats",
                    "autotune_channels": channels_payload,
                    "autotune_best_channel": self._best_channel,
                    "autotune_min_rssi": None,  # filled in below if auto-tune is loaded
                    "timestamp": int(time.time()),
                }

                # Try to enrich with actual auto-tune min_rssi
                try:
                    import pwnagotchi.plugins as _plugins
                    autotune = _plugins.loaded.get("auto-tune")
                    if autotune:
                        for attr in ("_min_rssi", "min_rssi"):
                            v = getattr(autotune, attr, None)
                            if v is not None:
                                msg["autotune_min_rssi"] = int(v)
                                break
                except Exception:
                    pass

                self._schedule_on_loop(
                    self._send_to_app(msg),
                    self.loop
                )

            # Device telemetry — vitals + RL reward + mood counters + density.
            # Drives the app's [ vitals ] section AND the emergent AI personality
            # (reward = the device's own self-score; the *_for_epochs counters are
            # its emotional state over time; temp/cpu = a 'running hot' stress signal).
            if self.app_connected:
                def _num(key, cast=float):
                    try:
                        v = epoch_data.get(key)
                        return cast(v) if v is not None else None
                    except (TypeError, ValueError):
                        return None

                telemetry = {
                    "type": "device_telemetry",
                    "timestamp": int(time.time()),
                    # vitals
                    "temperature": _num("temperature"),
                    "cpu_load": _num("cpu_load"),
                    "mem_usage": _num("mem_usage"),
                    # performance / self-assessment
                    "reward": _num("reward"),
                    # environment density
                    "num_aps": _num("num_aps", int),
                    "num_sta": _num("num_sta", int),
                    "num_peers": _num("num_peers", int),
                    # behavioural / mood counters (epochs spent in each state)
                    "active_for_epochs": _num("active_for_epochs", int),
                    "inactive_for_epochs": _num("inactive_for_epochs", int),
                    "bored_for_epochs": _num("bored_for_epochs", int),
                    "sad_for_epochs": _num("sad_for_epochs", int),
                    "blind_for_epochs": _num("blind_for_epochs", int),
                    # running totals
                    "total_handshakes": self._total_handshakes,
                    "epoch": int(epoch) if epoch is not None else None,
                }
                self._schedule_on_loop(
                    self._send_to_app(telemetry),
                    self.loop
                )

            # In AUTO mode: if this epoch had no handshakes and no associations,
            # broadcast an idle network_event so the AI can generate a quiet-period quip.
            if (self._current_mode == "AUTO"
                    and self.app_connected
                    and self.event_broadcaster
                    and int(epoch_data.get("num_handshakes", 0)) == 0
                    and int(epoch_data.get("num_associations", 0)) == 0):
                self._schedule_on_loop(
                    self._send_to_app({
                        "type": "network_event",
                        "event_type": "idle",
                        "description": "Quiet epoch — no handshakes or associations",
                        "timestamp": int(time.time()),
                    }),
                    self.loop
                )

        except Exception as e:
            log.debug(f"[pwn-companion] Error in on_epoch: {e}")

    def _detect_bnep_interface(self):
        """Return the name of an up Bluetooth-PAN interface (bnep* / bt-pan), or None.

        Fallback so discovery can start even when on_bt_tether_connected never
        fires — e.g. a bt-tether plugin version that doesn't emit the event, or
        this plugin loading after the tether came up. The BT-PAN link can be fully
        established at the network layer while no event was ever delivered.
        """
        try:
            for _idx, name in socket.if_nameindex():
                if name.startswith("bnep") or name == "bt-pan":
                    return name
        except Exception as e:
            log.debug(f"[pwn-companion] Could not enumerate interfaces: {e}")
        return None

    def _start_client_discovery(self, iface=None):
        """Start UDP discovery + WebSocket client in background thread"""
        # Atomic check-and-set: this is called from two different non-loop threads
        # (the bt-tether hook and the on_epoch fallback). Without the lock both can
        # pass the guard, spawn two discovery loops, and the second's UDP bind(8888)
        # fails with EADDRINUSE while the first loop is orphaned.
        with self.lock:
            if self.discovering:
                return
            self.discovering = True

        self.loop = asyncio.new_event_loop()
        self.ws_thread = threading.Thread(
            target=self._run_discovery,
            args=(iface,),
            daemon=True,
            name="pwn-companion-discovery",
        )
        self.ws_thread.start()
        iface_str = f" on {iface}" if iface else ""
        log.info(
            f"[pwn-companion]  Discovery started, listening on UDP:{UDP_DISCOVERY_PORT}{iface_str}"
        )

    def _run_discovery(self, iface=None):
        """Run discovery + connection in event loop"""
        try:
            asyncio.set_event_loop(self.loop)
            self.loop.run_until_complete(self._discovery_loop(iface))
        except Exception as e:
            log.error(f"[pwn-companion] Discovery error: {e}")
            self.discovering = False
        finally:
            try:
                if self.app_websocket:
                    self.loop.run_until_complete(self.app_websocket.close())
            except Exception as e:
                log.debug(f"[pwn-companion] Error closing websocket: {e}")
            try:
                self.loop.close()
            except Exception as e:
                log.debug(f"[pwn-companion] Error closing event loop: {e}")
            log.info("[pwn-companion] Discovery loop stopped")

    async def _discovery_loop(self, iface=None):
        """Main discovery and connection loop"""
        udp_socket = None
        last_connection_attempt = 0
        connection_retry_delay = INITIAL_RETRY_DELAY
        consecutive_failures = 0  # Track failed connection attempts

        try:
            # Create UDP socket for discovering announcements
            udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

            # CRITICAL: Enable broadcast reception on this socket
            # This allows us to receive broadcasts sent from the app
            udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            log.debug("[pwn-companion] ✓ Broadcast reception enabled on UDP socket")

            # If interface is provided, bind to the broadcast address on that interface
            # For receiving broadcasts, we should bind to 0.0.0.0 but on the specific interface
            if iface:
                try:
                    # On Linux, use SO_BINDTODEVICE to bind to specific interface (bnep0)
                    import socket as socket_module

                    udp_socket.setsockopt(
                        socket.SOL_SOCKET, SO_BINDTODEVICE, iface.encode()
                    )
                    log.info(f"[pwn-companion] ✓ Bound UDP socket to interface {iface}")
                except (AttributeError, OSError) as e:
                    log.debug(
                        f"[pwn-companion] ⚠️ Could not bind to interface {iface}: {e}, listening on all interfaces"
                    )

            # Bind to all interfaces on port UDP_DISCOVERY_PORT
            udp_socket.bind(("", UDP_DISCOVERY_PORT))
            udp_socket.setblocking(False)
            log.info(
                f"[pwn-companion] ✓ UDP socket listening on :{UDP_DISCOVERY_PORT} (broadcast enabled)"
                + (f" on {iface}" if iface else "")
            )

            loop = asyncio.get_event_loop()

            while self.discovering:
                # Try to receive announcement using asyncio's socket wrapper
                try:
                    data, addr = await asyncio.wait_for(
                        loop.sock_recvfrom(udp_socket, UDP_BUFFER_SIZE),
                        timeout=UDP_RECEIVE_TIMEOUT,
                    )
                    try:
                        msg = json.loads(data.decode("utf-8"))
                        log.info(
                            f"[pwn-companion] ✅  UDP received from {addr}: type={msg.get('type')}"
                        )
                        log.debug(f"[pwn-companion] Full announcement: {msg}")

                        # CRITICAL: Log the endpoint data for verification
                        if msg.get("type") == "announcement":
                            server_ip = msg.get("serverIp")
                            server_port = msg.get("serverPort")
                            log.info(
                                f"[pwn-companion]  Announcement details: serverIp={server_ip}, serverPort={server_port}"
                            )

                        if msg.get("type") == "announcement":
                            # Construct endpoint from serverIp and serverPort
                            server_ip = msg.get("serverIp")
                            server_port = msg.get("serverPort")
                            endpoint = msg.get(
                                "endpoint"
                            )  # Fallback if endpoint is provided directly

                            # If no endpoint but we have serverIp and serverPort, construct it
                            if not endpoint and server_ip and server_port:
                                endpoint = f"ws://{server_ip}:{server_port}"

                            ip = msg.get("ip", server_ip)
                            port = msg.get("port", server_port)

                            if not endpoint:
                                log.warning(
                                    f"[pwn-companion] ⚠️ Announcement missing endpoint data. Available fields: {list(msg.keys())}"
                                )
                            elif endpoint != self.app_endpoint:
                                self.app_endpoint = endpoint
                                log.info(
                                    f"[pwn-companion]  App announcement: {ip}:{port} → {endpoint}"
                                )
                                # Reset backoff + error counter on new announcement
                                connection_retry_delay = INITIAL_RETRY_DELAY
                                last_connection_attempt = 0
                                consecutive_failures = 0
                                # Try to connect
                                await self._connect_to_app()
                            else:
                                log.debug(
                                    f"[pwn-companion] Announcement endpoint unchanged: {endpoint}"
                                )
                    except json.JSONDecodeError as je:
                        log.debug(f"[pwn-companion] Invalid JSON from {addr}: {je}")
                    except Exception as parse_ex:
                        log.debug(
                            f"[pwn-companion] Error parsing announcement: {parse_ex}"
                        )

                except asyncio.TimeoutError:
                    pass  # No announcement received within timeout, continue
                except Exception as e:
                    log.debug(f"[pwn-companion] UDP receive error: {type(e).__name__}")

                # Try to connect if we have endpoint but no connection (with backoff)
                now = time.time()
                if (
                    self.app_endpoint
                    and not self.app_connected
                    and not self.app_websocket
                    and (now - last_connection_attempt) >= connection_retry_delay
                ):
                    last_connection_attempt = now
                    log.debug(
                        f"[pwn-companion] Attempting reconnection (retry delay: {connection_retry_delay}s, failures: {consecutive_failures})..."
                    )
                    success = await self._connect_to_app()
                    if success:
                        # Reset backoff + error counter on successful connection
                        connection_retry_delay = INITIAL_RETRY_DELAY
                        consecutive_failures = 0
                    else:
                        consecutive_failures += 1
                        # Increase backoff delay up to MAX_RETRY_DELAY
                        connection_retry_delay = min(
                            MAX_RETRY_DELAY,
                            connection_retry_delay * RETRY_BACKOFF_FACTOR,
                        )

                # Brief sleep to prevent busy-waiting
                await asyncio.sleep(DISCOVERY_LOOP_SLEEP)

        except Exception as e:
            log.error(
                f"[pwn-companion] Discovery loop error: {type(e).__name__}: {e}",
                exc_info=True,
            )
        finally:
            if udp_socket:
                try:
                    udp_socket.close()
                except Exception as e:
                    log.debug(f"[pwn-companion] Error closing UDP socket: {e}")
            self.discovering = False
            with self.lock:
                self.app_websocket = None
                self.app_connected = False
            log.info("[pwn-companion] Discovery loop ended")

    async def _connect_to_app(self):
        """Connect to app's WebSocket endpoint. Returns True on success, False on failure."""
        if not self.app_endpoint or self.app_connected:
            return False

        try:
            log.info(f"[pwn-companion]  Connecting to {self.app_endpoint}...")
            log.debug(
                f"[pwn-companion] Attempting WebSocket connection with {WEBSOCKET_CONNECT_TIMEOUT}s timeout"
            )
            self.app_websocket = await asyncio.wait_for(
                websockets.connect(
                    self.app_endpoint,
                    ping_interval=WEBSOCKET_PING_INTERVAL,
                    ping_timeout=WEBSOCKET_PING_TIMEOUT,
                    close_timeout=WEBSOCKET_CLOSE_TIMEOUT,
                ),
                timeout=WEBSOCKET_CONNECT_TIMEOUT,
            )
            log.debug(
                f"[pwn-companion] WebSocket connection established, state={self.app_websocket.state}"
            )
            with self.lock:
                self.app_connected = True
            log.info(f"[pwn-companion] ✓ Connected to app!")

            # Initialize event broadcaster for AI personality
            if not self.event_broadcaster:
                self.event_broadcaster = PwnagotchiEventBroadcaster(self._send_to_app)
                log.info("[pwn-companion]  AI event broadcaster ready")

            # Send ready signal to initiate app polling (on both initial connect and reconnect)
            log.debug(
                f"[pwn-companion]  Sending READY signal (initial or reconnect)..."
            )
            await self._send_to_app({"type": "ready"})

            # Send initial status message
            await self._send_status_message("Connected to companion app")

            # Seed the app with the historical capture log (geolocated handshakes).
            # Run the scan in a thread executor — it does blocking os.listdir + getmtime
            # sort + up to 300 json.load()s. Doing that inline on the event loop stalls
            # image push, GPS and the listen loop on every (re)connect, which on a device
            # with many captures times out the websocket and triggers a reconnect storm.
            try:
                captures = await asyncio.get_event_loop().run_in_executor(
                    None, self._scan_capture_history
                )
                if captures:
                    await self._send_to_app(
                        {"type": "capture_history", "captures": captures,
                         "total_files": getattr(self, "_last_scan_file_count", len(captures))}
                    )
                    log.info(f"[pwn-companion] Sent {len(captures)} captures to app")
            except Exception as e:
                log.error(f"[pwn-companion] Failed to send capture history: {e}")

            # Seed cracked passwords (from wpa-sec, if enabled + downloaded).
            await self._send_cracked()
            # Fresh wpa-sec reachability so the app's "cracking" row is accurate on connect.
            self._wpa_sec_online = await asyncio.get_event_loop().run_in_executor(
                None, self._check_wpa_sec_online
            )

            # Send immediate GPS request (don't wait for periodic task)
            log.info("[pwn-companion]  Sending initial GPS request on connection")
            await self._send_to_app(
                {"type": "gps_request", "timestamp": int(time.time()), "source": "initial_connect"}
            )

            # Start periodic tasks (image push, GPS requests)
            self._start_periodic_tasks()

            # Listen for messages from app
            await self._listen_to_app()
            return True
        except asyncio.TimeoutError:
            log.warning(
                f"[pwn-companion] ⏱️ Connection timeout ({WEBSOCKET_CONNECT_TIMEOUT}s) to {self.app_endpoint}"
            )
            log.warning(
                f"[pwn-companion] ⏸️ App WebSocket server may not be listening or is blocked by network"
            )
            return False
        except websockets.exceptions.InvalidStatus as ise:
            # HTTP error during WebSocket handshake
            log.warning(
                f"[pwn-companion] ❌ HTTP {ise.status} during WebSocket handshake: {ise.reason}"
            )
            log.warning(
                f"[pwn-companion] ⚠️ App responded with HTTP error - check if WebSocket server is running"
            )
            return False
        except websockets.exceptions.WebSocketException as e:
            log.warning(
                f"[pwn-companion] ❌ WebSocket error: {type(e).__name__}: {str(e)[:LOG_STRING_TRUNCATE_LENGTH]}"
            )
            log.debug(f"[pwn-companion] Full WebSocket error: {e}", exc_info=True)
            return False
        except ConnectionRefusedError as e:
            log.warning(f"[pwn-companion] ❌ Connection refused by {self.app_endpoint}")
            # When connection is refused, clear endpoint and go back to UDP listening
            log.info(
                "[pwn-companion]  Clearing endpoint, switching back to UDP discovery mode"
            )
            self.app_endpoint = None
            return False
        except OSError as e:
            log.warning(
                f"[pwn-companion] ❌ Network error: {type(e).__name__}: {str(e)[:LOG_STRING_TRUNCATE_LENGTH]}"
            )
            return False
        except Exception as e:
            log.warning(
                f"[pwn-companion] ❌ Connection error: {type(e).__name__}: {str(e)[:LOG_STRING_TRUNCATE_LENGTH]}"
            )
            return False
        finally:
            # Cancel periodic tasks
            self._stop_periodic_tasks()
            # Explicitly close the socket before dropping it. On a non-clean disconnect
            # (send failure / timeout / OSError) the peer hasn't closed it, so nulling
            # without closing leaks the connection + its FD — costly under the frequent
            # drop/reconnect churn of a shared BT/WiFi radio. This runs on the loop's own
            # thread, so awaiting close() here is safe.
            ws = self.app_websocket
            if ws is not None:
                try:
                    await asyncio.wait_for(ws.close(), timeout=WEBSOCKET_CLOSE_TIMEOUT)
                except Exception as e:
                    log.debug(f"[pwn-companion] Error closing websocket in finally: {e}")
            with self.lock:
                self.app_connected = False
                self.app_websocket = None

    async def _listen_to_app(self):
        """Listen for messages from connected app"""
        try:
            async for message in self.app_websocket:
                try:
                    await self._handle_message(message)
                except Exception as e:
                    log.error(f"[pwn-companion] Message handler error: {e}")
        except websockets.ConnectionClosed as cc:
            log.info(
                f"[pwn-companion]  Connection closed by app "
                f"(code: {cc.rcvd.code if cc.rcvd else 'N/A'}, "
                f"reason: {cc.rcvd.reason if cc.rcvd else 'unknown'})"
            )
            # Keep endpoint for reconnection attempt with exponential backoff
            # Don't clear it - let the discovery loop retry the connection
            log.info(
                "[pwn-companion] ⏸️ Connection closed, discovery loop will retry with backoff..."
            )
        except Exception as e:
            log.warning(
                f"[pwn-companion] ⚠️ Listen error: {type(e).__name__}: {str(e)[:LOG_STRING_TRUNCATE_LENGTH]}"
            )

    def _start_periodic_tasks(self):
        """Start periodic background tasks (image push, GPS requests, auto-tune stats)"""
        self.periodic_tasks = []

        if self.push_image_interval > 0:
            task = asyncio.create_task(self._periodic_image_push())
            self.periodic_tasks.append(task)
            log.debug(f"[pwn-companion]  Periodic image push started ({self.push_image_interval}s)")

        if self.request_gps_interval > 0:
            task = asyncio.create_task(self._periodic_gps_request())
            self.periodic_tasks.append(task)
            log.debug(f"[pwn-companion]  Periodic GPS request started ({self.request_gps_interval}s)")

        # Auto-tune stats — push every 30 seconds if auto-tune is loaded
        task = asyncio.create_task(self._periodic_autotune_push())
        self.periodic_tasks.append(task)
        log.debug("[pwn-companion]  Periodic auto-tune stats push started (30s)")

        # Vitals (temp/cpu/mem) — push regardless of mode so the app's gauges stay live
        # even in MANUAL (on_epoch, which sends full telemetry, only fires in AUTO).
        task = asyncio.create_task(self._periodic_vitals_push())
        self.periodic_tasks.append(task)
        log.debug(f"[pwn-companion]  Periodic vitals push started ({VITALS_PUSH_INTERVAL}s)")

    def _stop_periodic_tasks(self):
        """Stop all periodic background tasks"""
        if not self.periodic_tasks:
            return

        # Cancel all tasks
        tasks = [t for t in self.periodic_tasks if t and not t.done()]
        for task in tasks:
            task.cancel()

        # Actually await the cancellation so CancelledError cleanup runs and we
        # don't leak "Task was destroyed but it is pending" warnings. We can't
        # await in a sync method, so schedule a coroutine on the running loop.
        if tasks:
            try:
                loop = asyncio.get_running_loop()
                loop.create_task(self._await_cancelled(tasks))
            except RuntimeError:
                # No running loop - tasks will be cleaned up when the loop ends
                pass

        self.periodic_tasks = []

    @staticmethod
    async def _await_cancelled(tasks):
        """Await cancelled tasks so their cleanup completes cleanly."""
        try:
            await asyncio.gather(*tasks, return_exceptions=True)
        except Exception:
            pass

    def _schedule_on_loop(self, coro, loop=None):
        """Safely schedule a coroutine on the discovery event loop from any thread.

        Replaces direct asyncio.run_coroutine_threadsafe() calls so a call landing
        during/after loop teardown can't raise "event loop is closed" / "loop is
        not running". The optional ``loop`` arg is accepted only for call-site
        compatibility — the plugin's own validated ``self.loop`` is always used.
        Returns the concurrent.futures.Future, or None if the loop is unavailable.
        """
        target = self.loop
        if target is None or target.is_closed() or not target.is_running():
            try:
                coro.close()  # avoid "coroutine was never awaited" warning
            except Exception:
                pass
            return None
        try:
            return asyncio.run_coroutine_threadsafe(coro, target)
        except RuntimeError as e:
            log.debug(f"[pwn-companion] Could not schedule coroutine on loop: {e}")
            try:
                coro.close()
            except Exception:
                pass
            return None

    async def _periodic_vitals_push(self):
        """Push lightweight vitals (temp/cpu/mem) every VITALS_PUSH_INTERVAL seconds,
        REGARDLESS of AUTO/MANUAL mode. on_epoch only runs while hunting, so without this
        the app's [ vitals ] gauges are blank whenever the device is paused. These read
        the SoC directly via pwnagotchi's own helpers (same source as its e-ink face).
        The app merges telemetry field-by-field, so this sparse update never wipes the
        richer per-epoch reward/density values."""
        import pwnagotchi
        def _safe(fn, cast=float):
            try:
                v = fn()
                return cast(v) if v is not None else None
            except Exception:
                return None
        cycles = 0
        while self.app_connected:
            try:
                await asyncio.sleep(VITALS_PUSH_INTERVAL)
                if not self.app_connected:
                    continue
                await self._send_to_app({
                    "type": "device_telemetry",
                    "timestamp": int(time.time()),
                    "temperature": _safe(pwnagotchi.temperature),
                    "cpu_load": _safe(pwnagotchi.cpu_load),
                    "mem_usage": _safe(pwnagotchi.mem_usage),
                })
                # Re-check cracked results roughly every ~2 min (wpa-sec downloads
                # hourly, so this cheaply catches new cracks without extra load).
                cycles += 1
                if cycles % 10 == 0:
                    await self._send_cracked()
                # Re-check whether the wpa-sec service is reachable (~every 5 min); on a
                # change, push a status so the app's "cracking" row reflects on/offline.
                if cycles % 25 == 0:
                    online = await asyncio.get_event_loop().run_in_executor(None, self._check_wpa_sec_online)
                    if online != getattr(self, "_wpa_sec_online", None):
                        self._wpa_sec_online = online
                        await self._send_status_message(f"wpa-sec {'online' if online else 'offline'}")
            except asyncio.CancelledError:
                break
            except Exception as e:
                log.debug(f"[pwn-companion] Periodic vitals error: {e}")
                await asyncio.sleep(5)

    async def _periodic_autotune_push(self):
        """Push auto-tune channel efficiency stats every 30 seconds"""
        while self.app_connected:
            try:
                await asyncio.sleep(30)
                if self.app_connected and self._agent:
                    await self._send_autotune_stats(self._agent)
            except asyncio.CancelledError:
                break
            except Exception as e:
                log.debug(f"[pwn-companion] Periodic auto-tune error: {e}")
                await asyncio.sleep(5)

    async def _periodic_image_push(self):
        """Periodically fetch and push screenshot to app"""
        while self.app_connected:
            try:
                await asyncio.sleep(self.push_image_interval)
                if self.app_connected:
                    await self._handle_image_request()
            except asyncio.CancelledError:
                log.debug("[pwn-companion] Image push task cancelled")
                break
            except Exception as e:
                log.error(
                    f"[pwn-companion] Periodic image push error: {type(e).__name__}: {str(e)[:LOG_STRING_TRUNCATE_LENGTH]}"
                )
                await asyncio.sleep(
                    PERIODIC_TASK_RETRY_SLEEP
                )  # Brief delay before retry

    async def _periodic_gps_request(self):
        """Periodically request GPS data from app"""
        while self.app_connected:
            try:
                await asyncio.sleep(self.request_gps_interval)
                if self.app_connected:
                    log.debug(f"[pwn-companion]  Sending periodic GPS request (interval: {self.request_gps_interval}s)")
                    await self._send_to_app(
                        {"type": "gps_request", "timestamp": int(time.time()), "source": "periodic"}
                    )
            except asyncio.CancelledError:
                log.debug("[pwn-companion] GPS request task cancelled")
                break
            except Exception as e:
                log.error(
                    f"[pwn-companion] Periodic GPS request error: {type(e).__name__}: {str(e)[:LOG_STRING_TRUNCATE_LENGTH]}"
                )
                await asyncio.sleep(
                    PERIODIC_TASK_RETRY_SLEEP
                )  # Brief delay before retry

    async def _handle_message(self, message: str):
        """Handle incoming WebSocket message from app"""
        try:
            log.debug(
                f"[pwn-companion] Processing message: {message[:HANDLER_ERROR_TRUNCATE_LENGTH]}"
            )

            data = json.loads(message)
            msg_type = data.get("type")

            log.debug(f"[pwn-companion] Message type: {msg_type}")

            # Process messages
            try:
                if msg_type == "command":
                    await self._handle_command(data)
                elif msg_type == "gps":
                    await self._handle_gps(data)
                elif msg_type == "gps_response":
                    await self._handle_gps(data)  # App responding to our gps_request
                elif msg_type == "status_request":
                    await self._handle_status_request()
                elif msg_type == "image_request":
                    log.debug(f"[pwn-companion] Processing image request")
                    await self._handle_image_request()
                elif msg_type == "ready":
                    # App acknowledging ready signal
                    log.debug(f"[pwn-companion] App acknowledged ready signal")
                else:
                    log.warning(f"[pwn-companion] Unknown message type: {msg_type}")
                    await self._send_to_app(
                        {
                            "type": "error",
                            "message": f"Unknown message type: {msg_type}",
                        },
                    )
            except websockets.ConnectionClosed:
                log.warning(
                    f"[pwn-companion] Connection closed while handling {msg_type}"
                )
                raise
            except Exception as handler_ex:
                log.error(
                    f"[pwn-companion] Error in {msg_type} handler: {type(handler_ex).__name__}: {handler_ex}",
                    exc_info=True,
                )
                try:
                    await self._send_to_app(
                        {
                            "type": "error",
                            "message": f"Handler error: {str(handler_ex)[:HANDLER_ERROR_TRUNCATE_LENGTH]}",
                        },
                    )
                except Exception as send_ex:
                    log.error(
                        f"[pwn-companion] Failed to send error message: {send_ex}"
                    )

        except json.JSONDecodeError as je:
            log.error(f"[pwn-companion] Invalid JSON: {je}")
            try:
                await self._send_to_app({"type": "error", "message": "Invalid JSON"})
            except Exception as send_ex:
                log.error(
                    f"[pwn-companion] Failed to send JSON error response: {send_ex}"
                )
        except websockets.ConnectionClosed:
            log.warning(f"[pwn-companion] Connection closed during message processing")
            raise
        except Exception as e:
            log.error(
                f"[pwn-companion] Error processing message: {type(e).__name__}: {e}",
                exc_info=True,
            )

    async def _handle_command(self, data: dict):
        """Handle custom command from mobile app"""
        # Support both "action" (app JSON) and "message" (ScreenData queue format)
        action = data.get("action") or data.get("message")
        params = data.get("params", {})

        # The app's queueCommand() carries its payload in the generic "data" field
        # (e.g. set_channel_priority sends "1,6,11"); fold it into params so
        # execute_command can read it uniformly.
        raw = data.get("data")
        channels = data.get("channels")
        if channels is None and raw is not None:
            channels = raw
        if channels is not None and "channels" not in params:
            params = {**params, "channels": channels}
        if raw is not None and "value" not in params:
            params = {**params, "value": raw}

        log.info(f"[pwn-companion] Command received: {action}, params: {params}")

        with self.lock:
            self.last_command = {
                "action": action,
                "params": params,
                "timestamp": time.time(),
            }

        # Send confirmation
        await self._send_to_app({"type": "command_received", "action": action})

        # Execute off the event loop: execute_command may block (agent.run() does a
        # bettercap HTTP round-trip; pwnagotchi.restart() tears down the process).
        # Running it inline would freeze image push, GPS, and the listen loop.
        try:
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, self.execute_command, action, params)
        except Exception as e:
            log.error(f"[pwn-companion] Error dispatching command '{action}': {e}")

    def execute_command(self, action: str, params: dict):
        """
        Execute a command received from the companion app.

        Supported actions:
            restart_auto   — Restart pwnagotchi in autonomous (AUTO) mode
            restart_manual — Restart pwnagotchi in manual (MANUAL) mode
        """
        if not action:
            log.warning("[pwn-companion] execute_command: empty action")
            return

        log.info(f"[pwn-companion] ⚙️ Executing command: {action}")

        try:
            if action in ("restart_auto", "restart_manual"):
                mode = "AUTO" if action == "restart_auto" else "MANUAL"
                log.info(f"[pwn-companion]  Restarting pwnagotchi in {mode} mode...")
                try:
                    import pwnagotchi
                    pwnagotchi.restart(mode)
                except Exception as e:
                    log.error(f"[pwn-companion] Failed to restart in {mode} mode: {e}")
            elif action == "set_channel_priority":
                self._apply_channel_priority(params.get("channels"))
            elif action == "set_recon_time":
                self._apply_param("recon_time", params.get("value"))
            elif action == "set_param":
                raw = params.get("value")
                if raw is not None and ":" in str(raw):
                    k, v = str(raw).split(":", 1)
                    self._apply_param(k.strip(), v.strip())
                else:
                    log.warning(f"[pwn-companion] set_param: bad payload {raw!r} (want 'key:value')")
            elif action == "set_voice_pool":
                # App-driven speech-bubble lines (JSON: {category: [line, ...]}).
                self._apply_voice_pool(params.get("value"))
            else:
                log.warning(f"[pwn-companion] Unknown command action: {action}")

        except Exception as e:
            log.error(f"[pwn-companion] Error executing command '{action}': {e}")

    def _apply_channel_priority(self, channels):
        """Soft-steer the device using the app's learned channel intel: focus
        bettercap's Wi-Fi recon on the given channels. Live and reversible — an
        empty/invalid list clears the focus so recon hops all channels again.

        This is a 'soft' nudge: it biases where the device looks, but the
        Pwnagotchi's own RL brain still decides what to attack on those channels.
        """
        try:
            if isinstance(channels, str):
                parts = channels.split(",")
            elif isinstance(channels, (list, tuple)):
                parts = list(channels)
            else:
                parts = []

            chans = []
            for p in parts:
                try:
                    c = int(str(p).strip())
                    if 1 <= c <= 165 and c not in chans:
                        chans.append(c)
                except (TypeError, ValueError):
                    continue

            # Snapshot the agent under the lock — it's reassigned by hooks on other
            # threads and can be None before the first hook fires.
            with self.lock:
                agent = self._agent
            if not agent:
                log.warning("[pwn-companion] No agent yet — cannot apply channel priority")
                return

            if not chans:
                agent.run("wifi.recon.channel clear")
                log.info("[pwn-companion] Channel priority cleared — recon on all channels")
            else:
                chan_str = ",".join(str(c) for c in chans)
                agent.run(f"wifi.recon.channel {chan_str}")
                log.info(f"[pwn-companion] ✓ Channel priority applied — recon focused on {chan_str}")
        except Exception as e:
            log.error(f"[pwn-companion] Failed to apply channel priority: {e}")

    # jayofelony's build stripped the RL param-tuner; the phone re-implements that job
    # (see PersonalityTuner) and just tells us the values. We clamp + apply them:
    #   - rssi/ttl params go to bettercap live (agent.run "set wifi.*"), matching how
    #     agent.py applies them at startup;
    #   - recon/hop timing lives in personality config, which agent.recon()/set_channel
    #     re-read every cycle.
    # Everything is mirrored into personality config too, so it's consistent + survives.
    _PARAM_CLAMPS = {
        "min_rssi": (-90, -55),
        "ap_ttl": (30, 300),
        "sta_ttl": (60, 600),
        "recon_time": (10, 60),
        "hop_recon_time": (2, 30),
        "min_recon_time": (2, 30),
    }
    _PARAM_BETTERCAP = {
        "min_rssi": "wifi.rssi.min",
        "ap_ttl": "wifi.ap.ttl",
        "sta_ttl": "wifi.sta.ttl",
    }

    def _apply_param(self, key, raw):
        """Apply one personality/recon parameter at runtime. Allowlisted + clamped so a
        bad value can never stall recon or wedge the device. `deauth` is intentionally
        NOT tunable — we never stop the device attacking."""
        if key not in self._PARAM_CLAMPS:
            log.warning(f"[pwn-companion] set_param: unknown/blocked key '{key}'")
            return
        try:
            val = int(str(raw).strip())
        except (TypeError, ValueError):
            log.debug(f"[pwn-companion] set_param: bad value for {key}: {raw!r}")
            return
        lo, hi = self._PARAM_CLAMPS[key]
        val = max(lo, min(hi, val))
        with self.lock:
            agent = self._agent
        if not agent:
            log.warning(f"[pwn-companion] set_param: no agent yet ({key})")
            return
        try:
            if key in self._PARAM_BETTERCAP:
                agent.run(f"set {self._PARAM_BETTERCAP[key]} {val}")
            # Mirror into personality config (effective apply for recon/hop; harmless for the rest).
            cfg = getattr(agent, "_config", None)
            if isinstance(cfg, dict) and isinstance(cfg.get("personality"), dict):
                cfg["personality"][key] = val
            log.info(f"[pwn-companion] ✓ set_param {key} = {val}")
        except Exception as e:
            log.error(f"[pwn-companion] set_param {key} failed: {e}")

    def _apply_recon_time(self, seconds):
        """Back-compat shim for the old set_recon_time command → generic tuner."""
        self._apply_param("recon_time", seconds)

    async def _handle_gps(self, data: dict):
        """Handle GPS coordinate update from mobile app"""
        try:
            log.debug(f"[pwn-companion]  GPS handler received data: {data}")
            
            latitude = float(data.get("latitude"))
            longitude = float(data.get("longitude"))
            accuracy = float(data.get("accuracy", 0))
            altitude = float(data.get("altitude", 0))

            # CRITICAL: Validate GPS coordinates - reject invalid Earth coordinates
            if not (-90 <= latitude <= 90):
                raise ValueError(f"Invalid latitude: {latitude} (must be -90 to 90)")
            if not (-180 <= longitude <= 180):
                raise ValueError(
                    f"Invalid longitude: {longitude} (must be -180 to 180)"
                )
            if accuracy < 0:
                raise ValueError(f"Invalid accuracy: {accuracy} (must be >= 0)")

            log.info(
                f"[pwn-companion] ✓ GPS received: {latitude:.{GPS_COORD_PRECISION}f}, {longitude:.{GPS_COORD_PRECISION}f} (±{accuracy:.{ACCURACY_FORMAT_PRECISION}f}m, alt:{altitude:.{ACCURACY_FORMAT_PRECISION}f}m)"
            )

            with self.lock:
                self.last_gps = {
                    "latitude": latitude,
                    "longitude": longitude,
                    "accuracy": accuracy,
                    "altitude": altitude,
                    "timestamp": time.time(),
                }

            # Send confirmation
            response = {
                "type": "gps_received",
                "lat": latitude,
                "lon": longitude,
            }
            await self._send_to_app(response)

            # TODO: Store GPS or trigger location-based actions

        except (ValueError, TypeError) as e:
            log.error(f"[pwn-companion] Invalid GPS data: {type(e).__name__}: {e}")
            await self._send_to_app({"type": "error", "message": "Invalid GPS data"})
        except Exception as e:
            log.error(
                f"[pwn-companion] Error in GPS handler: {type(e).__name__}: {e}",
                exc_info=True,
            )
            await self._send_to_app(
                {
                    "type": "error",
                    "message": f"GPS handler error: {str(e)[:HANDLER_ERROR_TRUNCATE_LENGTH]}",
                },
            )

    async def _handle_status_request(self):
        """Send pwnagotchi status to app"""
        with self.lock:
            uptime = int(time.time() - self.start_time)

        status = {
            "type": "status",
            "uptime": uptime,
            "connected": self.app_connected,
            "last_gps": self.last_gps,
            "last_command": self.last_command,
        }

        await self._send_to_app(status)

    async def _handle_image_request(self):
        """Handle image request from mobile app - fetch UI screenshot and send to app"""
        try:
            log.debug(f"[pwn-companion]  Image request received from app")

            # Fetch the UI screenshot from pwnagotchi web interface
            url = PWNAGOTCHI_UI_URL
            log.debug(f"[pwn-companion]  Fetching screenshot from {url}...")

            try:
                loop = asyncio.get_event_loop()
                response = await loop.run_in_executor(
                    None, lambda: requests.get(url, timeout=IMAGE_REQUEST_TIMEOUT)
                )
                log.debug(
                    f"[pwn-companion] ✓ Got response from UI: status={response.status_code}, size={len(response.content)} bytes"
                )
                response.raise_for_status()
            except requests.exceptions.Timeout as te:
                log.error(f"[pwn-companion] ❌ TIMEOUT fetching screenshot: {te}")
                await self._send_to_app(
                    {"type": "error", "message": f"UI server timeout: {str(te)}"},
                )
                return
            except requests.exceptions.ConnectionError as ce:
                log.error(
                    f"[pwn-companion] ❌ CONNECTION ERROR fetching screenshot: {ce}"
                )
                await self._send_to_app(
                    {"type": "error", "message": f"Cannot connect to UI at {url}"},
                )
                return
            except requests.exceptions.HTTPError as he:
                log.error(
                    f"[pwn-companion] ❌ HTTP ERROR {response.status_code} from UI"
                )
                await self._send_to_app(
                    {
                        "type": "error",
                        "message": f"UI returned HTTP {response.status_code}",
                    },
                )
                return
            except Exception as re:
                log.error(f"[pwn-companion] ❌ ERROR fetching screenshot: {re}")
                await self._send_to_app(
                    {
                        "type": "error",
                        "message": f"Failed to fetch screenshot: {str(re)}",
                    },
                )
                return

            # Ensure content is bytes before encoding
            if isinstance(response.content, bytearray):
                image_bytes = bytes(response.content)
            else:
                image_bytes = response.content

            log.debug(
                f"[pwn-companion] Converting {len(image_bytes)} bytes to base64..."
            )

            # Send image without caching (lightweight - no image hashing)
            image_data = base64.b64encode(image_bytes).decode("utf-8")
            log.debug(
                f"[pwn-companion] ✓ Encoded to base64: {len(image_data)} characters"
            )

            # Create response object with proper JSON serialization
            response_obj = {
                "type": "image",
                "data": image_data,
                "contentType": response.headers.get("content-type", "image/png"),
                "timestamp": int(time.time()),
            }

            log.debug(
                f"[pwn-companion]  Sending image message (payload size: {len(image_data)} chars)..."
            )
            await self._send_to_app(response_obj)

            log.debug(
                f"[pwn-companion] ✓✓✓ Image successfully sent to app ({len(image_bytes)} bytes)"
            )

        except Exception as e:
            log.error(
                f"[pwn-companion] ❌ CRITICAL ERROR in image handler: {type(e).__name__}: {e}",
                exc_info=True,
            )
            try:
                await self._send_to_app(
                    {
                        "type": "error",
                        "message": f"Image error: {str(e)[:HANDLER_ERROR_TRUNCATE_LENGTH]}",
                    },
                )
            except:
                log.error("[pwn-companion] Failed to send error response to app")

    async def _send_mode_update(self, mode: str):
        """Push the authoritative pwnagotchi mode (AUTO/MANUAL) to the app.

        Advances self._last_sent_mode ONLY after a confirmed send. The previous code
        set _last_sent_mode optimistically at the (non-loop) call site before the
        fire-and-forget send; if that send was dropped during a loop-teardown /
        reconnect window, the dedupe latched and the app stayed stuck on the wrong
        mode until the mode physically changed again. Confirming here makes it
        self-heal: a dropped push leaves _last_sent_mode stale, so the next on_ui_update
        tick simply retries.
        """
        ok = await self._send_to_app({
            "type": "status",
            "status": "running",
            "message": f"{mode.title()} mode",
            "device_name": self.device_name,
            "pwnagotchi_mode": mode,
            "timestamp": int(time.time()),
        })
        if ok:
            self._last_sent_mode = mode

    async def _send_to_app(self, data: dict) -> bool:
        """Send a JSON message to the connected companion app via WebSocket.

        Returns True only if the message was actually put on the wire, False if
        there was no connection or the send failed. Callers that must not advance
        state until a confirmed delivery (e.g. the mode-update dedupe) key off this.
        """
        if not self.app_websocket or not self.app_connected:
            return False
        try:
            message = json.dumps(data)
            # Bound the send: a half-open BT link would otherwise block here until the
            # keepalive ping-timeout fires, stalling the 1s image-push task for tens of
            # seconds. On timeout we treat the link as dead so discovery reconnects.
            await asyncio.wait_for(self.app_websocket.send(message), timeout=WEBSOCKET_SEND_TIMEOUT)
            log.debug(f"[pwn-companion] → Sent: type={data.get('type')}")
            return True
        except (asyncio.TimeoutError, websockets.ConnectionClosed):
            log.info("[pwn-companion] Send timed out / connection closed while sending")
            with self.lock:
                self.app_connected = False
                # Clear the socket too, otherwise the discovery-loop reconnect
                # guard (`not self.app_websocket`) never fires and we wedge.
                self.app_websocket = None
            return False
        except Exception as e:
            log.debug(f"[pwn-companion] Error sending to app: {type(e).__name__}: {e}")
            with self.lock:
                self.app_connected = False
                self.app_websocket = None
            return False

    async def _send_autotune_stats(self, agent):
        """Push auto-tune channel efficiency stats to the app."""
        try:
            if not self._channel_stats:
                return
            # Snapshot under the lock — on_epoch mutates this on another thread.
            with self.lock:
                channels_payload = {str(c): dict(v) for c, v in self._channel_stats.items()}
            msg = {
                "type": "autotune_stats",
                "autotune_channels": channels_payload,
                "autotune_best_channel": self._best_channel,
                "autotune_min_rssi": None,
                "timestamp": int(time.time()),
            }
            try:
                import pwnagotchi.plugins as _plugins
                autotune = _plugins.loaded.get("auto-tune")
                if autotune:
                    for attr in ("_min_rssi", "min_rssi"):
                        v = getattr(autotune, attr, None)
                        if v is not None:
                            msg["autotune_min_rssi"] = int(v)
                            break
            except Exception:
                pass
            await self._send_to_app(msg)
        except Exception as e:
            log.debug(f"[pwn-companion] Error sending autotune stats: {e}")

    def _wpa_sec_status(self):
        """(enabled, download_results) for the wpa-sec plugin, read from the live
        pwnagotchi config so the app can flag whether cracking is actually on.
        Returns (None, None) if the config can't be read."""
        try:
            import pwnagotchi
            ws = (pwnagotchi.config or {}).get("main", {}).get("plugins", {}).get("wpa-sec", {})
            return bool(ws.get("enabled", False)), bool(ws.get("download_results", False))
        except Exception:
            return None, None

    def _wpa_sec_api_url(self):
        try:
            import pwnagotchi
            ws = (pwnagotchi.config or {}).get("main", {}).get("plugins", {}).get("wpa-sec", {})
            return ws.get("api_url") or "https://wpa-sec.stanev.org"
        except Exception:
            return "https://wpa-sec.stanev.org"

    def _check_wpa_sec_online(self):
        """Is the wpa-sec service reachable? (It goes down sometimes — handy to know
        before waiting on cracks.) Any HTTP response = online; timeout/conn error =
        offline. Blocking — call via run_in_executor. Returns True/False."""
        try:
            requests.get(self._wpa_sec_api_url(), timeout=8)
            return True
        except Exception:
            return False

    async def _send_status_message(self, message: str):
        """Send status message to app (on connection or important events)"""
        # Try to read the pwnagotchi's current mood name
        pwn_mood = None
        try:
            if self._agent and hasattr(self._agent, "_mood"):
                mood = self._agent._mood
                pwn_mood = getattr(mood, "name", None) or getattr(mood, "_name", None)
                if pwn_mood is None and hasattr(mood, "__class__"):
                    pwn_mood = mood.__class__.__name__.upper()
        except Exception:
            pass
        # Fall back to the last emotion the device actually dispatched (on_bored etc.).
        if pwn_mood is None:
            pwn_mood = getattr(self, "_last_mood", None)

        ws_enabled, ws_download = self._wpa_sec_status()
        status_obj = {
            "type": "status",
            "message": message,
            "device_name": self.device_name,
            "status": "running",
            "ip": self.current_ip,
            "tether_interface": self.tether_interface,
            "pwnagotchi_mood": pwn_mood,
            "pwnagotchi_mode": self._current_mode,
            "wpa_sec_enabled": ws_enabled,
            "wpa_sec_download": ws_download,
            "wpa_sec_online": getattr(self, "_wpa_sec_online", None),
            "timestamp": int(time.time()),
        }
        # Advance the mode dedupe only if the status (which carries pwnagotchi_mode)
        # was actually delivered — a dropped send must not latch a stale mode.
        if await self._send_to_app(status_obj):
            self._last_sent_mode = self._current_mode
        log.info(f"[pwn-companion]  Status sent: {message} (IP: {self.current_ip}, mood: {pwn_mood})")

    def _emit_mood(self, mood_name):
        """Push a device emotion to the app (and cache it for status messages).

        This is the app's only feed for the device's *own* feelings — the emergent
        AI personality folds it into its trait vector, which shapes the pet's tone.
        """
        if not mood_name:
            return
        mood_name = str(mood_name).upper()
        self._last_mood = mood_name
        try:
            log.info(f"[pwn-companion]  Mood: {mood_name}")
            if self.app_connected and self.loop:
                self._schedule_on_loop(
                    self._send_to_app({
                        "type": "status",
                        "status": "running",
                        "message": f"Mood: {mood_name}",
                        "device_name": self.device_name,
                        "pwnagotchi_mood": mood_name,
                        "pwnagotchi_mode": self._current_mode,
                        "timestamp": int(time.time()),
                    }),
                    self.loop
                )
        except Exception as e:
            log.debug(f"[pwn-companion] Error emitting mood: {e}")

    # Real pwnagotchi emotion hooks (dispatched by automata: 'grateful', 'bored',
    # 'sad', 'angry', 'excited', 'lonely'). These are the ACTUAL source of the
    # device's mood — the old on_mood/agent._mood path never fired because
    # pwnagotchi has no generic 'mood' event. Signature-agnostic (*args) since
    # different versions pass the agent (or nothing) alongside the event.
    def on_grateful(self, *args): self._emit_mood("GRATEFUL")
    def on_lonely(self, *args):   self._emit_mood("LONELY")
    def on_bored(self, *args):    self._emit_mood("BORED")
    def on_sad(self, *args):      self._emit_mood("SAD")
    def on_angry(self, *args):    self._emit_mood("ANGRY")
    def on_excited(self, *args):  self._emit_mood("EXCITED")

    def on_mood(self, agent, mood):
        """Legacy/generic mood hook (some forks) — delegate to the emitter."""
        self._agent = agent
        try:
            mood_name = (
                getattr(mood, "name", None)
                or getattr(mood, "_name", None)
                or getattr(mood, "__class__", type(mood)).__name__.upper()
                or str(mood)
            )
            self._emit_mood(mood_name)
        except Exception as e:
            log.debug(f"[pwn-companion] Error in on_mood: {e}")

    def on_manual_mode(self, agent):
        """Called when pwnagotchi switches to MANUAL mode — notify app to pause learning"""
        self._agent = agent
        self._current_mode = "MANUAL"
        log.info("[pwn-companion]  Mode → MANUAL (no scanning)")
        if self.app_connected and self.loop:
            self._schedule_on_loop(self._send_mode_update("MANUAL"), self.loop)

    def on_auto_mode(self, agent):
        """Called when pwnagotchi switches to AUTO mode — notify app to resume scanning+learning"""
        self._agent = agent
        self._current_mode = "AUTO"
        log.info("[pwn-companion]  Mode → AUTO (scanning active)")
        if self.app_connected and self.loop:
            self._schedule_on_loop(self._send_mode_update("AUTO"), self.loop
            )

    # ============================================================
    #  AI Event Triggers - Call these to send WiFi events to AI
    # ============================================================

    def trigger_handshakes_event(self, count: int, network_name: str, security: str = "WPA2"):
        """
        Trigger AI personality response for handshake capture

        Usage:
            self.trigger_handshakes_event(5, "StarbucksWiFi", "WPA2")
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_handshakes_captured(count, network_name, security),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering handshake event: {e}")

    def trigger_network_discovered_event(self, ssid: str, bssid: str = None, security: str = "Unknown",
                                        signal_strength: int = -50, channel: int = 1):
        """
        Trigger AI personality response for network discovery

        Usage:
            self.trigger_network_discovered_event("MyNetwork", "AA:BB:CC:DD:EE:FF", "WPA2", -45, 6)
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_network_discovered(ssid, bssid, security, signal_strength, channel),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering network discovery event: {e}")

    def trigger_connection_success_event(self, network_name: str, duration: float = 0.0):
        """
        Trigger AI personality response for successful connection

        Usage:
            self.trigger_connection_success_event("TargetNetwork", 3.5)
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_connection_success(network_name, duration),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering connection success event: {e}")

    def trigger_connection_failure_event(self, network_name: str, reason: str = "Unknown"):
        """
        Trigger AI personality response for connection failure

        Usage:
            self.trigger_connection_failure_event("TargetNetwork", "Weak signal")
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_connection_failure(network_name, reason),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering connection failure event: {e}")

    def trigger_anomaly_detected_event(self, anomaly_type: str, details: dict = None):
        """
        Trigger AI personality response for anomaly detection

        Usage:
            self.trigger_anomaly_detected_event("deauth_spike", {"count": 25, "source": "unknown"})
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_anomaly_detected(anomaly_type, details or {}),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering anomaly event: {e}")

    def trigger_high_value_target_event(self, network_name: str, reason: str = ""):
        """
        Trigger AI personality response for high-value target detection

        Usage:
            self.trigger_high_value_target_event("CompanyHQ-5GHz", "WPA3-Enterprise")
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_high_value_target(network_name, reason),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering high-value target event: {e}")

    def trigger_scan_complete_event(self, networks_found: int, duration: float = 0.0):
        """
        Trigger AI personality response for scan completion

        Usage:
            self.trigger_scan_complete_event(15, 5.2)
        """
        if self.event_broadcaster and self.app_connected:
            try:
                self._schedule_on_loop(
                    self.event_broadcaster.on_scan_complete(networks_found, duration),
                    self.loop
                )
            except Exception as e:
                log.error(f"[pwn-companion] Error triggering scan complete event: {e}")

    def _stop_client_discovery(self, wait: bool = True):
        """Stop discovery and close the WebSocket connection.

        This runs on the Pwnagotchi main thread, NOT the discovery thread that
        owns self.loop. We must never call run_until_complete()/loop.stop() on a
        loop that is already running in another thread — that raises RuntimeError
        and corrupts loop state. Instead we:
          1. flip self.discovering so _discovery_loop breaks out, and
          2. close the websocket *on the loop's own thread* to unblock the
             _listen_to_app `async for`.
        _run_discovery's finally block then closes the loop cleanly itself.

        wait=True  (plugin unload): block until the socket closes and the thread
                   joins, so teardown is clean.
        wait=False (BT-tether drop): fire-and-forget. BT/WiFi share one radio, so
                   drops are frequent, and blocking up to ~2×THREAD_SHUTDOWN_TIMEOUT
                   here would freeze the pwnagotchi main loop (UI/epoch/recon) on
                   every drop. We just signal stop and schedule the close; the
                   discovery loop's own finally tears down the socket + loop.
        """
        self.discovering = False

        # Close the websocket on the loop's own thread to unblock _listen_to_app.
        loop = self.loop
        ws = self.app_websocket
        if (
            loop is not None
            and not loop.is_closed()
            and loop.is_running()
            and ws is not None
        ):
            try:
                future = asyncio.run_coroutine_threadsafe(ws.close(), loop)
                if wait:
                    future.result(timeout=THREAD_SHUTDOWN_TIMEOUT)
                # else: fire-and-forget; do not block the main loop on a flaky link.
            except Exception as e:
                log.debug(f"[pwn-companion] Error closing websocket during shutdown: {e}")

        if wait:
            # Wait for the discovery thread to wind down; its finally closes the loop.
            if self.ws_thread:
                self.ws_thread.join(timeout=THREAD_SHUTDOWN_TIMEOUT)
                if self.ws_thread.is_alive():
                    log.warning(
                        "[pwn-companion] Discovery thread did not stop within timeout"
                    )
            self.app_websocket = None

        # Either way, mark ourselves disconnected immediately so nothing keeps sending.
        # In the non-blocking path the discovery loop's finally nulls app_websocket.
        self.app_connected = False
        log.info(f"[pwn-companion] Client discovery stop requested (wait={wait})")
