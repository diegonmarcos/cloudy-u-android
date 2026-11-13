#!/bin/sh
# #1512 Both Cloud Terminals reach the privileged shell through the SuperApp's shell broker, NOT a Shizuku client or an ADB channel of
# their own. `rish` (inside the proot) talks to a loopback relay in the terminal app's process (libs:shell-rish ShellRish/RishRelay),
# which asks the broker under the terminal's own allowlist entry. Asserts, for ac_cloud-termux AND ac_cloud-nix-on-droid:
#   - neither app links libs:shizuku-adb-debug-tools, declares ShizukuProvider or queries the Shizuku package;
#   - both link libs:shell-client and libs:shell-rish (settings.gradle + app/build.gradle) and export rish on launch via ShellRish;
#   - both have a broker allowlist entry under their application id;
#   - rish + its env are bound into the rootfs on enter (termux: enter.sh + install-in-rootfs.sh; nix: bake patch_bin_login_rish from binds[]);
#   - the relay is loopback-only with a per-process token and the launcher carries no secret.
# grep-based; python3 validates the JSON. Any miss exits 1.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"                    # ac_cloud-termux
ANDROID="$(cd "$DIR/.." && pwd)"                           # cloud-u-android
TERMUX="$DIR"
NIX="$ANDROID/ac_cloud-nix-on-droid"
LIB="$ANDROID/ab_cloud-libs-shared/libs/shell-rish/src/main/java/com/diegonmarcos/superapp/shellrish"
ALLOW="$ANDROID/ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json"

fails=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1"; fails=$((fails+1)); }
has()  { if grep -qF -- "$2" "$1" 2>/dev/null; then ok "$3"; else bad "$3 [$2 not in ${1##*/}]"; fi; }
hasnt(){ if grep -qF -- "$2" "$1" 2>/dev/null; then bad "$3 [$2 still in ${1##*/}]"; else ok "$3"; fi; }

echo "-- the terminals have no channel of their own --"
for pair in "termux:$TERMUX:cld.termux" "nix:$NIX:cld.termux.nix"; do
    name=${pair%%:*}; rest=${pair#*:}; repo=${rest%%:*}; appid=${rest#*:}
    hasnt "$repo/settings.gradle" 'shizuku-adb-debug-tools' "[$name] settings.gradle does not include the shizuku lib"
    hasnt "$repo/app/build.gradle" 'shizuku-adb-debug-tools' "[$name] app does not depend on the shizuku lib"
    hasnt "$repo/app/src/main/AndroidManifest.xml" 'ShizukuProvider' "[$name] manifest declares no ShizukuProvider"
    hasnt "$repo/app/src/main/AndroidManifest.xml" 'moe.shizuku.privileged.api' "[$name] manifest queries no Shizuku package"
    has "$repo/settings.gradle" ":libs:shell-client" "[$name] settings.gradle includes libs:shell-client"
    has "$repo/app/build.gradle" 'project(":libs:shell-client")' "[$name] app depends on libs:shell-client"
    has "$repo/settings.gradle" ":libs:shell-rish" "[$name] settings.gradle includes libs:shell-rish"
    has "$repo/app/build.gradle" 'project(":libs:shell-rish")' "[$name] app depends on libs:shell-rish"
    has "$repo/app/src/main/java/com/termux/app/TermuxApplication.java" 'ShellRish' "[$name] exports rish through ShellRish on launch"
    hasnt "$repo/app/src/main/java/com/termux/app/TermuxApplication.java" 'RishBridge' "[$name] no RishBridge left"
    has "$repo/app/src/main/java/com/termux/app/activities/SettingsActivity.java" 'ShellClient.Companion.openArmPage(context)' "[$name] the settings entry opens the SuperApp's arm flow"
    python3 - "$ALLOW" "$appid" <<'PY' || bad "[$name] broker allowlist has no entry for $appid"
import json, sys
d = json.load(open(sys.argv[1]))
c = d["callers"].get(sys.argv[2])
sys.exit(0 if c and c.get("allow") and not c.get("stdin") else 1)
PY
done

echo "-- rish reaches the rootfs on enter --"
has "$TERMUX/rootfs/enter.sh" '-b $HERE/rish/rish:/usr/local/bin/rish' "[termux] enter.sh binds rish"
has "$TERMUX/rootfs/enter.sh" '/usr/local/etc/rish.env' "[termux] enter.sh binds rish.env"
has "$TERMUX/rootfs/install-in-rootfs.sh" '/usr/local/bin/rish' "[termux] install-in-rootfs creates the rish mountpoint"
has "$NIX/app/src/main/cpp/bake_default_packages.py" 'def patch_bin_login_rish' "[nix] bake defines patch_bin_login_rish"
has "$NIX/app/src/main/cpp/bake_default_packages.py" 'patch_bin_login_rish(bin_login, app_id)' "[nix] bake calls patch_bin_login_rish"
python3 - "$TERMUX/build.json" "$NIX/build.json" <<'PY' || bad "build.json rish binds"
import json, sys
for p in sys.argv[1:]:
    d = json.load(open(p)).get("shizuku_client") or {}
    g = [b.get("guest") for b in d.get("binds", [])]
    if "/usr/local/bin/rish" not in g or "/usr/local/etc/rish.env" not in g: sys.exit(1)
PY
[ $? -eq 0 ] && ok "both build.json declare the rish + rish.env binds"

echo "-- the relay --"
has "$LIB/RishRelay.kt" 'InetAddress.getByName("127.0.0.1")' "the relay listens on loopback only"
has "$LIB/RishRelay.kt" 'MessageDigest.isEqual' "the token is compared in constant time"
has "$LIB/RishRelay.kt" 'SecureRandom' "the token is random per process"
has "$LIB/ShellRish.kt" 'ShellClient.over(app)' "requests go to the broker as this app (binder uid)"
has "$LIB/ShellRish.kt" '"0600"' "rish.env is written 0600"
hasnt "$LIB/ShellRish.kt" 'FleetToken' "the fleet token never crosses into the rootfs"

if [ "$fails" -eq 0 ]; then echo "ALL GREEN: both terminals reach the shell through the broker"; exit 0; else echo "$fails FAILED"; exit 1; fi
