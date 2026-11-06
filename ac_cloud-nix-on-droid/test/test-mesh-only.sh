#!/bin/sh
# Mesh-only (build.json::mesh_sshd.mesh_only): SSH reachable over the mesh VPN only, ON by default.
#
#   P  pick: the REAL com.termux.cloud.MeshAddrs on a JVM. Only an interface NAMED like the VPN tun, with an address inside a mesh
#      CIDR, is a mesh address: a wlan0 or rmnet address is never one even when it is 10.0.0.x (home LANs are), a tun address outside
#      the mesh ranges is not one, the v6 mesh range and a scoped literal work
#   B  binds: ON gives mesh addresses plus loopback and NEVER 0.0.0.0 or ::; ON with no mesh address returns null (FAIL CLOSED, no
#      wildcard fallback); OFF gives the declared wildcard
#   C  the generated sshd config of an ON bind has no wildcard ListenAddress; an sshd up with a DIFFERENT config (the old wildcard
#      one) is ended and started again, one up with the same config is left alone (script run against a stub sshd)
#   D  declaration and wiring: default ON, the boot path and the service both go through MeshAddrs.binds and fail closed, the
#      service takes the caller's flag from the declared extra
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0; ok() { echo "ok   $1"; }; bad() { echo "FAIL $1"; fail=1; }
J="$DIR/app/src/main/java/com/termux/cloud"
mkdir -p "$T/src" "$T/cls" "$T/home" "$T/bin" "$T/sys"
cp "$J/MeshAddrs.java" "$J/MeshSshd.java" "$T/src/"
cat > "$T/src/Gen.java" <<'G'
import java.util.*;
import com.termux.cloud.*;
public class Gen {
  public static void main(String[] a) {
    String[] cidrs = {"10.0.0.0/24", "10.1.0.0/24", "fd0c:1d00::/32", "fd0c:1d01::/32"};
    String re = "^(wg|tun)[0-9]*$";
    Map<String, List<String>> m = new LinkedHashMap<>();
    m.put("wlan0", Arrays.asList("10.0.0.5", "fe80::1%wlan0"));
    m.put("rmnet0", Arrays.asList("10.1.0.77"));
    m.put("tun0", Arrays.asList("10.0.0.9", "192.168.1.4", "fd0c:1d00::9%tun0", "fd0c:1d01::a", "fd99::1"));
    m.put("wg1", Arrays.asList("10.1.0.10"));
    List<String> got = MeshAddrs.pick(m, re, cidrs);
    System.out.println("PICK " + got);
    String[] lo = {"127.0.0.1", "::1"}, open = {"0.0.0.0", "::"};
    System.out.println("ON " + Arrays.toString(MeshAddrs.binds(true, got, lo, open)));
    System.out.println("FAILCLOSED " + (MeshAddrs.binds(true, new ArrayList<String>(), lo, open) == null));
    System.out.println("NOMAP " + (MeshAddrs.binds(true, null, lo, open) == null));
    System.out.println("OFF " + Arrays.toString(MeshAddrs.binds(false, null, lo, open)));
    System.out.println("NOIFACE " + MeshAddrs.pick(Collections.singletonMap("wlan0", Arrays.asList("10.0.0.5")), re, cidrs));
    if (a.length > 0) System.out.print(MeshSshd.script(8024, a[0].split(",")));
  }
}
G
javac -d "$T/cls" "$T/src/MeshAddrs.java" "$T/src/MeshSshd.java" "$T/src/Gen.java" 2>&1 | grep -v JAVA_TOOL; [ -f "$T/cls/Gen.class" ] || { echo "FAIL MeshAddrs does not compile"; exit 1; }
out=$(java -cp "$T/cls" Gen 2>/dev/null)
echo "$out" | grep -qx 'PICK \[10.0.0.9, fd0c:1d00::9, fd0c:1d01::a, 10.1.0.10\]' && ok "only tun/wg addresses inside the mesh ranges are picked (wlan0 10.0.0.5 and rmnet are not)" || bad "pick: $(echo "$out" | grep PICK)"
echo "$out" | grep -qx 'NOIFACE \[\]' && ok "a 10.0.0.x address on wlan0 alone is no mesh address" || bad "wlan0 counted as mesh"
echo "$out" | grep -qx 'ON \[10.0.0.9, fd0c:1d00::9, fd0c:1d01::a, 10.1.0.10, 127.0.0.1, ::1\]' && ok "mesh-only binds mesh addresses plus loopback" || bad "ON binds: $(echo "$out" | grep '^ON')"
echo "$out" | grep '^ON' | grep -q '0.0.0.0\|, ::\]\|\[::,' && bad "mesh-only bind list holds the wildcard" || ok "mesh-only bind list holds no 0.0.0.0 and no ::"
echo "$out" | grep -qx 'FAILCLOSED true' && echo "$out" | grep -qx 'NOMAP true' && ok "no mesh address: null, fail closed (no wildcard fallback)" || bad "fail-closed missing"
echo "$out" | grep -qx 'OFF \[0.0.0.0, ::\]' && ok "flag OFF gives the declared wildcard" || bad "OFF binds"

# C: config + restart on change, against a stub sshd
cat > "$T/bin/sshd" <<'S'
#!/bin/sh
cfg=$2; cp "$cfg" "$STUB_OUT/cfg"; echo started >> "$STUB_OUT/starts"
sleep 30 & echo $! > "$(sed -n 's/^PidFile //p' "$cfg")"
S
printf '#!/bin/sh\nwhile [ $# -gt 1 ]; do shift; done; : > "$1"\n' > "$T/bin/ssh-keygen"; chmod +x "$T/bin/sshd" "$T/bin/ssh-keygen"
export STUB_OUT="$T"
for t in sh cat mkdir chmod dirname printf sleep sed cp rm; do ln -s "$(command -v $t)" "$T/sys/$t"; done
run() { HOME="$T/home" PATH="$T/bin:$T/sys" sh "$1" 2>&1; }
java -cp "$T/cls" Gen "0.0.0.0,::" 2>/dev/null | sed '1,6d' > "$T/wild.sh"
java -cp "$T/cls" Gen "10.0.0.9,fd0c:1d00::9,127.0.0.1,::1" 2>/dev/null | sed '1,6d' > "$T/mesh.sh"
run "$T/wild.sh" > "$T/o1"
grep -qx "ListenAddress 0.0.0.0:8024" "$T/cfg" && ok "(setup) the old wildcard sshd is up" || bad "setup: wildcard sshd did not start"
run "$T/mesh.sh" > "$T/o2"
grep -q "restarting" "$T/o2" && [ "$(wc -l < "$T/starts")" -eq 2 ] && ok "a wildcard sshd is ended and started again with the mesh config" || bad "no restart on a changed config: $(cat "$T/o2")"
grep -q '^ListenAddress 0.0.0.0\|^ListenAddress \[::\]:' "$T/cfg" && bad "mesh-only sshd_config still listens on the wildcard" || ok "mesh-only sshd_config has no wildcard ListenAddress"
grep -qx "ListenAddress 10.0.0.9:8024" "$T/cfg" && grep -qx "ListenAddress \[fd0c:1d00::9\]:8024" "$T/cfg" && grep -qx "ListenAddress 127.0.0.1:8024" "$T/cfg" && ok "ListenAddress = the mesh addresses and loopback" || bad "ListenAddress lines wrong"
run "$T/mesh.sh" > "$T/o3"
[ "$(wc -l < "$T/starts")" -eq 2 ] && grep -q "already running" "$T/o3" && ok "the same config twice starts nothing" || bad "same config restarted"
kill "$(cat "$T/home/.ssh/mesh-sshd/pid")" 2>/dev/null

# D: wiring and declaration
python3 - "$DIR" <<'PY'
import json, re, sys
d = sys.argv[1]
bj = json.load(open(d + "/build.json"))["mesh_sshd"]
boot = open(d + "/app/src/main/java/com/termux/app/MeshSshdBoot.java").read()
svc = open(d + "/app/src/main/java/com/termux/app/MeshSshdService.java").read()
gr = open(d + "/app/build.gradle").read()
bad = []
mo = bj["mesh_only"]
if mo["default_on"] is not True: bad.append("mesh-only must default ON (secure by default)")
if not mo["cidrs"] or not mo["ifaces"] or "127.0.0.1" not in mo["loopback"]: bad.append("mesh_only declaration incomplete")
if not any(c.startswith("fd0c:1d00") for c in mo["cidrs"]) or "10.0.0.0/24" not in mo["cidrs"] or "10.1.0.0/24" not in mo["cidrs"]: bad.append("the mesh ranges are not all declared")
if bj["control"]["extra_mesh_only"] != re.search(r'EXTRA_MESH_ONLY = "([^"]+)"', svc).group(1): bad.append("the service's extra differs from build.json::mesh_sshd.control.extra_mesh_only")
if "MeshAddrs.binds(meshOnly" not in boot or "failed closed" not in boot or "stop(app)" not in boot.split("failed closed")[1][:80]: bad.append("MeshSshdBoot.start does not fail closed (end the sshd) when the mesh has no address")
if "BuildConfig.CLOUD_MESH_ONLY_DEFAULT" not in boot or "start(app, BuildConfig.CLOUD_MESH_ONLY_DEFAULT)" not in boot: bad.append("the boot path does not use the declared default")
if "getBooleanExtra(EXTRA_MESH_ONLY" not in svc or "MeshSshdBoot.start(app, meshOnly)" not in svc: bad.append("the service does not pass the caller's flag")
for k in ("CLOUD_MESH_ONLY_DEFAULT", "CLOUD_MESH_IFACES", "CLOUD_MESH_CIDRS", "CLOUD_MESH_LOOPBACK"):
    if k not in gr: bad.append("gradle does not bake " + k)
print("\n".join("FAIL " + b for b in bad) if bad else "ok   default ON, boot and service fail closed, the flag reaches the start path, gradle bakes the declaration")
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] || fail=1
exit $fail
