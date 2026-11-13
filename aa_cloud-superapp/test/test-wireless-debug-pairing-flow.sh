#!/usr/bin/env bash
#
# Permissions ▸ Wireless Debugging ▸ Pair — the Shizuku flow, exactly.
#
# The phone showed "java.net.SocketException: Connection reset … Conscrypt
# SSLInputStream.readFromSocket" on port 35191. 35191 is a wireless-debugging
# CONNECT port (main screen); pairing ports live only in the "Pair device with
# pairing code" dialog and change every time it opens. Our Pair asked the user
# to TYPE an IP and a "pair port", so the connect port went into the SPAKE2
# pairing handshake and adbd's connect listener reset the socket. Shizuku never
# asks: AdbMdns discovers _adb-tls-pairing._tcp, AdbPairingService posts a
# notification whose RemoteInput takes the code, and the pairing runs in that
# foreground service. This tester pins that shape:
#
#   P1  both services are discovered over NsdManager: _adb-tls-pairing._tcp
#       and _adb-tls-connect._tcp are declared in AdbMdns and both are used by
#       AdbPairingService (pair on the first, connect on the second)
#   P2  the pairing port is never user-typed: no "pair port" field on the
#       Permissions page, /api/adb/pair reads no port, and the only caller of
#       EmbeddedAdbChannel.pair() outside the channel is AdbPairingService
#   P3  the notification carries a RemoteInput for the code, and the service
#       reads the code back from it
#   P4  the pairing runs in a FOREGROUND service: a Service that calls
#       startForeground, declared in the lib manifest with a foreground type and
#       the FOREGROUND_SERVICE permissions, and the pair() call sits inside it
#   P5  after pairing it connects and VERIFIES: a shell round trip (probe) over the
#       connected session, then the app's onConnected hook arms the privileged plane.
#       The session IS the shell channel, so the service starts nothing through it
#       (no AdbShellBootstrap launch line: that was the circular bootstrap)
#   P6  the bundled Conscrypt (exported keying material) stays declared in the
#       lib — Android's platform Conscrypt does not expose it
#   P7  the ADB Shell page's Pair only starts the service — no pair() in the UI, no Pair on the Permissions page
#
# FAIL CLOSED: a moved file proves nothing and says so.

set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # -> cloud-u-android
LIB="$ROOT/ab_cloud-libs-shared/libs/shizuku-adb-debug-tools"
SRC="$LIB/src/main/java/com/diegonmarcos/superapp/adbdebug"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

MDNS="$ROOT/ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/AdbMdns.kt"   # #1682 P3 step 4e: shared by the pairing service and the SuperApp popup
SVC="$SRC/AdbPairingService.kt"
BOOT="$SRC/AdbShellBootstrap.kt"
MANIFEST="$LIB/src/main/AndroidManifest.xml"
GRADLE="$LIB/build.gradle"
PERMS="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt"
DCS="$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt"
APPKT="$APP/app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt"   # #1682 P3 step 5: the owner hooks live in the AdbSide facade (src/owner)

for f in "$MDNS" "$SVC" "$BOOT" "$MANIFEST" "$GRADLE" "$PERMS" "$DCS" "$APPKT"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done

# code <file> — comment lines removed, so prose in KDoc cannot satisfy (or
# trip) an assertion meant for the implementation.
code() { grep -vE '^[[:space:]]*(//|\*|/\*|#|<!--)' "$1"; }
# has <file> <pattern> — grep -c reads the whole pipe, so an early -q exit can
# never EPIPE the writer and trip pipefail on a match near the top of a file.
has()  { code "$1" | grep -c -- "$2" >/dev/null; }
hasi() { code "$1" | grep -ciE -- "$2" >/dev/null; }

echo "== wireless debugging pair: the Shizuku flow (mDNS + notification + foreground service) =="

# ── P1 ── both service types discovered
if has "$MDNS" '"_adb-tls-pairing._tcp"' && has "$MDNS" '"_adb-tls-connect._tcp"' \
  && has "$MDNS" 'discoverServices(' ; then
  ok "P1a AdbMdns discovers _adb-tls-pairing._tcp and _adb-tls-connect._tcp through NsdManager"
else
  bad "P1a AdbMdns no longer declares both adb mDNS service types over NsdManager"
fi
if has "$SVC" 'AdbMdns.TLS_PAIRING' && has "$SVC" 'AdbMdns.TLS_CONNECT'; then
  ok "P1b AdbPairingService pairs on the discovered pairing service and connects on the discovered connect service"
else
  bad "P1b AdbPairingService does not use both discovered services"
fi

# ── P2 ── the pairing port is never user-typed
if hasi "$PERMS" 'hint = "pair port"|portIn|hostField'; then
  bad "P2a the Permissions page still has a typed IP/pair-port field — the connect port goes into the pairing handshake again"
else
  ok "P2a no typed IP/pair-port field on the Permissions page"
fi
if code "$DCS" | sed -n '/"adb\/pair" ->/,/^                }/p' | grep -c 'query\["port"\]' >/dev/null; then
  bad "P2b /api/adb/pair still takes a port"
else
  ok "P2b /api/adb/pair takes no port"
fi
callers="$(grep -rln --include='*.kt' 'EmbeddedAdbChannel\.pair(' "$APP/app/src/main" "$LIB/src/main" | grep -v 'EmbeddedAdbChannel.kt' | grep -v 'AdbPairingService.kt' || true)"
if [ -z "$callers" ]; then
  ok "P2c AdbPairingService is the only caller of EmbeddedAdbChannel.pair()"
else
  bad "P2c pair() is also called from: $(echo "$callers" | tr '\n' ' ')"
fi

# ── P3 ── the notification carries a RemoteInput
if has "$SVC" 'RemoteInput.Builder(' && has "$SVC" 'addRemoteInput(' \
  && has "$SVC" 'RemoteInput.getResultsFromIntent(' && has "$SVC" 'setOngoing(true)'; then
  ok "P3 the ongoing pairing notification has an 'Enter pairing code' RemoteInput and the service reads the code from it"
else
  bad "P3 the pairing notification lost its RemoteInput (or the service no longer reads it)"
fi

# ── P4 ── the pairing runs in a foreground service
if has "$SVC" 'class AdbPairingService : Service()' && has "$SVC" 'startForeground(' \
  && has "$SVC" 'EmbeddedAdbChannel.pair('; then
  ok "P4a AdbPairingService is a Service that goes foreground and runs the pair() itself"
else
  bad "P4a the pairing no longer runs inside a foreground Service"
fi
if code "$MANIFEST" | grep -A3 'adbdebug.AdbPairingService' | grep -c 'foregroundServiceType=' >/dev/null \
  && has "$MANIFEST" 'android.permission.FOREGROUND_SERVICE"' \
  && has "$MANIFEST" 'android.permission.POST_NOTIFICATIONS"'; then
  ok "P4b the lib manifest declares the service with a foreground type, FOREGROUND_SERVICE and POST_NOTIFICATIONS"
else
  bad "P4b the lib manifest no longer declares AdbPairingService as a typed foreground service with its permissions"
fi

# ── P5 ── pair → connect → verify (nothing is started through the session)
if has "$SVC" 'EmbeddedAdbChannel.connect(' && has "$SVC" 'EmbeddedAdbChannel.probe(' \
  && ! has "$SVC" 'AdbShellBootstrap' && ! has "$SVC" 'shellCommand(' \
  && has "$BOOT" 'fun shellCommand(' && has "$APPKT" 'AdbPairingService.onConnected ='; then
  ok "P5 after pairing the service connects, proves the session with a shell round trip, starts no server through it, and the app arms the plane on onConnected"
else
  bad "P5 the pair → connect → verify chain is broken (or the service launches a server through the session again)"
fi

# ── P6 ── bundled Conscrypt stays declared
if has "$GRADLE" "org.conscrypt:conscrypt-android"; then
  ok "P6 org.conscrypt:conscrypt-android is declared in the lib (exportKeyingMaterial for the pairing TLS)"
else
  bad "P6 the bundled Conscrypt dependency is gone — the platform one cannot export keying material"
fi

# ── P7 ── the button only starts the service
DEVICE="$SRC/ChannelDevice.kt"
if has "$DEVICE" 'AdbPairingService.start(' && ! has "$DEVICE" 'EmbeddedAdbChannel.pair(' && ! has "$PERMS" 'AdbPairingService.start(' && ! has "$PERMS" 'EmbeddedAdbChannel.pair('; then
  ok "P7 the ADB Shell page's Pair starts AdbPairingService and pairs nothing itself; the Permissions page has no Pair"
else
  bad "P7 Pair is not (only) the ADB Shell page's start of AdbPairingService"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
