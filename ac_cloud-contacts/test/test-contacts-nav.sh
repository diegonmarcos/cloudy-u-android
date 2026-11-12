#!/usr/bin/env bash
# test-contacts-nav: Cloud Contacts' Groups | Business | All | People | Configs layout (#1671, the #1625 / #1626 work moved
# here from Cloud Account), statically. The model itself (Business / People split, app-origin filters, default groups,
# persisted assignment) is ContactFacetsTest's (JVM).
#
# Holds that:
#  1. build.json::ui.bottom_nav is groups, business, all, people, configs (default all); each of the four is one page of
#     contacts.html with a tab-<id> panel that switchTab() draws; Configs declares general, perms, about (the fleet first
#     three) then the app's own import and channels, drawn with FleetConfigsExtensions.withTail + FleetConfigsPageBody;
#  2. the page renders the four facets from the bridge's fields (business, origins, categories, groups) and the
#     switchboard toggles a group through Native.setGroup; the bridge persists it (ContactFacets.encode) and sends them;
#  3. the default groups are Friends, Family, Job, Favorites;
#  4. the device source is behind READ_CONTACTS: declared in the manifest and perms.needs (contacts_read), read by the
#     bridge only after hasContactsPermission(), asked only through Configs > Perms (PermsLink, never requestPermissions);
#  5. FIELD PARITY with the version before #1671: every DOM id, page function and Api method of contacts.html, every
#     @JavascriptInterface method of the bridge, and every earlier section id (people, import, about) is still there.
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-contacts"

check() {
python3 - "$1" "$A" <<'PY'
import json, os, re, sys
root, A = sys.argv[1], sys.argv[2]
fails = 0
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
def rd(p):
    try:
        return open(os.path.join(root, p), encoding="utf-8").read()
    except OSError:
        return ""
K = f"{A}/app/src/main/java/com/diegonmarcos/cloudcontacts"
d = json.loads(rd(f"{A}/build.json") or "{}")
ui = d.get("ui") or {}
secs = {s.get("id"): s for s in ui.get("sections") or []}
html = rd(f"{A}/app/src/main/assets/contacts.html")
ma = rd(f"{K}/MainActivity.kt")
br = rd(f"{K}/ContactsBridge.kt")
fa = rd(f"{K}/facets/ContactFacets.kt")

# 1 nav
ok(ui.get("bottom_nav") == ["groups", "business", "all", "people", "configs"] and ui.get("default_section") == "all",
   "bottom nav Groups | Business | All | People | Configs, default All", f"bottom nav is {ui.get('bottom_nav')} default {ui.get('default_section')}")
web = ["groups", "business", "all", "people"]
ok(all([p.get("id") for p in (secs.get(s) or {}).get("pages") or []] == [s] for s in web),
   "each of Groups / Business / All / People is one page", "a contacts section is not exactly its own one page")
ok(all(f'id="tab-{s}"' in html for s in web + ["import", "about"]), "contacts.html has a panel for every section and Configs' own tabs",
   "contacts.html lacks a tab-<id> panel")
cfg = [p.get("id") for p in (secs.get("configs") or {}).get("pages") or []]
ok(cfg == ["general", "perms", "about", "import", "channels"], "Configs: General, Perms, About (fleet) then Import, Channels (own)",
   f"Configs pages are {cfg}")
ok("FleetConfigsExtensions.withTail(" in ma and "FleetConfigsPageBody(" in ma and "isOptInTail(" in ma,
   "the Configs strip is the fleet default ({own} | Agents, Perms, Logcat, X-Ray App, About)", "the Configs strip is not drawn through withTail / FleetConfigsPageBody")
ok(re.search(r'"import"\s*->\s*"import"', ma) and re.search(r'"channels"\s*->\s*"about"', ma),
   "Configs > Import / Channels open the page's Import / channel-registry panels", "Configs' own tabs do not reach the page")
ok("switchTab('$tab')" in ma or "switchTab('$id')" in ma, "a section tap runs the page's switchTab()", "the shell no longer drives switchTab()")
# 2 facets on the page and the bridge
for fn in ("renderGroups", "renderBusiness", "renderPersons", "groupTogglesHtml"):
    ok(("function %s(" % fn) in html, f"contacts.html draws {fn}", f"contacts.html lost {fn}")
ok("p.business" in html and "categories" in html and "origins" in html and "'cat:'" in html and "'org:'" in html,
   "Business / People split and the People origin + category chips come from the bridge's fields", "the page no longer filters by business / origin")
ok("setGroup" in html and "fun setGroup(" in br and "fun getGroups(" in br and "ContactFacets.encode(" in br and "ContactFacets.decode(" in br,
   "a group toggle persists through Native.setGroup", "the group assignment is no longer persisted by the bridge")
for f in ('"business"', '"origins"', '"categories"', '"groups"'):
    ok(f"put({f}," in br, f"the bridge sends {f}", f"the bridge no longer sends {f}")
# 3 default groups
m = re.search(r'DEFAULT_GROUPS: List<String> = listOf\(([^)]*)\)', fa)
ok(m and re.findall(r'"([^"]+)"', m.group(1)) == ["Friends", "Family", "Job", "Favorites"], "default groups Friends, Family, Job, Favorites",
   "the default groups drifted")
# 4 device source behind the permission
mf = rd(f"{A}/app/src/main/AndroidManifest.xml")
ok("android.permission.READ_CONTACTS" in mf and "contacts_read" in (d.get("perms") or {}).get("needs", []),
   "READ_CONTACTS declared (manifest + perms.needs contacts_read)", "READ_CONTACTS / contacts_read is not declared")
ok(re.search(r"if \(hasContactsPermission\(\)\) raw \+= DeviceContacts\.read\(ctx\)", br) is not None,
   "the bridge reads the device contacts only while READ_CONTACTS is granted", "the device read is not gated on the permission")
ok("PermsLink.open(" in ma and '"contacts_read"' in ma and "requestPermissions" not in ma + br,
   "the grant is asked from Configs > Perms (PermsLink), never by a runtime prompt here", "the grant is asked outside Configs > Perms")
# 5 parity with pre-#1671
IDS = ['about-content', 'app-main', 'bottom-nav', 'filter-chips', 'grant-contacts-btn', 'import-content', 'people-count', 'people-list', 'search-input', 'switchboard-body', 'switchboard-close-btn', 'switchboard-overlay', 'tab-about', 'tab-import', 'tab-people', 'toast']
FNS = ['avatarStyle', 'callBridge', 'channelById', 'channelDotsHtml', 'channelTileHtml', 'classifySource', 'closeSwitchboard', 'copyToClipboard', 'decodeB64Json', 'esc', 'fallbackCopy', 'hasBridge', 'hashStr', 'initNav', 'initPeopleView', 'initSwitchboard', 'openSwitchboard', 'personMatchesGroup', 'personMatchesQuery', 'provenanceChipsHtml', 'rawRowsHtml', 'refreshPeople', 'refreshState', 'renderAbout', 'renderFilterChips', 'renderImport', 'renderPeopleCount', 'renderPeopleList', 'renderSwitchboard', 'sourceGroups', 'switchTab', 'toast', 'toastEl']
API = ['getState', 'getPeople', 'getPerson', 'requestContacts', 'pickImport', 'removeSource', 'open', 'getAbout']
gone = [i for i in IDS if f'id="{i}"' not in html and f"#{i}" not in html] + [f + "()" for f in FNS if ("function %s(" % f) not in html]
api = html.split("var Api = {", 1)[1].split("};", 1)[0] if "var Api = {" in html else ""
gone += ["Api." + a for a in API if not re.search(r"^\s*%s: function" % a, api, re.M)]
gone += ["Native." + a for a in API if not re.search(r"@JavascriptInterface\s+fun %s\(" % a, br)]
ok(not gone, "every DOM id, page function, Api and bridge method of the pre-#1671 version is still there", f"lost since pre-#1671: {gone}")
ids = {p.get("id") for s in secs.values() for p in s.get("pages") or []}
ok({"people", "import", "about"} <= ids, "the earlier pages People, Import, About are all still declared", "an earlier page (people / import / about) is gone")
sys.exit(1 if fails else 0)
PY
}

echo "test-contacts-nav"
check "$ROOT" || exit 1

mutate() {  # mutate <label> <file> <old> <new>
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/$A"; cp -r "$ROOT/$A/build.json" "$ROOT/$A/app" "$tmp/$A/"
  python3 - "$tmp/$2" "$3" "$4" <<'PY' || { echo "  mutation not planted: $1"; return 1; }
import sys
p, a, b = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if a not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
PY
  if check "$tmp" >/dev/null 2>&1; then echo "  mutation NOT caught: $1"; return 1; fi
  echo "  mutation caught: $1"
}
K="$A/app/src/main/java/com/diegonmarcos/cloudcontacts"
M=0
mutate "nav order swapped" "$A/build.json" '"groups",
      "business",' '"business",
      "groups",' || M=1
mutate "device read ungated" "$K/ContactsBridge.kt" 'if (hasContactsPermission()) raw += DeviceContacts.read(ctx)' 'raw += DeviceContacts.read(ctx)' || M=1
mutate "a default group dropped" "$K/facets/ContactFacets.kt" '"Friends", "Family", "Job", "Favorites"' '"Friends", "Family", "Favorites"' || M=1
mutate "Configs tail dropped" "$K/MainActivity.kt" 'FleetConfigsExtensions.withTail(configs.pages)' 'configs.pages' || M=1
mutate "an old page function lost" "$A/app/src/main/assets/contacts.html" 'function renderPeopleCount(' 'function renderPeopleCountX(' || M=1
mutate "group toggle not persisted" "$K/ContactsBridge.kt" 'ContactFacets.encode(next)' 'next.toString()' || M=1
[ "$M" = 0 ] || exit 1
echo "test-contacts-nav: OK"
