#!/usr/bin/env bash
# #1442 / #1512 / #1550 The Store's chip is green ONLY when the broker reports the ADB TRANSPORT connected (AdbLink.CONNECTED);
# the broker being armed (local server / Shizuku outlive Wireless debugging) gates shell work, never the colour.
#
# Held statically (no build, no device): the pure mapping ControlStatus.fromBroker answers UP only on `armed`; the Store reads the
# broker's live status (ShellClient.status through StoreShell), never a stored flag and never an adb link of its own (the Store has none
# since #1512); the chip paints DOWN amber and NOT_PAIRED (no SuperApp) red, re-probes on resume and when adb_wifi_enabled / adb_enabled change (no timer, #1550), and a non-green tap
# opens the SuperApp's arm flow. Then each mutation (a stale flag dressed up as green, ...) is planted in a scratch copy and must turn a
# check red. The behaviour itself is StoreChannelTest's (CI unit tests).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
A=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore
CS=$A/StoreChannel.kt
ST=$A/StoreStatus.kt
SB=$A/StoreBar.kt
SH=$A/StoreShell.kt

check() {
python3 - "$1" "$CS" "$ST" "$SB" "$SH" <<'PY'
import re, sys
R, CS, ST, SB, SH = sys.argv[1:]
rd = lambda p: open(f"{R}/{p}", encoding="utf-8").read()
fails = 0
def ok(c, msg):
    global fails
    print(("PASS " if c else "FAIL ") + msg); fails += 0 if c else 1
cs, st, sb, sh = rd(CS), rd(ST), rd(SB), rd(SH)
m = re.search(r"fun fromBroker\(.*?\n    }\n", cs, re.S); body = m.group(0) if m else ""
arms = re.findall(r"^\s*(.+?)\s*->\s*ChannelStatus\(Channel\.UP", body, re.M)
ok(arms == ["s.armed"], "fromBroker: the only UP arm is `s.armed ->` (got %s)" % arms)
ok("!s.brokerInstalled -> ChannelStatus(Channel.NOT_PAIRED" in body, "fromBroker: no SuperApp is NOT_PAIRED")
ok("val shellUsable: Boolean get() = state == Channel.UP" in cs, "shellUsable is UP and nothing else")
ok("ControlStatus.fromBroker(StoreShell.status(app))" in st and "ShellClient.status(ctx)" in sh, "the Store's status is the broker's live status, read per probe")
ok("adbdebug" not in st and "EmbeddedAdbChannel" not in st and "ShellChannels" not in st, "the Store keeps no adb link of its own to probe")
mi = re.search(r"fun ink\(.*?\n    }\n", cs, re.S); ink = mi.group(0) if mi else ""
greens = re.findall(r"^\s*(.+?)\s*->\s*Ink\.GREEN\b", ink, re.M)
ok(greens == ["s.adb.up"], "#1550 ink: the only GREEN arm is `s.adb.up ->` (got %s)" % greens)
ok("s.state == Channel.NOT_PAIRED -> Ink.RED" in ink, "ink: no SuperApp is red")
ok("ControlStatus.Ink.AMBER -> AMBER" in sb and "ControlStatus.Ink.RED -> RED_FILL" in sb and "ControlStatus.ink(ch)" in sb, "chip: painted from ControlStatus.ink")
ok("Lifecycle.Event.ON_RESUME" in sb and "AdbWatch.observe(" in sb, "chip: re-probes on resume and on the adb settings change")
ok("CHANNEL_RECHECK_MS" not in sb and not re.search(r"while \(true\) \{ kotlinx\.coroutines\.delay", sb), "#1550 chip: no polling loop")
ok("StoreShell.arm(ctx)" in sb and "adbdebug" not in sb, "chip: a non-green tap opens the SuperApp's arm flow")
sys.exit(fails)
PY
}

echo "== real tree"; check "$ROOT"; F=$?
M=0
mutate() {  # mutate <name> <file> <from> <to>: must turn red
  local t; t=$(mktemp -d "${TMPDIR:-/tmp}/wdbg.XXXXXX")
  for f in "$CS" "$ST" "$SB" "$SH"; do mkdir -p "$t/$(dirname "$f")"; cp "$ROOT/$f" "$t/$f"; done
  python3 -c 'import sys;p,a,b=sys.argv[1:];s=open(p).read();assert a in s,a;open(p,"w").write(s.replace(a,b,1))' "$t/$2" "$3" "$4" || { echo "MUTATION NOT PLANTED: $1"; rm -rf "$t"; return 1; }
  if check "$t" >/dev/null; then echo "NOT PROVEN: $1"; rm -rf "$t"; return 1; fi
  echo "proven red: $1"; rm -rf "$t"
}
mutate "an installed broker shows green (reachable = up)" "$CS" "        s.armed -> ChannelStatus(Channel.UP" "        s.brokerInstalled -> ChannelStatus(Channel.UP" || M=$((M+1))
mutate "a missing SuperApp is not red" "$CS" "!s.brokerInstalled -> ChannelStatus(Channel.NOT_PAIRED" "!s.brokerInstalled -> ChannelStatus(Channel.DOWN" || M=$((M+1))
mutate "down counts as usable" "$CS" "state == Channel.UP" "state != Channel.NOT_PAIRED" || M=$((M+1))
mutate "status read from a stale flag" "$ST" "ControlStatus.fromBroker(StoreShell.status(app))" "ControlStatus.fromBroker(com.diegonmarcos.superapp.shellclient.ShellStatus(true, true, \"x\", \"\"))" || M=$((M+1))
mutate "an adb link of the Store's own returns" "$ST" "private val running" "private val adbdebug = 0; private val running" || M=$((M+1))
mutate "down painted green" "$SB" "ControlStatus.Ink.AMBER -> AMBER" "ControlStatus.Ink.AMBER -> Ink.GREEN_FILL" || M=$((M+1))
mutate "#1550 broker armed painted green while adb is down" "$CS" "        s.adb.up -> Ink.GREEN" "        s.state == Channel.UP -> Ink.GREEN" || M=$((M+1))
mutate "#1550 no settings observer" "$SB" "AdbWatch.observe(" "AdbWatchX.observe(" || M=$((M+1))
mutate "no resume re-check" "$SB" "Lifecycle.Event.ON_RESUME" "Lifecycle.Event.ON_START" || M=$((M+1))
mutate "the tap no longer arms" "$SB" "StoreShell.arm(ctx)" "Unit" || M=$((M+1))
echo "== $F check failure(s), $M mutation(s) not proven"
[ "$F" -eq 0 ] && [ "$M" -eq 0 ]
