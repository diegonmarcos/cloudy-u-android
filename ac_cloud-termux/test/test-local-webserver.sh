#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ Option 37.1: enter.sh starts fleet-webserver on build.json's port, ONCE      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# This terminal serves its own $HOME through fleet-webserver on 127.0.0.1:<port>, where <port> is
# build.json::runtime.webserver (8000). enter.sh's `local_webserver` block sources the shared
# ab_cloud-terminal-store/local-webserver.sh (rendered with the port by build-rootfs.sh) and
# starts the server in a proot of its own -- only when nothing LISTENs on the port.
#
#  1. DECLARED: runtime.webserver is 8000 / 127.0.0.1 / $HOME; rootfs.json installs the
#     immutable per-commit release asset; build-rootfs.sh stages the rendered snippet; enter.sh
#     runs the block before the session exec. The renderer refuses a non-loopback bind, a root
#     other than $HOME and a privileged port.
#  2. EXECUTED with stubs: the block is cut out of enter.sh and run under sh with a stub proot
#     that records its argv and environment, and /proc/net/tcp{,6} replaced by fixtures. It must
#     start exactly one server when the port is free (right port, right root, the session's
#     binds, no --kill-on-exit, HTTPD_WRITE* scrubbed even if the login had them), and start
#     NOTHING when 127.0.0.1:8000 or [::]:8000 is LISTENing -- including on the second login
#     after the first one started it. A listener on another port, or a TIME_WAIT on this one,
#     does not count. No snippet or no binary: nothing runs and the login goes on.
#
# Offline, no Android SDK: python3, sh and coreutils.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
ENTER="$DIR/rootfs/enter.sh"
RENDER="$REPO/ab_cloud-terminal-store/render-local-webserver.py"
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── local webserver autostart [$(basename "$DIR")] ──"

# ── 1. declared ──────────────────────────────────────────────────────────────
python3 - "$DIR" <<'PY' && ok "build.json runtime.webserver = 8000 on 127.0.0.1 serving \$HOME; rootfs.json builds the shared fleet-webserver from ab_cloud-libs-shared/libs/fleet-webserver" || bad "the runtime.webserver / rootfs.json declaration is wrong (see above)"
import json, sys, os
d = sys.argv[1]
ws = json.load(open(os.path.join(d, "build.json")))["runtime"]["webserver"]
assert ws == {"port": 8000, "bind": "127.0.0.1", "root": "$HOME", "binary": "fleet-webserver"}, ws
r = json.load(open(os.path.join(d, "rootfs", "rootfs.json")))
c = r["fleet_webserver"]
assert c == {k: c[k] for k in ("_doc", "src", "bin")} and c["bin"] == "fleet-webserver", c
assert os.path.isfile(os.path.join(d, c["src"], "Cargo.toml")), "the shared lib is not at " + c["src"]
assert "my-webserver" not in r["tarballs"] and "my-webserver" not in r["binaries"], "the Node SEA is still installed"
ids = r["artifact"]["identity_files"]
assert any(i.endswith("libs/fleet-webserver/vendor/VENDOR.sha256") for i in ids) and any(i.endswith("libs/fleet-webserver/Cargo.lock") for i in ids), "the lib is not in the rootfs address"
PY
grep -q 'render-local-webserver.py" "$HERE/../build.json" "$OUT/cloud-store/local-webserver.sh"' "$DIR/rootfs/build-rootfs.sh" \
    && ok "build-rootfs.sh stages the rendered snippet as cloud-store/local-webserver.sh" \
    || bad "build-rootfs.sh does not render cloud-store/local-webserver.sh from build.json"
python3 - "$ENTER" <<'PY' && ok "enter.sh runs the local_webserver block once, after storage and before the session exec" || bad "enter.sh's local_webserver block is missing, duplicated or after the exec"
import sys
s = open(sys.argv[1]).read()
b, e, x = s.find("# local_webserver begin"), s.find("# local_webserver end"), s.rfind('exec "$PROOT"')
assert s.count("# local_webserver begin") == 1 and 0 < b < e < x, (b, e, x)
assert s.find("# startup-step: storage_links") < b
code = "\n".join(l for l in s[b:e].splitlines() if not l.lstrip().startswith("#"))
assert "--kill-on-exit" not in code and " -p " not in code, code
PY
for bad_decl in '"bind": "0.0.0.0"' '"root": "/"' '"port": 80'; do
    T0="$(mktemp -d)"
    python3 - "$DIR/build.json" "$T0/build.json" "$bad_decl" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); k, v = json.loads("{%s}" % sys.argv[3]).popitem()
d["runtime"]["webserver"][k] = v; json.dump(d, open(sys.argv[2], "w"))
PY
    if python3 "$RENDER" "$T0/build.json" >/dev/null 2>&1; then bad "the renderer accepted $bad_decl"; else ok "the renderer refuses $bad_decl"; fi
    rm -rf "$T0"
done

# ── 2. executed with stubs ─────────────────────────────────────────────────
T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL — mktemp -d failed"; exit 1; }
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/here/cloud-store" "$T/rootfs/usr/local/bin" "$T/system/bin" "$T/home" "$T/net"
ln -s "$(command -v grep)" "$T/system/bin/grep"
python3 "$RENDER" "$DIR/build.json" | sed "s#/system/bin/#$T/system/bin/#g" > "$T/here/cloud-store/local-webserver.sh" \
    || bad "render-local-webserver.py failed on build.json"
: > "$T/rootfs/usr/local/bin/fleet-webserver"; chmod 0755 "$T/rootfs/usr/local/bin/fleet-webserver"
# The stub proot: what a real launch would exec, written down. With LISTEN=1 it also "binds".
cat > "$T/here/proot" <<EOF
#!/bin/sh
{ echo "--ppid \$PPID"; printf '%s\n' "\$@"; echo "--env HTTPD_WRITE=\${HTTPD_WRITE-unset} HTTPD_WRITE_ROOTS=\${HTTPD_WRITE_ROOTS-unset} HTTPD_WRITE_TOKEN_FILE=\${HTTPD_WRITE_TOKEN_FILE-unset}"; } > "$T/launch.\$\$"
mv "$T/launch.\$\$" "$T/launch"
[ -z "\${LISTEN:-}" ] || echo '   1: 0100007F:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0' >> "$T/net/tcp"
EOF
chmod 0755 "$T/here/proot"
sed -n '/^# local_webserver begin$/,/^# local_webserver end$/p' "$ENTER" > "$T/block.sh"
SH="$(command -v sh)"

HDR='  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode'
net() {  # net <tcp lines> [<tcp6 lines>]
    printf '%s\n%s' "$HDR" "$1" > "$T/net/tcp"; printf '%s\n%s' "$HDR" "${2:-}" > "$T/net/tcp6"
}
# login: run the block as enter.sh would, then wait for the background launch (or its absence)
login() {
    rm -f "$T/launch"
    HOME="$T/home" HERE="$T/here" ROOTFS="$T/rootfs" binds=" -b /x:/sdcard" \
    CLOUD_WEBSERVER_PROC_NET="$T/net/tcp $T/net/tcp6" HTTPD_WRITE=1 HTTPD_WRITE_ROOTS=/ HTTPD_WRITE_TOKEN_FILE=/t \
        "$SH" -euc "echo \$\$ > '$T/loginpid'; . '$T/block.sh'; i=0; while [ ! -f '$T/launch' ] && [ \$i -lt 20 ]; do sleep 0.1; i=\$((i + 1)); done; echo login-continues" > "$T/out" 2>&1 || return 1
    grep -q login-continues "$T/out" || return 1
    i=0; while [ ! -f "$T/launch" ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
    return 0
}
launched() { [ -f "$T/launch" ]; }

net ""
if login && launched; then
    args="$(tr '\n' ' ' < "$T/launch")"
    case "$args" in
        *"-r $T/rootfs "*"-b $T/home:/root "*"-b /x:/sdcard -w /root "*"/usr/local/bin/fleet-webserver 8000 /root --env HTTPD_WRITE=unset HTTPD_WRITE_ROOTS=unset HTTPD_WRITE_TOKEN_FILE=unset"*)
            ok "port free: one fleet-webserver 8000 /root inside the root, the session's binds, HTTPD_WRITE* scrubbed" ;;
        *) bad "port free: the launch is wrong: $args" ;;
    esac
    case "$args" in
        "--ppid $(cat "$T/loginpid") "*) bad "the server is a direct child of the login shell: enter.sh execs the session proot in place, which would wait on it forever (double fork lost)" ;;
        "--ppid "*) ok "the server is not a child of the login shell (double fork): the session proot does not wait on it" ;;
        *) bad "the stub recorded no parent pid" ;;
    esac
    case "$args" in *--kill-on-exit*) bad "the server proot has --kill-on-exit: it would die with the session" ;; *) ok "the server proot outlives the session (no --kill-on-exit)" ;; esac
else
    bad "port free: nothing was started ($(cat "$T/out"))"
fi

net '   0: 0100007F:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0'
login && ! launched && ok "127.0.0.1:8000 LISTENing: nothing started, the login goes on" || bad "started a second server over a listening 127.0.0.1:8000"

net "" '   0: 00000000000000000000000000000000:1F40 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0'
login && ! launched && ok "[::]:8000 LISTENing (tcp6): nothing started" || bad "a tcp6 listener on 8000 was not seen"

net '   0: 0100007F:1F41 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0
   1: 0100007F:1F40 0100007F:A1B2 06 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0'
login && launched && ok "a listener on 8001 and a TIME_WAIT on 8000 do not count: the server starts" || bad "another port's listener, or a TIME_WAIT, suppressed the start"

net ""
LISTEN=1; export LISTEN
login && launched && ok "first login: started" || bad "first login: not started"
login && ! launched && ok "second login: the first one's server is LISTENing, nothing started (idempotent)" || bad "second login started another server"
unset LISTEN

net ""
rm -f "$T/rootfs/usr/local/bin/fleet-webserver"
login && ! launched && ok "no binary in the root (an older tree): nothing runs, the login goes on" || bad "missing binary: the block failed the login or launched anyway"
: > "$T/rootfs/usr/local/bin/fleet-webserver"; chmod 0755 "$T/rootfs/usr/local/bin/fleet-webserver"
mv "$T/here/cloud-store/local-webserver.sh" "$T/lws.sh"
login && ! launched && ok "no staged snippet: nothing runs, the login goes on" || bad "missing snippet: the block failed the login or launched anyway"

if [ "$fails" -ne 0 ]; then echo "::error::FAIL — $fails local webserver assertion(s)"; exit 1; fi
echo "PASS — local webserver autostart"
