#!/usr/bin/env bash
# ONE PLACE for the privileged channel. Static: no build, no device, no network.
#
# The ADB Shell page (libs:shizuku-adb-debug-tools AdbShellPage) is the only screen that pairs, connects,
# disconnects, starts or stops the local server, asks Shizuku for permission, switches the channel mode or
# opens Wireless debugging for the person. Everything else shows a compact status chip that opens it.
# 1_cicd/src/data/adb-shell-one-place.json declares the controls, the allowlist (each entry says why it is not
# a second control surface) and the apps that must host the page. Held here:
#   G1  no first-party source outside the allowlist contains a channel control (comments stripped)
#   G2  every allowlisted file still contains one (the list only shrinks)
#   G3  every app whose gradle links the lib exposes the page and declares build.json::privileged_channel.needs (#1512: that is the SuperApp alone)
#   G4  the page is ONE composable, with no minimum height and no Material Button (which reserves 40 dp)
#   G5  the chips (Store bar, Permissions, Dev control) open the page rather than carry a control
#   G6  every app presents ITS OWN identity: the lib hard-codes no app label (pairing name, key subject, server
#       process name) and derives them from the host's build.json (AdbIdentity); the Store's channel status is
#       held per package (AdbScope), so it never reads another app's state
#   G7  two apps, one phone: every app that runs a server of its own has its OWN port and process name, the peer list
#       (AdbPeers.FLEET) names the SuperApp's and Cloud Store's real application ids, each app answers the other over its own
#       AdbStatusProvider authority, the pairing window is claimed and refused across apps, and the SuperApp's home status
#       draws one dot per app
#   G8  ONE page: no first-party code outside the lib draws an ADB Shell page of its own, and the SuperApp and Cloud Store
#       host the same AdbShellHostedPage with no copy beside it
# Then each violation is planted in a scratch copy and must turn a check red.
#
# Usage: ./test-adb-shell-one-place.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
DATA=1_cicd/src/data/adb-shell-one-place.json

check() {  # check <root> [--files]  -> PASS/FAIL lines (exit = #fails); --files prints the files the checks read
python3 - "$1" "${2:-}" <<'PY'
import json, os, re, sys
R, mode = sys.argv[1], sys.argv[2]
D = json.load(open(f"{R}/1_cicd/src/data/adb-shell-one-place.json"))
SKIP = {".git", "build", "node_modules", "z_archive", "test", "tests", "androidTest", "intermediates", ".gradle"}
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad))
    fails += 0 if c else 1
def code(p):
    return COMMENT.sub("", open(os.path.join(R, p), encoding="utf-8", errors="replace").read())

sources = []
for root, dirs, files in os.walk(R):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for f in files:
        if f.endswith((".kt", ".java")):
            sources.append(os.path.relpath(os.path.join(root, f), R))
controls = {k: re.compile(v) for k, v in D["controls"].items()}
allow_dirs = list(D["allow"]["dirs"])
allow_files = set(D["allow"]["files"])
hits = {}
for p in sources:
    t = code(p)
    h = [k for k, rx in controls.items() if rx.search(t)]
    if h: hits[p] = h
def allowed(p): return p in allow_files or any(p.startswith(d) for d in allow_dirs)

# G1
bad = {p: h for p, h in hits.items() if not allowed(p)}
ok(not bad, f"no channel control outside the allowlist ({len(hits)} files hold one, all allowed)",
   "channel controls outside the ADB Shell page: " + "; ".join(f"{p} {h}" for p, h in sorted(bad.items())))
# G2
stale = [p for p in sorted(allow_files) if p not in hits]
ok(not stale, f"every allowlisted file still holds a control ({len(allow_files)} files)",
   "stale allowlist entries (no control left in them; delete the entry): " + ", ".join(stale))

# G3: consumers discovered from gradle, not listed
consumers = []
for d in sorted(os.listdir(R)):
    if not re.match(r"^(aa|ac)_", d): continue
    for g in ("app/build.gradle", "build.gradle", "settings.gradle"):
        gp = os.path.join(R, d, g)
        if os.path.isfile(gp) and re.search(r"project\(\s*[\"']:libs:shizuku-adb-debug-tools[\"']\s*\)", open(gp, encoding="utf-8", errors="replace").read()):
            consumers.append(d); break
hosts = {k: v for k, v in D["hosts"].items() if not k.startswith("_")}
ok(consumers == ["aa_cloud-superapp"], f"#1512 only the SuperApp links the lib: {', '.join(consumers)}", f"#1512 apps other than the SuperApp link the lib (or none does): {consumers}")
ok(not [c for c in consumers if c not in hosts], "every consumer app has a declared host for the page",
   "apps link the lib with no page host declared in the data: " + ", ".join(c for c in consumers if c not in hosts))
for app, h in sorted(hosts.items()):
    fp = os.path.join(R, h["file"])
    ok(os.path.isfile(fp) and h["has"] in code(h["file"]), f"{app} exposes the page ({h['has']})", f"{app} does not expose the ADB Shell page ({h['file']} lacks {h['has']})")
    bp = os.path.join(R, app, "build.json")
    sp = os.path.join(R, app, "privileged-channel.json")   # only where build.json is an input of a published payload's address
    block = None
    if os.path.isfile(bp):
        block = json.load(open(bp, encoding="utf-8")).get("privileged_channel")
    if block is None and os.path.isfile(sp):
        block = json.load(open(sp, encoding="utf-8"))
    needs = (block or {}).get("needs") or []
    good = bool(needs) and all(n.get("id") and n.get("label") and n.get("why") for n in needs) and len({n["id"] for n in needs if n.get("id")}) == len(needs)
    ok(good, f"{app} declares {len(needs)} needs (build.json::privileged_channel)", f"{app}: build.json::privileged_channel.needs is missing, empty, duplicated or lacks id/label/why")

# G4
pg = D["page"]
pdir = pg["dir"]
fb = re.compile(pg["forbidden_in_page"])
for f in pg["files"]:
    t = code(f"{pdir}/{f}")
    m = fb.search(t)
    ok(not m, f"{f}: no minimum height, no Material Button", f"{f} reserves a minimum height or uses a Material Button: {m.group(0) if m else ''}")
defs = [p for p in sources if re.search(r"\bfun\s+" + pg["composable"] + r"\s*\(", code(p))]
ok(defs == [f"{pdir}/AdbShellPage.kt"], f"{pg['composable']} is defined once, in the lib", f"{pg['composable']} is defined in: {defs}")
man = open(f"{R}/{pg['lib']}/src/main/AndroidManifest.xml", encoding="utf-8").read()
ok("com.diegonmarcos.superapp.adbdebug.AdbShellActivity" in man, "the lib manifest declares the page's Activity (every consumer merges it)", "AdbShellActivity is not declared in the lib manifest")

# G5
ok("StoreShell.arm(ctx)" in code("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt"), "the Store bar chip opens the SuperApp's arm flow (the Store has no channel page of its own)", "the Store bar chip no longer opens the SuperApp's arm flow")
for f, why in (("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt", "the Permissions page chip"),
               ("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt", "the Dev control chip")):
    ok("AdbShellLink.open(" in code(f), f"{why} opens the page", f"{why} no longer opens the ADB Shell page")
pp = code("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/PermsPage.kt")
ok('go("setup", "adb-shell")' in pp, "Account's Perms page links to Setup > ADB Shell", "Account's Perms page does not link to the ADB Shell page")

# G6: per-app identity (the lib derives it from the HOST's build.json; no app's label is typed in the shared code)
LIB = "ab_cloud-libs-shared/libs/shizuku-adb-debug-tools"
adbsrc = f"{LIB}/src/main/java/com/diegonmarcos/superapp/adbdebug"
lit = re.compile(r'"[^"\n]*(CloudSuperApp|superapp-ad[b\[])[^"\n]*"')
hard = [p for p in sources if p.startswith(LIB + "/src/main") and lit.search(code(p))]
ok(not hard, "no shared ADB code hard-codes an app label (pairing name, key subject, server process)", "hard-coded app identity in the shared ADB code: " + ", ".join(hard))
am = code(f"{adbsrc}/AdbManager.kt")
ok("identity.deviceName" in am and "identity.certSubject" in am and "AdbIdentity.host(" in am, "AdbManager names the pairing and the key from the host's AdbIdentity", "AdbManager does not derive its pairing name and key subject from AdbIdentity")
gr = open(f"{R}/{LIB}/build.gradle", encoding="utf-8").read()
ok('"HOST_APP_NAME"' in gr and '"HOST_APP_ID"' in gr and "shizukuClientJson.name" in gr, "the lib bakes the consuming app's build.json name + application_id", "the lib does not bake HOST_APP_NAME / HOST_APP_ID from the consuming build.json")
ss = code("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreStatus.kt")
ok("adbdebug" not in ss and "ShellChannels" not in ss and "StoreShell.status(app)" in ss, "the Store bar's channel status is the SuperApp broker's, with no adb link of its own", "StoreStatus names the adb lib / a channel of its own again")
bs = code(f"{adbsrc}/AdbShellBootstrap.kt")
ok("niceName()" in bs.split("pkill", 1)[1][:80] if "pkill" in bs else False, "restarting the server kills THIS app's process name, never another app's", "the server restart kills a hard-coded process name")

# G7: two apps, one phone (SuperApp + Cloud Store share ONE adbd and ONE pairing window, and nothing else)
servers = {}
for d in sorted(os.listdir(R)):
    if not re.match(r"^(aa|ac)_", d): continue
    bp = os.path.join(R, d, "build.json")
    if not os.path.isfile(bp): continue
    ls = ((json.load(open(bp, encoding="utf-8")).get("shizuku_diagnostics") or {}).get("local_server")) or {}
    if ls: servers[d] = (ls.get("port"), ls.get("nice_name"))
ports = [v[0] for v in servers.values()]; nices = [v[1] for v in servers.values()]
ok(len(servers) >= 1 and all(isinstance(p, int) for p in ports) and len(set(ports)) == len(ports),
   f"every app with a server of its own has its own port ({servers})", f"two apps share a local-server port, or one has none: {servers}")
ok(all(nices) and len(set(nices)) == len(nices), "every app with a server of its own has its own process name", f"two apps share a server process name: {servers}")
ids = {d: json.load(open(os.path.join(R, d, "build.json"), encoding="utf-8"))["android"]["application_id"] for d in ("aa_cloud-superapp", "ac_cloud-store")}
peers = code(f"{adbsrc}/AdbPeers.kt")
ok(all(f'"{i}"' in peers for i in ids.values()), f"the peer list names the SuperApp's and Cloud Store's application ids as their build.json declares them ({', '.join(ids.values())})",
   "AdbPeers.FLEET does not name the application ids the two build.json files declare")
ok(len(set(ids.values())) == 2, "the two apps have different application ids (so different keys, prefs, notification channels)", "the two apps share an application id")
prov = re.search(r"<provider\b[^>]*AdbStatusProvider[^>]*/>", man, re.S)
pt = prov.group(0) if prov else ""
ok('android:authorities="${applicationId}.adbstatus"' in pt and 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' in pt and 'android:exported="true"' in pt,
   "every app that links the lib answers its peer over ${applicationId}.adbstatus, behind the signature permission", "the lib manifest does not declare AdbStatusProvider on ${applicationId}.adbstatus behind CONSTELLATION_DATA")
ps = code(f"{adbsrc}/AdbPairingService.kt")
ok("PairingClaim.take(" in ps and "PairingClaim.release()" in ps and "AdbPeerReader.pairingHeldElsewhere(" in ps and "AdbPeers.pairingRefusal(" in ps,
   "pairing claims the shared window, releases it, and is refused while the other app holds it", "AdbPairingService does not claim / release / respect the pairing window across apps")
st = code("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt")
dots = code("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/AdbPeerDots.kt")
ok("AdbPeerDots.tints(" in st and "PeerDots(" in st and "AdbPeers.FLEET.map" in dots, "the home status draws ADB as one dot per app (SuperApp, Cloud Store)", "the home status strip does not draw one ADB dot per app")

# G8: ONE page, no copy
copies = [p for p in sources if not p.startswith(LIB + "/") and re.search(r"\bfun\s+AdbShell\w*\s*\(|\bAdbShellScreen\(", code(p))]
ok(not copies, "no first-party code outside the lib draws an ADB Shell page of its own", "an ADB Shell page outside the lib: " + ", ".join(copies))
both = [a for a in ("aa_cloud-superapp",) if "AdbShellHostedPage(" not in code(hosts[a]["file"])]
ok(not both, "the SuperApp hosts the lib's AdbShellHostedPage (the only app with one)", "these apps do not host AdbShellHostedPage: " + ", ".join(both))

if mode == "--files":
    keep = set(allow_files) | {h["file"] for h in hosts.values()} | {f"{pdir}/{f}" for f in pg["files"]}
    keep |= {f"{pg['lib']}/src/main/AndroidManifest.xml", "1_cicd/src/data/adb-shell-one-place.json",
             "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt",
             "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt",
             "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt",
             "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/PermsPage.kt",
             f"{adbsrc}/AdbManager.kt", f"{adbsrc}/AdbShellBootstrap.kt", f"{LIB}/build.gradle",
             f"{adbsrc}/AdbPeers.kt", f"{adbsrc}/AdbPairingService.kt",
             "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt",
             "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/AdbPeerDots.kt",
             "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreStatus.kt"}
    keep |= {f"{a}/build.json" for a in hosts} | {f"{a}/privileged-channel.json" for a in hosts}
    keep |= {f"{d}/build.json" for d in servers}
    for c in consumers:
        for g in ("app/build.gradle", "build.gradle", "settings.gradle"):
            if os.path.isfile(os.path.join(R, c, g)): keep.add(f"{c}/{g}")
    print("\n".join(sorted(keep)), file=sys.stderr)
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?
FILES="$(check "$ROOT" --files 2>&1 >/dev/null)"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>   (an empty <old> appends <new>)
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  while IFS= read -r x; do [ -f "$ROOT/$x" ] && { mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; }; done <<<"$FILES"
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import os, sys
p, old, new = sys.argv[1:4]
os.makedirs(os.path.dirname(p), exist_ok=True)
s = open(p, encoding='utf-8').read() if os.path.exists(p) else ''
if old and old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1) if old else s + new)
PY
  if check "$d" >/dev/null 2>&1; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
S=ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore
A=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
P=ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug
M=0
mutate "Store bar reconnects by itself" ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt '' $'\nfun z(c: android.content.Context) { ShellAccess.ensure(c) {} }\n' || M=$((M+1))
mutate "Permissions page toggles Wireless debugging" $A/configs/PermissionsFragment.kt '' $'\nfun w(c: android.content.Context) { WirelessDebugging.set(c, true) }\n' || M=$((M+1))
mutate "a new place opens Developer options" $A/devcontrol/DevControlFragment.kt '' $'\nval i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)\n' || M=$((M+1))
mutate "Shizuku prompt in a screen" $A/configs/PermissionsFragment.kt '' $'\nfun s(c: android.content.Context) { ShizukuStatus.act(c) }\n' || M=$((M+1))
mutate "a stale allowlist entry" ab_cloud-libs-shared/libs/wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiUi.kt 'Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS' 'Settings.ACTION_WIFI_SETTINGS' || M=$((M+1))
mutate "the SuperApp hosts a page of its own" $A/configs/AdbShellFragment.kt 'AdbShellHostedPage()' 'AdbShellScreen(dark = true)' || M=$((M+1))
mutate "a second copy of the page in an app" $S/MainActivity.kt '' $'\n@androidx.compose.runtime.Composable fun AdbShellMine() {}\n' || M=$((M+1))
mutate "the peer list drifts from build.json" $P/AdbPeers.kt '"com.diegonmarcos.cloudstore"' '"com.diegonmarcos.cloudstoreX"' || M=$((M+1))
mutate "the status provider is not declared" ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/AndroidManifest.xml 'com.diegonmarcos.superapp.adbdebug.AdbStatusProvider' 'com.diegonmarcos.superapp.adbdebug.Gone' || M=$((M+1))
mutate "the status provider loses its permission" ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/AndroidManifest.xml 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' '' || M=$((M+1))
mutate "pairing never claims the window" $P/AdbPairingService.kt 'PairingClaim.take()' 'Unit' || M=$((M+1))
mutate "pairing ignores the other app's claim" $P/AdbPairingService.kt 'AdbPeerReader.pairingHeldElsewhere(app)' 'null' || M=$((M+1))
mutate "the home status loses the per-app dots" $A/launcher/LauncherStatusStrip.kt 'AdbPeerDots.tints(adbPeers)' 'emptyList()' || M=$((M+1))
mutate "the Store pairs under the SuperApp's name" $P/AdbManager.kt 'getDeviceName(): String = identity.deviceName' 'getDeviceName(): String = "CloudSuperApp"' || M=$((M+1))
mutate "the key subject is the SuperApp's" $P/AdbManager.kt 'X500Name(identity.certSubject)' 'X500Name("CN=cloud-superapp, OU=adbdebug")' || M=$((M+1))
mutate "a server restart kills the SuperApp's process" $P/AdbShellBootstrap.kt "pkill -f '\${niceName().dropLast(1)}[\${niceName().last()}]'" "pkill -f 'superapp-ad[b]'" || M=$((M+1))
mutate "the identity is no longer baked from the host" ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/build.gradle 'HOST_APP_NAME' 'HOST_APP_NAMEX' || M=$((M+1))
mutate "Store re-implements Pair" $S/MainActivity.kt '' $'\nfun x(ctx: android.content.Context) { AdbPairingService.start(ctx) }\n' || M=$((M+1))
mutate "Store bar reconnects by itself" ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt '' $'\nfun z2(c: android.content.Context) { ShellAccess.ensure(c) {} }\n' || M=$((M+1))
mutate "the Store chip stops arming" ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt 'StoreShell.arm(ctx)' 'Unit' || M=$((M+1))
mutate "the Store status names the adb lib again" ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreStatus.kt 'private val running' 'private val adbdebug = 0; private val running' || M=$((M+1))
mutate "Store links the lib again (a second consumer)" ac_cloud-store/app/build.gradle '' $'dependencies { implementation project(\':libs:shizuku-adb-debug-tools\') }\n' || M=$((M+1))
mutate "a sixth app links the lib with no page" ac_cloud-fake/app/build.gradle '' $'dependencies { implementation project(\':libs:shizuku-adb-debug-tools\') }\n' || M=$((M+1))
mutate "the page reserves a minimum height" $P/AdbShellPage.kt 'Column(modifier.fillMaxWidth().padding(8.dp)' 'Column(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(8.dp)' || M=$((M+1))
mutate "the page uses a Material Button" $P/ChannelModeBar.kt '    Box(
        modifier.clip(shape)' '    androidx.compose.material3.OutlinedButton(onClick = onClick) { Text(label) }
    Box(
        modifier.clip(shape)' || M=$((M+1))
mutate "the Activity is not declared" ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/AndroidManifest.xml 'com.diegonmarcos.superapp.adbdebug.AdbShellActivity' 'com.diegonmarcos.superapp.adbdebug.Gone' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
