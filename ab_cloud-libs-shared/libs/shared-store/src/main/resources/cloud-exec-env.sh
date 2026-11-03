# cloud-exec-env.sh -- #1436 the environment every CLOUD_EXEC script (StoreExec.run / .job) runs in.
#
# StoreExec prepends one line, CLOUD_EXEC_SCRIPT_B64=<the job script, base64>, and runs the whole
# text with /bin/sh inside the login cld.termux gives it (~/.termux/shell = enter.sh). That login is
# one of two things, and a git job must work in both:
#
#   proot  enter.sh entered the glibc rootfs: HOME=/root, Debian's git, /bin/sh = dash.
#   plain  enter.sh fell back to plain Termux (rootfs not delivered, or being repaired), or there
#          is no ~/.termux/shell at all: HOME is the Termux home, /root does not exist (Android's
#          / is read-only: "mkdir: cannot create directory '/root'"), git is Termux's own package,
#          if it is installed at all ("git: inaccessible or not found", exit 127).
#
# In plain mode the guest paths Drive builds (/root/<store>/...) are the Termux home under another
# name (enter.sh binds $HOME as /root), so every quoted '/root/ and "/root/ in the script is
# rewritten to the real home, HOME is never /root, PATH starts at $PREFIX/bin, and git is
# installed with `pkg install -y git` when it is missing. Every failure says what to do.
# POSIX sh (dash, mksh, busybox): no bashisms. Tested by ac_cloud-termux/test/test-git-exec-modes.sh.

if [ "${CLOUD_ROOTFS_MODE:-}" = plain ] \
    || { [ "${HOME:-}" != /root ] && [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-/nonexistent}/bin" ]; }; then
    CLOUD_EXEC_MODE=plain
else
    CLOUD_EXEC_MODE=proot
fi

cloud_b64="${CLOUD_EXEC_SCRIPT_B64:-}"
cloud_script="$(printf '%s' "$cloud_b64" | base64 -d)"
unset CLOUD_EXEC_SCRIPT_B64
case "$cloud_script" in *git*) cloud_needs_git=1 ;; *) cloud_needs_git="" ;; esac

if [ "$CLOUD_EXEC_MODE" = plain ]; then
    cloud_home=""
    [ -n "${PREFIX:-}" ] && cloud_home="$(cd "$PREFIX/../home" 2>/dev/null && pwd -P)"
    [ -n "$cloud_home" ] || cloud_home="${HOME:-}"
    if [ -z "$cloud_home" ] || [ "$cloud_home" = /root ]; then
        echo "cloud-exec: plain Termux has no usable home (PREFIX=${PREFIX:-unset}, HOME=${HOME:-unset}). Fix: open cld.termux once so it finishes its install, then retry." >&2
        exit 78
    fi
    HOME="$cloud_home"; export HOME
    PATH="${PREFIX:+$PREFIX/bin:}${PATH:-}:/system/bin:/system/xbin"; export PATH
    cloud_sh="${PREFIX:-/nonexistent}/bin/sh"; [ -x "$cloud_sh" ] || cloud_sh=/system/bin/sh; [ -x "$cloud_sh" ] || cloud_sh=/bin/sh
    if [ -n "$cloud_needs_git" ] && ! command -v git >/dev/null 2>&1; then
        echo "cloud-exec: the cld.termux rootfs is not active (plain Termux) and git is not installed; installing it: pkg install -y git" >&2
        if command -v pkg >/dev/null 2>&1; then
            DEBIAN_FRONTEND=noninteractive pkg install -y git </dev/null >&2 2>&1 || true
        fi
        # The git the lib bundles: a detached job (CLOUD_EXEC_JOB=1, StoreExec.jobStart) waits for cld.termux's automatic
        # repair (CloudRootfs.repairInBackground re-stages rootfs.tar.zst from cloud-lib-rootfs-termux), then re-enters
        # through enter.sh, which unpacks it and runs the job in proot with the rootfs's own git. Once only (REENTERED).
        cloud_here="${CLOUD_ROOTFS_HERE:-}"
        if ! command -v git >/dev/null 2>&1 && [ "${CLOUD_EXEC_JOB:-}" = 1 ] && [ -z "${CLOUD_EXEC_REENTERED:-}" ] \
            && [ -n "$cloud_here" ] && [ -f "$cloud_here/enter.sh" ]; then
            echo "cloud-exec: waiting for cld.termux to re-extract its rootfs from cloud-lib-rootfs-termux (up to ${CLOUD_EXEC_REPAIR_WAIT:-900}s)" >&2
            cloud_waited=0
            while [ ! -f "$cloud_here/rootfs.tar.zst" ] && [ "$cloud_waited" -lt "${CLOUD_EXEC_REPAIR_WAIT:-900}" ]; do
                sleep 5; cloud_waited=$((cloud_waited + 5))
            done
            if [ -f "$cloud_here/rootfs.tar.zst" ]; then
                echo "cloud-exec: rootfs re-staged after ${cloud_waited}s; re-entering it (enter.sh unpacks it, then the job runs with the rootfs's git)" >&2
                cloud_guard="if [ \"\${CLOUD_ROOTFS_MODE:-}\" = plain ]; then echo 'cloud-exec: the rootfs repair did not take (enter.sh fell back again). Fix: open cld.termux once; if it repeats, reinstall cloud-lib-rootfs-termux from the Store.' >&2; exit 127; fi
"
                cloud_inner="$(printf '%s%s' "$cloud_guard" "$cloud_script" | base64 | tr -d '\n')"
                CLOUD_EXEC_REENTERED=1; export CLOUD_EXEC_REENTERED
                unset CLOUD_ROOTFS_MODE
                cloud_sys_sh=/system/bin/sh; [ -x "$cloud_sys_sh" ] || cloud_sys_sh=/bin/sh
                exec "$cloud_sys_sh" "$cloud_here/enter.sh" -c "exec /bin/sh -c \"\$(echo $cloud_inner | base64 -d)\""
            fi
        fi
        if ! command -v git >/dev/null 2>&1; then
            echo "cloud-exec: git is still missing in plain Termux. Fix: open cld.termux and run 'pkg install git' (needs network), or open cld.termux once so it repairs its rootfs from cloud-lib-rootfs-termux (reinstall that lib from the Store if this repeats)." >&2
            exit 127
        fi
    fi
    cloud_script="$(printf '%s' "$cloud_script" | sed "s#'/root/#'$HOME/#g; s#\"/root/#\"$HOME/#g")"
else
    PATH="${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"; export PATH
    cloud_sh=/bin/sh
    if [ -n "$cloud_needs_git" ] && ! command -v git >/dev/null 2>&1; then
        echo "cloud-exec: git is missing inside the cld.termux rootfs. Fix: open cld.termux once so it re-extracts the rootfs from cloud-lib-rootfs-termux, or reinstall that lib from the Store." >&2
        exit 127
    fi
fi
exec "$cloud_sh" -c "$cloud_script"
