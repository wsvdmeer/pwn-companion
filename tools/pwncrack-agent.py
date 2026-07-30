#!/usr/bin/env python3
"""
pwncrack-agent — GPU crack offload for PwnCompanion.

Runs on your PC/laptop (the one with the GPU + hashcat). PwnCompanion sends a captured
handshake's hashcat-22000 line here; this runs `hashcat -m 22000` against your wordlist on the
GPU (millions/s vs the phone's hundreds) and hands the cracked passphrase back to the app.

Standard library only — you just need Python 3.8+ and hashcat on PATH. No pip installs.

    python pwncrack-agent.py --token mysecret --wordlist rockyou.txt

Then in the app: Settings → set the PC endpoint to  http://<this-pc-ip>:9393  and the same token.

Security: this executes hashcat on whatever it's sent, so it's token-gated and meant for your
own LAN. Don't expose the port to the internet.

Protocol (tiny JSON-over-HTTP, so the app needs no extra libs):
  POST /crack   {"token","hash"}                 -> {"job": "<id>"}
  GET  /status?token=..&job=<id>                 -> {"state": queued|running|done|nomatch|error,
                                                     "password": "<pw>"?, "detail": "..."}
  GET  /ping?token=..                            -> {"ok": true, "hashcat": "<version>"}
"""
import argparse
import json
import os
import re
import subprocess
import tempfile
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

CFG = {}                       # filled from argparse
JOBS = {}                      # id -> dict(state, password, detail)
JOBS_LOCK = threading.Lock()

# A hashcat-22000 line: WPA*01*... (PMKID) or WPA*02*... (EAPOL). Validated before we shell out.
HASH_RE = re.compile(r"^WPA\*0[12]\*[0-9a-fA-F*]+\*?$")


def _hashcat_version():
    try:
        out = subprocess.run([CFG["hashcat"], "--version"], capture_output=True, text=True, timeout=15)
        return out.stdout.strip() or "unknown"
    except Exception as e:
        return f"unavailable: {e}"


def _run_job(job_id, hash_line):
    """Run hashcat for one hash in a temp dir; record the result under JOBS[job_id]."""
    def setstate(**kw):
        with JOBS_LOCK:
            JOBS[job_id].update(kw)

    setstate(state="running")
    try:
        with tempfile.TemporaryDirectory(prefix="pwncrack_") as d:
            hc = os.path.join(d, "h.hc22000")
            out = os.path.join(d, "out.txt")
            pot = os.path.join(d, "pot.potfile")
            with open(hc, "w") as f:
                f.write(hash_line + "\n")
            # -a 0 dict attack; --outfile-format 2 = just the plaintext; isolated potfile so runs
            # don't interfere. Exit 0 = cracked, 1 = exhausted (no match), else error.
            cmd = [CFG["hashcat"], "-m", "22000", "-a", "0", "--quiet",
                   "--potfile-path", pot, "-o", out, "--outfile-format", "2",
                   hc, CFG["wordlist"]]
            proc = subprocess.run(cmd, capture_output=True, text=True, cwd=d,
                                  timeout=CFG["timeout"])
            pw = ""
            if os.path.isfile(out):
                with open(out) as f:
                    pw = f.readline().strip()
            if pw:
                setstate(state="done", password=pw)
            elif proc.returncode in (0, 1):
                setstate(state="nomatch", detail="exhausted the wordlist, no match")
            else:
                setstate(state="error", detail=(proc.stderr or proc.stdout or "hashcat error")[:500])
    except subprocess.TimeoutExpired:
        setstate(state="error", detail="hashcat timed out")
    except Exception as e:
        setstate(state="error", detail=str(e)[:500])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # quieter default logging
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self, token):
        return token == CFG["token"]

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        token = (q.get("token") or [""])[0]
        if not self._authed(token):
            return self._send(403, {"error": "bad token"})
        if u.path == "/ping":
            return self._send(200, {"ok": True, "hashcat": _hashcat_version()})
        if u.path == "/status":
            job = (q.get("job") or [""])[0]
            with JOBS_LOCK:
                st = JOBS.get(job)
            if st is None:
                return self._send(404, {"error": "no such job"})
            return self._send(200, st)
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        if urlparse(self.path).path != "/crack":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._send(400, {"error": "bad json"})
        if not self._authed(data.get("token", "")):
            return self._send(403, {"error": "bad token"})
        hash_line = (data.get("hash") or "").strip()
        if not HASH_RE.match(hash_line):
            return self._send(400, {"error": "not a WPA*01/WPA*02 hash line"})
        job_id = uuid.uuid4().hex[:12]
        with JOBS_LOCK:
            JOBS[job_id] = {"state": "queued"}
        threading.Thread(target=_run_job, args=(job_id, hash_line), daemon=True).start()
        return self._send(200, {"job": job_id})


def main():
    ap = argparse.ArgumentParser(description="PwnCompanion GPU crack offload agent")
    ap.add_argument("--token", required=True, help="shared secret the app must send")
    ap.add_argument("--wordlist", required=True, help="path to the wordlist for hashcat")
    ap.add_argument("--hashcat", default="hashcat", help="hashcat binary (default: on PATH)")
    ap.add_argument("--host", default="0.0.0.0", help="bind address (default: all LAN interfaces)")
    ap.add_argument("--port", type=int, default=9393)
    ap.add_argument("--timeout", type=int, default=6 * 3600, help="max seconds per crack job")
    args = ap.parse_args()
    CFG.update(vars(args))
    if not os.path.isfile(CFG["wordlist"]):
        raise SystemExit(f"wordlist not found: {CFG['wordlist']}")
    print(f"pwncrack-agent on {args.host}:{args.port}  hashcat={_hashcat_version()}")
    print(f"wordlist={CFG['wordlist']}  timeout={CFG['timeout']}s")
    print(f"app endpoint → http://<this-pc-ip>:{args.port}   (same --token in the app)")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
