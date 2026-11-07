#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — every page of the app, at Galaxy A37 density. ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS FOR. The app opened straight onto the one served page and its Pages, API, Home
# and Configs vanished; string checks passed because the strings were still in the source. This starts the
# REAL server (server/examples/demo.rs: the real `start`, the real build.json ports, a stub of the two
# Android hooks) and loads the shell in headless Chrome with MOBILE emulation at the owner's phone
# (Samsung Galaxy A37, 384 x 832 dp, device pixel ratio 2.8125, a 1080 x 2340 px screenshot) through
# test/render-pages.mjs:
#
#   P   EVERY TAB DRAWS. The app opens on Home; Pages, API, Home, Configs (About, Engines, Links) each
#       draw, do not scroll sideways, and start BELOW the shell's top row.
#   F   THE FILE VIEW IS THE FILES TAB, UNCHANGED: the embedded page is the Linux File Browser (tree,
#       toolbar), sits between the top row and the bottom nav, carries no custom nav.
#   L   LINKS ASK. A link tapped in the file view's rendered Markdown, and a served-page link on Pages,
#       open the chooser with exactly "Open in browser" and "Open embedded", every choice >= 40 dp tall;
#       Open embedded loads the page in this WebView (private name rewritten to loopback).
#   R   NOTHING LEAVES THE PHONE.
#
# One PNG per page lands in $SHOTS_DIR (default: a temp dir, printed); the ship workflow uploads them as
# the "c3-webserver-shots" artifact. NO SKIP: a machine without Chrome, node, cargo or python3 is an
# ERROR, not a pass.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

find_chrome() {
  local c
  for c in "${CHROME:-}" google-chrome google-chrome-stable chromium chromium-browser; do
    [ -n "$c" ] && command -v "$c" >/dev/null 2>&1 && { command -v "$c"; return 0; }
  done
  for c in /opt/pw-browsers/chromium-*/chrome-linux/chrome /opt/pw-browsers/chromium-*/chrome-linux64/chrome; do
    [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}
CHROME_BIN="$(find_chrome)" || { echo "ERROR no Chrome/Chromium on this machine (set CHROME=…): the A37 render cannot be taken, and an untaken render is not a pass"; exit 1; }
for t in cargo node python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "ERROR $t absent — a verdict from a missing tool is not a verdict"; exit 1; }
done

WORK="$(mktemp -d)"
SHOTS="${SHOTS_DIR:-$WORK/shots}"; mkdir -p "$SHOTS"
SRV_PID=""
cleanup() { [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

ROOT="$WORK/drive"; mkdir -p "$ROOT/Documents" "$ROOT/DCIM"
printf '# Trip notes\n\nA **bold** line, `inline code` and a [site link](https://example.org/).\n\n| stop | km |\n|------|----|\n| A | 12 |\n' > "$ROOT/Documents/readme.md"
printf '{"device":"A37","ok":true}\n' > "$ROOT/Documents/data.json"
printf 'photo\n' > "$ROOT/DCIM/img.txt"; printf 'x\n' > "$ROOT/.hidden"

echo "── cloud-c3-webserver pages at Galaxy A37 density (384x832 dp @ 2.8125, 1080x2340 px) ──"
( cd "$APP/server" && CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$WORK/target}" cargo build -q --example demo 2>"$WORK/build.log" ) \
  || { echo "ERROR the demo server did not build:"; tail -20 "$WORK/build.log"; exit 1; }
"${CARGO_TARGET_DIR:-$WORK/target}/debug/examples/demo" "$ROOT" >"$WORK/server.log" 2>&1 &
SRV_PID=$!
for _ in $(seq 1 100); do grep -q '^READY' "$WORK/server.log" 2>/dev/null && break; sleep 0.1; done
read -r _ DRIVE DIAG < <(grep -m1 '^READY' "$WORK/server.log") || { echo "ERROR the demo server did not start:"; cat "$WORK/server.log"; exit 1; }

OUT="$(node "$APP/test/render-pages.mjs" "$CHROME_BIN" "http://127.0.0.1:$DRIVE/" "$SHOTS" readme.md Documents 2>"$WORK/render.err")"
RC=$?
echo "$OUT" | python3 -c '
import json, sys
for l in sys.stdin:
    l = l.strip()
    if not l.startswith("{"): continue
    r = json.loads(l)
    print("  %s  %s %s" % ("PASS" if r["ok"] else "FAIL", r["id"], r["msg"]))
'
[ "$RC" -eq 0 ] || { FAILURES=$((FAILURES + 1)); tail -5 "$WORK/render.err"; }
grep -q '^OPENED http://emulated.app/' "$WORK/server.log" && pass "L8 Open in browser reached the opener with the tapped served-page URL" || fail "L8 Open in browser never reached the opener"

echo "  shots: $SHOTS"; ls "$SHOTS" | sed 's/^/    /'
echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-pages: all green ──"; else echo "── test-c3-webserver-pages: $FAILURES FAILED ──"; exit 1; fi
