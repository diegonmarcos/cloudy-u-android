#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #900 Autocomplete: profiles, per-site memory, passwords, cards, vault ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The pure rules are JVM-tested (AcProfileTest, AcFieldClassifierTest, AcSiteMemoryTest, AcVaultMergeTest, AcCryptoTest,
# AcStoreTest, AcProposalsTest, AcNoSecretLogTest). This certifies the WIRING, in comment-stripped Kotlin:
#   A1 the page engine (assets/browser/ac_engine.js), run under node against a fake DOM (ac_js_harness.js): focus reports
#      signals and never a value, a submit reports the typed values once, the framework's fill is noticed, a fill uses the
#      native setter and skips what Android autofill filled, <select> is matched by text;
#   A2 the engine keeps nothing in the page, logs nothing, sends nothing over the network, rewrites no attribute;
#   A3 the host: engine installed per page, a private tab is never asked to save, a saved password or card is filled only
#      behind the unlock, local suggestions come BEFORE Cloud Vault, a framework fill is read back and merged (vault wins),
#      a save is offered only on submit and confirmed by a tap, "also save to the vault" is Android's own commit;
#   A4 at rest: the two encrypted stores are declared (profiles: secret, secrets: device), opened with the fleet's
#      MasterKey scheme, and every secret is sealed again with an AES-256-GCM Keystore key, record-bound;
#   A5 the unlock is the system's (BiometricPrompt, biometric or screen lock) and a phone without a lock refuses;
#   A6 nothing in the autocomplete sources logs, and the six new settings are declared and read;
#   A7 the Configs pages that draw it, and the page dropdown's "Autocomplete for this site".
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, subprocess, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, MANIFEST, kotlin, build_json, main

ASSETS = "ab_cloud-libs-shared/libs/browser/src/main/assets/browser"

def check(root, ok):
    js = open(os.path.join(root, ASSETS, "ac_engine.js"), encoding="utf-8").read()
    src = kotlin(root, LIB, APP)
    ac = src.get(LIB + "/AcAutocomplete.kt", "")
    andr = src.get(LIB + "/AcAndroid.kt", "")
    sec = src.get(LIB + "/AcSecrets.kt", "")
    store = src.get(LIB + "/AcStore.kt", "")
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    vault = src.get(LIB + "/VaultAutofill.kt", "")
    bj = build_json(root)
    # A1
    r = subprocess.run(["node", "ac_js_harness.js", os.path.join(root, ASSETS)], capture_output=True, text=True)
    lines = r.stdout.splitlines()
    ok(r.returncode == 0 and len(lines) >= 30, "A1 the engine runs under node", r.stderr[-300:])
    for l in lines:
        ok(l.startswith("ok "), "A1 " + l[4:], l)
    # A2
    code = re.sub(r"(?m)^\s*//.*$", "", js)
    ok(not re.search(r"console\.|localStorage|sessionStorage|indexedDB|document\.cookie|XMLHttpRequest|fetch\(|sendBeacon|alert\(", code), "A2 the engine logs nothing, stores nothing, sends nothing")
    ok("setAttribute" not in code and "removeAttribute" not in code and "innerHTML" not in code, "A2 the engine rewrites no attribute and writes no markup")
    ok(len(re.findall(r"bridge\('submitted'", code)) == 1 and len(re.findall(r"\.v = ", code)) == 1, "A2 the only place a value leaves the page is the submit report")
    focus = code[code.find("function onFocus"):code.find("function valueOf")]
    ok("valueOf(" not in focus and ".value" not in focus, "A2 the focus report reads no value")
    ok("Object.getOwnPropertyDescriptor(proto, 'value')" in code, "A2 values go through the native setter")
    # A3
    ok("ac_engine" in ac and 'JS_BRIDGE = "CloudAc"' in ac, "A3 the host installs ac_engine.js behind the CloudAc bridge")
    sub = ac[ac.find("private fun onSubmitted"):ac.find("fun confirmSave")]
    ok("if (incognito" in sub[:200] and "return" in sub[:200], "A3 a private tab is never asked to save")
    ok('unlocked("Fill saved password")' in ac and 'unlocked("Fill saved card")' in ac, "A3 a saved password or card is filled only behind the unlock")
    ok("AcUnlock.require(" in frag and 'unlock("Show saved password")' in frag and 'unlock("Show saved card")' in frag, "A3 revealing a saved password or card asks for the unlock")
    login = ac[ac.find("private fun loginBar"):ac.find("private fun cardBar")]
    ok(login.find("loginsFor(host)") != -1 and 0 <= login.find("loginsFor(host)") < login.find('AcChip("vault"'), "A3 local suggestions come before Cloud Vault's")
    ok("vaultState()" in login and "askVault(" in login and "AcVaultState.of(" in ac, "A3 Cloud Vault is asked only when it can be, and the reason shows when it cannot")
    ok('flag("autofill_use_vault") != true' in ac or 'flag("autofill_use_vault") == true' in ac, "A3 the vault option gates it")
    fw = ac[ac.find("private fun onFramework"):ac.find("private fun onSubmitted")]
    ok("mergeVaultLogin(" in ac and "mergeVaultCard(" in ac and "a.read(" in fw, "A3 a framework fill is read back and merged into the store")
    ok("val merged = hit.copy(username = v.username.ifBlank { hit.username }, password = v.password, source = AcSource.VAULT," in sec, "A3 the merge makes the vault's value the stored one")
    ok("VaultAutofill.commitSave(" in ac and "fun commitSave(" in vault and "am.commit()" in vault, "A3 'also save to the vault' is Android's own commit, nothing handed over by us")
    ok("AcProposals.fromSubmission(" in ac and "fun confirmSave(" in ac and "save.value = AcSaveState(" in ac, "A3 a save is a prompt the user answers")
    ok("h.store.saveLogin(" in ac[ac.find("fun confirmSave"):] and "if (i !in picked)" in ac, "A3 only what the user ticked is saved")
    ok("domAutofill" in frag and "!ac.hasLocalProfiles()" in frag, "A3 the Cloud Account chip steps aside once the user keeps profiles of their own")
    ok("ac.attach(this)" in frag and "ac.inject(it, u, tab.isPrivate)" in frag and "AcFillBar(" in frag and "AcChooserSheet(" in frag and "AcSaveSheet(" in frag, "A3 the fragment attaches, injects and draws the bar, chooser and save sheet")
    # A4
    st = json.load(open(os.path.join(root, MANIFEST), encoding="utf-8"))["stores"]
    ok(st.get("browser_ac_profiles", {}).get("kind") == "encrypted" and st["browser_ac_profiles"].get("class") == "secret", "A4 browser_ac_profiles is declared encrypted + secret")
    ok(st.get("browser_ac_secrets", {}).get("kind") == "encrypted" and st["browser_ac_secrets"].get("class") == "device", "A4 browser_ac_secrets is declared encrypted + device (never migrates in the clear)")
    safe = open(os.path.join(root, "ab_cloud-libs-shared/libs/crash/src/main/java/com/diegonmarcos/superapp/crash/SafeEncryptedPrefs.kt"), encoding="utf-8").read()
    ok("SafeEncryptedPrefs.prefs(app, PROFILES_STORE" in andr and "SafeEncryptedPrefs.prefs(app, SECRETS_STORE" in andr and "MasterKey.KeyScheme.AES256_GCM" in safe and "EncryptedSharedPreferences.create(" in safe and "getSharedPreferences" not in andr, "A4 both stores open with the fleet's MasterKey scheme (through the one safe opener, #1106)")
    ok('"AES/GCM/NoPadding"' in sec and sec.count("updateAAD(") == 2 and "GCMParameterSpec(TAG_BITS" in sec and 'aad("login", it.id, "pw")' in sec, "A4 secrets are sealed with AES-GCM, bound to their record")
    ok("AndroidKeyStore" in andr and "KEY_ALGORITHM_AES" in andr and "setKeySize(256)" in andr, "A4 the key is an AES-256 Android Keystore key")
    ok(".put(\"pw\", box.seal(" in sec and ".put(\"num\", box.seal(" in sec and ".put(\"cvc\", box.seal(" in sec and '.put("password"' not in sec and '.put("number"' not in sec, "A4 password, card number and code are written sealed, never as plain fields")
    ok("bak_" in store and "if (kv.getString(k) == null)" in store, "A4 unreadable json is backed up before anything is written over it")
    ok(not re.search(r"\.clear\(\)|\.edit\(\)\.clear|deleteSharedPreferences|\.remove\(K_", store) or "fun clearMemory" in store, "A4 the store never wipes itself (only the user's explicit forget)")
    ok("importLegacyOnce" in store and "BrowserProfileStore(app).load()" in andr and "STORE" not in store, "A4 the old browser profile is imported once and left where it was")
    # A5
    ok("BiometricPrompt" in andr and "DEVICE_CREDENTIAL" in andr and "BIOMETRIC_WEAK" in andr, "A5 the unlock is the system's biometric / screen lock prompt")
    ok("BIOMETRIC_ERROR_NONE_ENROLLED -> \"Set a screen lock" in andr and "unavailable(activity)?.let { return onDenied(it) }" in andr, "A5 a phone with no screen lock refuses instead of showing the secret")
    ok("androidx.biometric:biometric" in open(os.path.join(root, "ab_cloud-libs-shared/libs/browser/build.gradle")).read(), "A5 the biometric library is a dependency of libs:browser")
    # A6
    for p, t in src.items():
        if os.path.basename(p).startswith("Ac"):
            ok(not re.search(r"\bLog\.[dviwe]\(|println\(|printStackTrace|Timber\.", t), "A6 %s logs nothing" % os.path.basename(p))
    keys = {s["key"]: s for s in bj["ui"]["browser"]["settings"]}
    for k, sec_, cls in (("site_memory", "autocomplete", "config"), ("offer_save_profile_data", "autocomplete", "config"), ("offer_save_password", "passwords", "config"),
                         ("secrets_require_unlock", "passwords", "device"), ("offer_save_card", "payment", "config"), ("autofill_use_vault", "sync", "config")):
        ok(k in keys and keys[k]["section"] == "autocomplete" and keys[k].get("group") == sec_ and keys[k]["class"] == cls and keys[k]["type"] == "bool", "A6 setting `%s` is declared on Configs > Autocomplete > %s" % (k, sec_))
        ok('"%s"' % k in ac or '"%s"' % k in frag, "A6 setting `%s` is read" % k)
    ok(keys.get("autofill_use_vault", {}).get("default") is False and keys.get("secrets_require_unlock", {}).get("default") is True, "A6 Cloud Vault is opt-in and the unlock is on by default")
    # A7
    nat = bj["ui"]["browser"]["configs"]["native"]
    ok(nat.get("autocomplete") == ["profiles", "sites", "passwords", "cards", "vault"], "A7 Configs > Autocomplete has the sub-sections profiles, sites, passwords, cards, vault")
    # #1402 Passwords, Payment and Sync & Vault are groups of Autocomplete (whose blocks they used to repeat); their old anchors redirect there
    cfg_ = bj["ui"]["browser"]["configs"]
    ok([g["id"] for g in cfg_.get("groups", {}).get("autocomplete", [])] == ["autocomplete", "passwords", "payment", "sync"]
       and all(cfg_.get("redirects", {}).get(o) == "autocomplete" for o in ("passwords", "payment", "sync")), "A7 Passwords, Payment and Sync & Vault are groups of Autocomplete")
    ok('"site_autocomplete" ->' in frag and "AcSitePage(" in frag and "AcSiteMemory.originKey(host)" in frag, "A7 the page dropdown opens this site's remembered values")
    ok("fillLogin(f, l); thenVault(cur.id)" in ac and "fillCard(f, c); thenVault(cur.id)" in ac, "A7 a local fill then Cloud Vault overwrites it (local fills first, then Cloud Vault overwrites)")
    ok(os.path.exists(os.path.join(root, "ac_cloud-browser/docs/autocomplete.md")), "A7 docs/autocomplete.md states the model, the flows and the vault contract")

LIBP = LIB + "/"
main("autocomplete wiring", check, [
    ("the focus report reads a value", ASSETS + "/ac_engine.js", "bridge('focus', { field: idOf(e), formKey: g.key,", "bridge('focus', { v: e.value, field: idOf(e), formKey: g.key,", "focus report reads no value"),
    ("the engine logs", ASSETS + "/ac_engine.js", "function bridge(fn, payload) {", "function bridge(fn, payload) { console.log(payload);", "logs nothing"),
    ("a second place sends a value", ASSETS + "/ac_engine.js", "function onSubmit(ev) {", "function onSubmit(ev) { bridge('submitted', {v:1});", "only place a value leaves"),
    ("the fill skips the native setter", ASSETS + "/ac_engine.js", "Object.getOwnPropertyDescriptor(proto, 'value')", "null", "native setter"),
    ("a private tab is asked to save", LIBP + "AcAutocomplete.kt", "if (incognito || flag(\"autofill_enabled\") == false) return\n        val fields", "if (flag(\"autofill_enabled\") == false) return\n        val fields", "never asked to save"),
    ("a saved password fills without the unlock", LIBP + "AcAutocomplete.kt", "acts[id] = { unlocked(\"Fill saved password\") { fillLogin(f, l); thenVault(cur.id) } }", "acts[id] = { fillLogin(f, l); thenVault(cur.id) }", "only behind the unlock"),
    ("Cloud Vault no longer overwrites a local fill", LIBP + "AcAutocomplete.kt", "fillLogin(f, l); thenVault(cur.id)", "fillLogin(f, l)", "then Cloud Vault overwrites"),
    ("revealing skips the unlock", LIBP + "BrowserHostFragment.kt", "reveal = { l, show -> unlock(\"Show saved password\") { show(l.password) } }", "reveal = { l, show -> show(l.password) }", "asks for the unlock"),
    ("Cloud Vault is asked before the local store", LIBP + "AcAutocomplete.kt", "        } else if (h.store.secretsAvailable) {\n            h.store.loginsFor(host)", "        } else if (h.store.secretsAvailable) {\n            chips += AcChip(\"vault\", \"Cloud Vault\", \"vault\")\n            h.store.loginsFor(host)", "come before Cloud Vault"),
    ("the vault stops winning", LIBP + "AcSecrets.kt", "val merged = hit.copy(username = v.username.ifBlank { hit.username }, password = v.password, source = AcSource.VAULT,", "val merged = hit.copy(username = v.username.ifBlank { hit.username }, password = hit.password, source = AcSource.VAULT,", "vault's value the stored one"),
    ("a secret is stored in the clear", LIBP + "AcSecrets.kt", ".put(\"pw\", box.seal(it.password, aad(\"login\", it.id, \"pw\")))", ".put(\"pw\", it.password)", "written sealed"),
    ("the seal loses its record binding", LIBP + "AcSecrets.kt", "        c.updateAAD(aad.toByteArray(Charsets.UTF_8))\n        val ct", "        val ct", "bound to their record"),
    ("the secrets store becomes secret-class", MANIFEST, '"browser_ac_secrets": {\n      "kind": "encrypted",\n      "class": "device",', '"browser_ac_secrets": {\n      "kind": "encrypted",\n      "class": "secret",', "never migrates in the clear"),
    ("the store opens unencrypted", LIBP + "AcAndroid.kt", "SafeEncryptedPrefs.prefs(app, SECRETS_STORE", "app.getSharedPreferences(SECRETS_STORE, 0); SafeEncryptedPrefs.prefs(app, SECRETS_STORE", "open with the fleet's MasterKey"),
    ("a phone with no lock shows the secret", LIBP + "AcAndroid.kt", "unavailable(activity)?.let { return onDenied(it) }", "", "refuses instead of showing"),
    ("a log line prints a login", LIBP + "AcStore.kt", "    fun forgetMemory(id: String)", "    fun dump() = android.util.Log.d(\"x\", logins().toString())\n    fun forgetMemory(id: String)", "AcStore.kt logs nothing"),
    ("the vault option is dropped", "ac_cloud-browser/build.json", '"key": "autofill_use_vault",', '"key": "autofill_use_vault_gone",', "autofill_use_vault"),
    ("the dropdown loses this site's entries", LIBP + "BrowserHostFragment.kt", "AcSitePage(entries, origin,", "AcSitePageGone(entries, origin,", "opens this site's remembered values"),
])
PYEOF
