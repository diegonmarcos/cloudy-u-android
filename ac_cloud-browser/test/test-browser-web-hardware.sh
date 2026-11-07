#!/bin/sh
# ╔═════════════════════════════════════════════════════════════════╗
# ║ #1225 Web Serial, WebUSB and Web NFC in Cloud Browser:           ║
# ║ navigator.serial / navigator.usb / NDEFReader over a JS bridge   ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# WebView has none of the three. What this certifies (the native core's rules are JVM-tested in WebHardwareCoreTest):
#   H1 the polyfill, run under node against a fake page environment and a contract-driven fake native side with a fake port, device
#      and tag (hw_polyfill_harness.js + test/web_hardware_page.html, a local page whose clicks call requestPort, requestDevice and
#      NDEFReader.scan / write): the whole open -> write -> read, claim -> transfer -> control and scan -> reading -> write flows, the
#      Chrome rules (secure context, user gesture, argument TypeErrors, locked-stream close, abort), and every message / reply / event
#      checked against assets/browser/web-hardware-contract.json;
#   H2 the harness can fail: a polyfill without the gesture check, without the secure-context check, sending a renamed argument,
#      dropping serial data, not dispatching tag readings, or closing a locked port is caught (a tester that cannot go red proves nothing);
#   H3 one contract: the ops of the contract, of the polyfill and of the native core are the same set;
#   H4 the host: the bridge reads the origin from the WebView on the main thread (never from the page), installs at document start with
#      a page-start fallback, drops what a page opened when it navigates, and the fragment wires it;
#   H5 the native rules are in the core: secure origin, user gesture, origin-scoped grants (one store per API, declared in
#      fleet-config.json), a native Compose chooser / prompt, protected USB classes, private-tab grants in memory, Android's USB permission;
#   H6 the backends are the phone's own APIs (UsbManager, USB CDC ACM, NfcAdapter reader mode) and the product code carries no mock data.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$(dirname "$0")"
LIB="$ROOT/ab_cloud-libs-shared/libs/browser/src/main"
JS="$LIB/assets/browser/web_hardware.js"
CONTRACT="$ROOT/ab_cloud-libs-shared/libs/browser/contract/web-hardware-contract.json"
KT="$LIB/java/com/diegonmarcos/superapp/browser"
FLEET="$ROOT/ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$JS" "$CONTRACT" "$KT/WebHardware.kt" "$KT/WebHardwareCore.kt" "$KT/WebHardwareModel.kt" "$KT/WebHardwareAndroid.kt" "$KT/WebHardwareChooser.kt" "$KT/CdcAcmSerial.kt" \
         "$KT/BrowserHostFragment.kt" "$FLEET" web_hardware_page.html hw_polyfill_harness.js; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }
done
command -v node >/dev/null 2>&1 || { echo "  ABORT: node missing"; exit 2; }

echo "== H1: the polyfill against the contract-driven fake native side =="
R=$(node hw_polyfill_harness.js "$JS" "$CONTRACT" web_hardware_page.html 2>&1 || true)
N=$(printf '%s\n' "$R" | grep -c '^ok ' || true)
B=$(printf '%s\n' "$R" | grep -c '^bad ' || true)
printf '%s\n' "$R" | grep '^bad ' | sed 's/^/  /' || true
[ "$N" -ge 50 ] && [ "$B" -eq 0 ] && ok "H1 $N assertions pass, none fail" || bad "H1 $N ok, $B bad (need 50+ ok and no bad)"

echo "== H2: the harness goes red on a broken polyfill =="
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
run_mut() { # name, sed expression
  sed "$2" "$JS" > "$T/p.js"
  if cmp -s "$JS" "$T/p.js"; then bad "H2 mutation '$1' changed nothing (the tester's anchor drifted)"; return; fi
  if node hw_polyfill_harness.js "$T/p.js" "$CONTRACT" web_hardware_page.html 2>&1 | grep -q '^bad '; then ok "H2 caught: $1"; else bad "H2 NOT caught: $1"; fi
}
run_mut "no user-gesture check" 's/if (!hasGesture()) return Promise.reject/if (false) return Promise.reject/'
run_mut "no secure-context check" 's/if (!root.isSecureContext) return;/ /'
run_mut "renamed argument on the wire" 's/call(.usb.transferOut., { device: this.__id, endpointNumber: ep | 0,/call("usb.transferOut", { device: this.__id, endpoint: ep | 0,/'
run_mut "serial data not delivered" 's/p.__ctl.enqueue(fromHex(m.value))/void 0/'
run_mut "tag readings not dispatched" 's/this.dispatchEvent(ev);/void 0;/'
run_mut "close of a locked port allowed" 's/(this.readable \&\& this.readable.locked) || (this.writable \&\& this.writable.locked)/false/'
run_mut "usb events not delivered" "s/if (usbApi) emit(usbApi, 'disconnect', { device: u });/void 0;/"

echo "== H3: the ops of the contract, the polyfill and the core are one set =="
python3 - "$CONTRACT" "$JS" "$KT/WebHardwareCore.kt" <<'PY' && ok "H3 the three op sets are equal" || bad "H3 the op sets differ"
import json, re, sys
c = set(json.load(open(sys.argv[1]))['ops'])
js = set(re.findall(r"call\('([\w.]+)'", open(sys.argv[2]).read()))
kt = set(re.findall(r'^\s+"([\w.]+)" ->', open(sys.argv[3]).read().split('fun handle(')[1].split('/** The page went away')[0], re.M)) - {'else'}
print('contract', sorted(c)); print('polyfill', sorted(js)); print('core    ', sorted(kt))
sys.exit(0 if c == js == kt else 1)
PY

echo "== H4: the host =="
W="$KT/WebHardware.kt"; F="$KT/BrowserHostFragment.kt"
grep -q 'core.handle(WebBluetoothCore.originOf(w.url), text)' "$W" && grep -q 'main.post' "$W" && ok "H4 the origin is read from the WebView on the main thread" || bad "H4 the origin does not come from the WebView"
! grep -qE 'optString\("origin"\)|getString\("origin"\)' "$KT/WebHardwareCore.kt" && ok "H4 the page never supplies its own origin (no origin field is read from a request)" || bad "H4 the page supplies an origin"
grep -q 'WebViewFeature.DOCUMENT_START_SCRIPT' "$W" && grep -q 'addDocumentStartJavaScript' "$W" && grep -q 'w.evaluateJavascript(script(), null)' "$W" && ok "H4 installed at document start, with a page-start fallback" || bad "H4 the install paths are missing"
grep -q '@JavascriptInterface' "$W" && grep -q 'JS_BRIDGE = "AndroidWebHardware"' "$W" && grep -q 'AndroidWebHardware' "$JS" && ok "H4 one JS interface, named the same on both sides" || bad "H4 the interface name drifted"
grep -q 'webHardware.attach(this, tab.isPrivate)' "$F" && grep -q 'webHardware.onPageStarted(it)' "$F" && grep -q 'webHardware.detach(it)' "$F" && ok "H4 BrowserHostFragment attaches, re-injects on navigation and detaches" || bad "H4 the fragment does not wire it"
grep -q 'cores\[w\]?.reset()' "$W" && ok "H4 a navigation closes what the old page opened" || bad "H4 no reset on navigation"
grep -q 'web_hardware.js' "$W" && [ -f "$JS" ] && ok "H4 the script is a shipped asset, read at attach time" || bad "H4 the asset is not wired"

echo "== H5: the native rules =="
C="$KT/WebHardwareCore.kt"
grep -q 'WebBluetoothCore.isSecure(origin)' "$C" && grep -q 'Must be handling a user gesture' "$C" && ok "H5 secure origin and user gesture are enforced natively too" || bad "H5 the native side trusts the page"
grep -q 'serialGrants.find(origin' "$C" && grep -q 'usbGrants.find(origin' "$C" && grep -q 'nfcGrants.has(o, NFC_KEY)' "$C" && grep -q 'fun idOf(origin: String, key: String)' "$KT/WebHardwareModel.kt" && ok "H5 grants and ids are origin-scoped, one store per API" || bad "H5 grants are not origin-scoped"
grep -q 'chooser.pick(req' "$C" && grep -q 'chooser.allowNfc(' "$C" && grep -q 'chooser.tapTag(' "$C" && ok "H5 the user picks / allows / taps in a native UI" || bad "H5 no chooser"
grep -q 'setContent' "$KT/WebHardwareChooser.kt" && grep -q 'ComposeView' "$KT/WebHardwareChooser.kt" && ! grep -q 'AlertDialog' "$KT/WebHardwareChooser.kt" && ok "H5 the chooser and prompts are Compose (compose ratchet)" || bad "H5 the chooser is not Compose"
grep -q 'PROTECTED_CLASSES' "$C" && grep -q 'protected class' "$C" && grep -q 'fun onlyProtected' "$C" && ok "H5 WebUSB keeps Chrome's protected-class rule" || bad "H5 no protected-class rule"
grep -q 'if (privateTab) WebHwGrants(Mem(), api)' "$W" && ok "H5 a private tab's grants never reach disk" || bad "H5 a private tab persists grants"
grep -q 'fun reset()' "$C" && grep -q 'serial.close(it)' "$C" && grep -q 'usb.close(it)' "$C" && grep -q 'stopScan()' "$C" && ok "H5 leaving the page closes what it opened" || bad "H5 reset does not close"
grep -q 'permissions.ensure(d.key)' "$C" && grep -q 'requestPermission' "$KT/WebHardwareAndroid.kt" && ok "H5 Android's per-device USB permission is asked in context" || bad "H5 no USB permission step"
for g in web_serial_grants web_usb_grants web_nfc_grants; do
  grep -q "getSharedPreferences(\"$g\"" "$W" && python3 -c "import json,sys; d=json.load(open('$FLEET')); sys.exit(0 if '$g' in json.dumps(d['stores']) and d['stores']['$g']['class']=='device' else 1)" 2>/dev/null \
    && ok "H5 $g is a declared fleet-config store (class device)" || bad "H5 $g is not declared in fleet-config.json"
done

echo "== H6: real backends, no mock data =="
A="$KT/WebHardwareAndroid.kt"
grep -q 'getSystemService(Context.USB_SERVICE)' "$A" && grep -q 'openDevice' "$A" && grep -q 'bulkTransfer' "$A" && grep -q 'controlTransfer' "$A" && ok "H6 WebUSB runs on Android's UsbManager" || bad "H6 no UsbManager"
grep -q 'SET_LINE_CODING' "$KT/CdcAcmSerial.kt" && grep -q 'SET_CONTROL_LINE_STATE' "$KT/CdcAcmSerial.kt" && grep -q 'CDC_DATA' "$KT/CdcAcmSerial.kt" && ok "H6 Web Serial runs on USB CDC ACM over the same host" || bad "H6 no CDC serial"
grep -q 'enableReaderMode' "$A" && grep -q 'Ndef.get(tag)' "$A" && grep -q 'writeNdefMessage' "$A" && ok "H6 Web NFC runs on NfcAdapter reader mode (NDEF read and write)" || bad "H6 no NFC reader mode"
grep -q 'android.hardware.usb.host' "$LIB/AndroidManifest.xml" && grep -q 'android.hardware.nfc' "$LIB/AndroidManifest.xml" && grep -q 'android:required="false"' "$LIB/AndroidManifest.xml" && ok "H6 USB host and NFC are optional hardware in the manifest" || bad "H6 manifest features missing"
! grep -niE 'mock|sample device|dummy|lorem|fake' "$KT"/WebHardware*.kt "$KT/CdcAcmSerial.kt" >/dev/null && ok "H6 the product code carries no mock data" || bad "H6 mock data in product code"

echo; echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
