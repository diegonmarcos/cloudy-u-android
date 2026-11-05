#!/system/bin/sh
# Cloud Terminal's login shell: enter the glibc root baked into this APK.
#
# The app stages this file, proot, rootfs.tar.zst and rootfs.sha256 into one
# directory and points ~/.termux/shell here (com.termux.cloud.CloudRootfs), so
# Termux's own $PREFIX/bin/login execs it for every new session. Nothing in
# here names the app id or a path: everything is found relative to this file,
# so the com.termux -> cld.termux rewrite has nothing to rewrite.
#
# The failsafe session (long-press "New session") skips login and this file,
# and ~/.termux/shell can be deleted to go back to Termux bash.
#
# CI runs THIS file (rootfs/verify-rootfs.sh) against the tarball it built,
# with the same static proot, so the path tested is the path the phone takes.
set -eu

HERE="$(dirname "$(realpath "$0")")"
ROOTFS="$HERE/rootfs"
STAMP="$ROOTFS/.cloud-rootfs.sha256"

# The session's own arguments, quoted, so a fallback shell runs the same command (-c ...) or login.
CLOUD_ARGS_Q=""
for a in "$@"; do CLOUD_ARGS_Q="$CLOUD_ARGS_Q '$(printf '%s' "$a" | sed "s/'/'\\\\''/g")'"; done
[ -n "$CLOUD_ARGS_Q" ] || CLOUD_ARGS_Q=-l

fallback() {  # $* = why
    echo "cloud-rootfs: $*" >&2
    echo "cloud-rootfs: starting the plain Termux shell instead" >&2
    # #1436 repair is automatic: CloudRootfs.isStaged() counts only a DELIVERED rootfs (tarball here or a tree stamped
    # with the digest), so the app's next start, or the next command another app runs here (CloudExecService), re-extracts
    # it from cloud-lib-rootfs-termux. CLOUD_ROOTFS_MODE tells cloud-exec-env.sh (Drive's git jobs) it runs in plain Termux.
    echo "cloud-rootfs: repair: automatic, this app re-extracts the rootfs from the installed cloud-lib-rootfs-termux (no network) on its next start or the next Cloud Drive job; meanwhile jobs run in plain Termux. If this repeats, reinstall cloud-lib-rootfs-termux from the Store." >&2
    export CLOUD_ROOTFS_MODE=plain CLOUD_ROOTFS_HERE="$HERE"
    export PATH="${PATH:-}:/system/bin:/system/xbin"
    sh_bin="${CLOUD_ROOTFS_FALLBACK:-bash}"
    command -v "$sh_bin" >/dev/null 2>&1 || sh_bin=/system/bin/sh
    eval "exec \"\$sh_bin\" $CLOUD_ARGS_Q"
}

# Termux's login probes $SHELL with exactly this to decide whether termux-exec's
# LD_PRELOAD works. It is a question about the Termux side, so Termux's sh
# answers it: entering proot here would unpack the rootfs silently on first
# start and then fail the probe, since Debian has no coreutils multicall binary.
if [ "$#" -eq 2 ] && [ "$1" = -c ] && [ "$2" = "coreutils --coreutils-prog=true" ]; then
    exec sh "$@"
fi

login=""
if [ "${1:-}" = -l ]; then login=-l; shift; fi

# termux-exec is a bionic library; preloaded into glibc processes it breaks every exec.
unset LD_PRELOAD
export PROOT_TMP_DIR="$HERE/tmp"
mkdir -p "$PROOT_TMP_DIR"

# wipe_rootfs begin
# startup-step: wipe_stale_root
# Clears the old unpacked root before a re-extract. It can never fail the session:
# a previous agent session leaves nix/linux-store style files (0444 in 0555 dirs)
# under the root, which a plain rm cannot remove, so every entry is made writable
# first. The root's /tmp is scratch an update has no reason to touch (it is where
# those stale trees live), so it is left alone. Nothing is printed per file; one
# summary line says what could not be removed, and the caller carries on.
wipe_rootfs() {
    [ -d "$ROOTFS" ] || return 0
    for e in "$ROOTFS"/* "$ROOTFS"/.[!.]* "$ROOTFS"/..?*; do
        [ -e "$e" ] || [ -L "$e" ] || continue
        [ "$e" != "$ROOTFS/tmp" ] || continue
        [ -L "$e" ] || chmod -R u+rwx "$e" >/dev/null 2>&1 || true
        rm -rf "$e" >/dev/null 2>&1 || true
    done
    left=0
    for e in "$ROOTFS"/* "$ROOTFS"/.[!.]* "$ROOTFS"/..?*; do
        { [ -e "$e" ] || [ -L "$e" ]; } && [ "$e" != "$ROOTFS/tmp" ] && left=$((left + 1))
    done
    [ "$left" -eq 0 ] || echo "cloud-rootfs: $left old entries in the root could not be removed; continuing" >&2
    return 0
}
# wipe_rootfs end

# unpack_rootfs begin
# (part of startup-step wipe_stale_root: the unpack that follows the wipe; termux-only, nix has no rootfs tarball)
# Every login session and UnpackService (TerminalDebugApi.prepare after an update or a
# Store install) runs this file, so several unpackers can start at once. 2026-10-28 two of
# them wiped and extracted the same tree concurrently: hundreds of "Cannot stat", "Directory
# renamed before its status could be extracted", "Cannot hard link", then a failed unpack and
# the plain Termux shell. So: (1) one unpacker at a time, a lock in var/lib/cloud-rootfs that
# every entry point takes; the next caller waits, then re-reads the stamp and finds the work
# done; (2) the old root is renamed to rootfs.old.<pid> and the tarball is extracted at the final path
# (link2symlink links are absolute: no staging dir), checked and stamped; the old root is deleted
# after the unpack, never while anything extracts into it, and comes back if the unpack fails; (3) tar never tries to chown (an app uid
# cannot); (4) a failed unpack removes its partial tree and puts the previous root back.
LOCKDIR="$HERE/var/lib/cloud-rootfs"
UNPACK_OLD=""
UNPACK_ERR=""
rootfs_lock() {
    mkdir -p "$LOCKDIR"
    # fd 9 holds the lock file open in THIS shell. The flock child gets a dup of it on fd 8
    # (`8>&9`): Android's /system/bin/sh (mksh) opens `exec 9>` close-on-exec, so a bare
    # `flock 9` ran with no fd 9 and failed "flock: 9: Bad file descriptor" on the CLOUD_EXEC
    # path (Cloud Drive's clone, 2026-10-27). A dup shares the open file description, so the
    # lock stays held by fd 9 after flock exits, in mksh, dash and bash alike.
    if command -v flock >/dev/null 2>&1 && { exec 9>"$LOCKDIR/unpack.lock"; } 2>/dev/null; then
        flock 8 8>&9 2>/dev/null && return 0
        exec 9>&-
    fi
    # No flock: mkdir is atomic; a lock whose owner is gone is taken over.
    while ! mkdir "$LOCKDIR/unpack.lck" 2>/dev/null; do
        holder="$(cat "$LOCKDIR/unpack.lck/pid" 2>/dev/null || true)"
        if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then
            rm -rf "$LOCKDIR/unpack.lck"
        else
            sleep 1
        fi
    done
    echo $$ > "$LOCKDIR/unpack.lck/pid"
}
rootfs_unlock() {
    exec 9>&-
    [ "$(cat "$LOCKDIR/unpack.lck/pid" 2>/dev/null || true)" != $$ ] || rm -rf "$LOCKDIR/unpack.lck"
}
unpack_rootfs() {  # $1 = wanted digest; 0 = $ROOTFS holds it (UNPACK_OLD = old root to delete), 1 = UNPACK_ERR says why
    rootfs_lock
    if [ "$(cat "$STAMP" 2>/dev/null || true)" = "$1" ]; then rootfs_unlock; return 0; fi
    if [ ! -f "$HERE/rootfs.tar.zst" ]; then
        rootfs_unlock; UNPACK_ERR="rootfs.tar.zst is missing and no unpacked rootfs matches $1"; return 1
    fi
    echo "Unpacking the agent toolbelt (first start after install or update)..." >&2
    live="$ROOTFS"
    for d in "$HERE"/rootfs.old.* "$HERE"/rootfs.new.*; do   # left by an unpacker that died; nobody extracts while we hold the lock
        [ -d "$d" ] || continue
        ROOTFS="$d"; wipe_rootfs; rm -rf "$d" >/dev/null 2>&1 || true
    done
    ROOTFS="$live"
    # The old root steps aside with one rename; the new tree is extracted AT ITS FINAL PATH. Not into a
    # staging dir that is renamed afterwards: --link2symlink writes the emulated hard links (claude's
    # bin/claude.exe is one) as symlinks to absolute host paths, so a tree extracted elsewhere and moved
    # keeps links into a directory that no longer exists (2026-10-29: `claude: not on PATH`).
    if [ -e "$ROOTFS" ] || [ -L "$ROOTFS" ]; then
        UNPACK_OLD="$HERE/rootfs.old.$$"
        mv "$ROOTFS" "$UNPACK_OLD" || { UNPACK_OLD=""; rootfs_unlock; UNPACK_ERR="the old root could not be moved aside"; return 1; }
    fi
    mkdir -p "$ROOTFS"
    # Android forbids hard links in app storage; --link2symlink emulates the
    # rootfs's and proot resolves them later.
    if zstd -dc "$HERE/rootfs.tar.zst" | "$HERE/proot" --link2symlink -0 \
            tar --no-same-owner --no-same-permissions -xf - -C "$ROOTFS" \
        && [ -f "$ROOTFS/etc/passwd" ]; then
        :
    else
        wipe_rootfs; rm -rf "$ROOTFS" >/dev/null 2>&1 || true
        if [ -n "$UNPACK_OLD" ]; then mv "$UNPACK_OLD" "$ROOTFS" 2>/dev/null || true; UNPACK_OLD=""; fi
        rootfs_unlock; UNPACK_ERR="unpacking the rootfs failed (the previous root, if any, is unchanged)"; return 1
    fi
    echo "$1" > "$ROOTFS/.cloud-rootfs.sha256"
    # The root's /tmp is scratch an update has no reason to touch: it moves into the new root.
    if [ -n "$UNPACK_OLD" ] && [ -d "$UNPACK_OLD/tmp" ] && [ ! -L "$UNPACK_OLD/tmp" ]; then
        rm -rf "$ROOTFS/tmp" >/dev/null 2>&1 || true
        mv "$UNPACK_OLD/tmp" "$ROOTFS/tmp" 2>/dev/null || true
    fi
    rm -f "$HERE/rootfs.tar.zst"
    rootfs_unlock
    echo "cloud-rootfs: unpacked $1" >&2
    return 0
}
# unpack_rootfs end

want="$(cat "$HERE/rootfs.sha256" 2>/dev/null)" || fallback "rootfs.sha256 is missing: the app has not staged the rootfs"
if [ "$(cat "$STAMP" 2>/dev/null || true)" != "$want" ]; then
    unpack_rootfs "$want" || fallback "$UNPACK_ERR"
fi
# The old root, after the swap and outside the lock: nothing extracts into this tree any more.
if [ -n "$UNPACK_OLD" ]; then
    live="$ROOTFS"; ROOTFS="$UNPACK_OLD"
    wipe_rootfs
    ROOTFS="$live"; rm -rf "$UNPACK_OLD" >/dev/null 2>&1 || true
fi

# The login shell is root's entry in the rootfs, written at build time from
# rootfs.json::default_shell. This file does not choose one.
login_shell=""
while IFS=: read -r user _ _ _ _ _ entry_shell; do
    if [ "$user" = root ]; then login_shell="$entry_shell"; break; fi
done < "$ROOTFS/etc/passwd"
[ -n "$login_shell" ] || fallback "the rootfs /etc/passwd has no root entry"

# Android denies apps /proc/stat and /proc/uptime; top, htop and node read them.
binds=""
mkdir -p "$HERE/fake"
if [ ! -r /proc/stat ]; then
    printf 'cpu  1 0 1 1000 0 0 0 0 0 0\ncpu0 1 0 1 1000 0 0 0 0 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n' > "$HERE/fake/stat"
    binds="$binds -b $HERE/fake/stat:/proc/stat"
fi
if [ ! -r /proc/uptime ]; then
    printf '1000.00 1000.00\n' > "$HERE/fake/uptime"
    binds="$binds -b $HERE/fake/uptime:/proc/uptime"
fi
# /storage/emulated/0 is MediaProvider FUSE: every lstat is an IPC round trip and
# it stalls for minutes under load, which freezes the whole single-threaded proot
# (git on CloudDrive hung every session for 5-60 min). Prefer the raw volume or the
# pass-through FUSE when this app can read them; same files, 10-50x faster stats.
sdcard=""
for cand in /data/media/0 /mnt/pass_through/0/emulated/0 /mnt/androidwritable/0/emulated/0 /storage/emulated/0; do
    [ -r "$cand/CloudDrive" ] && [ -w "$cand/CloudDrive" ] && { sdcard="$cand"; break; }
done
[ -n "$sdcard" ] || [ ! -d /storage/emulated/0 ] || sdcard=/storage/emulated/0
[ -z "$sdcard" ] || binds="$binds -b $sdcard:/sdcard -b $sdcard:/storage/emulated/0"

# #644 — the declarative link store, staged beside this file by build-rootfs.sh
# and bound at the same path the nix terminal extracts its copy to. Guarded on
# presence so an APK built before #644 (or one whose staging failed) still logs
# in: the store is worth this terminal's tooling, never this terminal's shell.
# install-in-rootfs.sh creates the mountpoint inside the tree.
store_entry=""
if [ -d "$HERE/cloud-store" ] && [ -d "$ROOTFS/usr/lib/cloud-store" ]; then
    binds="$binds -b $HERE/cloud-store:/usr/lib/cloud-store"
    store_entry="/bin/sh /usr/lib/cloud-store/login-exec"
fi

# startup-step: rish_bridge
# build.json::shizuku_client (the block keeps its name) — the rish bridge into the rootfs. ShellRish
# (libs:shell-client, #1512) writes `rish` + `rish.env` (the in-process relay's loopback URL and a
# per-process token) into $HERE/rish on launch; bind them so a shell INSIDE proot runs allowlisted
# commands at adb-shell (uid 2000) privilege through the SuperApp's shell broker (app_process cannot
# run in here, no /system is bound; the relay forwards under this terminal's own allowlist entry).
# Guarded on presence like cloud-store: a session before the app has written them still logs in.
# install-in-rootfs.sh created the mountpoints. The two guest paths below are the literals in
# build.json::shizuku_client.binds[].guest — keep them in sync with that, not a copy.
if [ -f "$HERE/rish/rish" ] && [ -f "$ROOTFS/usr/local/bin/rish" ]; then
    binds="$binds -b $HERE/rish/rish:/usr/local/bin/rish"
    if [ -f "$HERE/rish/rish.env" ] && [ -f "$ROOTFS/usr/local/etc/rish.env" ]; then
        binds="$binds -b $HERE/rish/rish.env:/usr/local/etc/rish.env"
    fi
fi

# startup-step: storage_links
# #612/#736: ~/emulated is a SYMLINK in $HOME to /storage/emulated/0, and
# /storage/emulated/0 is bound at the SAME path inside the root so one absolute
# target resolves for the guest shell, the Termux failsafe shell and the documents
# provider alike. The fleet's store is NOT here any more: it is ~/cloud-shared-store
# below, in this app's own data dir.
storage_link() {  # $1 = link in $HOME, $2 = its target
    # An empty directory an older enter.sh left is replaced; rmdir never removes content.
    [ -L "$1" ] || rmdir "$1" 2>/dev/null || true
    if [ -e "$1" ] && [ ! -L "$1" ]; then
        echo "⚠ $1 is a directory with content, so it is not replaced by the link to $2" >&2
    else
        ln -sfn "$2" "$1"
    fi
}
storage_unlink() {  # $1 = link in $HOME: only our link or an empty directory goes
    if [ -L "$1" ]; then rm -f "$1"; else rmdir "$1" 2>/dev/null || true; fi
}
# Exactly ONE entry for shared storage: the upstream ~/storage tree
# (termux-setup-storage's links to DCIM, Download, ...) would be a second one. Only
# its symlinks are removed, and the directory only once it is empty.
if [ -d "$HOME/storage" ] && [ ! -L "$HOME/storage" ]; then
    for l in "$HOME/storage"/*; do [ ! -L "$l" ] || rm -f "$l"; done
    rmdir "$HOME/storage" 2>/dev/null || true
fi
# /storage/emulated/0 exists as a directory even without storage access, but is
# then not traversable, so a link to it would list nothing. Probe readability
# and, when it fails, say why in one line instead of an empty listing.
if ls /storage/emulated/0 >/dev/null 2>&1; then
    # The guest's /storage/emulated/0 is the sdcard bind above (same files, the fastest readable path).
    storage_link "$HOME/emulated" /storage/emulated/0
else
    storage_unlink "$HOME/emulated"
    echo "⚠ ~/emulated: shared store not mounted, storage access is not granted. Allow it on the Cloud Terminal prompt, then open a new session." >&2
fi

# startup-step: shared_store
# THE fleet's one store (build.json::shared_store): a REAL directory in this app's
# private home, on ext4/f2fs -- exec allowed, fast stats, real symlinks, no FUSE.
# The guest sees it as /root/cloud-shared-store ($HOME is bound as /root);
# cloud-terminal-nix runs as the same uid (android:sharedUserId) and links to it;
# every other fleet app reaches it through SharedStoreProvider. It never depends
# on the storage grant. Keep the literal equal to build.json::shared_store.dir
# (test/test-shared-store.sh).
CLOUD_STORE="$HOME/cloud-shared-store"
CLOUD_STORE_CFG="$HOME/.config/cloud-shared-store"
mkdir -p "$CLOUD_STORE" "$CLOUD_STORE_CFG" 2>/dev/null || true
# legacy_store_migration begin (build.json::shared_store.legacy_name): one-time, idempotent, never deletes user data.
# The old store name is only ever an INPUT here: whatever is under it is merged into the store and the old
# link/dir goes. test/test-shared-store.sh pins this block equal to cloud-terminal-nix's (bake_default_packages.py).
legacy_store_merge() {  # $1 = dir emptied into $2; $3 = where a differing file of the same name is kept
    for e in "$1"/* "$1"/.[!.]* "$1"/..?*; do
        [ -e "$e" ] || [ -L "$e" ] || continue
        n="${e##*/}"
        if [ ! -e "$2/$n" ] && [ ! -L "$2/$n" ]; then mv "$e" "$2/$n"
        elif [ -d "$e" ] && [ ! -L "$e" ] && [ -d "$2/$n" ] && [ ! -L "$2/$n" ]; then
            ( legacy_store_merge "$e" "$2/$n" "$3/$n" ); rmdir "$e" 2>/dev/null || true
        elif [ -f "$e" ] && [ ! -L "$e" ] && [ -f "$2/$n" ] && cmp -s "$e" "$2/$n"; then rm -f "$e"
        else
            mkdir -p "$3"; t="$3/$n"; [ ! -e "$t" ] && [ ! -L "$t" ] || t="$t.$$"
            mv "$e" "$t"
        fi
    done
}
legacy_store_migrate() {  # $1 = the old name in $HOME, $2 = the store
    if [ -L "$1" ]; then
        if [ ! -e "$2" ] && [ ! -L "$2" ]; then mv "$1" "$2"; else rm -f "$1"; fi
        return 0
    fi
    [ -d "$1" ] || return 0
    mkdir -p "$2" 2>/dev/null || true
    legacy_store_merge "$1" "$2" "$2/.legacy-backup-$(date +%Y%m%d)"
    rmdir "$1" 2>/dev/null || echo "⚠ $1 still has content or is a mount point; it is left in place and migrated again at the next login" >&2
}
# legacy_store_migration end
legacy_store_migrate "$HOME/cloud-drive-shared-store" "$CLOUD_STORE"  # legacy_store_migration
# The web token fleet-webserver's write path checks (shared_store.web.token_file); reads are
# loopback-only and need none. Made once, 0600, never printed.
if [ ! -s "$CLOUD_STORE_CFG/web-token" ]; then
    ( umask 077; od -An -tx1 -N24 /dev/urandom 2>/dev/null | tr -d ' \n' > "$CLOUD_STORE_CFG/web-token" ) || true
fi
# Optional export mirror (shared_store.export_mirror), OFF unless the flag says "on":
# one way, <store>/export -> <shared storage>/CloudDrive/export, never deletes, in the
# background so a login never waits for it.
if [ "$(cat "$CLOUD_STORE_CFG/export-mirror" 2>/dev/null)" = "on" ] && [ -n "$sdcard" ] && [ -d "$CLOUD_STORE/export" ]; then
    ( mkdir -p "$sdcard/CloudDrive/export" && cp -ru "$CLOUD_STORE/export/." "$sdcard/CloudDrive/export/" ) \
        >"$CLOUD_STORE_CFG/export-mirror.log" 2>&1 </dev/null &
fi
# One-time hint: Cloud Drive itself imports the clones it made in the legacy CloudDrive folder
# (LegacyImport, run by its seed worker; anything missing is cloned from the declared list).
if [ ! -d "$CLOUD_STORE/git" ] && [ -n "$sdcard" ] && [ -d "$sdcard/CloudDrive/git" ]; then
    echo "ℹ ~/cloud-shared-store/git is empty; Cloud Drive imports the legacy CloudDrive clones when it next opens (nothing to do here)" >&2
fi

# startup-step: local_webserver
# local_webserver begin
# Option 37.1 -- this terminal serves its own disk: fleet-webserver on 127.0.0.1, port from
# build.json::runtime.webserver, root = this terminal's $HOME (bound as /root). The logic is
# ab_cloud-terminal-store/local-webserver.sh, rendered with the port by build-rootfs.sh into
# cloud-store/ beside this file; it starts the server only when nothing LISTENs on the port yet,
# so every later session is a no-op. The binary is glibc, so it runs in a proot of its own over
# the same root and binds as the session (no --kill-on-exit: it outlives the session that
# started it). Guarded on presence like cloud-store: an older tree without the binary, or a
# staging without the snippet, logs in exactly as before.
# cloud-shared-store (build.json::shared_store.web): the server stays read-only over $HOME; writes
# are opened for the STORE ONLY (fleet-webserver's HTTPD_WRITE_ROOTS allowlist) and only with the 0600
# token made above (HTTPD_WRITE_TOKEN_FILE: every write must present it). No token file, no writes.
# Passed as explicit `env -i` words because the launcher starts from an empty environment.
store_web_env=""
if [ -s "${CLOUD_STORE_CFG:-$HOME/.config/cloud-shared-store}/web-token" ]; then
    store_web_env="HTTPD_WRITE=1 HTTPD_WRITE_ROOTS=/root/cloud-shared-store HTTPD_WRITE_TOKEN_FILE=/root/.config/cloud-shared-store/web-token"
fi
if [ -f "$HERE/cloud-store/local-webserver.sh" ]; then
    . "$HERE/cloud-store/local-webserver.sh"
    if [ -x "$ROOTFS/usr/local/bin/$CLOUD_WEBSERVER_BIN" ]; then
        # shellcheck disable=SC2086 # $binds is a list of flags
        cloud_webserver_autostart /root "$HERE/proot" --link2symlink --sysvipc -0 -r "$ROOTFS" \
            -b /dev -b /proc -b /sys -b /proc/self/fd:/dev/fd -b "$HOME:/root" $binds -w /root \
            /usr/bin/env -i HOME=/root USER=root LOGNAME=root LANG=C.UTF-8 TMPDIR=/tmp \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin $store_web_env \
            "/usr/local/bin/$CLOUD_WEBSERVER_BIN"
    fi
fi
# local_webserver end

# $HOME is bound as /root, so credentials, git config and work survive a
# rootfs update (which replaces the tree above) and stay visible to Termux.
#
# startup-step: dns_resolver
# #741 -p is how a shell in here resolves names through ANDROID's resolver, so
# through the SuperApp's DNS menu: the tree's /etc/resolv.conf names 127.0.0.1
# (rootfs.json::nameservers), an app may not listen on :53, and -p moves a
# guest's loopback port below 1024 up by 2000 -- so :53 reaches the app's
# SystemDnsBridge on libs/sysdns data/sysdns.json::bridge_port. A guest server
# binding a port below 1024 moves the same way (proot prints the new port).
# shellcheck disable=SC2086 # $binds is a list of flags
exec "$HERE/proot" --kill-on-exit --link2symlink --sysvipc -p -0 -r "$ROOTFS" \
    -b /dev -b /proc -b /sys -b /proc/self/fd:/dev/fd -b "$HOME:/root" $binds -w /root \
    /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL="$login_shell" \
        TERM="${TERM:-xterm-256color}" COLORTERM="${COLORTERM:-truecolor}" LANG=C.UTF-8 TMPDIR=/tmp \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        CLOUD_SHARED_STORE=/root/cloud-shared-store \
        CLOUD_SHARED_STORE_WEB_TOKEN_FILE=/root/.config/cloud-shared-store/web-token \
    $store_entry "$login_shell" $login "$@"
