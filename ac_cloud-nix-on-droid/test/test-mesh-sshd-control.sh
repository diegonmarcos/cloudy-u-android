#!/bin/sh
# The mesh door's start/stop hook for the fleet (build.json::mesh_sshd.control). Cloud C3 WebServer's keep-alive
# supervisor asks this app for the sshd so ONE supervised app brings up the phone's external access.
#
#   S  stop script: the REAL com.termux.cloud.MeshSshd.stopScript() runs on a JVM against a live pid: it ends that pid
#      and only that pid, removes the pidfile, and is idempotent (no / dead / garbage pidfile = "not running", exit 0)
#   M  manifest: MeshSshdService is exported ONLY behind the declared signature permission, with no intent filter
#   D  declaration: the service class, both actions and the permission in Java equal build.json::mesh_sshd.control;
#      start reuses the boot path; start_on_boot stays false (the terminal's own boot default, #1487)
#   N  no baked keys: nothing in the hook names a key, and the sshd still reads its own ~/.ssh/authorized_keys
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0; ok() { echo "ok   $1"; }; bad() { echo "FAIL $1"; fail=1; }
J="$DIR/app/src/main/java/com/termux"

mkdir -p "$T/src" "$T/cls" "$T/home/.ssh/mesh-sshd"
cp "$J/cloud/MeshSshd.java" "$T/src/"
cat > "$T/src/Gen.java" <<'G'
public class Gen { public static void main(String[] a) { System.out.print(com.termux.cloud.MeshSshd.stopScript()); } }
G
javac -d "$T/cls" "$T/src/MeshSshd.java" "$T/src/Gen.java" || { echo "FAIL MeshSshd.java does not compile"; exit 1; }
java -cp "$T/cls" Gen > "$T/stop.sh"

D="$T/home/.ssh/mesh-sshd"
sleep 60 & live=$!
sleep 60 & other=$!
echo "$live" > "$D/pid"
HOME="$T/home" sh "$T/stop.sh" > "$T/o1" 2>&1; rc=$?
sleep 0.3
[ $rc -eq 0 ] && grep -q stopped "$T/o1" && ok "stop ends the pid in the pidfile" || bad "stop did not report stopped (rc $rc: $(cat "$T/o1"))"
kill -0 "$live" 2>/dev/null && bad "the sshd pid is still alive" || ok "the sshd pid is gone"
kill -0 "$other" 2>/dev/null && ok "a different process is untouched (no pattern kill)" || bad "stop killed a process that is not the sshd"
[ -e "$D/pid" ] && bad "pidfile left behind" || ok "pidfile removed"
HOME="$T/home" sh "$T/stop.sh" > "$T/o2" 2>&1; rc=$?
[ $rc -eq 0 ] && grep -q "not running" "$T/o2" && ok "stop twice is a no-op, exit 0" || bad "second stop: rc $rc"
echo "1; reboot" > "$D/pid"
HOME="$T/home" sh "$T/stop.sh" > "$T/o3" 2>&1; rc=$?
[ $rc -eq 0 ] && grep -q "not running" "$T/o3" && [ ! -e "$D/pid" ] && ok "a garbage pidfile is dropped, nothing is killed" || bad "garbage pidfile handling rc $rc"
kill "$other" 2>/dev/null

python3 - "$DIR" <<'PY'
import json, re, sys
d = sys.argv[1]
bj = json.load(open(d + "/build.json"))
c = bj["mesh_sshd"]["control"]
mf = open(d + "/app/src/main/AndroidManifest.xml").read()
svc = open(d + "/app/src/main/java/com/termux/app/MeshSshdService.java").read()
boot = open(d + "/app/src/main/java/com/termux/app/MeshSshdBoot.java").read()
bad = []
m = re.search(r'<service\s+android:name="\.app\.MeshSshdService"(.*?)/>', mf, re.S)
if not m:
    bad.append("M MeshSshdService is not declared as a self-closing <service> (an intent-filter would make it implicit)")
else:
    a = m.group(1)
    if 'android:exported="true"' not in a: bad.append("M not exported")
    if 'android:permission="%s"' % c["permission"] not in a: bad.append("M not behind the declared permission")
if c["permission"] != "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA": bad.append("D the guard is not the fleet constellation permission")
if "." + c["service"].rsplit(".", 1)[1] != ".MeshSshdService" or not c["service"].startswith("com.termux.app."): bad.append("D control.service does not name MeshSshdService")
for k, const in (("action_start", "ACTION_START"), ("action_stop", "ACTION_STOP")):
    if 'String %s = "%s"' % (const, c[k]) not in svc: bad.append("D %s in Java differs from build.json" % const)
if "MeshSshdBoot.start(app, meshOnly)" not in svc or "MeshSshdBoot.stop(app)" not in svc: bad.append("D the service does not use the boot path for start/stop")
if "MeshSshd.script(BuildConfig.CLOUD_MESH_SSHD_PORT" not in boot: bad.append("D MeshSshdBoot.start lost the declared port")
if "START_NOT_STICKY" not in svc or "START_STICKY" in svc.replace("START_NOT_STICKY", ""): bad.append("D the service must be NOT_STICKY")
if bj["mesh_sshd"]["start_on_boot"] is not False: bad.append("D start_on_boot must stay false (#1487)")
if re.search(r"ssh-(rsa|ed25519)|BEGIN [A-Z ]*PRIVATE", svc):
    bad.append("N a key is named or written by the hook")
print("\n".join("FAIL " + b for b in bad) if bad else "ok   manifest, declaration and hook agree (permission-guarded, no intent filter, no keys)")
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] || fail=1
exit $fail
