#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1436 Cloud Drive git jobs work in BOTH cld.termux logins, and a rootfs    ║
# ║ that is not delivered is repaired with no user involved                    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The phone: every clone / bare clone / force_pull failed with
#   cloud-rootfs: rootfs.tar.zst is missing and no unpacked rootfs matches <sha>   (fell back to plain Termux)
#   mkdir: cannot create directory '/root': Read-only file system
#   /bin/sh: git: inaccessible or not found (exit 127)
#
# S  sha agreement: the digest the lib manifest carries is hashed from the payload it ships; CloudRootfs gates the
#    extraction on it and writes it to the SAME rootfs.sha256 enter.sh compares with the SAME stamp; and a digest file
#    alone is never "staged" -- isStaged() needs the rootfs DELIVERED (tarball or stamped tree), so the app re-extracts.
# A  automatic repair: CloudExecService re-stages in the background before every command; enter.sh's fallback tells
#    the jobs they are in plain Termux and no longer asks the user to rm files.
# H  HOME is never /root outside proot (the prelude rewrites guest /root/ paths to the Termux home).
# G  git resolves from $PREFIX/bin in plain Termux, is installed with `pkg install -y git` when missing, and a
#    failed install says what to do (exit 127).
# P  proot mode is untouched (no rewrite).
# Every rule is then broken once in a scratch copy and must go red. Runs the REAL prelude
# (ab_cloud-libs-shared/libs/shared-store/src/main/resources/cloud-exec-env.sh). Offline, no Android SDK.
set -u

APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PRELUDE="$ROOT/ab_cloud-libs-shared/libs/shared-store/src/main/resources/cloud-exec-env.sh"
EXEC_KT="$ROOT/ab_cloud-libs-shared/libs/shared-store/src/main/java/com/diegonmarcos/cloudlib/sharedstore/StoreExec.kt"
CR="$APP/app/src/main/java/com/termux/cloud/CloudRootfs.java"
CES="$APP/app/src/main/java/com/termux/cloud/CloudExecService.java"
ENTER="$APP/rootfs/enter.sh"
GRADLE="$APP/app/build.gradle"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
FAIL=0
ok()  { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; FAIL=1; }

REALGIT="$(command -v git)" || { echo "git must be on PATH"; exit 1; }
# A tool dir WITHOUT git: what plain Termux looks like before `pkg install git`.
TOOLS="$T/tools"; mkdir -p "$TOOLS"
for b in sh base64 sed cat mkdir dirname ln rm tr env head printf pwd basename uname expr cp; do
    p="$(command -v "$b" 2>/dev/null)" && case "$p" in /*) ln -sf "$p" "$TOOLS/$b" ;; esac
done
printf '#!/bin/sh\necho decoy-git\n' > "$T/decoy-git"; chmod +x "$T/decoy-git"
SEED="$T/seed"; "$REALGIT" init -q -b main "$SEED" && echo x > "$SEED/a" && "$REALGIT" -C "$SEED" add a \
    && "$REALGIT" -C "$SEED" -c user.name=t -c user.email=t@t commit -q -m one

# fake Termux prefix: $1 = git present (1) or not, $2 = pkg works (1) or fails; prints the prefix
mkprefix() {
    d="$(mktemp -d "$T/px.XXXX")"; mkdir -p "$d/usr/bin" "$d/home"
    ln -s "$(command -v sh)" "$d/usr/bin/sh"
    [ "$1" = 1 ] && ln -s "$REALGIT" "$d/usr/bin/git"
    if [ "$2" = 1 ]; then printf '#!/bin/sh\necho "pkg $*" >> "%s/pkg.log"\nln -sf "%s" "%s/usr/bin/git"\n' "$d" "$REALGIT" "$d" > "$d/usr/bin/pkg"
    else printf '#!/bin/sh\necho "pkg $*" >> "%s/pkg.log"\nexit 100\n' "$d" > "$d/usr/bin/pkg"; fi
    chmod +x "$d/usr/bin/pkg"; echo "$d"
}
# run <prelude> <script> [env...]: exactly what StoreExec.loginLine hands /bin/sh
run() {
    pre="$1"; scr="$2"; shift 2
    b64="$(printf '%s' "$scr" | base64 | tr -d '\n')"
    env -i "$@" sh -c "CLOUD_EXEC_SCRIPT_B64=$b64
$(cat "$pre")" 2>&1
}

check_prelude() {  # $1 = prelude; prints one line per broken rule
    pre="$1"
    # H + plain clone: a guest path lands in the Termux home, HOME is never /root (even when the caller says /root)
    px="$(mkprefix 1 1)"; H="$(cd "$px/home" && pwd -P)"
    out="$(run "$pre" "set -e; echo HOME=\$HOME; mkdir -p \"\$(dirname '/root/cloud-shared-store/git/r')\"; git clone -q 'file://$SEED' '/root/cloud-shared-store/git/r'" \
        HOME=/root PREFIX="$px/usr" CLOUD_ROOTFS_MODE=plain PATH="$TOOLS")"
    echo "$out" | grep -qx "HOME=$H" || echo "H1 HOME is not the Termux home in plain Termux: $out"
    [ -d "$H/cloud-shared-store/git/r/.git" ] || echo "H2 the guest /root/ path was not rewritten to the Termux home: $out"
    # G1 git resolves from $PREFIX/bin, ahead of anything else on PATH
    px="$(mkprefix 1 1)"; mkdir -p "$T/dec"; ln -sf "$T/decoy-git" "$T/dec/git"
    out="$(run "$pre" 'command -v git' HOME="$px/home" PREFIX="$px/usr" PATH="$T/dec:$TOOLS")"
    [ "$out" = "$px/usr/bin/git" ] || echo "G1 git does not resolve from \$PREFIX/bin in plain Termux: $out"
    # G2 git missing -> pkg install -y git, then the job runs
    px="$(mkprefix 0 1)"
    out="$(run "$pre" 'git --version' HOME="$px/home" PREFIX="$px/usr" PATH="$TOOLS")"
    { grep -q 'install -y git' "$px/pkg.log" 2>/dev/null && echo "$out" | grep -q 'git version'; } \
        || echo "G2 a missing git is not installed with pkg install -y git: $out"
    # G3 install fails -> exit 127 and the Logs say what to do
    px="$(mkprefix 0 0)"
    out="$(run "$pre" 'git --version' HOME="$px/home" PREFIX="$px/usr" PATH="$TOOLS")"; rc=$?
    { [ "$rc" = 127 ] || echo "$out" | grep -q "pkg install git"; } && echo "$out" | grep -q "Fix: .*pkg install git" \
        || echo "G3 a failed git install does not say what to do: rc=$rc $out"
    # G4 a script with no git in it never triggers an install
    px="$(mkprefix 0 1)"; run "$pre" 'echo plain' HOME="$px/home" PREFIX="$px/usr" PATH="$TOOLS" >/dev/null
    [ ! -f "$px/pkg.log" ] || echo "G4 a script without git still ran pkg install"
    # G5 still no git (pkg cannot serve cld.termux): a detached job waits for the repair, then re-enters the rootfs
    px="$(mkprefix 0 0)"; here="$px/usr/var/lib/cloud-rootfs"; mkdir -p "$here" "$px/rootfs-bin"
    ln -s "$REALGIT" "$px/rootfs-bin/git"; : > "$here/rootfs.tar.zst"
    printf '#!/bin/sh\n# fake enter.sh: proot entered, the rootfs git on PATH\nunset CLOUD_ROOTFS_MODE; PATH="%s:%s"; export PATH\nexec sh "$@"\n' "$px/rootfs-bin" "$TOOLS" > "$here/enter.sh"
    out="$(run "$pre" 'echo via=$(command -v git)' HOME="$px/home" PREFIX="$px/usr" PATH="$TOOLS" CLOUD_ROOTFS_MODE=plain CLOUD_ROOTFS_HERE="$here" CLOUD_EXEC_JOB=1 CLOUD_EXEC_REPAIR_WAIT=5)"
    echo "$out" | grep -qx "via=$px/rootfs-bin/git" || echo "G5 a job with no git does not re-enter the repaired rootfs: $out"
    # P proot (HOME=/root, no PREFIX): nothing rewritten, git is the rootfs's
    out="$(run "$pre" "echo '/root/x'" HOME=/root PATH="$(dirname "$REALGIT"):$TOOLS")"
    [ "$out" = /root/x ] || echo "P1 proot mode rewrote a guest path: $out"
}

check_static() {  # $1 = CloudRootfs.java, $2 = CloudExecService.java, $3 = enter.sh, $4 = build.gradle, $5 = StoreExec.kt
    python3 - "$@" <<'PY'
import re, sys
cr, ces, en, gr, kt = (open(p).read() for p in sys.argv[1:6])
bad = []
# S1 the manifest digest is hashed from the payload the lib ships, in the same task
if not re.search(r"def digest = sha256Hex\(tarball\)", gr) or "sha256: digest" not in gr or "payload: 'rootfs.tar.zst'" not in gr:
    bad.append("S1 the lib manifest sha256 is not computed from the rootfs.tar.zst it ships")
# S2 CloudRootfs and enter.sh name the same two files
if 'DIGEST = "rootfs.sha256"' not in cr or 'TARBALL = "rootfs.tar.zst"' not in cr or 'rootfs/.cloud-rootfs.sha256' not in cr:
    bad.append("S2 CloudRootfs does not name rootfs.sha256 / rootfs.tar.zst / the unpack stamp")
if '"$HERE/rootfs.sha256"' not in en or 'STAMP="$ROOTFS/.cloud-rootfs.sha256"' not in en or '"$HERE/rootfs.tar.zst"' not in en:
    bad.append("S2 enter.sh does not compare the same rootfs.sha256 with the same stamp")
# S3 a digest file alone is not staged
m = re.search(r"public static boolean isStaged\(Context context\) \{(.*?)\n    \}", cr, re.S)
if not m or "isDelivered(want)" not in m.group(1):
    bad.append("S3 isStaged() trusts the digest file alone: a deleted tarball + stale stamp is never re-extracted")
d = re.search(r"static boolean isDelivered\(String want\) \{(.*?)\n    \}", cr, re.S)
if not d or "TARBALL" not in d.group(1) or ".cloud-rootfs.sha256" not in d.group(1):
    bad.append("S3 isDelivered() does not check both the tarball and the unpack stamp")
# A1 the exec service repairs before every command
r = re.search(r"public static Bundle run\(.*?\{(.*?)Runtime\.getRuntime\(\)\.exec", ces, re.S)
if not r or "CloudRootfs.repairInBackground(context)" not in r.group(1):
    bad.append("A1 CloudExecService.run does not repair the rootfs before running a command")
if not r or "CloudDnsBridge.start(context)" not in r.group(1):
    bad.append("A4 CloudExecService.run does not start the DNS bridge: a job after process death has no DNS in proot")
if "REPAIRING.compareAndSet(false, true)" not in cr or "stage(app)" not in cr:
    bad.append("A1 repairInBackground is not a single-flight stage()")
# A2 enter.sh fallback: plain mode exported, no manual rm asked of the user
f = re.search(r"fallback\(\) \{(.*?)\n\}", en, re.S)
if not f or "export CLOUD_ROOTFS_MODE=plain" not in f.group(1):
    bad.append("A2 enter.sh's fallback does not tell the jobs they run in plain Termux")
if f and "repair: rm -f" in f.group(1):
    bad.append("A2 enter.sh still asks the user to rm files to repair")
# A3 StoreExec runs every script behind the prelude, with or without ~/.termux/shell
if "CLOUD_EXEC_JOB=1; export CLOUD_EXEC_JOB" not in kt:
    bad.append("A3 StoreExec.jobStart does not mark a detached job (it could not wait for the repair)")
if f and 'CLOUD_ROOTFS_HERE="$HERE"' not in f.group(1):
    bad.append("A2 enter.sh's fallback does not tell the jobs where the rootfs is staged")
if "ENV_PRELUDE" not in kt or 'getResourceAsStream("cloud-exec-env.sh")' not in kt or "CLOUD_EXEC_SCRIPT_B64=$inner" not in kt:
    bad.append("A3 StoreExec.loginLine does not run the script behind cloud-exec-env.sh")
if kt.count('else exec /system/bin/sh -c ') < 1 or kt.count('else /system/bin/sh -c ') < 1:
    bad.append("A3 StoreExec has no fallback when ~/.termux/shell is missing")
print("\n".join(bad))
PY
}

echo "── H/G/P: the real prelude, in plain Termux and in proot ──"
v="$(check_prelude "$PRELUDE")"
if [ -z "$v" ]; then ok "plain Termux: HOME never /root, guest paths in the Termux home, git from \$PREFIX/bin, auto-installed, actionable; proot untouched"
else echo "$v" | while IFS= read -r l; do bad "$l"; done; fi

echo "── S/A: sha agreement and automatic repair ──"
v="$(check_static "$CR" "$CES" "$ENTER" "$GRADLE" "$EXEC_KT")"
if [ -z "$v" ]; then ok "manifest sha from the payload; same digest/stamp files; isStaged needs a delivered rootfs; exec service repairs; fallback is plain-aware"
else echo "$v" | while IFS= read -r l; do bad "$l"; done; fi

echo "── mutants: each rule broken once must go red ──"
mut_prelude() {  # $1 = id, $2 = sed expression
    m="$T/prelude-$1.sh"; sed "$2" "$PRELUDE" > "$m"
    cmp -s "$m" "$PRELUDE" && { bad "mutant $1 did not change the prelude (stale sed)"; return; }
    if check_prelude "$m" | grep -q "^$1"; then ok "mutation proved: $1"; else bad "MUTATION SURVIVED: $1"; fi
}
mut_prelude H1 's#HOME="$cloud_home"; export HOME#export HOME#'
mut_prelude H2 's#| sed "s.*")"#)"#'
mut_prelude G1 's#PATH="${PREFIX:+$PREFIX/bin:}${PATH:-}#PATH="${PATH:-}:${PREFIX:+$PREFIX/bin}#'
mut_prelude G2 's#DEBIAN_FRONTEND=noninteractive pkg install -y git#true#'
mut_prelude G3 's#Fix: open cld.termux and run .pkg install git.#see the log#'
mut_prelude G4 's#\*git\*) cloud_needs_git=1#*) cloud_needs_git=1#'
mut_prelude G5 's#\[ "${CLOUD_EXEC_JOB:-}" = 1 \]#[ "${CLOUD_EXEC_JOB:-}" = never ]#'
mut_prelude P1 's#if \[ "${CLOUD_ROOTFS_MODE:-}" = plain \]#if true#'

mut_static() {  # $1 = id, $2 = file var name, $3 = sed expression
    eval "src=\$$2"; m="$T/mut-$1-$(basename "$src")"; sed "$3" "$src" > "$m"
    cmp -s "$m" "$src" && { bad "mutant $1 did not change $(basename "$src") (stale sed)"; return; }
    a="$CR"; b="$CES"; c="$ENTER"; d="$GRADLE"; e="$EXEC_KT"
    case "$2" in CR) a="$m" ;; CES) b="$m" ;; ENTER) c="$m" ;; GRADLE) d="$m" ;; EXEC_KT) e="$m" ;; esac
    if check_static "$a" "$b" "$c" "$d" "$e" | grep -q "^$1"; then ok "mutation proved: $1"; else bad "MUTATION SURVIVED: $1"; fi
}
mut_static S1 GRADLE 's#def digest = sha256Hex(tarball)#def digest = new File(cloudRootfsAssetsDir, "rootfs.sha256").text.trim()#'
mut_static S2 ENTER 's#STAMP="$ROOTFS/.cloud-rootfs.sha256"#STAMP="$ROOTFS/.stamp"#'
mut_static S3 CR 's#want.equals(read(new FileInputStream(staged))) \&\& isDelivered(want)#want.equals(read(new FileInputStream(staged)))#'
mut_static A1 CES 's#CloudRootfs.repairInBackground(context);##'
mut_static A4 CES 's#CloudDnsBridge.start(context);##'
mut_static A2 ENTER 's#    export CLOUD_ROOTFS_MODE=plain CLOUD_ROOTFS_HERE="$HERE"##'
mut_static A3 EXEC_KT 's#("CLOUD_EXEC_SCRIPT_B64=$inner\\n" + ENV_PRELUDE)#script#'

[ "$FAIL" = 0 ] && echo "PASS test-git-exec-modes" || { echo "FAIL test-git-exec-modes"; exit 1; }
