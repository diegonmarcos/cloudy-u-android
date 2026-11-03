#!/usr/bin/env bash
# #1442 The Store's "Wireless Dbg" chip is green ONLY on a live adb round trip.
#
# Held statically (no build, no device): the pure mapping ControlStatus.linkStatus answers UP only
# on echoOk; the Store backend's echo is a real `echo` over the paired adb (EmbeddedAdbChannel.probe),
# never a stored flag; the chip paints DOWN amber and NOT_PAIRED red, re-probes on resume and on a
# timer, and a non-green tap goes to pairing or the Wireless debugging switch.
# Then each mutation (a stale flag dressed up as green, ...) is planted in a scratch copy and must
# turn a check red. The behaviour itself is ControlStatusTest's (CI unit tests).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CS=ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/ControlStatus.kt
ST=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreStatus.kt
SB=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt
LK=ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbShellActivity.kt

check() {
python3 - "$1" "$CS" "$ST" "$SB" "$LK" <<'PY'
import re, sys
R, CS, ST, SB, LK = sys.argv[1:]
rd = lambda p: open(f"{R}/{p}", encoding="utf-8").read()
fails = 0
def ok(c, msg):
    global fails
    print(("PASS " if c else "FAIL ") + msg); fails += 0 if c else 1
cs, st, sb, lk = rd(CS), rd(ST), rd(SB), rd(LK)
m = re.search(r"fun linkStatus\(.*?\n    }\n", cs, re.S); body = m.group(0) if m else ""
arms = re.findall(r"^\s*(.+?)\s*->\s*ChannelStatus\(Channel\.UP", body, re.M)
ok(arms == ["echoOk"], "linkStatus: the only UP arm is `echoOk ->` (got %s)" % arms)
pn = re.search(r"private fun probeNow\(\).*?\n        }\n", cs, re.S); pn = pn.group(0) if pn else ""
ok(re.search(r"Channel\.UP", pn.split("if (sz != null)")[-1].split("\n",1)[-1] if "if (sz != null)" in pn else pn) is None,
   "probeNow: outside Shizuku mode, UP only comes from linkStatus")
ok("timed { backend.adbEcho() }" in pn, "probeNow: the echo is bounded by the probe timeout")
ok(re.search(r"override fun adbEcho\(\)\s*=\s*EmbeddedAdbChannel\.probe\(app\)", st) is not None,
   "Store backend: adbEcho is the live `echo` round trip (EmbeddedAdbChannel.probe)")
ok(re.search(r"Channel\.DOWN -> AMBER", sb) is not None, "chip: DOWN is amber")
ok(re.search(r"Channel\.NOT_PAIRED -> RED_FILL", sb) is not None, "chip: NOT_PAIRED is red")
ok("Lifecycle.Event.ON_RESUME" in sb and "CHANNEL_RECHECK_MS" in sb, "chip: re-probes on resume and periodically")
ok(re.search(r"AdbShellLink\.open\(ctx, ch\?", sb) is not None and "Action.OPEN_SETTINGS" in lk, "chip: a non-green tap goes to pair/settings")
sys.exit(fails)
PY
}

echo "== real tree"; check "$ROOT"; F=$?
M=0
mutate() {  # mutate <name> <file> <from> <to>: must turn red
  local t; t=$(mktemp -d "${TMPDIR:-/tmp}/wdbg.XXXXXX")
  for f in "$CS" "$ST" "$SB" "$LK"; do mkdir -p "$t/$(dirname "$f")"; cp "$ROOT/$f" "$t/$f"; done
  python3 -c 'import sys;p,a,b=sys.argv[1:];s=open(p).read();assert a in s,a;open(p,"w").write(s.replace(a,b,1))' "$t/$2" "$3" "$4" || { echo "MUTATION NOT PLANTED: $1"; rm -rf "$t"; return 1; }
  if check "$t" >/dev/null; then echo "NOT PROVEN: $1"; rm -rf "$t"; return 1; fi
  echo "proven red: $1"; rm -rf "$t"
}
mutate "stale flag shows green (local server alive = up)" "$CS" "        echoOk -> ChannelStatus(Channel.UP" "        echoOk || shellVia != null -> ChannelStatus(Channel.UP" || M=$((M+1))
mutate "probe trusts the id answer again" "$CS" "            val echo = timed" "            if (shellVia != null) return ChannelStatus(Channel.UP, shellVia)
            val echo = timed" || M=$((M+1))
mutate "echo replaced by the cached isConnected flag" "$ST" "override fun adbEcho() = EmbeddedAdbChannel.probe(app)" "override fun adbEcho() = EmbeddedAdbChannel.isReady(app)" || M=$((M+1))
mutate "down painted green" "$SB" "Channel.DOWN -> AMBER" "Channel.DOWN -> Ink.GREEN_FILL" || M=$((M+1))
mutate "no resume re-check" "$SB" "Lifecycle.Event.ON_RESUME" "Lifecycle.Event.ON_START" || M=$((M+1))
echo "== $F check failure(s), $M mutation(s) not proven"
[ "$F" -eq 0 ] && [ "$M" -eq 0 ]
