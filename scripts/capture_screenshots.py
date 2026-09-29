#!/usr/bin/env python3
"""Capture dashboard screenshots via CDP Fetch-domain proxying.

Why this exists: in sandboxed environments the system Chromium blocks
navigation to localhost (Local Network Access checks) and has no usable
headless --screenshot path. This script launches Chromium with remote
debugging, intercepts every request via the Fetch domain, fulfills
localhost/127.0.0.1 requests from Python (urllib, proxy bypassed), answers
CORS preflights directly, and screenshots the rendered pages.

Usage: python3 scripts/capture_screenshots.py
Requires: the platform (:8080), worker (:8001), and dashboard (:5173) running;
          pip packages: websocket-client; chromium at /opt/meta-chromium/chrome
          (override with CHROME_BIN env).
Writes: screenshots/dashboard-{overview,versions,compare,decisions}.png
"""
import os
import json, subprocess, time, urllib.request, urllib.error, urllib.parse, websocket, base64, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOT_DIR = os.path.join(ROOT, "screenshots")
os.makedirs(SHOT_DIR, exist_ok=True)
PAGES = [
    ("http://127.0.0.1:5173/", os.path.join(SHOT_DIR, "dashboard-overview.png")),
    ("http://127.0.0.1:5173/versions", os.path.join(SHOT_DIR, "dashboard-versions.png")),
    ("http://127.0.0.1:5173/compare", os.path.join(SHOT_DIR, "dashboard-compare.png")),
    ("http://127.0.0.1:5173/decisions", os.path.join(SHOT_DIR, "dashboard-decisions.png")),
]

def pyfetch(url, headers=None):
    h = {"User-Agent": "cdp-shot"}
    if headers:
        for k, v in headers.items():
            if k.lower() not in ("host", "content-length", "connection"):
                h[k] = v
    req = urllib.request.Request(url, headers=h)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        r = opener.open(req, timeout=10)
        return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers or {}), e.read()
    except Exception as e:
        return 502, {"Content-Type": "text/plain"}, f"fetch failed: {e}".encode()

chrome = subprocess.Popen([
    os.environ.get("CHROME_BIN", "/opt/meta-chromium/chrome"), "--headless", "--no-sandbox", "--no-proxy-server",
    "--disable-gpu", "--disable-dev-shm-usage",
    "--remote-debugging-port=19227", "--remote-allow-origins=*",
    "--user-data-dir=/tmp/cdp-profile6",
    "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(3)
try:
    tabs = json.load(urllib.request.urlopen("http://127.0.0.1:19227/json/list"))
    ws = websocket.create_connection(tabs[0]["webSocketDebuggerUrl"])
    seq = [0]
    def send(method, params=None):
        seq[0] += 1
        ws.send(json.dumps({"id": seq[0], "method": method, "params": params or {}}))
        return seq[0]
    def handle(m):
        if m.get("method") == "Fetch.requestPaused":
            p = m["params"]; rid, url = p["requestId"], p["request"]["url"]
            method = p["request"].get("method", "GET")
            if method == "OPTIONS":
                h = [{"name": "Access-Control-Allow-Origin", "value": "*"},
                     {"name": "Access-Control-Allow-Headers", "value": "*"},
                     {"name": "Access-Control-Allow-Methods", "value": "*"},
                     {"name": "Access-Control-Max-Age", "value": "86400"}]
                send("Fetch.fulfillRequest", {"requestId": rid, "responseCode": 200,
                      "responseHeaders": h, "body": ""})
            elif url.startswith(("http://127.0.0.1", "http://localhost")):
                st, hdrs, body = pyfetch(url, p["request"].get("headers"))
                urlparse = urllib.parse.urlparse
                if st == 404 and "." not in urlparse(url).path.split("/")[-1]:
                    # SPA route: vite dev has no fallback for direct loads; serve index.html
                    st, hdrs, body = pyfetch("http://127.0.0.1:5173/")
                h = [{"name": k, "value": v} for k, v in hdrs.items()
                     if k.lower() not in ("content-length", "transfer-encoding")]
                h += [{"name": "Access-Control-Allow-Origin", "value": "*"},
                      {"name": "Access-Control-Allow-Headers", "value": "*"},
                      {"name": "Access-Control-Allow-Methods", "value": "*"}]
                send("Fetch.fulfillRequest", {"requestId": rid, "responseCode": st,
                      "responseHeaders": h, "body": base64.b64encode(body).decode()})
            else:
                send("Fetch.continueRequest", {"requestId": rid})
    send("Fetch.enable", {"patterns": [{"urlPattern": "*"}]})
    send("Page.enable")
    ws.settimeout(20)
    for url, out in PAGES:
        send("Page.navigate", {"url": url})
        end = time.time() + 14
        while time.time() < end:
            try: handle(json.loads(ws.recv()))
            except Exception: break
        i = send("Page.captureScreenshot", {"format": "png"})
        end = time.time() + 15; data = None
        while time.time() < end:
            try: m = json.loads(ws.recv())
            except Exception: break
            if m.get("id") == i:
                data = base64.b64decode(m["result"]["data"]); break
            handle(m)
        if data:
            open(out, "wb").write(data)
            print("wrote", out, len(data), flush=True)
    ws.close()
finally:
    chrome.terminate()
