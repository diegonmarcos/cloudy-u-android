#!/bin/sh
# test-c3-webserver-native.sh — #1682 P1: the app is a native Kotlin / Compose app over the Rust core, and it has every
# route, root, action, status and page the Tauri web UI had (the parity test of P1).
#
# THE FAILURE THIS EXISTS FOR. The owner moved the app off Tauri: the UI is now native Compose (the fleet's nav bars, web view
# and perms libs) and the Rust server is a JNI core (core/). Two things can silently go wrong: Tauri (or a hand-rolled Kotlin
# file outside the app's package) creeps back, and the native pages quietly drop something the web shell (server/ui/shell.html,
# still served on /__app__/) shows or does. This guard holds both. It replaces test-c3-webserver-no-kotlin.sh, whose rule
# ("no Kotlin beyond the Tauri glue") the owner's decision retired; its pins become the rules below.
#
#   K1  NO TAURI: no tauri.conf.json, no tauri / tauri-build / wry dependency in any Cargo.toml of the app, no Tauri Kotlin
#       (TauriActivity, WryActivity, RustWebView, the RustPlugin / BuildTask gradle plugin), no buildSrc, no `id("rust")`
#   K2  WHERE KOTLIN LIVES: every .kt / .java of the app is under build.json::native.kotlin.dir (or its test_dir); a standalone
#       .kts or a *.gradle.kts that build.json::native.build_scripts does not declare fails
#   K3  THE JNI BINDING: every `external fun` of the Kotlin app has its Java_<pkg>_<Class>_<name> export in core/src/lib.rs and
#       every export has its `external fun`; the Rust core calls MainActivity.syncKeepAlive and R8 keeps it (proguard)
#   K4  NO NEW EXPOSURE: the Kotlin app binds nothing (no ServerSocket, no bind(), no LocalServerSocket) and talks only to
#       http://127.0.0.1 (Api.base); the core binds nothing of its own (no TcpListener / UdpSocket / bind in core/src): the
#       servers bind exactly what build.json::runtime / serving declare, through c3_webserver::start, as before
#   P1  ROUTES: every admin route the web shell reads (`'/__app__/api/...'`, `/__api__/health`) is read by the native app
#       (a constant of Api.kt that a page uses); /__app__/api/configs-open is the web bridge to the shared Configs pages, which
#       the native app draws in place (FleetConfigsPageBody), so it must call that instead
#   P2  PAGES: the native bottom nav = the web bottom tabs (app.rs ROUTES, '-' as '_'), Configs a section behind the gear; the
#       Pages sub-pages = the web PAGES_SUBS labels in order (All | File Explorer | Pages); the API pages = Topology + the api
#       sub-pages of build.json::subpages (same ids and labels, in order); the web Configs pages (About = device report,
#       Engines, Links) and its Server block exist natively; the Configs strip goes through FleetConfigsExtensions.withTail and
#       the web CFG_TAIL ids are the lib's TAIL_IDS
#   P3  ACTIONS: every write the web shell makes has its native call: power (server, keep_alive, ssh_door, follow), sshd (start,
#       stop, follow, mesh_only), served (rescan, declare, stop_undeclared, a server-described action, the add form), roots
#       (open, Start app, Perms, Copy clone), the ADB Shell launch, Open in browser, the link chooser (browser / embedded,
#       remember), Copy All Infos (about, doors), Try it, the topology filter / grouping, the explorer tree
#   P4  STATUSES: the web ROOT_STATUS keys, the door bind states, the served page states and the power states are all handled
#   P5  DECLARATION: every Decl.SECTION_* / PAGE_* id is declared in build.json::ui and every declared section / page is
#       dispatched by Kotlin (no dead page, no undeclared id); General / Perms / About are the shared pages, the api sub-pages
#       are dispatched by their build.json::subpages route
#   P6  ROOTS: no build.json::roots id is typed in Kotlin (they come through /__app__/api/roots)
#
#   sh test-c3-webserver-native.sh          the guard, then every mutation below
#   sh test-c3-webserver-native.sh check    the guard alone (build.sh runs it before the build)
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"

guard() {
  python3 - "$1" <<'PY'
import glob, json, os, re, sys

app = sys.argv[1]
bj = json.load(open(os.path.join(app, "build.json")))
nat = bj["native"]
kdir = nat["kotlin"]["dir"]
tdir = nat["kotlin"]["test_dir"]
scripts = set(nat["build_scripts"])
SKIP = {".git", "target", "build", ".gradle", "dist", "node_modules"}
errors = []

def rd(rel):
    p = os.path.join(app, rel)
    return open(p, encoding="utf-8", errors="replace").read() if os.path.isfile(p) else ""

def strip(src):
    return re.sub(r"/\*.*?\*/", "", re.sub(r"(?<![:\"])//[^\n]*", "", src), flags=re.S)

kotlin = {}
for base, dirs, files in os.walk(app):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for name in files:
        rel = os.path.relpath(os.path.join(base, name), app)
        if name.endswith(".gradle.kts"):
            if rel not in scripts:
                errors.append("K2 %s: a gradle Kotlin script build.json::native.build_scripts does not declare" % rel)
            continue
        if name == "tauri.conf.json":
            errors.append("K1 %s: Tauri's config is back" % rel)
        if name == "Cargo.toml" and re.search(r'^\s*(tauri|tauri-build|wry)\s*=', rd(rel), re.M):
            errors.append("K1 %s: a Tauri dependency is back" % rel)
        if not name.endswith((".kt", ".java", ".kts")):
            continue
        if not (rel.startswith(kdir + "/") or rel.startswith(tdir + "/")):
            errors.append("K2 %s: Kotlin/Java outside build.json::native.kotlin.dir" % rel)
            continue
        kotlin[rel] = rd(rel)
if os.path.isdir(os.path.join(app, nat["android_project"], "buildSrc")):
    errors.append("K1 %s/buildSrc is back (the Tauri RustPlugin / BuildTask)" % nat["android_project"])
main = {k: strip(v) for k, v in kotlin.items() if k.startswith(kdir + "/")}
allk = "\n".join(main.values())
for pat in ("TauriActivity", "WryActivity", "RustWebView", "RustPlugin", "app.tauri"):
    if pat in allk:
        errors.append("K1 Tauri Kotlin (%s) is referenced by the app" % pat)
gradle = rd(os.path.join(nat["android_project"], "app/build.gradle.kts"))
if re.search(r'id\("rust"\)', gradle) or "tauri.build.gradle.kts" in gradle:
    errors.append("K1 the app module applies the Tauri rust plugin / tauri.build.gradle.kts")

# K3 JNI
lib = rd(os.path.join(nat["crate_dir"], "src/lib.rs"))
pkg = bj["android"]["application_id"].replace(".", "_")
exports = set(re.findall(r"Java_%s_([A-Za-z0-9]+)_(native\w+)\(" % pkg, lib))
externs = set()
for rel, src in main.items():
    cls = os.path.splitext(os.path.basename(rel))[0]
    for m in re.finditer(r"\bexternal\s+fun\s+(\w+)\(", src):
        externs.add((cls, m.group(1)))
for e in sorted(externs - exports):
    errors.append("K3 %s.%s is declared external but core/src/lib.rs exports no Java_%s_%s_%s" % (e[0], e[1], pkg, e[0], e[1]))
for e in sorted(exports - externs):
    errors.append("K3 core/src/lib.rs exports %s_%s but no Kotlin class declares that external fun" % e)
bridge = rd(os.path.join(nat["crate_dir"], "src/bridge.rs"))
pro = rd(os.path.join(nat["android_project"], "app/proguard-rules.pro"))
if '"syncKeepAlive"' in bridge and not re.search(r"keepclassmembers class [\w.]*MainActivity \{[^}]*syncKeepAlive\(\)", pro):
    errors.append("K3 the core calls MainActivity.syncKeepAlive by name but proguard-rules.pro does not keep it")
if not re.search(r"keepclasseswithmembernames class [\w.]*MainActivity \{ native <methods>; \}", pro):
    errors.append("K3 proguard-rules.pro does not keep MainActivity's native methods")

# K4 exposure
for rel, src in main.items():
    if re.search(r"\b(ServerSocket|LocalServerSocket|DatagramSocket)\b|\.bind\(", src):
        errors.append("K4 %s opens or binds a socket: the UI only talks to the core's loopback server" % rel)
api = rd(kdir + "/Api.kt")
if 'fun base(): String = "http://127.0.0.1:' not in api:
    errors.append("K4 Api.kt does not talk to http://127.0.0.1 only")
for rel in glob.glob(os.path.join(app, nat["crate_dir"], "src", "*.rs")):
    if re.search(r"\b(TcpListener|UdpSocket)\b|\.bind\(", strip(open(rel).read())):
        errors.append("K4 %s binds a socket of its own: the servers bind through c3_webserver::start only" % os.path.relpath(rel, app))

# P1 routes
shell = rd("server/ui/shell.html")
web_routes = set(re.findall(r"'(/__app__/api/[a-z0-9/_-]+|/__api__/health)'", shell))
consts = dict(re.findall(r'const val (\w+) = "([^"]+)"', api))
pages_src = "\n".join(v for r, v in main.items() if not r.endswith("/Api.kt"))
used = {k for k in consts if re.search(r"\bApi\.%s\b" % k, pages_src)}
native_routes = {consts[k] for k in used}
for r in sorted(web_routes):
    if r == "/__app__/api/configs-open":
        if "FleetConfigsPageBody(" not in allk or "isFleetConfigsPage(" not in allk:
            errors.append("P1 the web shell opens the shared Configs pages (%s); the native app must draw them (FleetConfigsPageBody)" % r)
        continue
    if r not in native_routes:
        errors.append("P1 the web shell reads %s; no native page reads it (an Api.kt constant a page uses)" % r)
if "/__app__/api/roots/ls" not in native_routes:
    errors.append("P1 the File Explorer tree (/__app__/api/roots/ls) is not read natively")
if '"/__app__/root/"' not in api or "Api.rootPath(" not in pages_src:
    errors.append("P1 the roots are not served through /__app__/root/<id>/<path> (Api.rootPath)")

# P2 pages
ui = bj["ui"]
app_rs = rd("server/src/app.rs").split("#[cfg(test)]")[0]
rows = re.findall(r'Route \{ kind: Kind::\w+, path: "([^"]+)", tab: Some\("([^"]+)"\)', app_rs)
top = re.search(r"pub const TOP_BAR: &\[&str\] = &\[([^\]]*)\]", app_rs)
top = re.findall(r'"([^"]+)"', top.group(1)) if top else []
web_bottom = [t.lower().replace(" ", "_").replace("-", "_") for p, t in rows if p not in top]
if ui["bottom_nav"] != web_bottom:
    errors.append("P2 the native bottom nav %r is not the web shell's %r" % (ui["bottom_nav"], web_bottom))
secs = {s["id"]: s for s in ui["sections"]}
def labels(sid): return [p["label"] for p in (secs.get(sid) or {}).get("pages") or []]
def ids(sid): return [p["id"] for p in (secs.get(sid) or {}).get("pages") or []]
m = re.search(r"var PAGES_SUBS = \[(.*?)\];", shell)
web_subs = re.findall(r"label: '([^']+)'", m.group(1)) if m else []
if labels("pages") != web_subs:
    errors.append("P2 the Pages sub-pages %r are not the web shell's %r" % (labels("pages"), web_subs))
api_subs = [(s["id"], s["label"]) for s in bj["subpages"] if s["tab"] == "api"]
if list(zip(ids("api"), labels("api")))[1:] != api_subs or (ids("api") or [""])[0] != "topology":
    errors.append("P2 the API pages %r are not Topology + build.json::subpages api %r" % (list(zip(ids("api"), labels("api"))), api_subs))
if "configs" not in secs or "configs" in ui["bottom_nav"]:
    errors.append("P2 Configs must be a declared section behind the top bar's gear")
for need in ("device", "engines", "links", "server"):
    if need not in ids("configs"):
        errors.append("P2 the web Configs page %r has no native Configs page" % need)
if "FleetConfigsExtensions.withTail(" not in allk:
    errors.append("P2 the native Configs strip does not go through FleetConfigsExtensions.withTail (the fleet tail)")
m = re.search(r"var CFG_TAIL = \[(.*?)\];", shell)
web_tail = re.findall(r"\['(\w+)',", m.group(1)) if m else []
lib_ext = os.path.join(app, "..", "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigsExtensions.kt")
if os.path.isfile(lib_ext):
    m = re.search(r"TAIL_IDS: List<String> = listOf\(([^)]*)\)", open(lib_ext).read())
    lib_tail = re.findall(r'"(\w+)"', m.group(1)) if m else []
    if lib_tail and lib_tail != web_tail:
        errors.append("P2 the web CFG_TAIL %r is not the lib's TAIL_IDS %r" % (web_tail, lib_tail))

# P3 actions: (web needle, native needle, what)
ACTIONS = [
    ("data-power", 'set("server"', "Server ON / OFF"), ("data-power", 'set("keep_alive"', "Keep alive"), ("data-power", 'set("ssh_door"', "SSH door switch"),
    ('data-to="follow"', 'set("ssh_door", null)', "SSH door Follow Keep alive"), ("Turn the Server OFF?", "Turn the Server OFF?", "the Server OFF confirmation"),
    ("data-door-op", '.put("op", o)', "door Start / Stop / Follow"), ("data-mesh-only", '.put("mesh_only", it)', "per-port Mesh-only"),
    ("op: 'rescan'", '.put("op", "rescan")', "Rescan"), ("op: 'declare'", '.put("op", "declare")', "Declare an undeclared port"),
    ("op: 'stop_undeclared'", '.put("op", "stop_undeclared")', "Stop an undeclared port"), ("data-act=", "actions.run(", "a server-described action"),
    ("data-submit", '"c3_form_submit"', "the add form"), ("data-root-open", '"c3_root_open_', "open a root"), ("data-start-app", "Start app", "Start app"),
    ("r.perms", 'optJSONObject("perms")', "a root's Perms action"), ("data-copy-clone", '"c3_copy_clone"', "Copy clone command"),
    ("data-root-reload", "handle.reload()", "reload the frame"), ("data-root-ext", "Api.OPEN", "Open in cloud-browser"),
    ("post('/__app__/api/adb-shell'", "Api.post(Api.ADB_SHELL", "open the SuperApp's ADB Shell"), ("data-choice", '"c3_link_choice_$id"', "the link chooser"),
    ("embedUrl", "fun embedUrl(", "Open embedded"), ("data-remember", "Links.remember(", "remember a link choice"),
    ("data-copy-about", '"c3_copy_about"', "Copy All Infos (about)"), ("data-copy-doors", '"c3_copy_doors"', "Copy All Infos (doors)"),
    ("data-topo-try", '"c3_topo_try"', "Try it (GET)"), ("data-topo-group", '"c3_topo_group_$id"', "topology grouping"), ("data-topo-q", '"c3_topo_q"', "topology filter"),
    ("data-tree-dir", '"c3_tree_dir"', "expand a folder"), ("data-tree-file", '"c3_tree_file"', "select a file"),
]
for web, nat_needle, what in ACTIONS:
    if web in shell and nat_needle not in allk:
        errors.append("P3 the web shell can %s (%s); the native app cannot (%s missing)" % (what, web, nat_needle))

# P4 statuses
m = re.search(r"var ROOT_STATUS = \{(.*?)\};", shell)
for k in (re.findall(r"(\w+): \[", m.group(1)) if m else []):
    if '"%s" to' % k not in allk:
        errors.append("P4 the root status %r is not handled natively" % k)
for st in ('"mesh-only"', '"exposed"', '"listening"', '"connected"', '"not serving"', '"stopped"', '"n/a"', '"unknown"', '"running"'):
    if st not in allk:
        errors.append("P4 the state %s is not handled natively" % st)

# P5 declaration both ways
decl = rd(kdir + "/Decl.kt")
declared_ids = {s["id"] for s in ui["sections"]} | {p["id"] for s in ui["sections"] for p in s.get("pages") or []}
dispatched = {}
for k, v in re.findall(r'const val ((?:SECTION|PAGE)_\w+) = "([^"]+)"', decl):
    dispatched[v] = k
    if v not in declared_ids:
        errors.append("P5 Decl.%s = %r is not declared in build.json::ui" % (k, v))
    elif not re.search(r"\bDecl\.%s\b" % k, pages_src.replace(decl, "")):
        errors.append("P5 Decl.%s is declared but no page dispatches on it" % k)
shared = {"general", "perms", "about"} | {s["id"] for s in bj["subpages"] if s["tab"] == "api"}
for sid in sorted(declared_ids):
    if sid not in dispatched and sid not in shared:
        errors.append("P5 build.json::ui declares %r but Kotlin dispatches no page for it" % sid)

# P6 roots
roots = bj.get("roots") or {}
for kind in ("explorer", "pages"):
    for r in roots.get(kind) or []:
        rid = r.get("id") if isinstance(r, dict) else None
        if rid and re.search(r'"%s"' % re.escape(rid), allk):
            errors.append("P6 the root id %r is typed in Kotlin: roots come from build.json::roots through /__app__/api/roots" % rid)

for e in errors:
    print("    " + e)
sys.exit(1 if errors else 0)
PY
}

if [ "${1:-}" = "check" ]; then
  if guard "$APP"; then echo "native app: no Tauri, Kotlin in place, JNI bound, no new exposure, parity with the web shell"; exit 0; fi
  exit 1
fi

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

if guard "$APP"; then
  pass "K1-K4 P1-P6 the native app: no Tauri, Kotlin in place, JNI bound both ways, no new exposure, every web route / page / action / status native"
else
  fail "the native app guard"
fi

KDIR="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['native']['kotlin']['dir'])" "$APP/build.json")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
  title="$1"; mut="$2"; copy="$WORK/m$(od -An -N4 -tu4 /dev/urandom | tr -d ' ')"
  mkdir -p "$copy/ac_cloud-c3-webserver"
  (cd "$APP" && tar --exclude=./core/target --exclude=./server/target --exclude=build --exclude=.gradle --exclude=dist -cf - .) | (cd "$copy/ac_cloud-c3-webserver" && tar -xf -)
  if [ -d "$APP/../ab_cloud-libs-shared/libs/ui-nav-bars" ]; then
    mkdir -p "$copy/ab_cloud-libs-shared/libs" && cp -R "$APP/../ab_cloud-libs-shared/libs/ui-nav-bars" "$copy/ab_cloud-libs-shared/libs/"
  fi
  c="$copy/ac_cloud-c3-webserver"
  if ! "$mut" "$c"; then fail "M: mutation '$title' did not apply"; return 0; fi
  if guard "$c" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN"; else pass "M: '$title' goes red"; fi
  rm -rf "$copy"
}
ed() { python3 - "$@" <<'PY'
import sys; p, a, b = sys.argv[1], sys.argv[2], sys.argv[3]; s = open(p).read()
assert a in s, a; open(p, "w").write(s.replace(a, b, 1))
PY
}
jed() { python3 - "$@" <<'PY'
import json, sys; p, code = sys.argv[1], sys.argv[2]; d = json.load(open(p)); exec(code); json.dump(d, open(p, "w"), indent=2)
PY
}
m_stray_kt()     { printf 'class Stray\n' > "$1/core/src/Stray.kt"; }
m_tauri_conf()   { printf '{}\n' > "$1/core/tauri.conf.json"; }
m_tauri_dep()    { ed "$1/core/Cargo.toml" 'c3-webserver = { path' 'tauri = "2"
c3-webserver = { path'; }
m_build_src()    { mkdir -p "$1/src-tauri/gen/android/buildSrc"; }
m_extra_kts()    { printf '\n' > "$1/src-tauri/gen/android/app/extra.gradle.kts"; }
m_extern_drop()  { ed "$1/core/src/lib.rs" 'Java_com_diegonmarcos_cloudwebserver_MainActivity_nativeDetach' 'Java_com_diegonmarcos_cloudwebserver_MainActivity_nativeGone'; }
m_keep_drop()    { ed "$1/src-tauri/gen/android/app/proguard-rules.pro" 'public void syncKeepAlive();' 'public void other();'; }
m_socket()       { ed "$1/$KDIR/Core.kt" 'private const val LIB' 'private val s = java.net.ServerSocket(9999)
    private const val LIB'; }
m_core_bind()    { ed "$1/core/src/lib.rs" 'pub fn admin_port() -> u16 {' 'pub fn open() { let _ = std::net::TcpListener::bind("0.0.0.0:1"); }
pub fn admin_port() -> u16 {'; }
m_route_drop()   { sed -i 's/Api\.SSHD/"\/x"/g' "$1/$KDIR/ui/SshdPage.kt" "$1/$KDIR/ui/HomePage.kt" && ! grep -rq 'Api\.SSHD' "$1/$KDIR"; }
m_fleet_inline() { ed "$1/$KDIR/ui/Shell.kt" 'page != null && isFleetConfigsPage(page) -> FleetConfigsPageBody(' 'page != null && isFleetConfigsPage(page) -> NotThere(' && ed "$1/$KDIR/ui/Shell.kt" 'else -> FleetConfigsPageBody(' 'else -> NotThere('; }
m_bottom_drift() { jed "$1/build.json" 'd["ui"]["bottom_nav"]=["pages","api","home","adb_shell","sshd"]'; }
m_subs_drift()   { jed "$1/build.json" 'd["ui"]["sections"][0]["pages"][2]["label"]="Sites"'; }
m_api_drop()     { jed "$1/build.json" 'd["ui"]["sections"][1]["pages"]=d["ui"]["sections"][1]["pages"][:-1]'; }
m_device_drop()  { jed "$1/build.json" 'c=[s for s in d["ui"]["sections"] if s["id"]=="configs"][0]; c["pages"]=[x for x in c["pages"] if x["id"]!="device"]'; }
m_no_tail()      { ed "$1/$KDIR/ui/Shell.kt" 'FleetConfigsExtensions.withTail(s.pages)' 's.pages'; }
m_no_rescan()    { ed "$1/$KDIR/ui/PagesPage.kt" '.put("op", "rescan")' '.put("op", "noop")'; }
m_no_mesh_only() { ed "$1/$KDIR/ui/SshdPage.kt" '.put("mesh_only", it)' '.put("mesh", it)'; }
m_no_confirm()   { ed "$1/$KDIR/ui/HomePage.kt" 'Text("Turn the Server OFF?")' 'Text("Off?")'; }
m_no_embed()     { ed "$1/$KDIR/ui/Links.kt" 'fun embedUrl(' 'fun embedLink(' && ed "$1/$KDIR/ui/Links.kt" 'Links.embedUrl(url)' 'Links.embedLink(url)'; }
m_status_drop()  { ed "$1/$KDIR/ui/PagesPage.kt" '"no_access" to' '"noaccess" to'; }
m_decl_ghost()   { ed "$1/$KDIR/Decl.kt" 'const val PAGE_LINKS = "links"' 'const val PAGE_LINKS = "links"
    const val PAGE_GHOST = "ghost"'; }
m_dead_page()    { ed "$1/$KDIR/ui/Shell.kt" 'page == Decl.PAGE_ENGINES -> EnginesPage()' 'page == "engines-x" -> EnginesPage()'; }
m_root_typed()   { ed "$1/$KDIR/ui/PagesPage.kt" 'val list = (roots' 'val pinned = "git-repos-master"
    val list = (roots'; }
mutate "a Kotlin file outside the app package"                 m_stray_kt
mutate "tauri.conf.json back"                                  m_tauri_conf
mutate "a tauri dependency back in the core"                   m_tauri_dep
mutate "the Tauri buildSrc back"                               m_build_src
mutate "an undeclared gradle Kotlin script"                    m_extra_kts
mutate "an external fun with no Rust export"                   m_extern_drop
mutate "syncKeepAlive not kept by R8"                          m_keep_drop
mutate "the Kotlin app opens a server socket"                  m_socket
mutate "the core binds a socket of its own"                    m_core_bind
mutate "a web route no native page reads"                      m_route_drop
mutate "the shared Configs pages not drawn natively"           m_fleet_inline
mutate "the native bottom nav drifts from the web tabs"        m_bottom_drift
mutate "a Pages sub-page label drifts"                         m_subs_drift
mutate "an API sub-page dropped"                               m_api_drop
mutate "the web Configs device report dropped"                 m_device_drop
mutate "the fleet tail bypassed"                               m_no_tail
mutate "Rescan dropped"                                        m_no_rescan
mutate "the per-port Mesh-only switch dropped"                 m_no_mesh_only
mutate "the Server OFF confirmation dropped"                   m_no_confirm
mutate "Open embedded dropped"                                 m_no_embed
mutate "a root status not handled"                             m_status_drop
mutate "a Kotlin page id build.json does not declare"          m_decl_ghost
mutate "a declared page Kotlin does not dispatch"              m_dead_page
mutate "a root id typed in Kotlin"                             m_root_typed

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-native: all green ──"; else echo "── test-c3-webserver-native: $FAILURES FAILED ──"; exit 1; fi
