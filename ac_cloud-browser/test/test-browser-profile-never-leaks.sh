#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I6 the autofill profile never leaks, and never holds a card ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The profile is personal data in a secret-class store. No route, log line or
# toast may print a value of it: the API answers masked() only and fill is dry.
# Card numbers and codes must never be stored. The store must be the encrypted
# one the manifest declares, opened with FleetConfig's MasterKey scheme, or the
# Account's import writes a file the browser cannot read. Comment-stripped Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, MANIFEST, kotlin, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    store = src.get(os.path.join(LIB, "BrowserProfileStore.kt"), "")
    prof = src.get(os.path.join(LIB, "BrowserProfile.kt"), "")
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    sect = api[api.find('"profile" ->'):api.find('"history/clear" ->')]
    ok(sect, "the profile routes exist")
    ok("BrowserProfileStore(app).load().masked()" in sect, "profile answers masked() only")
    ok(not re.search(r"\.load\(\)(?!\.masked\(\))", sect), "no profile route hands out an unmasked load()")
    ok('"profile/fill" -> BrowserBus.call("fill_dry")' in api, "profile/fill over the API is dry")
    dry = frag[frag.find('dry -> done('):][:200]
    ok('put("fields", fields)' in dry and "value(" not in dry, "the dry answer carries field kinds, never values")
    for f, t in src.items():
        for m in re.finditer(r"Log\.[dviwe]\([^)]*\)", t):
            ok(not re.search(r"profile|identity|address", m.group(0), re.I), "no log line prints the profile (%s)" % os.path.basename(f))
    safe = open(os.path.join(root, "ab_cloud-libs-shared/libs/crash/src/main/java/com/diegonmarcos/superapp/crash/SafeEncryptedPrefs.kt"), encoding="utf-8").read()
    ok("SafeEncryptedPrefs.prefs(" in store and "EncryptedSharedPreferences.create(" in safe and "MasterKey.KeyScheme.AES256_GCM" in safe, "the store is encrypted with FleetConfig's MasterKey scheme (through the one safe opener, #1106)")
    ok('const val STORE = "browser_autofill"' in store, "the store is browser_autofill")
    m = json.load(open(os.path.join(root, MANIFEST), encoding="utf-8"))["stores"].get("browser_autofill", {})
    ok(m.get("kind") == "encrypted" and m.get("class") == "secret", "the manifest declares browser_autofill encrypted + secret")
    # no importer keeps a full number or a code
    card = prof[prof.find("fun cardJson"):][:300]
    ok(card and not re.search(r'"(card_number|number|cvv|cvc|code|cc-number)"', card), "a stored card holds no number and no code")
    ok(not re.search(r'put\("(card_number|cvv|cvc|code|cc-number)"', prof), "nothing stores a card number or code")
    for fn in ("fromBitwardenJson", "fromFirefoxJson", "fromNative"):
        body = prof[prof.find("fun " + fn):][:1600]
        ok("last4(" in body, "%s reduces card numbers to last4" % fn)
    ok("refuse.containsMatchIn" in prof and '"password"' in prof, "the matcher refuses passwords and card fields")

LIBP = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
API = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/debugapi/BrowserDebugApi.kt"
main("profile never leaks", check, [
    ("a stored card keeps its number", LIBP + "BrowserProfile.kt", '.put("last4", c.last4)', '.put("number", c.last4)', "holds no number"),
    ("the profile route answers unmasked", API, "BrowserProfileStore(app).load().masked()", "BrowserProfileStore(app).load().toJson()", "unmasked load()"),
    ("an importer keeps the full number", LIBP + "BrowserProfile.kt", 'last4(c.s("number"))', 'c.s("number")', "fromBitwardenJson reduces"),
    ("a log line prints the profile", LIBP + "BrowserProfileStore.kt", "    fun clear() =", '    fun dump() = android.util.Log.d("x", "profile=" + load())\n    fun clear() =', "no log line"),
    ("the store stops being encrypted", "fleet-private-data/fleet-config.json", '"kind": "encrypted",\n      "class": "secret",\n      "doc": "#802 the browser', '"kind": "prefs",\n      "class": "secret",\n      "doc": "#802 the browser', "encrypted + secret"),
    ("fill over the API writes", API, '"profile/fill" -> BrowserBus.call("fill_dry")', '"profile/fill" -> BrowserBus.call("fill_profile")', "is dry"),
])
PYEOF
