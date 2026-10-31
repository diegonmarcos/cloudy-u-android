#!/usr/bin/env bash
# #975 The rootfs-lib notification loop: Cloud Mail (and every other fleet app) kept
# posting "tap to finish installing" for cloud-lib-rootfs-termux / -nixdroid.
#
# Reproduced from the tree itself (no build, no device, no network):
#  L1 ONE OWNER. Every app that compiles libs:updater (the lib closure, derived from
#     disk) bakes the whole fleet, and UpdateWorker.updateFleet ran Fleet.autoPass in
#     each of them on every process start (Updater.start's 30 s kick). The tester
#     evaluates the gates the source puts in front of Fleet.autoPass for each host's
#     package and counts the hosts that reach it: the old tree gives 12 (each one
#     downloading the ~400 MB rootfs libs and asking for a tap), the fixed one 1 -
#     Cloud Store, the app that owns the fleet (AppStoreHost.runsFleetPass, #865).
#  L2 ONCE PER ACTUAL CHANGE. An unattended pass (Mode.AUTO) with no privileged
#     channel committed the same cached bytes on every pass, so the same tap prompt
#     came back each time. Three passes over one unchanged pending lib are simulated
#     from installAllLocked: the old tree offers it 3 times, the fixed one once - the
#     sha256 already waiting on a tap is skipped before commit() and remembered when a
#     pass leaves it pending, and a landed install forgets it.
#  L3 QUIET PROGRESS. The pass's progress line (BatchForeground) stays a LOW-or-below
#     channel and a silent, alert-once builder, as Cloud Store's channels are (ccfde8f45).
# Then each planted mutation must turn a check red.
#
# Usage: ./test-fleet-pass-one-owner.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
UPD_REL="updater/src/main/java/com/diegonmarcos/superapp/updater"
CORE_REL="core/src/main/java/com/diegonmarcos/superapp/core"

# check <libs-dir> -> PASS/FAIL lines, exit = #fails
check() {
  python3 - "$ROOT" "$1/$UPD_REL" "$1/$CORE_REL" <<'PY'
import glob, json, os, re, sys
root, upd, core = sys.argv[1:4]
sys.path.insert(0, os.path.join(root, "1_cicd/src/scripts"))
import cloud_android_lib_closure as closure

fails = 0
def ok(m): print("  PASS: " + m)
def bad(m):
    global fails; fails += 1; print("  FAIL: " + m)

def strip(path):
    s = open(path, encoding="utf-8").read()
    s = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)

def block(src, i):
    """(start, end) of the brace-matched block opening at the first '{' at or after [i]."""
    j = src.find("{", i)
    if j < 0: return None
    depth = 0
    for k in range(j, len(src)):
        depth += {"{": 1, "}": -1}.get(src[k], 0)
        if depth == 0: return j + 1, k
    return None

def body(src, head):
    """The brace-matched body of the first declaration that starts with [head]."""
    i = src.find(head)
    b = block(src, i) if i >= 0 else None
    return src[b[0]:b[1]] if b else None

W = strip(os.path.join(upd, "UpdateWorker.kt"))
F = strip(os.path.join(upd, "Fleet.kt"))
B = strip(os.path.join(upd, "BatchForegroundService.kt"))
G = strip(os.path.join(core, "StoreNotifyGate.kt"))
consts = dict(re.findall(r'const val (\w+) = "([^"]+)"', G))

def predicate(src, fn):
    """fn(packageName) as a Python callable, from `fun fn(packageName: String): Boolean = <expr>`."""
    m = re.search(r"fun " + re.escape(fn) + r"\(packageName: String\): Boolean\s*=\s*([^\n]+)", src)
    if not m: return None
    e = m.group(1).strip()
    for k, v in consts.items():
        e = re.sub(r"\b(?:StoreNotifyGate\.)?" + k + r"\b", repr(v), e)
    e = e.replace("&&", " and ").replace("||", " or ")
    e = re.sub(r"!(?!=)", " not ", e)
    e = e.replace("packageName", "pkg")
    try:
        return eval("lambda pkg: " + e)
    except Exception:
        return None

# ── the hosts: every app that compiles libs:updater, and its package ─────────
edges, libs = closure.lib_edges(root), closure.shared_libs(root)
apps = sorted(os.path.basename(os.path.dirname(b)) for b in glob.glob(os.path.join(root, "a[ac]_*", "build.json")))
hosts = [a for a in apps if "updater" in closure.analyse(root, a, edges, libs)[1]]
fleet = json.load(open(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json"), encoding="utf-8"))
pkg_of = {}
for e in fleet["apps"]:
    u = e.get("repo_url") or ""
    if e.get("kind", "app") == "app" and "/tree/main/" in u:
        pkg_of.setdefault(u.rsplit("/tree/main/", 1)[1], e["package"])
rootfs = [e["package"] for e in fleet["apps"] if e.get("kind") == "lib" and "rootfs" in e["id"]]
pkgs = {h: pkg_of.get(h) for h in hosts}
if len(hosts) >= 3 and all(pkgs.values()) and rootfs:
    ok(f"fixture: {len(hosts)} apps compile libs:updater; the fleet carries {len(rootfs)} rootfs libs")
else:
    bad(f"fixture unusable: hosts={pkgs} rootfs={rootfs}")

# ── L1 which hosts reach Fleet.autoPass from UpdateWorker ────────────────────
dowork = body(W, "private suspend fun work(") or ""  # #fgs-timeout doWork wraps work() (cooperative stop + budget)
upf = body(W, "private fun updateFleet") or ""
gates = []
m = re.search(r"if \(!StoreNotifyGate\.mayPost\(applicationContext\)\)", dowork)
if m:
    p = predicate(G, "mayPost")
    if p: gates.append(("doWork", p))
if "updateFleet()" not in dowork: bad("doWork no longer calls updateFleet() - the model below is stale")
ap = upf.find("Fleet.autoPass(")
if ap < 0: bad("updateFleet no longer calls Fleet.autoPass - the model below is stale")
for g in re.finditer(r"if \(!(\w+)\(applicationContext\.packageName\)\)\s*\{", upf):
    b = block(upf, g.start())
    if g.start() < ap and b and re.search(r"\breturn\b", upf[b[0]:b[1]]):
        p = predicate(W, g.group(1)) or predicate(F, g.group(1))
        if p: gates.append(("updateFleet:" + g.group(1), p))
        else: bad(f"gate {g.group(1)} has no evaluable `fun {g.group(1)}(packageName: String): Boolean = ...`")
runners = sorted(h for h, p in pkgs.items() if p and all(fn(p) for _, fn in gates))
store = consts.get("STORE_PKG")
if runners == [h for h, p in pkgs.items() if p == store] and len(runners) == 1:
    ok(f"L1 one owner: only {runners[0]} runs the unattended fleet pass (gates: {', '.join(n for n, _ in gates)})")
else:
    bad(f"L1 {len(runners)} apps run the unattended fleet pass, each asking for the rootfs libs "
        f"({', '.join(runners)}) - expected only Cloud Store ({store})")
if "Fleet.parse(" in upf and ap >= 0:
    first_gate = min([g.start() for g in re.finditer(r"if \(!\w+\(applicationContext\.packageName\)\)", upf)] or [10**9])
    if first_gate < upf.find("Fleet.parse("):
        ok("L1 a non-owner returns before the fleet is even parsed")
    else:
        bad("L1 the fleet is parsed (and acted on) before the ownership gate")

# ── L2 how many times three unattended passes offer one unchanged pending lib ─
inst = body(F, "private fun installAllLocked") or ""
loop = body(inst, "staged.forEachIndexed") or ""
commit = loop.find("commit(ctx, app, apk)")
skip = re.search(r"if \(mode == Mode\.AUTO[^{}]*?PassLedgerStore\.offeredBefore\(ctx, app\.pkg, candidateSha\)\)\s*\{", loop)
if skip:
    b = block(loop, skip.start())
    if not (b and "return@forEachIndexed" in loop[b[0]:b[1]]): skip = None
lb = re.search(r"if \(landed\)\s*\{", loop)
landed_blk = block(loop, lb.start()) if lb else None
pend = None
if landed_blk:
    m2 = re.match(r"\s*else\s*\{", loop[landed_blk[1] + 1:])
    if m2:
        pb = block(loop, landed_blk[1] + 1)
        pend = loop[pb[0]:pb[1]] if pb else None
marks = bool(pend and re.search(r"PassLedgerStore\.markOffered\(ctx, app\.pkg, candidateSha\)", pend))
L = strip(os.path.join(upd, "PassLedger.kt"))
store_ok = all(re.search(r"fun " + f + r"\(", L) for f in ("offeredBefore", "markOffered", "clearOffered"))
remembered = None
offers = 0
for _ in range(3):
    if skip and commit >= 0 and skip.start() < commit and store_ok and remembered == "sha-A":
        continue
    offers += 1
    if marks and store_ok: remembered = "sha-A"
if offers == 1:
    ok("L2 three unattended passes offer one unchanged pending lib once")
else:
    bad(f"L2 three unattended passes offer one unchanged pending lib {offers} times "
        f"(skip-before-commit={bool(skip) and skip.start() < commit}, remembered-when-pending={marks})")
if landed_blk and "PassLedgerStore.clearOffered(ctx, app.pkg)" in loop[landed_blk[0]:landed_blk[1]]:
    ok("L2 a landed install forgets what was offered, so the next build is offered again")
else:
    bad("L2 a landed install does not clear the offered marker")
if re.search(r'getString\("offered:\$pkg", null\)\s*\.equals\(sha256, ignoreCase = true\)', L) and \
   re.search(r'putString\("offered:\$pkg", sha256\.lowercase\(\)\)\.commit\(\)', L):
    ok("L2 the marker is keyed by package and sha256, and committed (the next pass may be a cold process)")
else:
    bad("L2 the offered marker is not a committed per-package sha256")

# ── L3 quiet progress ────────────────────────────────────────────────────────
n = body(B, "fun notification(ctx: Context)") or ""
if re.search(r"SilentChannels\.ensure\(ctx, CHANNEL, [^,]+, NotificationManager\.IMPORTANCE_(LOW|MIN), progress = true\)", n):
    ok("L3 the progress line's channel is LOW at most (MIN and silent in Cloud Store)")
else:
    bad("L3 the progress line's channel is above LOW")
if ".setSilent(true)" in n and ".setOnlyAlertOnce(true)" in n and not re.search(r"setSound|setVibrate|setDefaults", n):
    ok("L3 the progress line is silent and alerts once")
else:
    bad("L3 the progress line can make a sound or alert again")
if "if (BatchForeground.allowed(applicationContext))\n                fgsToken = holdDataSync(" in W:
    ok("L3 the worker's foreground line is gated like every Store notification")
else:
    bad("L3 the worker draws a foreground line without the Store gate")
sys.exit(min(fails, 99))
PY
}

echo "test-fleet-pass-one-owner: the tree"
check "$LIBS"; base=$?
[ "$base" -ge 100 ] && echo "  CHECK CRASHED (exit $base)"

# ── mutations: each must turn the check red ──────────────────────────────────
M=0
mutate() {  # mutate <name> <file-under-libs> <from> <to>
  local tmp; tmp="$(mktemp -d)"
  mkdir -p "$tmp/libs"
  cp -r "$LIBS/updater" "$LIBS/core" "$tmp/libs/"
  python3 - "$tmp/libs/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT PLANTED: $1"; M=$((M+1)); rm -rf "$tmp"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if a not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
PY
  check "$tmp/libs" >/dev/null; local rc=$?
  # A crashed check (killed, no python3) is not a caught mutation: only a counted FAIL is.
  if [ "$rc" -eq 0 ]; then echo "  MUTATION SURVIVED: $1"; M=$((M+1))
  elif [ "$rc" -ge 100 ]; then echo "  CHECK CRASHED (exit $rc): $1"; M=$((M+1))
  else echo "  mutation caught: $1"; fi
  rm -rf "$tmp"
}
W="$UPD_REL/UpdateWorker.kt"; F="$UPD_REL/Fleet.kt"; L="$UPD_REL/PassLedger.kt"; B="$UPD_REL/BatchForegroundService.kt"
mutate "no ownership gate in updateFleet"  "$W" 'if (!ownsFleetPass(applicationContext.packageName)) {' 'if (false) {'
mutate "every app owns the fleet pass"     "$W" '= packageName == StoreNotifyGate.STORE_PKG' '= packageName != StoreNotifyGate.SUPERAPP_PKG'
mutate "a fleet pass before the gate"      "$W" 'if (!ownsFleetPass(applicationContext.packageName)) {' 'Fleet.autoPass(applicationContext, Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64), owner = "early"); if (!ownsFleetPass(applicationContext.packageName)) {'
mutate "AUTO re-offers the same bytes"     "$F" 'PassLedgerStore.offeredBefore(ctx, app.pkg, candidateSha)) {' 'false) {'
mutate "a pending offer is not remembered" "$F" 'if (mode == Mode.AUTO && candidateSha != null) PassLedgerStore.markOffered(ctx, app.pkg, candidateSha)' ''
mutate "a landed install keeps the marker" "$F" 'PassLedgerStore.clearOffered(ctx, app.pkg)' ''
mutate "the marker lives in memory"        "$L" 'sha256.lowercase()).commit()' 'sha256.lowercase()).apply()'
mutate "a loud progress channel"           "$B" 'NotificationManager.IMPORTANCE_LOW, progress = true)' 'NotificationManager.IMPORTANCE_HIGH, progress = true)'
mutate "a progress line with sound"        "$B" '.setSilent(true)' '.setSilent(false)'

echo "test-fleet-pass-one-owner: tree failures=$base, mutations not caught=$M"
[ "$base" -eq 0 ] && [ "$M" -eq 0 ]
