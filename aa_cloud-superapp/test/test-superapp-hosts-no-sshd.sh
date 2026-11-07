#!/usr/bin/env bash
# SuperApp hosts NO sshd and carries NO sshd manager page (owner decision: only the webserver, Cloud Terminal Termux and
# Cloud Terminal Nix-on-Droid may host an sshd; the SuperApp's ADB-over-mesh plane and DevControl loopback stay).
#
#   S1  DevControlFragment has no "SSH servers" section, no terminal-sshd probe list, no port probe and no package probe for it
#   S2  the SuperApp manifest declares no sshd/dropbear service, and no source file starts an sshd
#   S3  the ADB plane and the DevControl API section are still there (only the sshd page went)
#
# Offline, python3 only. A mutation proves each check can go red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt"

check() {
python3 - "$1" "$2" <<'PY'
import os, re, sys
frag, app = sys.argv[1:3]
bad = []
t = open(frag, encoding="utf-8").read()
for needle in ('infoSection("SSH servers', "SSH_TERMINALS", "SshTerminal", "portOpen(", "isPackageInstalled("):
    if needle in t:
        bad.append("S1 DevControlFragment still carries %r (the sshd manager/probe page)" % needle)
mf = open(os.path.join(app, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
if re.search(r"(?i)sshd|dropbear|MeshSshd", mf):
    bad.append("S2 the SuperApp manifest declares an sshd component")
for root, _, files in os.walk(os.path.join(app, "app/src/main")):
    for f in files:
        if f.endswith((".kt", ".java")):
            src = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
            if re.search(r'ProcessBuilder\([^)]*sshd|exec\([^)]*sshd|"sshd"|"dropbear"', src):
                bad.append("S2 %s starts an sshd" % f)
if 'infoSection("Self-contained ADB' not in t or 'infoSection("Dev Control HTTP API")' not in t:
    bad.append("S3 the ADB plane or the DevControl API section was removed with the sshd page")
print("\n".join("FAIL " + b for b in bad) if bad else "ok   SuperApp has no sshd page, hosts no sshd, ADB plane and DevControl stay")
sys.exit(1 if bad else 0)
PY
}

check "$FRAG" "$APP" || exit 1
# mutation: the sshd manager section put back must go red
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
sed 's|infoSection("Dev Control HTTP API")|infoSection("SSH servers (Termux \& co.)") { }\n        infoSection("Dev Control HTTP API")|' "$FRAG" > "$TMP/F.kt"
if check "$TMP/F.kt" "$APP" >/dev/null 2>&1; then echo "FAIL mutation: an sshd section put back stayed green"; exit 1; fi
echo "ok   mutation: an sshd manager section put back goes red"
