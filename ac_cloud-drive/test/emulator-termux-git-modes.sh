#!/usr/bin/env bash
# #1436 every Cloud Drive git job (clone, bare clone, force_pull) failed in cld.termux on the phone:
#   cloud-rootfs: rootfs.tar.zst is missing and no unpacked rootfs matches <sha>  -> plain Termux
#   mkdir: cannot create directory '/root': Read-only file system ; git: inaccessible or not found (127)
# Reproduced on an emulator with the real, release-signed cld.termux + cloud-lib-rootfs-termux + Cloud Drive:
#   1. healthy rootfs: Drive clone, bare clone and force_pull of public repositories all succeed.
#   2. the PHONE'S STATE: the staged tarball deleted (enter.sh does that after unpacking) and the unpacked
#      stamp stale, digest file still naming the lib. A cld.termux login falls back to plain Termux (shown).
#      Then, with no user action, Drive's bare clone, clone and force_pull must all succeed: CloudExecService
#      re-stages the rootfs in the background, the job runs behind cloud-exec-env.sh (HOME never /root, git
#      resolved or the repaired rootfs re-entered), and the rootfs ends repaired (stamp == digest).
# Routes over the fleet IPC provider as root (`content call`), like emulator-termux-bare-clone.sh.
# Usage: emulator-termux-git-modes.sh <drive.apk> <terminal.apk> <rootfs.apk> <out dir>
set -u
DRIVE_APK="$1"; TERM_APK="$2"; ROOTFS_APK="$3"; OUT="$4"
mkdir -p "$OUT"
DRIVE=com.diegonmarcos.clouddrive; TERMUX=cld.termux
STAGE=/data/data/cld.termux/files/usr/var/lib/cloud-rootfs
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
fail() { echo "::error title=cld.termux git modes::$*"; shot fail; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; dlog; exit 1; }
dlog() { ipc $DRIVE log/tail lines=400 > "$OUT/drive-log.json" 2>&1; }
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
ipc() {
  local pkg="$1" op="$2" extras="" k v; shift 2
  for kv in "$@"; do
    k="${kv%%=*}"; v="${kv#*=}"
    printf '%s' "$v" | sed 's/\\/\\\\/g; s/:/\\:/g' | adb shell "cat > /data/local/tmp/ipc_$k"
    extras="$extras --extra \"$k:s:\$(cat /data/local/tmp/ipc_$k)\""
  done
  timeout "${IPC_TIMEOUT:-300}" adb shell "content call --uri content://$pkg.fleet --method fleet.ipc.v1 --arg $op$extras" 2>&1 || echo "ipc $op @ $pkg: no answer in ${IPC_TIMEOUT:-300}s (rc $?)"
}
state() { adb shell "cat $STAGE/rootfs.sha256 2>&1; ls -l $STAGE/rootfs.tar.zst 2>&1; cat $STAGE/rootfs/.cloud-rootfs.sha256 2>&1"; }
break_rootfs() {  # the phone's state: no tarball, stale stamp, digest still the lib's (writes keep owner + label)
  adb shell "rm -f $STAGE/rootfs.tar.zst; echo stale > $STAGE/rootfs/.cloud-rootfs.sha256"
  adb shell am force-stop $TERMUX; sleep 2
  echo "-- broken: $(state | tr '\n' ' ')"
}
clone_wait() {  # $1 name, $2 url: Drive's clone route waits 120 s; past that, poll the registry
  local r; r="$(IPC_TIMEOUT=200 ipc $DRIVE git/clone "name=$1" "url=$2")"; echo "$r"
  echo "$r" | grep -q '"ok":true' && return 0
  echo "$r" | grep -q '"status":"timeout"' || return 1
  for _ in $(seq 1 90); do
    sleep 10; s="$(ipc $DRIVE git/state)"
    echo "$s" | grep -q "\"name\":\"$1\"" && { echo "$s" | head -c 600; return 0; }
  done; return 1
}

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
for a in "$TERM_APK" "$ROOTFS_APK" "$DRIVE_APK"; do
  timeout 600 adb install -r -g "$a" >"$OUT/install.log" 2>&1 || { cat "$OUT/install.log"; fail "install $(basename "$a") failed"; }
done
for p in "$TERMUX" "$DRIVE"; do
  adb shell monkey -p "$p" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
done

echo "── bootstrap cld.termux (Termux bootstrap + rootfs unpack) ──"
IPC_TIMEOUT=960 ipc $TERMUX terminal/exec "cmd=echo shell=\$SHELL home=\$HOME; command -v git" timeout=600000 | tee "$OUT/bootstrap.json"; echo
grep -q '"ok":true' "$OUT/bootstrap.json" || fail "cld.termux did not bootstrap: $(head -c 600 "$OUT/bootstrap.json")"
state | tee "$OUT/state-healthy.txt"

# #1731 the clone takes the remote's default branch: Spoon-Knife (main) and Hello-World (master) must both clone, bare clone and pull.
echo "── 1. healthy rootfs: clone, bare clone, force_pull ──"
clone_wait Spoon-Knife https://github.com/octocat/Spoon-Knife.git | tee "$OUT/1-clone.json"; echo
grep -q '"ok":true\|"name":"Spoon-Knife"' "$OUT/1-clone.json" || fail "healthy clone failed: $(head -c 1500 "$OUT/1-clone.json")"
IPC_TIMEOUT=900 ipc $DRIVE git/bare "url=https://github.com/github/gitignore.git" depth=1 | tee "$OUT/1-bare.json"; echo
grep -q '"ok":true' "$OUT/1-bare.json" || fail "healthy bare clone failed: $(head -c 1500 "$OUT/1-bare.json")"
IPC_TIMEOUT=420 ipc $DRIVE git/pull force=1 name=Spoon-Knife | tee "$OUT/1-force-pull.json"; echo
grep -q '"ok":true' "$OUT/1-force-pull.json" || fail "healthy force_pull failed: $(head -c 1500 "$OUT/1-force-pull.json")"
echo "-- 1b master-default fixture (octocat/Hello-World, #1731) --"
clone_wait Hello-World https://github.com/octocat/Hello-World.git | tee "$OUT/1b-clone-master.json"; echo
grep -q '"ok":true\|"name":"Hello-World"' "$OUT/1b-clone-master.json" || fail "master-default clone failed: $(head -c 1500 "$OUT/1b-clone-master.json")"
grep -q 'Remote branch main not found' "$OUT/1b-clone-master.json" && fail "clone still pins --branch main"
IPC_TIMEOUT=900 ipc $DRIVE git/bare "url=https://github.com/octocat/Hello-World.git" depth=1 | tee "$OUT/1b-bare-master.json"; echo
grep -q '"ok":true' "$OUT/1b-bare-master.json" || fail "master-default bare clone failed: $(head -c 1500 "$OUT/1b-bare-master.json")"
IPC_TIMEOUT=420 ipc $DRIVE git/pull force=1 name=Hello-World | tee "$OUT/1b-force-pull-master.json"; echo
grep -q '"ok":true' "$OUT/1b-force-pull-master.json" || fail "master-default force_pull failed: $(head -c 1500 "$OUT/1b-force-pull-master.json")"
shot 1-healthy

echo "── 2. the phone's state: tarball gone, stamp stale ──"
break_rootfs
# The proof the state is the phone's: a plain login falls back (read from the HOST side, as root, through enter.sh).
TUID="$(adb shell stat -c %u /data/data/cld.termux | tr -d '\r')"
adb shell "su $TUID /system/bin/sh -c 'HOME=/data/data/cld.termux/files/home PREFIX=/data/data/cld.termux/files/usr /system/bin/sh $STAGE/enter.sh -c true' 2>&1 | head -5" | tee "$OUT/2-fallback.txt"
grep -q 'no unpacked rootfs matches' "$OUT/2-fallback.txt" || echo "::warning::the fallback line was not captured (host login probe): $(cat "$OUT/2-fallback.txt")"
adb shell "rm -f $STAGE/rootfs.tar.zst; echo stale > $STAGE/rootfs/.cloud-rootfs.sha256"  # the probe must not have changed it

echo "-- 2a bare clone, no user action --"
IPC_TIMEOUT=1800 ipc $DRIVE git/bare "url=https://github.com/octocat/Spoon-Knife.git" depth=1 | tee "$OUT/2-bare.json"; echo
shot 2-bare
grep -q '"ok":true' "$OUT/2-bare.json" || fail "bare clone in the broken state failed: $(head -c 2000 "$OUT/2-bare.json")"
grep -q "cannot create directory '/root'" "$OUT/2-bare.json" && fail "a job still ran a /root path in plain Termux"
echo "-- 2b clone --"
break_rootfs
clone_wait gitignore https://github.com/github/gitignore.git | tee "$OUT/2-clone.json"; echo
grep -q '"ok":true\|"name":"gitignore"' "$OUT/2-clone.json" || fail "clone in the broken state failed: $(head -c 2000 "$OUT/2-clone.json")"
echo "-- 2c force_pull --"
break_rootfs
IPC_TIMEOUT=1500 ipc $DRIVE git/pull force=1 name=Spoon-Knife | tee "$OUT/2-force-pull.json"; echo
grep -q '"ok":true' "$OUT/2-force-pull.json" || fail "force_pull in the broken state failed: $(head -c 2000 "$OUT/2-force-pull.json")"

echo "── 3. the rootfs ends repaired ──"
for _ in $(seq 1 60); do
  d="$(adb shell cat $STAGE/rootfs.sha256 | tr -d '\r')"; s="$(adb shell cat $STAGE/rootfs/.cloud-rootfs.sha256 2>/dev/null | tr -d '\r')"
  [ -n "$d" ] && [ "$d" = "$s" ] && break
  [ -n "$(adb shell ls $STAGE/rootfs.tar.zst 2>/dev/null)" ] && IPC_TIMEOUT=960 ipc $TERMUX terminal/exec "cmd=true" timeout=600000 >/dev/null
  sleep 10
done
state | tee "$OUT/state-final.txt"
[ -n "$d" ] && [ "$d" = "$s" ] || fail "the rootfs was not repaired (digest $d, stamp $s)"
ipc $TERMUX terminal/exec "cmd=echo home=\$HOME; command -v git; ls /root/cloud-shared-store/git/_bare/octocat" | tee "$OUT/final-exec.json"; echo

# Drive's Git page and its Logs, for the eye
adb shell monkey -p $DRIVE -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6; shot 3-drive-git
dlog
echo "cld.termux git modes: clone, bare clone and force_pull work with a healthy rootfs AND from the phone's broken state, repaired with no user action"
