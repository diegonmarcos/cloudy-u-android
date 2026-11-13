#!/bin/sh
# test-c3-webserver-fgs-guard.sh (#1724): every startForegroundService() call site of this app and of the shared libs it compiles
# names a service that calls startForeground() UNCONDITIONALLY: first statement of onStartCommand (every start request needs its own
# startForeground within ~5 s, the early returns included) or, when the service has no onStartCommand, the first statement of
# onCreate (the text before the first startForeground holds no return, stopSelf, if, when, try or loop). A service that goes foreground only behind an `if`, after an early return, or not at all crashes the app with
# ForegroundServiceDidNotStartInTimeException (the owner's startup crash).
#
#   G1 every call site's service class is found in the scanned tree (an unresolvable one is listed, never skipped silently)
#   G2 that service goes foreground unconditionally as above
#   G3 KeepAliveService's contract is held by KeepAliveFlow (foreground first on every path) and no one calls
#      startForegroundService on it except through KeepAliveStarter
#   X  mutation: a service whose onStartCommand returns early before startForeground goes red; so does a missing call
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
WF="$ROOT/1_cicd/src/cicd/ship-cloud-c3-webserver.yml"

check() {
python3 - "$1" "$2" "$3" <<'PY'
import os, re, sys
app, root, wf = sys.argv[1], sys.argv[2], sys.argv[3]
dirs = [os.path.join(app, "src-tauri/gen/android/app/src")]
if os.path.isfile(wf):
    for m in re.finditer(r'"(ab_cloud-libs-shared/libs/[^/*"]+)/\*\*"', open(wf).read()):
        dirs.append(os.path.join(root, m.group(1)))
dirs += [d for d in os.environ.get("FGS_EXTRA_DIRS", "").split(":") if d]
files = []
for d in dirs:
    for dp, dn, fn in os.walk(d):
        dn[:] = [x for x in dn if x not in ("build", "test", "androidTest", ".git")]
        files += [os.path.join(dp, f) for f in fn if f.endswith((".kt", ".java"))]
src = {f: open(f, errors="replace").read() for f in files}
FG = re.compile(r"startForeground\s*\(|startKeepAlive\s*\(|enterForeground\s*\(|KeepAliveFlow\.onStartCommand\s*\(")
def strip(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)//.*$", "", t)
def body(t, name):
    m = re.search(r"fun\s+" + name + r"\s*\(|\b(?:public|protected)?\s*(?:int|void)\s+" + name + r"\s*\(", t)
    if not m: return None
    i = t.index("{", m.end()) if "{" in t[m.end():] else None
    eq = re.compile(r"\)\s*(?::\s*\w+\s*)?=").search(t, m.end(), t.index("{", m.end()) if i else len(t))
    if eq and (i is None or eq.start() < i):   # expression body: the whole expression is the first statement
        return t[eq.end():eq.end() + 400]
    depth, j = 0, i
    while j < len(t):
        depth += (t[j] == "{") - (t[j] == "}")
        if depth == 0: break
        j += 1
    return t[i + 1:j]
def before_foreground(b):
    """The text of [b] before its first foreground call, or None when it has none."""
    m = FG.search(b)
    if not m: return None
    pre = b[:m.start()]
    pre = re.sub(r"if\s*\(\s*Build\.VERSION\.SDK_INT[^)]*\)", "", pre)   # the API-level split of one startForeground (both branches call it)
    return pre
def unconditional(b):
    """True when [b] reaches a foreground call before any return / stop / branch."""
    pre = before_foreground(b)
    return pre is not None and not re.search(r"\b(return|stopSelf|stopForeground|if|when|else|try|for|while)\b", pre)
bad, sites = [], 0
for f, raw in src.items():
    t = strip(raw)
    for m in re.finditer(r"startForegroundService\s*\(", t):
        sites += 1
        win = re.sub(r"^\w+", "", t[max(0, m.start() - 1500):m.end() + 300])   # a cut-off first word is not a class name
        names = re.findall(r"(\w+)::class\.java|Class\.forName\(\"(?:[\w.]*\.)?(\w+)\"\)|new\s+Intent\([^,]+,\s*(\w+)\.class", win)
        cls = [n for tup in names for n in tup if n]
        if not cls:
            if re.search(r"fun\s+startForegroundService", t[max(0, m.start() - 40):m.end()]): continue   # a definition, not a call
            bad.append("G1 %s: cannot tell which service startForegroundService starts here" % os.path.relpath(f, root)); continue
        for c in set(cls):
            if c == "Intent": continue
            sf = [g for g, r in src.items() if re.search(r"\bclass\s+" + c + r"\b", strip(r)) and re.search(r"\bService\b|:\s*\w*Service\(", strip(r))]
            if not sf:
                bad.append("G1 %s: service %s is not in the scanned tree" % (os.path.relpath(f, root), c)); continue
            st = strip(src[sf[0]])
            osc = body(st, "onStartCommand"); oc = body(st, "onCreate")
            if osc is not None:
                if not unconditional(osc):
                    bad.append("G2 %s.onStartCommand does not call startForeground before every return / stopSelf / branch" % c)
            elif oc is None or not unconditional(oc):
                bad.append("G2 %s has no onStartCommand and onCreate does not call startForeground unconditionally first" % c)
if sites == 0: bad.append("G1 no startForegroundService call site found: the guard lost the app")
print("\n".join("FAIL " + b for b in bad) if bad else "ok   %d startForegroundService call site(s), every service goes foreground first" % sites)
sys.exit(1 if bad else 0)
PY
}
fail=0
check "$APP" "$ROOT" "$WF" || fail=1
# G3: the flow contract is what KeepAliveService runs
K="$APP/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver"
grep -q 'KeepAliveFlow.onStartCommand' "$K/KeepAliveService.kt" && echo "ok   KeepAliveService delegates onStartCommand to KeepAliveFlow" || { echo "FAIL G3 KeepAliveService does not run KeepAliveFlow.onStartCommand"; fail=1; }
grep -qE 'val foreground = host\.enterForeground\(\)' "$K/KeepAliveFlow.kt" && echo "ok   KeepAliveFlow enters the foreground before any branch" || { echo "FAIL G3 KeepAliveFlow does not enter the foreground first"; fail=1; }
for f in "$K"/*.kt "$K"/ui/*.kt; do
  case "$f" in */KeepAliveStarter.kt) continue;; esac
  if grep -v '^\s*\(//\|\*\|/\*\)' "$f" | grep -q 'startForegroundService'; then echo "FAIL G3 $(basename "$f") calls startForegroundService directly; use KeepAliveStarter.sync"; fail=1; fi
done
# X: mutations on a temp copy
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/app/src-tauri/gen/android/app/src/main/java/x" "$T/root"
S="$T/app/src-tauri/gen/android/app/src/main/java/x"
cat > "$S/Caller.kt" <<'KT'
package x
class Caller { fun go(c: android.content.Context) { c.startForegroundService(android.content.Intent(c, SvcService::class.java)) } }
KT
good() { cat > "$S/SvcService.kt" <<'KT'
package x
class SvcService : android.app.Service() {
    override fun onCreate() { super.onCreate(); startForeground(1, n()) }
    override fun onStartCommand(i: android.content.Intent?, f: Int, id: Int): Int {
        startForeground(1, n())
        if (i == null) return START_NOT_STICKY
        return START_STICKY
    }
}
KT
}
good; check "$T/app" "$T/root" "$T/none.yml" >/dev/null 2>&1 && echo "ok   mutation base: a correct service is green" || { echo "FAIL mutation base is red"; fail=1; }
good; sed -i 's/        startForeground(1, n())$/        if (i == null) return START_NOT_STICKY\n        startForeground(1, n())/' "$S/SvcService.kt"
check "$T/app" "$T/root" "$T/none.yml" >/dev/null 2>&1 && { echo "FAIL mutation: an early return before startForeground stayed green"; fail=1; } || echo "ok   mutation: early return before startForeground goes red"
good; sed -i '/startForeground/d' "$S/SvcService.kt"
check "$T/app" "$T/root" "$T/none.yml" >/dev/null 2>&1 && { echo "FAIL mutation: a service with no startForeground stayed green"; fail=1; } || echo "ok   mutation: a service with no startForeground goes red"
good; sed -i 's/        startForeground(1, n())$/        if (f == 0) startForeground(1, n())/' "$S/SvcService.kt"
check "$T/app" "$T/root" "$T/none.yml" >/dev/null 2>&1 && { echo "FAIL mutation: a conditional startForeground stayed green"; fail=1; } || echo "ok   mutation: conditional startForeground goes red"
exit $fail
