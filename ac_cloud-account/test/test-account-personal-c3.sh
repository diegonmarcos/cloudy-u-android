#!/usr/bin/env bash
# test-account-personal-c3: #1438 Cloud Account's Personal Info and Configs, statically.
#
# Holds that:
#  1. NAV: build.json::ui declares Personal Info = IDs, Pay Cards, Others (Entries, Me), Autocomplete and Configs =
#     Android (Cloud Apps, Phone Apps, Runbook), Desktop, C3 (Infra, Containers), and MainActivity mounts every leaf;
#  2. SECRET VALUES NEVER RENDERED: C3Decl keeps NAMES only (an env entry is cut at '=' / ':', a block-scalar line is
#     skipped, ServiceDecl has no value field) and the Containers page draws only containerLines;
#  3. CARD MASKING: a card number is drawn through CardMask.display, which shows the last four digits only unless the
#     row was revealed, and a reveal goes only through RevealGate (device auth);
#  4. ENCRYPTED STORAGE: personal data is written only through EncryptedSharedPreferences (AES256-SIV keys,
#     AES256-GCM values), with no plain fallback, no other file or prefs, no log call, and an entry's toString holds
#     no value.
# Then each mutation is planted in a scratch copy and the check must go RED. The runtime half is PersonalC3Test.kt.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"
K="$A/app/src/main/java/com/diegonmarcos/cloudaccount"
# #1439 the pages and the router are libs:account-ui's (shared with Cloud Me); account-ui.json declares the structure once.
LK="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount"; UJ="ab_cloud-libs-shared/libs/account-ui/account-ui.json"
MA="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/superapp/accountui/AccountUi.kt"; PS="$LK/personal/PersonalStore.kt"; PP="$LK/personal/PersonalPages.kt"
CD="$LK/configs/C3Decl.kt"; CP="$LK/configs/ConfigsPages.kt"
FILES="$A/build.json $MA $PS $PP $CD $CP $UJ"

check() {
python3 - "$1" "$A" "$MA" "$PS" "$PP" "$CD" "$CP" <<'PY'
import json, os, re, sys
R, A, MA, PS, PP, CD, CP = sys.argv[1:8]
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//[^\n]*|\s//\s[^\n]*", "", s)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

# 1. nav structure
ui = json.loads(rd(f"{A}/build.json"))["ui"]
sec = {s["id"]: s for s in ui["sections"]}
def ids(pages): return [p["id"] for p in pages]
def sub(s, p): return ids(next(x for x in sec[s]["pages"] if x["id"] == p).get("pages", []))
ok(ids(sec["vault"]["pages"]) == ["ids", "cards", "secrets", "autocomplete"], "Vault = IDs, PayCards, Secrets, Autocomplete (#1482)", f"Vault is {ids(sec['vault']['pages'])}")
ok([p["label"] for p in sec["vault"]["pages"]] == ["IDs", "PayCards", "Secrets", "Autocomplete"], "Vault labels as asked", "Vault labels drifted")
ok(sub("vault", "secrets") == ["providers", "keys", "tokens", "grants", "import", "entries"], "Secrets = Providers, Fleet keys, Tokens, Grants, Import, Entries", f"Secrets is {sub('vault', 'secrets')}")
ok(ids(sec["config"]["pages"]) == ["android", "desktop", "c3", "repos"], "Config = Android, Desktop, C3, Repos (#1482, #1445)", f"Config is {ids(sec['config']['pages'])}")
ok(ids(sec["configs"]["pages"]) == ["general", "perms", "about"], "the app's own Configs = General, Perms, About (#1443), not in the bottom nav", f"Configs is {ids(sec['configs']['pages'])}")
ok(ids(sec["devices"]["pages"]) == ["mesh", "peer-control", "backup", "files", "working", "diff"] and sub("devices", "backup") == ["databases", "bundle", "me"], "Devices = Mesh, Peer Control, Backup, Files, Working, Diff", f"Devices is {ids(sec['devices']['pages'])}")
ok(sub("config", "android") == ["cloud-apps", "phone-apps", "runbook"], "Android = Cloud Apps, Phone Apps, Runbook", f"Android is {sub('config', 'android')}")
ok(sub("config", "desktop") == [], "Desktop is one page (the tree)", "Desktop grew sub-pages")
ok(sub("config", "c3") == ["infra", "containers"], "C3 = Infra, Containers", f"C3 is {sub('config', 'c3')}")
ma = code(rd(MA))
want = {'"entries:ids" -> PersonalEntriesPage(PersonalKind.IDS)', '"entries:cards" -> PersonalEntriesPage(PersonalKind.CARDS)', '"entries:others" -> PersonalEntriesPage(PersonalKind.OTHERS)',
        '"host:me" -> AccountUiHost.current.MePage(go)', '"configs:phone-apps" -> PhoneAppsPage()', '"configs:runbook" -> RunbookTogglesPage(go)', '"configs:desktop" -> DesktopTreePage()', '"configs:repos" -> RepoConnectorPage()',
        '"configs:infra" -> InfraPage()', '"configs:containers" -> ContainersPage()', 'else -> CloudAppsPage()'}
miss = [w for w in want if w not in ma]
ok(not miss, "the shared router mounts every new leaf", f"the shared router does not mount {miss}")
def leafs(ps):
    for p in ps:
        if p.get("pages"): yield from leafs(p["pages"])
        else: yield p
def shape(ps): return [(p["id"], p["label"], p.get("icon"), shape(p.get("pages") or [])) for p in ps]
uj = {x["id"]: x for x in json.loads(rd("ab_cloud-libs-shared/libs/account-ui/account-ui.json"))["sections"]}
ok(shape(sec["config"]["pages"]) == shape(uj["configs"]["pages"]) and sec["config"]["label"] != "" and all(any(q["id"] == p["id"] and q.get("icon") == p.get("icon") for q in [x for s_ in ("vault", "devices") for x in leafs(sec[s_]["pages"])]) for p in leafs(uj["personal"]["pages"])),
   "Cloud Account's Config matches libs:account-ui's Configs page for page, and its Vault / Devices hold every Personal Info leaf", "Cloud Account's sections drifted from account-ui.json")

# 2. secret values never rendered
cd = code(rd(CD)); cp = code(rd(CP))
sd = cd[cd.index("class ServiceDecl("):cd.index("object ComposeDecl")]
ok(re.findall(r"val (\w+)", sd) == ["name", "image", "envNames", "secretNames", "envFiles", "volumes"], "ServiceDecl holds names, image and volumes only", "ServiceDecl gained a field")
en = cd[cd.index("fun envName("):cd.index("private fun volume(")]
ok(".substringBefore('=').substringBefore(':')" in en and "KEY.matches(it)" in en, "an env entry is cut to its NAME", "envName keeps more than the name")
ok("// a continuation" in rd(CD) and 'ind > itemIndent && key != "volumes"' in cd, "a block-scalar / nested line under an entry is skipped", "continuation lines are read as names")
ok('"environment" -> (if (t.startsWith("- ")) envName(t.removePrefix("- ")) else envName(t))?.let { env += it }' in cd, "environment only ever adds envName()", "environment adds raw lines")
tf = cd[cd.index("object TfDecl"):cd.index("class TreeNode(")]
ok(not re.search(r'=\\\\s\*\(|=\\s\*\(\.', tf) and "VARIABLE" in tf, "Terraform keeps provider / resource / variable NAMES (no right-hand side)", "TfDecl captures an assignment value")
body = cp[cp.index("fun ContainersPage("):]
ok("containerLines(s)" in body and not any(w in body for w in ("fields", "ComposeDecl.parse", "envNames.join")), "Containers draws only containerLines", "Containers draws raw data")

# 3. card masking
ps = code(rd(PS)); pp = code(rd(PP))
mk = ps[ps.index("object CardMask"):ps.index("class PersonalRepo(")]
ok("digits.takeLast(4)" in mk and "$DOT$DOT$DOT$DOT $DOT$DOT$DOT$DOT $DOT$DOT$DOT$DOT" in mk and "if (digits.length < 8) return hide(number)" in mk, "a card number masks to its last four digits", "CardMask shows more than the last four")
ok("revealed || field !in kind.masked -> value" in mk and 'kind == PersonalKind.CARDS && field == "number" -> mask(value)' in mk, "display: clear value only when revealed, card number through mask()", "CardMask.display leaks the clear value")
ok('CARDS("cards", listOf("holder", "number", "expiry", "brand"), setOf("number"))' in ps, "a card's number is a masked field", "the card number is not masked")
ok("CardMask.display(kind, f, v, shown)" in pp and pp.count("${v}") == 0 and "Text(v" not in pp, "the page draws fields only through CardMask.display", "the page draws a raw field")
ok("gate.reveal(" in pp and "show = { revealed[e.id] = true }" in pp and pp.count("revealed[e.id] = true") == 1, "a row is revealed only through RevealGate", "a row is revealed without device auth")
ok("onDispose { revealed.clear() }" in pp, "leaving the page masks everything again", "reveals outlive the page")

# 4. encrypted storage
ok("EncryptedSharedPreferences.create(ctx, FILE, key" in ps and re.search(r"PrefKeyEncryptionScheme\.AES256_SIV\b", ps) and re.search(r"PrefValueEncryptionScheme\.AES256_GCM\b", ps) and re.search(r"KeyScheme\.AES256_GCM\b", ps),
   "PersonalStore opens EncryptedSharedPreferences (AES256-SIV / AES256-GCM, keystore master key)", "PersonalStore does not use EncryptedSharedPreferences")
rp = ps[ps.index("fun repo(ctx: Context)"):]
ok("testPrefs?.invoke(ctx) ?: encrypted(ctx.applicationContext)" in rp and "getSharedPreferences" not in ps + pp, "no plain fallback: the store is encrypted or there is none", "a plain store is reachable")
bad = [w for w in ("getSharedPreferences", "openFileOutput", "File(", "FileOutputStream", "Log.", "println(", "printStackTrace", "Timber") if w in ps + pp]
ok(not bad, "personal/ writes nowhere else and logs nothing", f"personal/ uses {bad}")
ts = ps[ps.index("override fun toString()"):ps.index("fun json()")]
ok("fields.keys" in ts and "values" not in ts and "label" not in ts and "$fields" not in ts, "an entry's toString names keys only", "an entry's toString carries values")
ok("testPrefs" not in ma and "testPrefs" not in pp, "production never sets the test store", "production code sets the test store")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"; for f in $FILES; do mkdir -p "$TMP/r/$(dirname "$f")"; cp "$ROOT/$f" "$TMP/r/$f"; done
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding="utf-8").read()
if o not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(o, n, 1))
PY
  if check "$TMP/r" >/dev/null; then echo "  MUTATION SURVIVED: $1"; return 1; fi
  echo "  mutation caught: $1"
}
M=0
# nav
mutate "the shared structure drifts from Cloud Account" "$UJ" '"icon": "badge"' '"icon": "badgex"' || M=1
mutate "Pay Cards leaves Personal Info" "$A/build.json" '"id": "cards",' '"id": "wallet",' || M=1
mutate "Runbook leaves Android" "$A/build.json" '"id": "runbook",
                "label": "Runbook",' '"id": "push",
                "label": "Runbook",' || M=1
mutate "Containers not mounted" "$MA" '"configs:containers" -> ContainersPage()' '"configs:containers" -> InfraPage()' || M=1
# secret values
mutate "envName keeps the value" "$CD" ".substringBefore('=').substringBefore(':')" ".substringBefore(':')" || M=1
mutate "block-scalar lines read as names" "$CD" 'ind > itemIndent && key != "volumes"' 'false' || M=1
mutate "ServiceDecl carries values" "$CD" '    val volumes: List<String>,
)' '    val volumes: List<String>,
    val values: List<String> = emptyList(),
)' || M=1
mutate "Containers draws the raw compose" "$CP" 'listOf("▸ ${s.name}") + containerLines(s) })' 'listOf("▸ ${s.name}") + s.envNames.map { it + s.fields } })' || M=1
# card masking
mutate "mask shows the whole number" "$PS" 'digits.takeLast(4)' 'digits' || M=1
mutate "display ignores the mask" "$PS" 'revealed || field !in kind.masked -> value' 'true -> value' || M=1
mutate "card number unmasked" "$PS" 'setOf("number")),
    OTHERS' 'emptySet()),
    OTHERS' || M=1
mutate "reveal without auth" "$PP" 'if (shown) revealed.remove(e.id)' 'if (shown) revealed.remove(e.id) else if (true) revealed[e.id] = true' || M=1
mutate "raw field drawn" "$PP" 'CardMask.display(kind, f, v, shown)' 'v' || M=1
mutate "reveals outlive the page" "$PP" 'onDispose { revealed.clear() }' 'onDispose { }' || M=1
# encrypted storage
mutate "plain prefs instead of encrypted" "$PS" 'testPrefs?.invoke(ctx) ?: encrypted(ctx.applicationContext)' 'testPrefs?.invoke(ctx) ?: ctx.getSharedPreferences(FILE, 0)' || M=1
mutate "weaker value scheme" "$PS" 'PrefValueEncryptionScheme.AES256_GCM' 'PrefValueEncryptionScheme.AES256_GCMX' || M=1
mutate "personal data logged" "$PP" 'repo.put(kind, PersonalEntry(' 'android.util.Log.d("p", label); repo.put(kind, PersonalEntry(' || M=1
mutate "toString prints values" "$PS" 'fields=${fields.keys.sorted()}' 'fields=$fields' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
