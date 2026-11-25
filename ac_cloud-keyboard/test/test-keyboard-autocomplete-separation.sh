#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1103 Autocomplete is its own engine and database: no vault, no network, ║
# ║ no clipboard link; Cloud Account fills it over the local provider        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The JVM suite (AutocompleteSeparationTest, AutocompleteStoreTest, AutocompleteEngineTest, AutocompleteSyncTest in
# ac_cloud-account) holds the behaviour. This holds what only the whole tree can see, in the first CI step:
#
#   S1  the wire contract the keyboard reads (libs:autofill AutocompleteContract) and the one Cloud Account serves
#       (libs:account AutocompletePublisher) are the same names. libs:account cannot compile the contract lib, so
#       this is the guard that keeps the two halves one.
#   S2  the engine (latin/autocomplete, keyboard/autocomplete) has no vault name, no network class, no clipboard type;
#       the clipboard side names no Autocomplete type; the old Clipboard-to-Autocomplete bridge and the vault field
#       tables are gone.
#   S3  the store is encrypted at rest with a Keystore key and declared in the fleet manifest as a device store.
#   S4  the panel has its own sub-page tabs, masks the Secrets and asks the screen lock; closing locks again.
#   S5  Cloud Account serves the rows only behind a grant and seeds it for the keyboard package.
#
# SELF-PROVING: each mutation must turn its named check red. python3 only; a missing source file is fatal.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
FILES=(
    "ab_cloud-libs-shared/libs/autofill/src/main/java/com/diegonmarcos/superapp/autofill/AutocompleteContract.kt"
    "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AutocompletePublisher.kt"
    "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt"
    "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt"
    "fleet-private-data/fleet-config.json"
)
KJ=ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard
DIRS=("$KJ/latin/autocomplete" "$KJ/keyboard/autocomplete" "$KJ/keyboard/clipboard" "$KJ/latin/autofill")
for f in "${FILES[@]}" "$KJ/latin/ClipboardHistoryManager.kt" "$KJ/latin/database/ClipboardDao.kt"; do
    [ -f "$ROOT/$f" ] || { echo "FATAL  missing $f"; exit 1; }
done

ROOT="$ROOT" KJ="$KJ" python3 - <<'PY'
import glob, json, os, re, sys
R, KJ = os.environ["ROOT"], os.environ["KJ"]
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s): return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", s, flags=re.S))
fails = 0
def check(ok, name):
    global fails
    print(("ok     " if ok else "FAIL   ") + name)
    if not ok: fails += 1
def kt(d): return sorted(glob.glob(os.path.join(R, d, "*.kt")))

CON = rd("ab_cloud-libs-shared/libs/autofill/src/main/java/com/diegonmarcos/superapp/autofill/AutocompleteContract.kt")
PUB = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AutocompletePublisher.kt")
print("== S1 the two halves of the wire contract are one ==")
def const(src, name):
    m = re.search(r'const val %s = "([^"]*)"' % name, src)
    return m.group(1) if m else None
for n in ("METHOD", "GRANT", "KEY_OK", "KEY_JSON"):
    a, b = const(CON, n), const(PUB, n)
    check(a is not None and a == b, f"S1 {n} is {a!r} on both sides")
pa = re.search(r"val PAGES = listOf\(([^)]*)\)", CON); pb = re.search(r"val PAGES = listOf\(([^)]*)\)", PUB)
check(pa is not None and pb is not None and pa.group(1) == pb.group(1), "S1 the four pages are the same list")
check(const(CON, "AUTHORITY") == "com.diegonmarcos.cloudaccount.accountdata", "S1 the authority is Cloud Account's accountdata provider")

print("== S2 separation ==")
engine = kt(f"{KJ}/latin/autocomplete") + [os.path.join(R, KJ, "keyboard/autocomplete/AutocompleteView.kt")]
for f in engine:
    c = code(open(f, encoding="utf-8").read())
    n = os.path.basename(f)
    check(not re.search(r"cloudvault|launchCloudVault|KeyCode\.VAULT|bitwarden", c, re.I), f"S2 {n} has no vault name")
    check(not re.search(r"java\.net\.|okhttp3|HttpURLConnection|FleetHttp|FleetEndpoints|WebView|URL\(", c), f"S2 {n} has no network class")
    check(not re.search(r"ClipboardHistory|ClipboardDao|keyboard\.clipboard|ClipboardContentProvider", c), f"S2 {n} has no clipboard type")
cl = kt(f"{KJ}/keyboard/clipboard") + [os.path.join(R, KJ, "latin/ClipboardHistoryManager.kt"), os.path.join(R, KJ, "latin/database/ClipboardDao.kt")]
for f in cl:
    s = open(f, encoding="utf-8").read()
    check(not re.search(r"AutocompleteStore|AutocompleteEngine|AutocompleteView|AutocompletePage|AutocompleteUnlock|AutocompleteSync|AutocompleteContract|AutocompletePanel|AutofillSot", s),
          f"S2 {os.path.basename(f)} names no Autocomplete type")
check("utocomplete" not in rd(f"{KJ}/keyboard/clipboard/ClipboardHistoryView.kt"), "S2 the clipboard panel has no Autocomplete tab or mode")
check(not os.path.exists(os.path.join(R, KJ, "keyboard/clipboard/AutocompletePanel.kt")), "S2 the Clipboard-to-Autocomplete bridge is gone")
check(not os.path.exists(os.path.join(R, KJ, "latin/autofill/AutofillSchemas.kt")) and not os.path.exists(os.path.join(R, KJ, "latin/autofill/AutocompleteItems.kt")),
      "S2 the vault field tables are gone")
ks = rd(f"{KJ}/keyboard/internal/KeyboardState.kt")
check("KeyCode.AUTOCOMPLETE -> setAutocompleteKeyboard()" in ks and "AutocompletePanel" not in ks, "S2 the toolbar key opens its own panel")

print("== S3 encrypted at rest, declared ==")
st = rd(f"{KJ}/latin/autocomplete/AutocompleteStore.kt")
check("AndroidKeyStore" in st and "AES/GCM/NoPadding" in st and "getSharedPreferences(PREFS" in st, "S3 the store is AES-GCM under a Keystore key in its own prefs file")
m = re.search(r'const val PREFS = "([^"]+)"', st)
manifest = json.loads(rd("fleet-private-data/fleet-config.json"))
decl = manifest["stores"].get(m.group(1)) if m else None
check(decl is not None and decl["kind"] == "prefs" and decl["class"] == "device" and "lib-keyboard" in decl.get("used_by", []) and decl.get("doc"),
      "S3 the fleet manifest declares the store as a device prefs store of lib-keyboard, with a doc")

print("== S4 the panel ==")
v = rd(f"{KJ}/keyboard/autocomplete/AutocompleteView.kt")
check("AutocompletePage.entries.forEach" in v, "S4 the panel draws its own sub-page tabs")
check("AutocompleteEngine.shown(row, unlocked)" in v and "NEEDS_UNLOCK -> AutocompleteUnlockActivity.start" in v, "S4 secrets are drawn masked and ask for the screen lock")
check("AutocompleteUnlock.lock()" in v.split("fun stopAutocomplete")[1], "S4 closing the panel locks the secrets again")
check("EXTRA_IS_SENSITIVE" in v, "S4 a copied secret is marked sensitive")

print("== S5 Cloud Account serves behind a grant ==")
ad = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt")
av = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt")
check(re.search(r"AutocompletePublisher\.METHOD ->.*?grants\.allow\(who, AutocompletePublisher\.GRANT\)", ad, re.S) is not None, "S5 the provider checks the autocomplete.read grant")
check("com.diegonmarcos.cloudkeyboard" in av and re.search(r"5 to mapOf\(KEYBOARD_PACKAGE to listOf\(.*AutocompletePublisher\.GRANT", av) is not None
      and "SEED_VERSION = 5" in av, "S5 the keyboard package is seeded the grant (seed version 5)")

print(f"== {fails} failed ==")
sys.exit(1 if fails else 0)
PY
rc=$?
[ "$rc" -eq 0 ] || { echo "test-keyboard-autocomplete-separation: the real tree is RED"; exit 1; }
[ "${SEPARATION_NO_MUTATIONS:-0}" = "1" ] && exit 0

MUT_FAIL=0
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && cp --parents -r -t "$WORK/t" "${FILES[@]}" "${DIRS[@]}" "$KJ/latin/ClipboardHistoryManager.kt" "$KJ/latin/database/ClipboardDao.kt" "$KJ/keyboard/internal/KeyboardState.kt")
    local before; before="$(cat "$WORK/t/$2")"
    python3 -c "import re,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    if [ "$before" = "$(cat "$WORK/t/$2")" ]; then echo "FAIL   mutation '$1' did not land — the fixture moved"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    local out; out="$(CLOUD_ANDROID_ROOT="$WORK/t" SEPARATION_NO_MUTATIONS=1 bash "$SELF" 2>&1)"
    if grep -q "^FAIL   $4" <<<"$out"; then echo "ok     mutation '$1' turns $4 red"
    else echo "FAIL   mutation '$1' left $4 green"; MUT_FAIL=$((MUT_FAIL + 1)); fi
}
echo "== mutations =="
mutate "the two sides disagree on the grant name" "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AutocompletePublisher.kt" \
    "s=s.replace('autocomplete.read','autocomplete.get')" "S1 GRANT"
mutate "the engine calls the vault" "$KJ/latin/autocomplete/AutocompleteSync.kt" \
    "s=s.replace('object AutocompleteSync {','object AutocompleteSync {\n    fun x() = KeyCode.VAULT',1)" "S2 AutocompleteSync.kt has no vault name"
mutate "the engine fetches over the network" "$KJ/latin/autocomplete/AutocompleteEngine.kt" \
    "s=s.replace('object AutocompleteEngine {','object AutocompleteEngine {\n    fun x() = java.net.URL(\"http://x\")',1)" "S2 AutocompleteEngine.kt has no network class"
mutate "the panel reads the clipboard history" "$KJ/keyboard/autocomplete/AutocompleteView.kt" \
    "s=s.replace('fun stopAutocomplete() {','fun stopAutocomplete() {\n        ClipboardHistoryManager.hashCode()',1)" "S2 AutocompleteView.kt has no clipboard type"
mutate "the clipboard panel links to the Autocomplete store again" "$KJ/keyboard/clipboard/ClipboardHistoryView.kt" \
    "s=s.replace('class ClipboardHistoryView','val link = AutocompleteStore::class\nclass ClipboardHistoryView',1)" "S2 ClipboardHistoryView.kt names no Autocomplete type"
mutate "the toolbar key goes through the clipboard again" "$KJ/keyboard/internal/KeyboardState.kt" \
    "s=s.replace('KeyCode.AUTOCOMPLETE -> setAutocompleteKeyboard()','KeyCode.AUTOCOMPLETE -> setClipboardKeyboard()')" "S2 the toolbar key opens its own panel"
mutate "the store keeps plain text" "$KJ/latin/autocomplete/AutocompleteStore.kt" \
    "s=s.replace('AES/GCM/NoPadding','AES/ECB/PKCS5Padding')" "S3 the store is AES-GCM"
mutate "the secrets are no longer re-locked on close" "$KJ/keyboard/autocomplete/AutocompleteView.kt" \
    "s=s.replace('AutocompleteUnlock.lock() // the Secrets are masked again whenever the panel closes','')" "S4 closing the panel"
mutate "the provider serves without the grant" "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt" \
    "s=s.replace('!vault.grants.allow(who, AutocompletePublisher.GRANT)','false')" "S5 the provider checks"
[ "$MUT_FAIL" -eq 0 ] || { echo "test-keyboard-autocomplete-separation: $MUT_FAIL mutation(s) not caught"; exit 1; }
echo
echo "test-keyboard-autocomplete-separation: OK (real tree green, every mutation red)"
