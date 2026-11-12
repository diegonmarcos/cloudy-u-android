#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — the file view is the shared one, the app is  ║
# ║ five tabs around it, Rust + web (Tauri), the rename holds.         ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# THE FAILURES THIS EXISTS FOR. (1) :8002 served a custom file browser and renderers the owner never
# asked for; the owner's rule is ONE web UI for the whole fleet, the Linux my-webserver's, served by
# ONE shared server (ab_cloud-libs-shared/libs/fleet-webserver). (2) The fix for (1) took the whole
# app with it: it opened straight onto the one served page and Pages, API, Home and Configs were
# gone, with every check still green. (3) An earlier APK in this family shipped pages that drew "not
# built yet" behind green checks. (4) A rename that leaves one copy of the old name behind publishes
# to the wrong asset, package or workflow, and the guard for that is a ledger, not a hope.
#
#   T1  THE FILE VIEW IS THE SHARED SERVER'S. server/ui holds diag.html (:8003) and shell.html
#       (/__app__/) only; server/src holds about, apis, app, crashes, diag, dns, engines, lib, meshlogs, repos, serving and sub only; every
#       route this crate answers is under /__app__/ (or is the :8003 page); no file-browser code in the
#       shell; the crate depends on the shared server by path and serves the drive through
#       drive_handler over fleet_webserver::handler. (That the shared server IS the Linux UI is
#       test-c3-webserver-shared.sh's business, and that it renders is test-c3-webserver-render.sh's.)
#   T7  NAVIGATION STRUCTURE. The bottom nav is exactly Pages, API, Home, SSHd, ADB Shell (#1677; Configs is the top-bar gear's page), each routed at
#       /__app__/tab/<id> with a view in the shell; the SSHd tab embeds the file view at / below the SSH doors; the Tauri
#       window opens on the shell, never straight onto one served page.
#   T4  NO PLACEHOLDER IN A SHIPPED PAGE: no not-built wording in ui/ or src/.
#   T5  TAURI SHAPE. build.json::tauri maps every shipped ABI to a tauri target;
#       the app crate's [lib] is the declared lib_name and links the server
#       crate; tauri.conf.json carries build.json's identity and declares NO
#       window (one is opened in code only after start() has bound the port);
#       lib.rs binds first, takes port and root from build.json via build.rs,
#       asks the storage permission from Rust; the template's gradle reads
#       build.json for identity, SDK levels and label; the one declared keep-alive service, no exec, no
#       server binary, no rootfs residue. (The no-Kotlin pin itself is
#       test-c3-webserver-no-kotlin.sh.)
#   T6  RENAME LEDGER. The retired spellings (the old cloud- id without the c3-
#       family, its asset and image names) appear nowhere in this app.
#   M   mutation-proof: each defect above is planted in a copy and shown red.
#
# OWN-SOURCE ONLY: reads this application's directory and nothing else.
# python3, grep, jq.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for tool in python3 node grep jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR $tool absent — a verdict from a missing tool is not a verdict"; exit 1; }
done
for f in "$APP/build.json" "$APP/server/src/lib.rs" "$APP/server/src/app.rs" "$APP/server/ui/diag.html" "$APP/server/ui/shell.html" \
         "$APP/src-tauri/src/lib.rs" "$APP/src-tauri/tauri.conf.json" "$APP/src-tauri/gen/android/app/build.gradle.kts"; do
  [ -f "$f" ] || { echo "ERROR missing source: $f — this tester is unrun, not passing"; exit 1; }
done

# ── t1 <app> : the file view is the shared server's; the app's own pages live under /__app__/ ONLY ──
t1() {
  python3 - "$1" <<'PY'
import glob, os, re, sys
app = sys.argv[1]
bad = []
ui = sorted(os.listdir(os.path.join(app, "server/ui"))) if os.path.isdir(os.path.join(app, "server/ui")) else []
if ui != ["diag.html", "shell.html"]:
    bad.append("server/ui holds %r; the only pages this app may carry are diag.html (:8003) and shell.html (/__app__/): the file view at / is the shared Linux UI" % ui)
src = sorted(os.path.basename(p) for p in glob.glob(os.path.join(app, "server/src/*.rs")))
want = ["about.rs", "apis.rs", "app.rs", "crashes.rs", "diag.rs", "dns.rs", "door.rs", "engines.rs", "ipc.rs", "jump.rs", "lib.rs", "meshlogs.rs", "power.rs", "repos.rs", "roots.rs", "served.rs", "serving.rs", "sshd.rs", "sub.rs", "topology.rs"]   # ipc.rs (#1495/#1580): the unified IPC holder — the mesh listener's /ipc/<app>/<route> gateway and its centralized per-peer, per-route ACL over the declared registry, not a file-view route; topology.rs: the mesh topology app page under /__app__/, not a file-view route; sshd.rs: the webserver's own embedded russh sshd (mesh-gated, pubkey only), not an HTTP route; door.rs / jump.rs (#1565): the SSH door supervisor and the jump-host helpers, app logic under /__app__/ with no file-view route; crashes.rs: libs:core's local crash sink (#1122), an app route under /__app__/, not a file-view route; apis/sub/meshlogs/repos (#1490): the API and Pages sub-pages, the mesh /logs route and the repo sites (served.rs: the Pages tab list, add form and undeclared checker, app routes under /__app__/; power.rs #1503: the Server / Keep alive state machine; roots.rs #1677: the Pages tab roots, /__app__/api/roots, /__app__/root/<id>/ and the ADB Shell link, app routes under /__app__/ served through repos.rs), app routes under /__app__/ or the mesh listener's own /logs and /endpoints
if src != want:
    bad.append("server/src holds %r, want exactly %r (no routes/render/files of the file view: those are the shared server's)" % (src, want))
cargo = open(os.path.join(app, "server/Cargo.toml")).read()
if not re.search(r'^fleet-webserver\s*=\s*\{\s*path\s*=\s*"\.\./\.\./ab_cloud-libs-shared/libs/fleet-webserver"', cargo, re.M):
    bad.append("server/Cargo.toml does not depend on the shared web server by path")
for p in sorted(glob.glob(os.path.join(app, "server/src/*.rs"))):
    body = re.split(r"#\[cfg\(test\)\]", open(p).read())[0]
    body = re.sub(r"//.*", "", body)
    for lit in re.findall(r'"(/__[a-z_-]+__[^"]*)"', body):
        if os.path.basename(p) == "repos.rs" and lit.startswith("/__api__/ls?"):
            continue   # #1490 a CLIENT call to a terminal's own shared server (to list its repos), not a route this crate answers
        if lit.startswith("/__app__") or lit in ("/__diag__",) or lit.startswith("/__api__/diag/") or lit in ("/__api__/health", "/__api__/serving"):
            continue
        bad.append("%s spells the route %r: the file view's routes belong to the shared server, this crate answers /__app__/ only" % (os.path.relpath(p, app), lit))
    if re.search(r"include_str!\(\"\.\./ui/(?!diag\.html|shell\.html)", body):
        bad.append("%s embeds a page other than diag.html / shell.html" % os.path.relpath(p, app))
lib = open(os.path.join(app, "server/src/lib.rs")).read()
if "fleet_webserver::handler" not in lib or "drive_handler(" not in lib:
    bad.append("server/src/lib.rs does not serve the drive through drive_handler over fleet_webserver::handler")
appsrc = open(os.path.join(app, "server/src/app.rs")).read()
for path in re.findall(r'path: "([^"]+)"', re.split(r"#\[cfg\(test\)\]", appsrc)[0]):
    if not (path == "/__app__" or path.startswith("/__app__/")):
        bad.append("app.rs routes %r, outside /__app__/: it would shadow the shared file server" % path)
shell = open(os.path.join(app, "server/ui/shell.html")).read()
for needle in ("/__api__/ls", "/__api__/read", "/__api__/search", "loadDir("):
    if needle in shell:
        bad.append("shell.html carries file-browser code (%s): the file view is the shared UI, embedded unchanged" % needle)
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t7 <app> : NAVIGATION STRUCTURE. The app is five tabs, the file view is one of them ─────────────
# THE FAILURE THIS EXISTS FOR: a change made the app open straight onto the one served page and the
# rest of its structure (Pages, API, Home, Configs) vanished with no check going red.
t7() {
  python3 - "$1" <<'PY'
import os, re, sys
app = sys.argv[1]
bad = []
src = open(os.path.join(app, "server/src/app.rs")).read()
body = re.split(r"#\[cfg\(test\)\]", src)[0]
rows = re.findall(r'Route \{ kind: Kind::(\w+), path: "([^"]+)", tab: (None|Some\("([^"]+)"\))', body)
tabs = [r[3] for r in rows if r[3]]
# #1677 the bottom nav is Pages, API, Home, SSHd, ADB Shell; Configs is still a tab page, reached from the top bar's gear (TOP_BAR).
top = re.search(r'pub const TOP_BAR: &\[&str\] = &\[([^\]]*)\]', body)
top = re.findall(r'"([^"]+)"', top.group(1)) if top else []
bottom = [r[3] for r in rows if r[3] and r[1] not in top]
if bottom != ["Pages", "API", "Home", "SSHd", "ADB Shell"]:
    bad.append("the bottom nav is %r, want Pages, API, Home, SSHd, ADB Shell" % bottom)
if top != ["/__app__/tab/configs"] or "Configs" not in tabs:
    bad.append("Configs is not the top bar's tab page (TOP_BAR %r)" % top)
if "$gear.addEventListener('click', function () { show('configs', true); });" not in open(os.path.join(app, "server/ui/shell.html")).read():
    bad.append("the top bar's gear no longer opens Configs")
for r in rows:
    if r[3] and r[1] != "/__app__/tab/" + r[3].lower().replace(" ", "-"):
        bad.append("tab %s is routed at %s" % (r[3], r[1]))
shell = open(os.path.join(app, "server/ui/shell.html")).read()
if re.search(r'<iframe id="fileview"', shell) or "<h2>Files</h2>" in shell:
    bad.append("the stale Files table/iframe is back in the SSHd tab")
for view in ("pages:", "api:", "home:", "sshd:", "configs:", "'adb-shell':"):
    if view not in shell:
        bad.append("shell.html has no view for the %s tab" % view[:-1])
tauri = open(os.path.join(app, "src-tauri/src/lib.rs")).read()
m = re.search(r'format!\("http://127\.0\.0\.1:\{\}(/[^"]*)"', tauri)
if not m or m.group(1) != "/__app__/":
    bad.append("the app opens its window on %r, not on the shell /__app__/ (it must not open straight onto one served page)" % (m.group(1) if m else None))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t4 <app> : no placeholder in a shipped tab ─────────────────────────────
t4() {
  local hits
  # `placeholder=` is the HTML attribute.
  hits="$(grep -rniE "not built( yet)?|coming soon|placeholder|TODO_PAGE|under construction|lorem ipsum" "$1/server/ui" "$1/server/src" 2>/dev/null | grep -v "^.*://" | grep -viE "placeholder=" || true)"
  if [ -n "$hits" ]; then printf '    %s\n' "$hits"; return 1; fi
  return 0
}

# ── t5 <app> : the Tauri shape ─────────────────────────────────────────────
t5() {
  python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bj = json.load(open(os.path.join(app, "build.json")))
bad = []
def read(rel):
    p = os.path.join(app, rel)
    return open(p).read() if os.path.isfile(p) else ""
tauri = bj.get("tauri") or {}
TARGETS = {"aarch64", "armv7", "i686", "x86_64"}   # cargo-mobile2's Android target keys
for v in bj["release"]["variants"]:
    for abi in v["abis"]:
        t = (tauri.get("targets") or {}).get(abi) or {}
        if t.get("tauri") not in TARGETS:
            bad.append("build.json::tauri.targets[%s].tauri = %r is not a tauri android target %s" % (abi, t.get("tauri"), sorted(TARGETS)))
        if not str(t.get("rust", "")).startswith(str(t.get("tauri")) + "-linux-android"):
            bad.append("build.json::tauri.targets[%s].rust = %r is not the Android triple of %r" % (abi, t.get("rust"), t.get("tauri")))
for stale in ("server", "bake"):
    if stale in bj:
        bad.append("build.json still carries a %r block — the exec'd binary and the rootfs are retired" % stale)
crate = tauri.get("crate_dir", "")
cargo = read(os.path.join(crate, "Cargo.toml"))
if not re.search(r'\[lib\][^\[]*name\s*=\s*"%s"' % re.escape(tauri.get("lib_name", "\0")), cargo, re.S):
    bad.append("%s/Cargo.toml [lib] name is not build.json::tauri.lib_name — Tauri's activity would load a library that is not there" % crate)
if not re.search(r'crate-type\s*=\s*\[[^\]]*"cdylib"', cargo):
    bad.append("%s/Cargo.toml does not build a cdylib — nothing for Android to load" % crate)
if not re.search(r'c3-webserver\s*=\s*\{\s*path\s*=\s*"\.\./%s"' % re.escape(tauri.get("server_crate", "\0")), cargo):
    bad.append("%s/Cargo.toml does not link the server crate build.json::tauri.server_crate names" % crate)
conf = json.loads(read(os.path.join(crate, "tauri.conf.json")) or "{}")
if conf.get("identifier") != bj["android"]["application_id"]:
    bad.append("tauri.conf.json identifier %r != build.json::android.application_id %r — the APK would change package" % (conf.get("identifier"), bj["android"]["application_id"]))
if conf.get("productName") != bj["name"]:
    bad.append("tauri.conf.json productName %r != build.json::name %r" % (conf.get("productName"), bj["name"]))
if (conf.get("app") or {}).get("windows") != []:
    bad.append("tauri.conf.json declares a window — Tauri opens declared windows BEFORE setup, so the webview would race the server's bind")
lib = re.sub(r"//.*", "", read(os.path.join(crate, "src/lib.rs")))
i_start, i_win = lib.find("c3_webserver::start("), lib.find("WebviewWindowBuilder::new(")
if i_start < 0 or i_win < 0 or i_start > i_win:
    bad.append("src-tauri/src/lib.rs must call c3_webserver::start( before WebviewWindowBuilder::new( — bind first, then open the window")
if 'env!("C3_WEBSERVER_PORT")' not in lib or 'env!("C3_WEBSERVER_SERVE_ROOT")' not in lib:
    bad.append("src-tauri/src/lib.rs does not take port and root from build.json (env! set by build.rs)")
if re.search(r"\b%d\b" % bj["runtime"]["port"], lib) or bj["runtime"]["serve_root"] in lib:
    bad.append("src-tauri/src/lib.rs types the port or the root — they come from build.json::runtime")
if "READ_EXTERNAL_STORAGE" not in lib or "requestPermissions" not in lib:
    bad.append("src-tauri/src/lib.rs does not ask for the storage permission — the served root would be unreadable")
if "build.json" not in read(os.path.join(crate, "build.rs")):
    bad.append("src-tauri/build.rs does not read build.json")
proj = tauri.get("android_project", "")
gradle = read(os.path.join(proj, "app/build.gradle.kts"))
for want, why in ((r'JsonSlurper\(\)\s*\.parse\(\s*file\("[^"]*build\.json"\)', "read build.json"),
                  (r'applicationId\s*=\s*androidJson\["application_id"\]', "take applicationId from build.json"),
                  (r'namespace\s*=\s*androidJson\["application_id"\]', "take the namespace from build.json"),
                  (r'targetSdk\s*=\s*\(androidJson\["target_sdk"\]', "take targetSdk from build.json"),
                  (r'resValue\("string",\s*"app_name",\s*buildJson\["display_name"\]', "set the launcher label from build.json::display_name"),
                  (r'useLegacyPackaging\s*=\s*true', "store the Rust library compressed (the size budget)"),
                  (r'manifestPlaceholders\["usesCleartextTraffic"\]\s*=\s*"true"', "allow cleartext to the loopback server in every build type")):
    if not re.search(want, gradle):
        bad.append("%s/app/build.gradle.kts does not %s" % (proj, why))
pkg_dir = os.path.join(proj, "app/src/main/java", bj["android"]["application_id"].replace(".", "/"))
if not os.path.isdir(os.path.join(app, pkg_dir)):
    bad.append("%s is missing — `cargo tauri android build` refuses a project whose package dir does not match the identifier" % pkg_dir)
manifest = read(os.path.join(proj, "app/src/main/AndroidManifest.xml"))
if "android.permission.READ_EXTERNAL_STORAGE" not in manifest:
    bad.append("the manifest does not declare READ_EXTERNAL_STORAGE")
# The one declared Android component pair: the keep-alive foreground service and its boot receiver
# (build.json::serving.keepalive). Anything else hand-written in the manifest is still refused.
ka = (json.loads(read("build.json")).get("serving") or {}).get("keepalive") or {}
services = re.findall(r'<service\b[^>]*android:name="([^"]+)"', manifest)
receivers = re.findall(r'<receiver\b[^>]*android:name="([^"]+)"', manifest)
pkg_id = bj["android"]["application_id"]
def full(n): return pkg_id + n if n.startswith(".") else n
if [full(n) for n in services] != [ka.get("service")]:
    bad.append("the manifest's services %r are not exactly build.json::serving.keepalive.service %r" % (services, ka.get("service")))
if [full(n) for n in receivers] != [ka.get("receiver")]:
    bad.append("the manifest's receivers %r are not exactly build.json::serving.keepalive.receiver %r" % (receivers, ka.get("receiver")))
for want in ("android.permission.FOREGROUND_SERVICE", "android.permission.RECEIVE_BOOT_COMPLETED",
             "android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"):
    if want not in manifest:
        bad.append("the manifest does not declare %s — the servers would not start at boot or after an update" % want)
if ka.get("permission") and 'android:permission="%s"' % ka["permission"] not in manifest:
    bad.append("the keep-alive service is not guarded by %s — any app could start it" % ka.get("permission"))
if "nativeStart" not in read(os.path.join(crate, "src/lib.rs")) or "KeepAliveService_nativeStart" not in read(os.path.join(crate, "src/lib.rs")):
    bad.append("src-tauri/src/lib.rs does not export KeepAliveService_nativeStart — the service would have no servers to keep alive")
for stale in ("resolve_runtime.py", "test/test-webserver-rootfs.sh", "app", "settings.gradle", "build.gradle"):
    if os.path.exists(os.path.join(app, stale)):
        bad.append("%s still exists — the Kotlin shell / Route D residue" % stale)
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t6 <app> : rename ledger ───────────────────────────────────────────────
t6() {
  # The retired spellings, assembled so this file does not itself trip the grep.
  local old_id="cloud-web""server" old_asset="Cloud-Web""Server" hits
  hits="$(grep -rnI --exclude-dir=test --exclude-dir=target --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle \
           -e "$old_id" -e "$old_asset" "$1" 2>/dev/null || true)"
  if [ -n "$hits" ]; then printf '    %s\n' "$hits"; return 1; fi
  jq -e --arg n "$(basename "$1" | sed 's/^ac_//')" '.name == $n' "$1/build.json" >/dev/null 2>&1 \
    || { echo "    build.json::name is not the directory's name without ac_ — the fleet id would not match"; return 1; }
  return 0
}

echo "── cloud-c3-webserver ──"
t1 "$APP" && pass "T1 the file view is the shared server's: the app's own pages answer /__app__/ only, no file-browser code of its own" || fail "T1 the app shadows or reimplements the file view"
t7 "$APP" && pass "T7 navigation structure: five bottom tabs Pages, API, Home, SSHd, ADB Shell and Configs behind the top-bar gear, the file view is inside the SSHd tab, the app opens on the shell" || fail "T7 the app lost its navigation structure"
t4 "$APP" && pass "T4 no placeholder wording in a shipped tab" || fail "T4 a shipped tab can render a placeholder"
t5 "$APP" && pass "T5 Tauri shape: ABIs mapped, lib + server crate linked, identity from build.json, bind before window, storage asked from Rust, no shell residue" || fail "T5 the Tauri shape is broken"
t6 "$APP" && pass "T6 rename ledger clean: no retired spelling anywhere in this app" || fail "T6 a retired spelling survives"

# ── M: mutation-proof ──────────────────────────────────────────────────────
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
  local title="$1" check="$2" mut="$3" copy="$WORK/m$RANDOM$RANDOM"
  mkdir -p "$copy"; cp -R "$APP/." "$copy/"
  rm -rf "$copy/server/target" "$copy/src-tauri/target" "$copy/build" "$copy/dist" \
         "$copy/src-tauri/gen/android/app/build" "$copy/src-tauri/gen/android/build" "$copy/src-tauri/gen/android/.gradle"
  "$mut" "$copy" || { fail "M: mutation '$title' did not apply — a mutation that does not mutate proves nothing"; return; }
  if "$check" "$copy" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN — the check is vacuous"; else pass "M: '$title' goes red"; fi
}
m_placeholder() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/diag.html"; s=open(p).read()
new=s.replace('<body>', '<body><div>not built yet</div>',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_custom_page() { printf '<!doctype html><nav>Pages API Home</nav>' > "$1/server/ui/browse.html"; }
m_drop_adb_tab() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/src/app.rs"; s=open(p).read()
new=s.replace('tab: Some("ADB Shell")','tab: None',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_configs_bottom() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/src/app.rs"; s=open(p).read()
new=s.replace('pub const TOP_BAR: &[&str] = &["/__app__/tab/configs"];','pub const TOP_BAR: &[&str] = &[];',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_drop_files_tab() { python3 - "$1" <<'PY'
import sys,re; p=sys.argv[1]+"/server/src/app.rs"; s=open(p).read()
new=s.replace('tab: Some("SSHd")','tab: None',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_open_on_file_view() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/src-tauri/src/lib.rs"; s=open(p).read()
new=s.replace('/__app__/"','/"',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_route_outside_prefix() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/src/app.rs"; s=open(p).read()
new=s.replace('path: "/__app__/api/config"','path: "/__api__/ls"',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_fileview_copy() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace("(data.other.length ? renderOther(data.other) : '') +","(data.other.length ? renderOther(data.other) : '') + '<section><h2>Files</h2><iframe id=\"fileview\" class=\"inline\" title=\"Files\" src=\"/\"></iframe></section>' +",1)
assert new!=s; open(p,"w").write(new)
PY
}
m_route_in_lib() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/src/lib.rs"; s=open(p).read()
new=s.replace('"/__api__/health" =>', '"/__tab__/pages" => Resp::text(200, "planted"),\n        "/__api__/health" =>')
assert new!=s; open(p,"w").write(new)
PY
}
m_own_renderer() { printf 'pub fn page() {}\n' > "$1/server/src/render.rs"; }
m_drop_shared_dep() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/Cargo.toml"; s=open(p).read()
new=s.replace('fleet-webserver = {','fleet-webserver-gone = {')
assert new!=s; open(p,"w").write(new)
PY
}
m_no_legacy_packaging() { python3 - "$1" <<'PY'
import sys,re; p=sys.argv[1]+"/src-tauri/gen/android/app/build.gradle.kts"; s=open(p).read()
new=re.sub(r"useLegacyPackaging\s*=\s*true", "useLegacyPackaging = false", s)
assert new!=s; open(p,"w").write(new)
PY
}
m_extra_service() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/src-tauri/gen/android/app/src/main/AndroidManifest.xml"; s=open(p).read()
new=s.replace('<provider','<service android:name=".Evil" android:exported="true" />\n        <provider',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_no_boot() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/src-tauri/gen/android/app/src/main/AndroidManifest.xml"; s=open(p).read()
new=s.replace('android.intent.action.BOOT_COMPLETED','android.intent.action.NOPE',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_unguarded_service() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/src-tauri/gen/android/app/src/main/AndroidManifest.xml"; s=open(p).read()
new=s.replace('android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"','',1)
assert new!=s; open(p,"w").write(new)
PY
}
m_old_asset() { python3 - "$1" <<'PY'
import sys,json; p=sys.argv[1]+"/build.json"; d=json.load(open(p))
d["release"]["gh_release"]["asset_name"]="Cloud-Web"+"Server.apk"; json.dump(d,open(p,"w"),indent=2)
PY
}
m_rust_port() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/src-tauri/src/lib.rs"; s=open(p).read()
new=s.replace('env!("C3_WEBSERVER_PORT").parse()?', '"8002".parse()?')
assert new!=s; open(p,"w").write(new)
PY
}
m_window_before_bind() { python3 - "$1" <<'PY'
import sys,json; p=sys.argv[1]+"/src-tauri/tauri.conf.json"; d=json.load(open(p))
d["app"]["windows"]=[{"url":"http://127.0.0.1:8002/"}]; json.dump(d,open(p,"w"),indent=2)
PY
}
m_identifier_drift() { python3 - "$1" <<'PY'
import sys,json; p=sys.argv[1]+"/src-tauri/tauri.conf.json"; d=json.load(open(p))
d["identifier"]="com.diegonmarcos.cloudc3webserver"; json.dump(d,open(p,"w"),indent=2)
PY
}
mutate "a custom page next to the shared UI"                 t1 m_custom_page
mutate "a route outside /__app__/ (shadows the file server)" t1 m_route_outside_prefix
mutate "the SSHd tab removed from the nav"                 t7 m_drop_files_tab
mutate "the app opens straight onto the file view"          t7 m_open_on_file_view
mutate "the stale Files table put back in the SSHd tab"     t7 m_fileview_copy
mutate "the ADB Shell tab dropped from the nav"            t7 m_drop_adb_tab
mutate "Configs back in the bottom nav (no top-bar entry)"  t7 m_configs_bottom
mutate "a :8002 route spelled in this crate"                t1 m_route_in_lib
mutate "a renderer of its own in this crate"                t1 m_own_renderer
mutate "the shared server dependency dropped"               t1 m_drop_shared_dep
mutate "a not-built body in the diagnostics page"           t4 m_placeholder
mutate "the Rust library stored uncompressed"               t5 m_no_legacy_packaging
mutate "the port typed into the Tauri app"                  t5 m_rust_port
mutate "a window declared in tauri.conf.json (races bind)"  t5 m_window_before_bind
mutate "tauri.conf.json identifier drifts from build.json"  t5 m_identifier_drift
mutate "a second, undeclared service"                       t5 m_extra_service
mutate "the boot start dropped from the receiver"           t5 m_no_boot
mutate "the keep-alive service open to any app"             t5 m_unguarded_service
mutate "the retired asset name put back"                    t6 m_old_asset

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver: all green ──"; else echo "── test-c3-webserver: $FAILURES FAILED ──"; exit 1; fi
