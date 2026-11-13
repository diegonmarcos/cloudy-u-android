# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/emulator-terminal-target-sdk.sh ───
#!/bin/bash
# #1708 the two Cloud Terminals at targetSdk 35+ : every feature the app had at 28 still runs.
#
# From targetSdk 29 Android forbids execve() of a file in the app's own data directory, and a
# terminal is nothing but such files: the Termux bootstrap ($PREFIX/bin, bash, coreutils, apt,
# dpkg, every #! script), ~/.termux/shell, proot and the loader it extracts. The apps answer it
# the way upstream Termux does (the first exec through the system linker, termux-exec for every
# later one, proot and its loader from the native library directory). This script proves it on
# a real emulator, under the app's own uid and SELinux domain, with nothing mocked:
#
#   emulator-terminal-target-sdk.sh APP APK LIB_APK OUT_DIR MIN_TARGET [SELFTEST_JSON]
#     APP         termux | nix
#     APK         the app APK to test (release-signed; `adb root` is used, never run-as)
#     LIB_APK     the rootfs library APK installed first, as the Store does ('' = none)
#     OUT_DIR     evidence: results.tsv, the device-side logs, logcat, screenshots
#     MIN_TARGET  the lowest targetSdk the installed APK must report (35 for the new build;
#                 0 = whatever it is, for the published build the parity job compares against)
#     SELFTEST_JSON  data/fleet/app/<pkg>/terminal-selftest.json: its checks run in the guest too
#
# WHAT RUNS (each line of results.tsv is  check <TAB> ok|FAIL|skip <TAB> detail):
#   meta.*      targetSdk, the process's SELinux domain, the native libraries on disk
#   native.*    (termux) in the plain Termux environment, through RUN_COMMAND: bash, dash,
#               coreutils, pipelines, #! scripts of every shape, dpkg, apt, pkg, and a package
#               built, installed (with a maintainer script) and removed offline
#   runcmd.*    RUN_COMMAND itself: a background command ran and wrote its output
#   ui.*        the login shell of a typed session reached a prompt and ran what was typed
#   guest.*     inside the login session (the Debian root / the nix store, under proot): the
#               shell, fish, git, node, a #! script in $HOME and on the noexec shared store,
#               a binary written at run time, DNS, HTTPS, the local web server
#   selftest.*  every check of the declared terminal-selftest list
#   failsafe.*  the failsafe session (/system/bin/sh) still starts and still runs the bootstrap
#   net.*       checks that need the network (apt update), reported apart
#
# EXIT 1 when a check is FAIL, except selftest.* and net.*: the declared selftest list includes
# checks an emulator cannot satisfy (agent logins, a repository in the shared store) and net.*
# needs the outside world, so those are measured here and judged by the parity job
# (prove-terminal-target-sdk.yml), which lays this file beside the published build's and fails
# on any check that was ok there and is not here. With MIN_TARGET 0 (the published build being
# measured) the exit is always 0: that run is the yardstick, not the subject.
set -u

APP="$1"; APK="$2"; LIB="${3:-}"; OUT="$4"; MIN_TARGET="${5:-35}"; SELFTEST="${6:-}"
case "$APP" in
  termux) PKG=cld.termux; GUEST_ARGS='-c,sh /sdcard/cloudprove/guest.sh' ;;
  nix)    PKG=cld.termux.nix; GUEST_ARGS='fish,-l,-c,sh /sdcard/cloudprove/guest.sh' ;;
  *) echo "usage: $0 termux|nix APK LIB_APK OUT_DIR MIN_TARGET [SELFTEST_JSON]"; exit 2 ;;
esac
D="/data/data/$PKG/files"
PREFIX="$D/usr"
HOME_DIR="$D/home"
W="$(mktemp -d)"
mkdir -p "$OUT"
R="$OUT/results.tsv"; : > "$R"
row() { printf '%s\t%s\t%s\n' "$1" "$2" "$(printf '%s' "${3:-}" | tr '\n\t\r' '   ' | cut -c1-240)" | tee -a "$R"; }
sh_() { adb shell "$@" 2>&1 | tr -d '\r'; }

evidence() {
  adb exec-out screencap -p > "$OUT/screen-$1.png" 2>/dev/null || true
}
finish() {
  adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null || true
  grep -E "avc: +denied" "$OUT/logcat.txt" | grep -E "untrusted_app|$PKG" > "$OUT/avc-denied.txt" 2>/dev/null || true
  sh_ "ps -AZ -o PID,PPID,LABEL,ARGS" > "$OUT/ps.txt" || true
  sh_ "dumpsys package $PKG" > "$OUT/package.txt" || true
  sh_ "ls -la $D $PREFIX/bin/login $PREFIX/bin/proot-static $PREFIX/var/lib/cloud-rootfs 2>&1" > "$OUT/files.txt" || true
  for f in cloudprove-native.log cloudprove-native.tsv; do sh_ "cat $HOME_DIR/$f" > "$OUT/$f" 2>/dev/null || true; done
  adb pull /sdcard/cloudprove "$OUT/sdcard" >/dev/null 2>&1 || true
  echo "──────── results ────────"; cat "$R"
  fails=$(awk -F'\t' '$2=="FAIL" && $1 !~ /^(selftest|net)\./' "$R" | wc -l)
  soft=$(awk -F'\t' '$2=="FAIL" && $1 ~ /^(selftest|net)\./' "$R" | wc -l)
  echo "$APP: $(awk -F'\t' '$2=="ok"' "$R" | wc -l) ok, $fails FAIL, $soft FAIL among selftest/net (judged by parity), $(awk -F'\t' '$2=="skip"' "$R" | wc -l) skipped"
  echo "── SELinux denials for the app (first 40) ──"; head -40 "$OUT/avc-denied.txt" 2>/dev/null
  if [ "$MIN_TARGET" = 0 ]; then echo "$APP: the published build, measured (not judged here)"; exit 0; fi
  if [ "$fails" -gt 0 ]; then
    echo "::error title=$APP at targetSdk $MIN_TARGET+::$fails check(s) failed: $(awk -F'\t' '$2=="FAIL" && $1 !~ /^(selftest|net)\./{print $1}' "$R" | tr '\n' ' ')"
    exit 1
  fi
  exit 0
}

# ── 0. install, as the Store does (the library first), with the grants the Perms page asks ──
adb root >/dev/null 2>&1 || true
sleep 3; adb wait-for-device
[ "$(sh_ id -u)" = 0 ] || { echo "::error::adb root is not available on this image"; exit 2; }
if [ -n "$LIB" ] && [ -f "$LIB" ]; then adb install -r "$LIB" >/dev/null || { echo "::error::lib APK install failed"; exit 2; }; fi
adb install -r -g "$APK" >/dev/null || { echo "::error::app APK install failed"; exit 2; }
SDK="$(sh_ getprop ro.build.version.sdk)"
# All files access (the storage grant at targetSdk 30+), battery optimisation off and display over
# other apps: the three special grants the app's Perms page asks for at first run.
sh_ "appops set $PKG MANAGE_EXTERNAL_STORAGE allow" >/dev/null
sh_ "appops set $PKG SYSTEM_ALERT_WINDOW allow" >/dev/null
sh_ "cmd deviceidle whitelist +$PKG" >/dev/null
adb logcat -c || true

target="$(sh_ "dumpsys package $PKG" | sed -n 's/.*targetSdk=\([0-9]*\).*/\1/p' | head -1)"
if [ "${target:-0}" -ge "$MIN_TARGET" ] && [ -n "$target" ]; then row meta.target_sdk ok "targetSdk=$target (device API $SDK)"; else row meta.target_sdk FAIL "targetSdk=${target:-?} < $MIN_TARGET"; fi

# ── 1. the scripts the device runs ─────────────────────────────────────────
mkdir -p "$W/cloudprove"
cat > "$W/cloudprove/lib.sh" <<'EOS'
# t <check> <command...>: one line in $R; ok = exit 0. s <check> <tool> ...: skip when the tool is absent.
t() { n="$1"; shift; o="$("$@" 2>&1)"; rc=$?; if [ "$rc" = 0 ]; then st=ok; else st=FAIL; fi
      printf '%s\t%s\t%s\n' "$n" "$st" "rc=$rc $(printf '%s' "$o" | head -4 | tr '\n\t\r' '   ' | cut -c1-200)" >> "$R"; }
s() { n="$1"; tool="$2"; shift 2; if command -v "$tool" >/dev/null 2>&1; then t "$n" "$@"; else printf '%s\tskip\t%s is not installed here\n' "$n" "$tool" >> "$R"; fi; }
EOS

# native.sh: plain Termux, run by $PREFIX/bin/bash through RUN_COMMAND.
cat > "$W/cloudprove/native.sh" <<'EOS'
R="$HOME/cloudprove-native.tsv"; : > "$R"
exec > "$HOME/cloudprove-native.log" 2>&1
. /sdcard/cloudprove/lib.sh
set -x
P="$HOME/cloudprove"; rm -rf "$P"; mkdir -p "$P"; cd "$P" || exit 1
printf 'info.context\tok\t%s\n' "$(cat /proc/self/attr/current 2>/dev/null | tr -d '\0')" >> "$R"
printf 'info.ld_preload\tok\t%s\n' "${LD_PRELOAD:-unset}" >> "$R"
printf 'info.linker_exec\tok\t%s\n' "$(termux-exec-system-linker-exec is-enabled 2>&1 | tail -1)" >> "$R"
printf 'info.proc_self_exe\tok\t%s\n' "$(readlink /proc/self/exe 2>&1)" >> "$R"
t native.bash            bash -c 'echo "bash $BASH_VERSION"'
t native.dash            "$PREFIX/bin/sh" -c 'echo dash-ok'
t native.coreutils       sh -c 'ls "$PREFIX/bin/login" && id -u && uname -s && date +%Y >/dev/null && stat -c %s "$PREFIX/bin/login"'
t native.pipeline        sh -c 'test "$(seq 1 5 | sort -r | head -1)" = 5 && test "$(printf "a\nb\n" | grep -c .)" = 2'
t native.sed_awk_grep    sh -c 'test "$(echo abc | sed s/b/X/ | awk "{print \$1}")" = aXc'
t native.find_xargs      sh -c 'find "$PREFIX/bin" -maxdepth 1 -name "ba*" | xargs -n1 basename | grep -qx bash'
t native.env_exec        env FOO=bar sh -c 'test "$FOO" = bar'
t native.subshell_bg     sh -c '(sleep 0.2; echo bg) & wait; true'
t native.tar_gzip        sh -c 'echo hi > f.txt && tar czf f.tgz f.txt && rm f.txt && tar xzf f.tgz && grep -q hi f.txt'
t native.which_path      sh -c 'command -v apt && command -v dpkg && command -v bash'
# #! scripts in the data directory, every shape.
printf '#!/usr/bin/env bash\necho "env-bash:$1"\n' > s-env.sh
printf '#!%s/bin/sh\necho prefix-sh\n' "$PREFIX" > s-prefix.sh
printf '#!/system/bin/sh\necho system-sh\n' > s-system.sh
printf '#!/bin/sh\necho bin-sh\n' > s-bin.sh
printf 'echo no-shebang\n' > s-none.sh
chmod 0700 s-*.sh
t native.shebang_env     sh -c 'test "$(./s-env.sh x)" = env-bash:x'
t native.shebang_prefix  sh -c 'test "$(./s-prefix.sh)" = prefix-sh'
t native.shebang_system  sh -c 'test "$(./s-system.sh)" = system-sh'
t native.shebang_bin_sh  sh -c 'test "$(./s-bin.sh)" = bin-sh'
t native.shebang_none    bash -c 'test "$(./s-none.sh)" = no-shebang'
t native.shebang_from_bash bash -c 'test "$(./s-env.sh y)" = env-bash:y && test "$(./s-prefix.sh)" = prefix-sh'
t native.exec_builtin    sh -c 'exec "$PREFIX/bin/true"'
t native.path_lookup     sh -c 'PATH="$P:$PATH"; test "$(s-prefix.sh)" = prefix-sh'
# The package manager: versions, the database, and a package built, installed and removed offline.
t native.dpkg_version    dpkg --version
t native.apt_version     apt --version
t native.dpkg_status     dpkg -s bash
t native.apt_list        sh -c 'test "$(apt list --installed 2>/dev/null | wc -l)" -gt 20'
t native.apt_cache       apt-cache policy bash
t native.pkg_list        sh -c 'test "$(pkg list-installed 2>/dev/null | wc -l)" -gt 20'
mkdir -p "deb/DEBIAN" "deb$PREFIX/share/cloudprove"
printf 'Package: cloudprove\nVersion: 1.0\nArchitecture: all\nMaintainer: ci <ci@localhost>\nDescription: target sdk probe\n' > deb/DEBIAN/control
printf '#!%s/bin/sh\necho postinst-ran > %s/share/cloudprove/postinst.txt\n' "$PREFIX" "$PREFIX" > deb/DEBIAN/postinst
chmod 0755 deb/DEBIAN deb/DEBIAN/postinst
echo hello > "deb$PREFIX/share/cloudprove/hello.txt"
t native.dpkg_deb_build  dpkg-deb --build deb cloudprove.deb
t native.dpkg_install    dpkg -i cloudprove.deb
t native.dpkg_postinst   grep -q postinst-ran "$PREFIX/share/cloudprove/postinst.txt"
t native.dpkg_listed     sh -c 'dpkg -l cloudprove | grep -q "^ii"'
t native.apt_remove      apt-get remove -y cloudprove
t native.dpkg_removed    sh -c '! test -e "$PREFIX/share/cloudprove/hello.txt"'
t net.apt_update         bash -c 'set -o pipefail; apt-get update 2>&1 | tail -3'
echo done > "$HOME/cloudprove-native.done"
EOS

# guest.sh: inside the login session (under proot). Results go to /sdcard, which both terminals bind.
{
cat <<'EOS'
R=/sdcard/cloudprove/guest.tsv; : > "$R"
. /sdcard/cloudprove/lib.sh
P="$HOME/cloudprove-guest"; rm -rf "$P"; mkdir -p "$P"; cd "$P" || exit 1
printf 'info.guest_id\tok\t%s\n' "$(id 2>&1 | cut -c1-80)" >> "$R"
printf 'info.guest_shell\tok\t%s\n' "$(readlink /proc/$PPID/exe 2>&1)" >> "$R"
t guest.uname          sh -c 'test "$(uname -s)" = Linux'
t guest.ls_home        sh -c 'test "$(ls -a "$HOME" | wc -l)" -gt 2'
s guest.fish fish      fish -c 'uname'
s guest.bash bash      bash -c 'echo "bash $BASH_VERSION"'
s guest.zsh zsh        zsh -c 'echo zsh-ok'
t guest.pipeline       sh -c 'test "$(seq 1 5 | sort -r | head -1)" = 5'
printf '#!/usr/bin/env sh\necho home-script\n' > s.sh; chmod 0755 s.sh
t guest.shebang_home   sh -c 'test "$(./s.sh)" = home-script'
t guest.exec_written   sh -c 'cp "$(command -v sh)" ./sh-copy && chmod 0755 ./sh-copy && test "$(./sh-copy -c "echo copied")" = copied'
mkdir -p /sdcard/cloudprove/x; printf '#!/usr/bin/env sh\necho store-script\n' > /sdcard/cloudprove/x/s.sh
t guest.shebang_noexec sh -c 'cd /sdcard/cloudprove/x && test "$(./s.sh)" = store-script'
s guest.git git        sh -c 'git --version && rm -rf r && git init -q r && cd r && git -c user.name=ci -c user.email=ci@localhost commit -q --allow-empty -m probe && git log --oneline | grep -q probe'
s guest.node node      node -e 'console.log(1 + 1)'
s guest.node_spawn node node -e 'const r = require("child_process").spawnSync("sh", ["-c", "echo spawned"]); if (String(r.stdout).trim() !== "spawned") process.exit(1)'
s net.guest_node_netif node node -e 'const n = require("os").networkInterfaces(); console.log(Object.keys(n).join(","))'
s guest.python python3 python3 -c 'import subprocess; print(subprocess.run(["sh", "-c", "echo py"], capture_output=True, text=True).stdout.strip())'
s guest.ip_addr ip     sh -c 'ip -o addr | head -3'
s guest.claude claude  claude --version
s guest.gh gh          gh --version
s guest.ssh ssh        ssh -V
s guest.curl_version curl curl --version
s guest.nix nix        nix --version
s guest.ps ps          sh -c 'ps aux | head -3'
t guest.tmp            sh -c 'f="$(mktemp)" && echo x > "$f" && rm "$f"'
t guest.store_dir      sh -c 'test -d "$HOME/cloud-shared-store" || test -L "$HOME/cloud-shared-store"'
t guest.sdcard         sh -c 'test -w /sdcard/cloudprove'
s net.guest_dns getent sh -c 'getent hosts github.com'
s net.guest_https curl sh -c 'test "$(curl -sS -o /dev/null -m 30 -w "%{http_code}" https://github.com)" -lt 500'
EOS
if [ -n "$SELFTEST" ] && [ -f "$SELFTEST" ]; then
  python3 - "$SELFTEST" <<'PY'
import json, sys
for i, check in enumerate(json.load(open(sys.argv[1]))["checks"], 1):
    # Every declared check is `sh -c '<posix script>'` with no single quote or backslash inside.
    print("t selftest.%02d %s" % (i, check))
PY
fi
echo 'echo done > /sdcard/cloudprove/guest.done'
} > "$W/cloudprove/guest.sh"

# ui.sh: typed into the first session, as test-terminal-fresh-install does.
printf '%s\n' 'echo "L$(ls -a "$HOME" | wc -l)U$(uname -s)" > /sdcard/cloudprove/ui.txt' > "$W/cloudprove/ui.sh"

# failsafe.sh: typed into the failsafe session (/system/bin/sh, the system's PATH).
if [ "$APP" = termux ]; then
cat > "$W/cloudprove/failsafe.sh" <<EOS
R=/sdcard/cloudprove/failsafe.tsv; : > "\$R"
. /sdcard/cloudprove/lib.sh
PREFIX=$PREFIX; export PREFIX
printf 'info.failsafe_shell\tok\t%s\n' "\$(readlink /proc/\$\$/exe 2>&1)" >> "\$R"
t failsafe.system_shell   test -x /system/bin/sh
t failsafe.bootstrap_ls   \$PREFIX/bin/ls \$PREFIX/bin/login
t failsafe.bootstrap_bash \$PREFIX/bin/bash -c 'echo failsafe-bash'
t failsafe.bootstrap_dpkg \$PREFIX/bin/dpkg --version
t failsafe.bootstrap_script \$PREFIX/bin/gunzip --version
echo done > /sdcard/cloudprove/failsafe.done
EOS
else
cat > "$W/cloudprove/failsafe.sh" <<EOS
R=/sdcard/cloudprove/failsafe.tsv; : > "\$R"
. /sdcard/cloudprove/lib.sh
printf 'info.failsafe_shell\tok\t%s\n' "\$(readlink /proc/\$\$/exe 2>&1)" >> "\$R"
t failsafe.system_shell   test -x /system/bin/sh
t failsafe.login_by_sh    /system/bin/sh $PREFIX/bin/login fish -l -c 'echo from-failsafe'
echo done > /sdcard/cloudprove/failsafe.done
EOS
fi
sh_ "rm -rf /sdcard/cloudprove" >/dev/null
adb push "$W/cloudprove" /sdcard/ >/dev/null || { echo "::error::could not push the probe scripts to /sdcard"; exit 2; }

run_command() {  # $1 = path, $2 = comma-separated arguments: a background RUN_COMMAND, as another app sends it
  sh_ "am startservice --user 0 -n $PKG/com.termux.app.RunCommandService -a $PKG.RUN_COMMAND \
        --es $PKG.RUN_COMMAND_PATH '$1' --esa $PKG.RUN_COMMAND_ARGUMENTS '$2' \
        --es $PKG.RUN_COMMAND_WORKDIR '$HOME_DIR' --ez $PKG.RUN_COMMAND_BACKGROUND true"
}
wait_file() {  # $1 = device path, $2 = tries of 10 s; 0 when it appears
  local i=0
  while [ "$i" -lt "$2" ]; do
    [ "$(sh_ "[ -e '$1' ] && echo y")" = y ] && return 0
    sh_ "pidof $PKG" >/dev/null || { echo "::warning::$PKG is not running while waiting for $1"; }
    sleep 10; i=$((i + 1))
  done
  return 1
}
type_line() { adb shell input text sh; adb shell input keyevent 62; adb shell input text "$1"; adb shell input keyevent 66; }

# ── 2. launch: the bootstrap installs, the first session logs in ───────────
act="$(sh_ "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" | tail -1)"
case "$act" in */*) ;; *) row meta.launcher FAIL "$PKG has no launcher activity ($act)"; finish ;; esac
sh_ "am start -W -n $act" >/dev/null
if wait_file "$PREFIX/bin/login" 90; then row meta.bootstrap ok "$PREFIX/bin/login is installed"; else row meta.bootstrap FAIL "no $PREFIX/bin/login 15 min after the first launch"; evidence bootstrap; finish; fi
sleep 5
app_pid="$(sh_ "pidof $PKG" | awk '{print $1}')"
ctx="$(sh_ "cat /proc/${app_pid:-0}/attr/current" | tr -d '\0')"
[ "$MIN_TARGET" -ge 29 ] && legacy=FAIL || legacy=ok
case "$ctx" in
  u:r:untrusted_app_27:*|u:r:untrusted_app_25:*) row meta.selinux_domain "$legacy" "$ctx: the legacy domain (targetSdk <= 28, may exec its data)$([ "$legacy" = FAIL ] && echo ", so this run proves nothing about targetSdk $MIN_TARGET")" ;;
  u:r:untrusted_app*) row meta.selinux_domain ok "$ctx (may NOT execve its data files)" ;;
  *) row meta.selinux_domain FAIL "unexpected context '$ctx'" ;;
esac
abi="$(sh_ getprop ro.product.cpu.abi)"
nld="$(dirname "$(sh_ "pm path $PKG" | head -1 | sed 's/^package://')")/lib/$(case "$abi" in x86_64) echo x86_64 ;; arm64*) echo arm64 ;; *) echo "$abi" ;; esac)"
sh_ "ls -l $nld" > "$OUT/native-libs.txt"
if [ "$MIN_TARGET" -ge 29 ]; then
  for lib in libcloudproot.so libcloudprootloader.so; do
    [ "$(sh_ "[ -f $nld/$lib ] && echo y")" = y ] && row "meta.native.$lib" ok "$nld/$lib" || row "meta.native.$lib" FAIL "missing from $nld"
  done
fi

# ── 3. plain Termux through RUN_COMMAND (termux only: the nix terminal has no host userland) ──
if [ "$APP" = termux ]; then
  out="$(run_command "$PREFIX/bin/bash" "/sdcard/cloudprove/native.sh")"; echo "$out" | head -3
  if wait_file "$HOME_DIR/cloudprove-native.done" 60; then
    row runcmd.background ok "RUN_COMMAND ran $PREFIX/bin/bash in the background and it finished"
    sh_ "cat $HOME_DIR/cloudprove-native.tsv" >> "$R"
  else
    row runcmd.background FAIL "RUN_COMMAND: no result 10 min after the intent ($(echo "$out" | tail -1))"
    sh_ "cat $HOME_DIR/cloudprove-native.tsv" >> "$R" 2>/dev/null
  fi
fi

# ── 4. the typed session: the login shell reaches a prompt (first start unpacks the rootfs) ──
verdict=""
for i in $(seq 1 100); do
  [ $((i % 5)) = 1 ] && type_line /sdcard/cloudprove/ui.sh
  sleep 30
  verdict="$(sh_ "cat /sdcard/cloudprove/ui.txt 2>/dev/null" | tr -d ' ' | grep -o 'L[0-9]*U[A-Za-z]*' | tail -1 || true)"
  [ -n "$verdict" ] && break
  sh_ "pidof $PKG" >/dev/null || { echo "::warning::$PKG died during the first session"; break; }
  echo "no prompt yet ($i)"
done
evidence session
case "$verdict" in
  L[1-9]*ULinux) row ui.login_shell ok "a typed session ran ls and uname ($verdict)" ;;
  *) row ui.login_shell FAIL "no typed session reached a prompt (verdict '${verdict:-none}')" ;;
esac

# ── 5. the login session's own environment, headless, through RUN_COMMAND ──
out="$(run_command "$PREFIX/bin/login" "$GUEST_ARGS")"; echo "$out" | head -3
if wait_file /sdcard/cloudprove/guest.done 90; then
  row runcmd.login ok "RUN_COMMAND ran a command through bin/login and it finished"
else
  row runcmd.login FAIL "RUN_COMMAND through bin/login: no result after 15 min ($(echo "$out" | tail -1))"
fi
sh_ "cat /sdcard/cloudprove/guest.tsv 2>/dev/null" >> "$R"

# ── 6. the failsafe session ────────────────────────────────────────────────
sh_ "am force-stop $PKG" >/dev/null; sleep 3
sh_ "am start -W -n $act -a android.intent.action.RUN --ez $PKG.app.failsafe_session true" >/dev/null
sleep 20
for i in 1 2 3 4 5 6; do
  type_line /sdcard/cloudprove/failsafe.sh
  wait_file /sdcard/cloudprove/failsafe.done 6 && break
done
evidence failsafe
if [ "$(sh_ "[ -e /sdcard/cloudprove/failsafe.done ] && echo y")" = y ]; then
  row failsafe.session ok "the failsafe session started and ran what was typed"
else
  row failsafe.session FAIL "the failsafe session ran nothing in 6 min"
fi
sh_ "cat /sdcard/cloudprove/failsafe.tsv 2>/dev/null" >> "$R"

finish
