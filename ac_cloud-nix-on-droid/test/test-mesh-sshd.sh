#!/bin/sh
# The mesh door (build.json::mesh_sshd): a rebooted or freshly installed phone must listen on the
# mesh without anyone opening a session. The A37 answered ping on 10.0.0.10 and refused 8024/8023
# because nothing in the app ever started sshd.
#
#   B  behaviour: the REAL com.termux.cloud.MeshSshd is compiled on a JVM, its script runs against
#      a stub sshd: first run starts it with the declared port, the WILDCARD binds (never a mesh
#      address), key-only auth and its own host key; a second run with the pid alive starts nothing;
#      no sshd anywhere is a loud exit 3; a non-address bind is refused.
#   W  wiring: BOOT_COMPLETED -> MeshSshdBoot -> foreground TermuxService + the script, with port,
#      bind and start_on_boot read from build.json through gradle.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0; ok() { echo "ok   $1"; }; bad() { echo "FAIL $1"; fail=1; }

PORT=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['mesh_sshd']['port'])" "$DIR/build.json")
BIND=$(python3 -c "import json,sys;print(','.join(json.load(open(sys.argv[1]))['mesh_sshd']['bind']))" "$DIR/build.json")
case ",$BIND," in *,10.*|*,fd0c:*) bad "mesh_sshd.bind names a mesh address ($BIND): it must follow whatever the WG profile assigns";; *) ok "bind is the wildcard ($BIND)";; esac

mkdir -p "$T/src" "$T/cls" "$T/home" "$T/bin"
cp "$DIR/app/src/main/java/com/termux/cloud/MeshSshd.java" "$T/src/"
cat > "$T/src/Gen.java" <<'J'
public class Gen { public static void main(String[] a) { System.out.print(com.termux.cloud.MeshSshd.script(Integer.parseInt(a[0]), a[1].split(","))); } }
J
javac -d "$T/cls" "$T/src/MeshSshd.java" "$T/src/Gen.java" || { echo "FAIL MeshSshd.java does not compile"; exit 1; }
java -cp "$T/cls" Gen "$PORT" "$BIND" > "$T/script.sh"

# Stub sshd + ssh-keygen: record the config, leave a live pid behind like a daemonised sshd.
cat > "$T/bin/sshd" <<'S'
#!/bin/sh
cfg=$2; cp "$cfg" "$STUB_OUT/cfg"; echo started >> "$STUB_OUT/starts"
sleep 30 & echo $! > "$(sed -n 's/^PidFile //p' "$cfg")"
S
printf '#!/bin/sh\nwhile [ $# -gt 1 ]; do shift; done; : > "$1"\n' > "$T/bin/ssh-keygen"
chmod +x "$T/bin/sshd" "$T/bin/ssh-keygen"
export STUB_OUT="$T"
# A PATH of only what the script needs, so the host's own sshd/nix-shell cannot answer for the stub.
mkdir -p "$T/sys"; for t in sh cat mkdir chmod dirname printf sleep sed cp rm; do ln -s "$(command -v $t)" "$T/sys/$t"; done
HOME="$T/home" PATH="$T/bin:$T/sys" sh "$T/script.sh" > "$T/out1" 2>&1 || bad "first run failed: $(cat "$T/out1")"
grep -qx "Port $PORT" "$T/cfg" && ok "sshd started on the declared port $PORT" || bad "sshd not on port $PORT"
grep -qx "ListenAddress 0.0.0.0:$PORT" "$T/cfg" && grep -qx "ListenAddress \[::\]:$PORT" "$T/cfg" && ok "listens on 0.0.0.0 and :: (the mesh tun included, whatever its address)" || bad "wildcard ListenAddress missing"
grep -q '10\.0\.0\.\|fd0c:' "$T/cfg" && bad "sshd_config pins a mesh address" || ok "no mesh address pinned"
grep -qx "PasswordAuthentication no" "$T/cfg" && ok "key auth only" || bad "password auth not off"
grep -q "^HostKey $T/home/.ssh/mesh-sshd/ssh_host_ed25519_key" "$T/cfg" && ok "own host key under ~/.ssh/mesh-sshd" || bad "host key not its own"
HOME="$T/home" PATH="$T/bin:$T/sys" sh "$T/script.sh" > "$T/out2" 2>&1
[ "$(wc -l < "$T/starts")" -eq 1 ] && grep -q "already running" "$T/out2" && ok "second run with sshd alive starts nothing" || bad "second run started sshd again"
kill "$(cat "$T/home/.ssh/mesh-sshd/pid")" 2>/dev/null; rm -f "$T/home/.ssh/mesh-sshd/pid"  # as after a reboot
rm "$T/bin/sshd"
HOME="$T/home" PATH="$T/bin:$T/sys" sh "$T/script.sh" > "$T/out3" 2>&1; rc=$?
[ $rc -eq 3 ] && ok "no sshd anywhere: exit 3, said out loud" || bad "missing sshd exit $rc, not 3"
java -cp "$T/cls" Gen "$PORT" "0.0.0.0;reboot" > /dev/null 2>&1 && bad "a non-address bind was accepted" || ok "a non-address bind is refused"

# Wiring.
R="$DIR/app/src/main/java/com/termux/app/event/SystemEventReceiver.java"
B="$DIR/app/src/main/java/com/termux/app/MeshSshdBoot.java"
G="$DIR/app/build.gradle"
grep -q 'MeshSshdBoot.onBoot(context)' "$R" && ok "BOOT_COMPLETED starts the mesh sshd" || bad "boot receiver does not call MeshSshdBoot"
grep -q 'TerminalDebugApi.prepare(app)' "$B" && grep -q 'MeshSshd.script(BuildConfig.CLOUD_MESH_SSHD_PORT' "$B" && ok "boot path: foreground TermuxService, then the script" || bad "MeshSshdBoot wiring"
grep -q 'cloudMeshSshd().port' "$G" && grep -q 'cloudMeshSshd().start_on_boot' "$G" && ok "gradle reads port/bind/start_on_boot from build.json" || bad "gradle wiring"
grep -q 'android.intent.action.BOOT_COMPLETED' "$DIR/app/src/main/AndroidManifest.xml" && ok "manifest receives BOOT_COMPLETED" || bad "no BOOT_COMPLETED in manifest"

[ $fail -eq 0 ] && echo "PASS mesh-sshd" || exit 1
