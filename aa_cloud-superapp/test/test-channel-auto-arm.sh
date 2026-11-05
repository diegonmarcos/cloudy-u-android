#!/usr/bin/env bash
# The privileged channel arms itself only when the app's switch says so. Static: no build, no device.
#   A1  default is DATA: build.json::privileged_channel.default_on (SuperApp false; #1512 it is the only app with a channel)
#   A2  SuperApp's Configs switch (Keep Wireless debugging on) declares the same default
#   A3  every implicit caller of the embedded connect goes through autoArm (gated); raw autoConnect( is explicit-only
#   A4  both boot receivers consult ChannelAutoArm; PrivilegedPlaneWorker and HostShell.reconnect return before connecting
#   A5  ChannelAutoArm.resolve honours the stored choice over the declared default; SuperApp plugs its one switch in
# Then each violation is planted in a scratch copy and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
check() {
python3 - "$1" <<'PY'
import json, os, re, sys
R = sys.argv[1]; fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad)); fails += 0 if c else 1
def src(p): return re.sub(r"//[^\n]*|/\*.*?\*/", "", open(os.path.join(R, p), encoding="utf-8").read(), flags=re.S)
def bj(app): return json.load(open(f"{R}/{app}/build.json"))
SA="aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/"
LIB="ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/"
sa = bj("aa_cloud-superapp")
ok(sa["privileged_channel"].get("default_on") is False, "A1 SuperApp declares default_on=false", "A1 SuperApp must declare privileged_channel.default_on=false")
ctl = [c for g in sa["ui"]["control_panel"]["groups"] for c in g.get("controls", []) if c.get("id") == "wireless_debugging_keepalive"]
ok(len(ctl) == 1 and ctl[0].get("default_on") == sa["privileged_channel"].get("default_on"), "A2 the Configs switch default equals the declared default", "A2 Configs switch default_on differs from privileged_channel.default_on")
# A3: raw autoConnect( only in the lib's explicit paths (EmbeddedAdbChannel itself, ShellAccess.ensure, ChannelDevice step) and the autoArm wrapper
SCOPE = {"aa_cloud-superapp", "ab_cloud-libs-shared"}  # the apps this switch governs; others declare no default (on)
raw = []
for base, dirs, files in os.walk(R):
    if base == R: dirs[:] = [d for d in dirs if d in SCOPE]
    dirs[:] = [d for d in dirs if d not in {".git", "build", "test", "tests", "androidTest", "node_modules", ".gradle", "z_archive"}]
    for f in files:
        if f.endswith((".kt", ".java")):
            p = os.path.relpath(os.path.join(base, f), R)
            if re.search(r"EmbeddedAdbChannel\.autoConnect\(", src(p)): raw.append(p)
allowed = {LIB+"ShellAccess.kt", LIB+"ChannelDevice.kt", LIB+"EmbeddedAdbChannel.kt"}
bad = sorted(set(raw) - allowed)
ok(not bad, "A3 no implicit caller uses the raw autoConnect", "A3 raw EmbeddedAdbChannel.autoConnect( in: " + ", ".join(bad))
ok(re.search(r"fun autoArm\(.*ChannelAutoArm\.enabled\(ctx\).*autoConnect\(ctx\)", src(LIB+"EmbeddedAdbChannel.kt"), re.S) is not None, "A3 autoArm asks the switch before connecting", "A3 EmbeddedAdbChannel.autoArm does not consult ChannelAutoArm.enabled")
for p, why in [(SA+"devcontrol/DevControlServer.kt", "start thread and /api/adb/autoconnect"), (SA+"system/WirelessDebugKeeper.kt", "keeper reconnect"),
               (SA+"system/PrivilegedPlaneWorker.kt", "boot worker"), (LIB+"WirelessDebugging.kt", "implicit re-arm of a privileged tool"),
               ("ab_cloud-libs-shared/libs/updater/src/shell/java/com/diegonmarcos/superapp/updater/ShellChannelAccess.kt", "install path reconnect"), (LIB+"HostShell.kt", "Store/Account re-arm")]:
    ok("autoArm(" in src(p) or "ChannelAutoArm.enabled(" in src(p), f"A3 {os.path.basename(p)} gated ({why})", f"A3 {p} ({why}) is not gated")
ok("ChannelAutoArm.enabled(" in src(SA+"system/PrivilegedPlaneBootReceiver.kt"), "A4 SuperApp boot receiver consults the switch", "A4 PrivilegedPlaneBootReceiver ignores the switch")
hs = src(LIB+"HostShell.kt")
ok(re.search(r"ACTION_BOOT_COMPLETED\)\s*\{\s*if \(ChannelAutoArm\.enabled\(context\)\) HostShell\.schedule", hs) is not None, "A4 Store boot receiver consults the switch", "A4 HostShellBootReceiver schedules the re-arm without the switch")
ok(re.search(r"fun reconnect\([^)]*\)[^{]*\{\s*if \(!ChannelAutoArm\.enabled\(ctx\)\) return", hs) is not None, "A4 HostShell.reconnect returns before keepWirelessDebuggingOn when off", "A4 HostShell.reconnect arms without the switch")
pw = src(SA+"system/PrivilegedPlaneWorker.kt")
ok(pw.find("ChannelAutoArm.enabled(ctx)") != -1 and pw.find("ChannelAutoArm.enabled(ctx)") < pw.find("for (attempt in 1..ATTEMPTS)"), "A4 PrivilegedPlaneWorker checks the switch before its connect loop", "A4 PrivilegedPlaneWorker connects before checking the switch")
ok("ChannelAutoArm.hostSwitch" in src(SA+"App.kt") and "WirelessDebugKeeper.Prefs.enabled" in src(SA+"App.kt"), "A5 SuperApp plugs its one switch into ChannelAutoArm", "A5 SuperApp App.onCreate does not set ChannelAutoArm.hostSwitch")
ok(re.search(r"fun resolve\(stored: Boolean\?, json: String\?\): Boolean = stored \?: declaredDefault\(json\)", src(LIB+"ChannelAutoArm.kt")) is not None, "A5 stored choice wins over the declared default", "A5 ChannelAutoArm.resolve changed")
sys.exit(fails)
PY
}
echo "== channel auto-arm =="; check "$ROOT"; REAL=$?
MUT=0; N=0
SAp=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
LIBp=ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug
FILES="aa_cloud-superapp/build.json $SAp/App.kt $SAp/devcontrol/DevControlServer.kt $SAp/system/WirelessDebugKeeper.kt $SAp/system/PrivilegedPlaneWorker.kt $SAp/system/PrivilegedPlaneBootReceiver.kt ab_cloud-libs-shared/libs/updater/src/shell/java/com/diegonmarcos/superapp/updater/ShellChannelAccess.kt $LIBp/EmbeddedAdbChannel.kt $LIBp/WirelessDebugging.kt $LIBp/HostShell.kt $LIBp/ChannelAutoArm.kt $LIBp/ShellAccess.kt $LIBp/ChannelDevice.kt"
mutate() { # mutate <desc> <file> <python-expr old> <new>
  N=$((N+1)); S="$(mktemp -d)"; mkdir -p "$S/x"
  (cd "$ROOT" && for f in $FILES; do mkdir -p "$S/x/$(dirname "$f")"; cp "$f" "$S/x/$f"; done)
  python3 - "$S/x/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; rm -rf "$S"; MUT=$((MUT+1)); return; }
import sys
p,a,b=sys.argv[1:4]; s=open(p).read()
if a not in s: sys.exit(1)
open(p,'w').write(s.replace(a,b,1))
PY
  if check "$S/x" >/dev/null 2>&1; then echo "  MUTATION NOT CAUGHT: $1"; MUT=$((MUT+1)); else echo "  mutation caught: $1"; fi
  rm -rf "$S"
}
mutate "SuperApp defaults on" aa_cloud-superapp/build.json '"default_on": false,
    "_default_on_doc"' '"default_on": true,
    "_default_on_doc"'
mutate "the DevControl start thread connects raw" $SAp/devcontrol/DevControlServer.kt 'EmbeddedAdbChannel.autoArm(app)' 'EmbeddedAdbChannel.autoConnect(app)'
mutate "the boot receiver ignores the switch" $SAp/system/PrivilegedPlaneBootReceiver.kt 'ChannelAutoArm.enabled(context)' 'true'
mutate "the Store boot receiver ignores the switch" $LIBp/HostShell.kt 'if (ChannelAutoArm.enabled(context)) HostShell.schedule' 'HostShell.schedule'
mutate "autoArm connects whatever the switch says" $LIBp/EmbeddedAdbChannel.kt 'if (ChannelAutoArm.enabled(ctx)) autoConnect(ctx) else false to ChannelAutoArm.OFF_MSG' 'autoConnect(ctx)'
mutate "the privileged tool's re-arm connects raw" $LIBp/WirelessDebugging.kt 'EmbeddedAdbChannel.autoArm(ctx).first' 'EmbeddedAdbChannel.autoConnect(ctx).first'
echo "== RESULT: real tree $REAL failure(s), $MUT mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$MUT" -eq 0 ]
