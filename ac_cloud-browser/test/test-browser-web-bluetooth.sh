#!/bin/sh
# ╔═════════════════════════════════════════════════════════════════╗
# ║ #1222 Web Bluetooth in Cloud Browser: navigator.bluetooth over a ║
# ║ JS bridge to libs:bluetooth                                      ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# WebView has no Web Bluetooth. What this certifies (the native core's rules are JVM-tested in WebBluetoothCoreTest):
#   W1 the polyfill, run under node against a fake page environment and a contract-driven fake native side with a fake adapter
#      (ble_polyfill_harness.js + test/web_bluetooth_page.html, a local page whose click calls requestDevice): the whole
#      requestDevice -> connect -> getPrimaryService -> getCharacteristic -> readValue / writeValue -> startNotifications ->
#      characteristicvaluechanged flow, the Chrome rules (secure context, user gesture, argument TypeErrors, service allow-list),
#      and every message / reply checked against assets/browser/web-bluetooth-contract.json;
#   W2 the harness can fail: a polyfill without the gesture check, without the secure-context check, or sending a renamed argument
#      is caught (a tester that cannot go red proves nothing);
#   W3 one contract: the ops of the contract, of the polyfill and of the native core are the same set;
#   W4 the host: the bridge reads the origin from the WebView on the main thread (never from the page), installs at document start
#      with a page-start fallback, drops what a page connected when it navigates, and the fragment wires it;
#   W5 the native rules are in the core: secure origin, user gesture, service allow-list, origin-scoped grants, private-tab grants
#      in memory, a native chooser the user picks from, Android's Bluetooth permission asked in context.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$(dirname "$0")"
LIB="$ROOT/ab_cloud-libs-shared/libs/browser/src/main"
JS="$LIB/assets/browser/web_bluetooth.js"
CONTRACT="$ROOT/ab_cloud-libs-shared/libs/browser/contract/web-bluetooth-contract.json"
KT="$LIB/java/com/diegonmarcos/superapp/browser"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$JS" "$CONTRACT" "$KT/WebBluetooth.kt" "$KT/WebBluetoothCore.kt" "$KT/BrowserHostFragment.kt" web_bluetooth_page.html ble_polyfill_harness.js; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }
done
command -v node >/dev/null 2>&1 || { echo "  ABORT: node missing"; exit 2; }

echo "== W1: the polyfill against the contract-driven fake native side =="
R=$(node ble_polyfill_harness.js "$JS" "$CONTRACT" web_bluetooth_page.html 2>&1 || true)
N=$(printf '%s\n' "$R" | grep -c '^ok ' || true)
B=$(printf '%s\n' "$R" | grep -c '^bad ' || true)
printf '%s\n' "$R" | grep '^bad ' | sed 's/^/  /' || true
[ "$N" -ge 33 ] && [ "$B" -eq 0 ] && ok "W1 $N assertions pass, none fail" || bad "W1 $N ok, $B bad (need 33+ ok and no bad)"

echo "== W2: the harness goes red on a broken polyfill =="
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
run_mut() { # name, sed expression
  sed "$2" "$JS" > "$T/p.js"
  if cmp -s "$JS" "$T/p.js"; then bad "W2 mutation '$1' changed nothing (the tester's anchor drifted)"; return; fi
  if node ble_polyfill_harness.js "$T/p.js" "$CONTRACT" web_bluetooth_page.html 2>&1 | grep -q '^bad '; then ok "W2 caught: $1"; else bad "W2 NOT caught: $1"; fi
}
run_mut "no user-gesture check" 's/if (!hasGesture()) return Promise.reject/if (false) return Promise.reject/'
run_mut "no secure-context check" 's/if (!root.isSecureContext) return;/ /'
run_mut "renamed argument on the wire" "s/a.characteristic = resolve('characteristics', characteristic)/a.char = resolve('characteristics', characteristic)/"
run_mut "events not delivered" "s/emit(c, 'characteristicvaluechanged')/void 0/"

echo "== W3: the ops of the contract, the polyfill and the core are one set =="
python3 - "$CONTRACT" "$JS" "$KT/WebBluetoothCore.kt" <<'PY' && ok "W3 the three op sets are equal" || bad "W3 the op sets differ"
import json, re, sys
c = set(json.load(open(sys.argv[1]))['ops'])
js = set(re.findall(r"call\('(\w+)'", open(sys.argv[2]).read()))
kt = set(re.findall(r'^\s+"(\w+)" ->', open(sys.argv[3]).read().split('fun handle(')[1].split('/** The page went away')[0], re.M)) - {'else'}
print('contract', sorted(c)); print('polyfill', sorted(js)); print('core    ', sorted(kt))
sys.exit(0 if c == js == kt else 1)
PY

echo "== W4: the host =="
W="$KT/WebBluetooth.kt"; F="$KT/BrowserHostFragment.kt"
grep -q 'core.handle(WebBluetoothCore.originOf(w.url), text)' "$W" && grep -q 'main.post' "$W" && ok "W4 the origin is read from the WebView on the main thread" || bad "W4 the origin does not come from the WebView"
! grep -qE 'optString\("origin"\)|getString\("origin"\)' "$KT/WebBluetoothCore.kt" && ok "W4 the page never supplies its own origin (no origin field is read from a request)" || bad "W4 the page supplies an origin"
grep -q 'WebViewFeature.DOCUMENT_START_SCRIPT' "$W" && grep -q 'addDocumentStartJavaScript' "$W" && grep -q 'w.evaluateJavascript(script(), null)' "$W" && ok "W4 installed at document start, with a page-start fallback" || bad "W4 the install paths are missing"
grep -q '@JavascriptInterface' "$W" && grep -q 'JS_BRIDGE = "AndroidWebBluetooth"' "$W" && grep -q 'AndroidWebBluetooth' "$JS" && ok "W4 one JS interface, named the same on both sides" || bad "W4 the interface name drifted"
grep -q 'webBluetooth.attach(this, tab.isPrivate)' "$F" && grep -q 'webBluetooth.onPageStarted(it)' "$F" && grep -q 'webBluetooth.detach(it)' "$F" && ok "W4 BrowserHostFragment attaches, re-injects on navigation and detaches" || bad "W4 the fragment does not wire it"
grep -q 'done(false); permsNeeded(perms.toList())' "$F" && ! grep -q 'RequestMultiplePermissions' "$F" && ok "W4 a missing Bluetooth permission opens the Permissions banner (#1274), never Android's dialog from here" || bad "W4 the browser asks Android itself"
grep -q '__ALIASES__' "$JS" && grep -q 'catalog.services' "$W" && ok "W4 the Web Bluetooth names come from libs:bluetooth's catalog, not a table in the polyfill" || bad "W4 the alias table is not data"

echo "== W5: the native rules =="
C="$KT/WebBluetoothCore.kt"
grep -q 'if (!isSecure(origin))' "$C" && grep -q 'Must be handling a user gesture' "$C" && ok "W5 secure origin and user gesture are enforced natively too" || bad "W5 the native side trusts the page"
grep -q 'allowedService(g, ' "$C" && grep -q "Add the service UUID to 'optionalServices'" "$C" && ok "W5 a page reaches only the services it named" || bad "W5 no service allow-list"
grep -q 'grants.find(origin' "$C" && grep -q 'fun deviceId(origin: String, address: String)' "$C" && ok "W5 grants and device ids are origin-scoped" || bad "W5 grants are not origin-scoped"
grep -q 'chooser.show(req' "$C" && grep -q 'User cancelled the requestDevice() chooser' "$C" && ok "W5 the user picks in a native chooser (or cancels)" || bad "W5 no chooser"
grep -q 'if (privateTab) WebBleGrants(MemoryKv())' "$W" && ok "W5 a private tab's grants never reach disk" || bad "W5 a private tab persists grants"
grep -q 'fun reset()' "$C" && grep -q 'session.disconnect(it)' "$C" && ok "W5 leaving the page disconnects what it connected" || bad "W5 reset does not disconnect"
grep -q 'Web Serial' "$W" && grep -q 'WebUSB' "$W" && grep -q 'Web NFC' "$W" && ok "W5 the Web Serial / USB / NFC feasibility is written down" || bad "W5 the feasibility note is missing"

echo; echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
