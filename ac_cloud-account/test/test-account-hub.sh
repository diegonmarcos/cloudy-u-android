#!/usr/bin/env bash
# test-account-hub: Cloud Account's information architecture (a0_docs/eng-specs/cloud-account-ia.md), statically.
#
# Holds that:
#  1. build.json::ui.bottom_nav is personal, configs, data, connect, activity (#1081), default personal; the
#     strips (Autocomplete, Android, Secrets, Backups, Devices, Status, Authentication providers) are declared as
#     a page's own pages, every section declares its background, and MainActivity draws a second NavBarTopSecondRow for them
#     through NavDecl.stripSurface;
#  2. every old (section, page) id - the five islands, the four of 2026-10-13 and of 2026-10-14 - has its place in
#     HubNav.LEGACY, every LEGACY target is a declared leaf, and MainActivity's go() and OPEN-intent deep links
#     resolve through it;
#  3. the hub pages draw a secret value only through RevealGate (a reveal / copy asks device auth first), the
#     copy goes through SecretClipboard (auto-clear), MainActivity provides the device auth;
#  4. no hub file logs, and none reads the AI key (the decisions-use guard's key_readers ratchet);
#  5. one place per thing: Home > Connections is tiles, the Connections table, Setup all; Home > Import is the
#     personal-data importer then the token importer; the token list (SecretLine) is drawn by the provider card
#     only; each provider card and each app profile has its own Setup; the Apps page has a loading and an
#     error state; the Connections view and each provider card lead with the status pill and the next action and
#     open their details on a tap (a list, never a wide grid).
# Then each mutation is planted in a scratch copy and the check must go RED. The behaviour itself is
# HubTest.kt's (Robolectric).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"
K="$A/app/src/main/java/com/diegonmarcos/cloudaccount"
LK="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount"   # #1439 RevealGate / LocalDeviceAuth live in libs:account-ui

check() {
python3 - "$1" "$A" "$K" <<'PY'
import json, os, re, sys
R, A, K = sys.argv[1:4]
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//[^\n]*|\s//\s[^\n]*", "", s)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

ui = json.loads(rd(f"{A}/build.json"))["ui"]
ok(ui["bottom_nav"] == ["vault", "config", "connect", "devices", "activity"] and ui["default_section"] == "vault",
   "bottom nav: Vault, Config, Connect, Devices, Activity; default Vault", f"bottom nav is {ui['bottom_nav']}")
ok(all(s["id"] != "setup" for s in ui["sections"]), "no Setup island", "a Setup island is declared")

nav = code(rd(f"{K}/hub/HubNav.kt"))
old = {"account": ["", "profile", "connect", "autofill", "sites", "snippets", "import"],
       "profiles": ["", "apps", "autofill", "personal", "sites", "snippets", "phone", "devices", "working", "diff"],
       "setup": ["", "runbook", "apps", "configs", "perms", "adb-shell"],
       "secrets": ["", "providers", "connections", "bundle", "secrets", "grants"], "settings": [""],
       "home": ["", "home", "account", "connections", "connect", "import"],
       "configs": ["settings", "setup", "runbook", "apps", "configs", "perms", "adb-shell", "android", "cloud-apps", "phone-apps", "desktop", "c3", "infra", "containers", "repos"],
       "personal": ["", "ids", "cards", "entries", "me", "others", "autocomplete", "personal", "sites", "snippets", "wallet"],
       "data": ["", "providers", "keys", "tokens", "grants", "import", "databases", "bundle", "mesh", "files", "working", "diff", "devices", "backups"]}
def leaves(pages):
    for p in pages:
        if p.get("pages"): yield from leaves(p["pages"])
        else: yield p["id"]
pages = {s["id"]: list(leaves(s["pages"])) for s in ui["sections"]}
legacy = dict(((a, b), (c, d)) for a, b, c, d in re.findall(r'\("([\w-]+)" to "([\w-]*)"\) to \("([\w-]+)" to "([\w-]*)"\)', nav))
missing = [f"{s}/{p}" for s, ps in old.items() for p in ps if (s, p) not in legacy]
ok(not missing, "every old page (all earlier layouts) has a place in HubNav.LEGACY", f"no place for {missing}")
dead = [f"{a}/{b}->{c}/{d}" for (a, b), (c, d) in legacy.items() if c not in pages or (d and d not in pages[c])]
ok(not dead, "every LEGACY target is a declared leaf page", f"LEGACY points at undeclared or container pages {dead}")
ok(nav.index("LEGACY[section to page.orEmpty()]") < nav.index("if (section in KNOWN)"), "resolve() translates a renamed page before passing a current section through", "a renamed page inside a current section passes through untranslated")
strips = {s["id"]: [p["id"] for p in s["pages"] if p.get("pages")] for s in ui["sections"]}
ok(strips == {"vault": ["secrets", "autocomplete"], "config": ["android", "c3"], "connect": ["status", "auth", "fleetsetup"], "devices": ["backup"], "activity": [], "configs": []},
   "the second strips are Vault > Secrets / Autocomplete, Config > Android / C3, Connect > Status / Authentication providers / Setup, Devices > Backup", f"second strips are {strips}")
ok(all(s.get("background") in ("dark", "light", "theme") for s in ui["sections"]), "every section declares its strip background", "a section leaves its background undeclared")
ma = code(rd(f"{K}/MainActivity.kt"))
ok("HubNav.resolve(s, p)" in ma, "MainActivity's go() resolves old ids", "go() does not go through HubNav.resolve")
ok("requested.value = deepLink(intent)" in ma and "override fun onNewIntent(" in ma and "return HubNav.resolve(s, intent" in ma,
   "OPEN intent extras (section, page) deep-link through HubNav.resolve, at launch and on a new intent", "an OPEN intent cannot deep-link, or bypasses HubNav")
ok(ma.count("NavBarTopSecondRow(") >= 2 and ma.count("stripSurface(") >= 2 and "pageId = container.id" in ma and "p.leaf().id" in ma, "MainActivity draws the top strip and the second strip, each on its declared surface, a container pill opening its first leaf", "the second strip or its surface is missing")
ok("LocalDeviceAuth provides deviceAuth" in ma and "SystemDeviceAuth(this)" in ma, "MainActivity provides the device auth", "no device auth provided")

pg = code(rd(f"{K}/hub/HubPages.kt"))
ok(pg.count("gate.reveal(") >= 3, "reveal and copy go through RevealGate", "a reveal or copy bypasses RevealGate")
ok(not re.search(r"Text\(\s*HubAndroid\.value", pg) and not re.search(r"SecretClipboard\.copy\(ctx,\s*HubAndroid", pg),
   "no page draws or copies a value without the gate", "a value is drawn or copied without the gate")
ok("SecretClipboard.copy(ctx, it)" in pg, "copy goes through SecretClipboard (auto-clear)", "copy bypasses SecretClipboard")
rv = code(rd("ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/hub/Reveal.kt"))
# EVERY authenticate block (reveal and revealOnIo) must open with the refusal before anything is read
gated = [re.match(r"\s*\{ ok ->\s*if \(!ok\) \{ denied\(\); return@authenticate \}", b) is not None for b in rv.split("auth.authenticate(reason)")[1:]]
ok(len(gated) >= 2 and all(gated) and rv.index("if (!ok)") < rv.index("val v = read()"),
   "RevealGate reads only after a yes", "RevealGate can read before device auth")
ok("!km.isDeviceSecure) { finish(false)" in rv, "no screen lock = refused (fail closed)", "a phone without a lock can reveal")

hub = [f for f in os.listdir(os.path.join(R, K, "hub")) if f.endswith(".kt")]
logs = [f for f in hub if re.search(r"Log\.[a-z]\(|println\(|printStackTrace", code(rd(f"{K}/hub/{f}")))]
ok(not logs, "no hub file logs", f"hub files log: {logs}")
ai = [f for f in hub if "revealAiKey(" in code(rd(f"{K}/hub/{f}"))]
ok(not ai, "no hub file reads the AI key", f"hub files read the AI key: {ai}")

conn = pg[pg.index("fun ConnectionsPage("):pg.index("fun ImportPage(")]
order = ["KitStatTile(", "KitStatusBanner(", "ConnectionsList(", "SetupAllCard {"]
pos = [conn.find(x) for x in order]
ok(all(p >= 0 for p in pos) and pos == sorted(pos) and all(f'tag = "{t}"' in conn for t in ("home:empty", "home:expired-banner", "home:todo", "home:ok")),
   "Connect > Status > Providers: tiles, the status banner (empty / expired / to do / ok) with its next action, the provider list, Setup all", f"Connections blocks missing or out of order {pos}")
imp = pg[pg.index("fun ImportPage("):pg.index("private fun ConnectionsList(")]
ok(imp.find("AutofillImportBody(") >= 0 and imp.find("AutofillImportBody(") < imp.find("TokenImporter("), "Data > Secrets > Import: personal data, then tokens", "Import blocks missing or out of order")
ok(pg.count("AutofillImportBody(") == 1 and pg.count("TokenImporter(") == 2, "one importer: the personal-data body and the token importer are each drawn once", "an importer is drawn in two places")
prov = pg[pg.index("fun ProviderCard("):pg.index("private fun ConnectToken(")]
ok(pg.count("SecretLine(e,") == 1 and "SecretLine(e," in prov and pg.count("SecretLine(e)") == 0, "the token list is drawn by the provider card only (Data > Secrets > Providers)", "a secret line is drawn outside the provider card")
ok("HubAndroid.providerTarget(ctx, p).run()" in pg and "HubAndroid.appTarget(ctx, app, plan).run()" in pg,
   "each provider and each app profile has its own Setup", "a provider or app profile lost its Setup")
apps = pg[pg.index("fun AppProfilesPage("):pg.index("fun ConnectionsPage(")]
ok("LaunchedEffect(tick)" in apps and "withContext(Dispatchers.IO) { HubAndroid.plan(ctx) }" in apps and "KitState.BUSY" in apps and "HubTags.APPS_NO_PLAN" in apps and "Empty(" in apps,
   "Apps page reads the plan off the main thread, with loading, error and empty states", "Apps page lost its loading / error / empty state or reads the plan on the main thread")
lst = pg[pg.index("private fun ConnectionsList("):pg.index("private fun TokenImporter(")]
ok("horizontalScroll" not in pg and "if (open == r.provider.id) {" in lst and lst.index("pill = worst.word to stateOf(worst)") < lst.index("for (m in AuthMethod.values())"),
   "Connections is a status-first list: one row per provider (worst state, next action), the ways on a tap, no sideways grid", "Connections draws a grid or shows every way at once")
ok('"next: re-auth "' in lst and '"next: set up "' in lst, "each provider row names its next action", "a provider row has no next action")
prov = pg[pg.index("fun ProviderCard("):pg.index("private fun ConnectToken(")]
ok("if (!open) return@KitCard" in prov and prov.index("pill = pill, onClick = { open = !open }") < prov.index("if (!open) return@KitCard") and "mutableStateOf(attention)" in prov,
   "a provider card leads with its status row and opens its details on a tap (by itself when something needs doing)", "a provider card dumps its details with no status row")
ok("KitEmptyState(" in pg and "KitStatusBanner(" in pg and "KitSectionHeader(" in pg and "KitCard(" in pg, "hub pages draw with the kit (header, card, banner, empty state)", "a hub page stopped using the kit")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$A"; cp -r "$ROOT/$A/build.json" "$ROOT/$A/app" "$TMP/r/$A/" 2>/dev/null; mkdir -p "$TMP/r/ab_cloud-libs-shared/libs"; cp -r "$ROOT/ab_cloud-libs-shared/libs/account-ui" "$TMP/r/ab_cloud-libs-shared/libs/"
  rm -rf "$TMP/r/$A/app/build"
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
mutate "Setup island back" "$A/build.json" '"bottom_nav": ["vault",' '"bottom_nav": ["setup", "vault",' || M=1
mutate "an old page loses its place" "$K/hub/HubNav.kt" '("setup" to "perms") to ("connect" to "perms"),' '' || M=1
mutate "go() skips the resolver" "$K/MainActivity.kt" 'HubNav.resolve(s, p)' 'Pair(s, p)' || M=1
mutate "gate reads before auth" "$LK/hub/Reveal.kt" 'if (!ok) { denied(); return@authenticate }' 'read(); if (!ok) { denied(); return@authenticate }' || M=1
mutate "no lock still reveals" "$LK/hub/Reveal.kt" '!km.isDeviceSecure) { finish(false)' 'false) { finish(false)' || M=1
mutate "copy without the gate" "$K/hub/HubPages.kt" 'SecretClipboard.copy(ctx, it)' 'SecretClipboard.copy(ctx, HubAndroid.value(ctx, e.path).orEmpty())' || M=1
mutate "a hub file logs" "$K/hub/TokenImport.kt" 'val t = text.trim()' 'val t = text.trim(); android.util.Log.d("x", t)' || M=1
mutate "Setup all dropped from Connections" "$K/hub/HubPages.kt" '        SetupAllCard { tick++ }' '' || M=1
mutate "a 2026-10-13 id loses its place" "$K/hub/HubNav.kt" '("secrets" to "connections") to ("devices" to "bundle"),' '' || M=1
mutate "a renamed page passes through untranslated" "$K/hub/HubNav.kt" 'val exact = LEGACY[section to page.orEmpty()]
        if (exact != null) return exact.first to exact.second.ifEmpty { null }
        if (section in KNOWN) return section to page' 'if (section in KNOWN) return section to page
        val exact = LEGACY[section to page.orEmpty()]
        if (exact != null) return exact.first to exact.second.ifEmpty { null }' || M=1
mutate "LEGACY points at a container" "$K/hub/HubNav.kt" '("setup" to "") to ("config" to "runbook"),' '("setup" to "") to ("config" to "android"),' || M=1
mutate "a strip is flattened" "$A/build.json" '"id": "devices",' '"id": "devicesx",' || M=1
mutate "a section drops its background" "$A/build.json" '"icon": "activity",
        "background": "dark",' '"icon": "activity",' || M=1
mutate "deep link bypasses the resolver" "$K/MainActivity.kt" 'return HubNav.resolve(s, intent' 'return Pair(s, intent' || M=1
mutate "second strip drawn on the section's surface" "$K/MainActivity.kt" 'surface = NAV.stripSurface(section.id, darkTheme = true, pageId = container.id),' 'surface = NAV.stripSurface(section.id, darkTheme = true),' || M=1
mutate "token list drawn on Connections too" "$K/hub/HubPages.kt" '        KitSectionHeader("Setup all", "", eyebrow = true)' '        for (e in groups.values.flatten()) SecretLine(e) { tick++ }
        KitSectionHeader("Setup all", "", eyebrow = true)' || M=1
mutate "Apps page reads the plan on the main thread" "$K/hub/HubPages.kt" 'withContext(Dispatchers.IO) { HubAndroid.plan(ctx) }' 'HubAndroid.plan(ctx)' || M=1
mutate "Connections shows every way at once" "$K/hub/HubPages.kt" 'if (open == r.provider.id) {' 'if (true) {' || M=1
mutate "a provider card loses its status row" "$K/hub/HubPages.kt" 'if (!open) return@KitCard' 'if (false) return@KitCard' || M=1
mutate "Connections loses its status banner" "$K/hub/HubPages.kt" 'else -> KitStatusBanner("Every provider is connected.", KitState.OK, tag = "home:ok")' 'else -> Unit' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
