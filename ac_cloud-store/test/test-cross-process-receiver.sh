#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1121 — no app class crosses into another app's process in an Intent     ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Cloud Terminal's UnpackService died on the phone with BadParcelableException
# (ClassNotFoundException ...UnpackAfterInstall$run$receiver$1): Cloud Store put an
# anonymous ResultReceiver subclass in the UNPACK intent and the terminal's
# classloader cannot load it. Rule (X1): a source file that declares a custom or
# anonymous ResultReceiver subclass AND sends an Intent to another package
# (ComponentName / setPackage / setComponent / setClassName) must pass the
# receiver through libs:core CrossProcess.plainResultReceiver (or
# putPlainResultReceiver). Rule (X2): CrossProcess still does the Parcel
# round-trip. Fixtures prove the checker; a mutant of the real sender must go red.
#
# Static, offline, python3. Scans every .kt/.java under the repo except tests and builds.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
fail=0

cat > "$T/check.py" <<'PY'
import os, re, sys
SUB = re.compile(r'object\s*:\s*(android\.os\.)?ResultReceiver\s*\(|class\s+\w+[^{]*:\s*(android\.os\.)?ResultReceiver\s*\(|extends\s+(android\.os\.)?ResultReceiver\b')
FOREIGN = re.compile(r'ComponentName\s*\(|\.setPackage\s*\(|\.setComponent\s*\(|\.setClassName\s*\(')
PUT = re.compile(r'putExtra\s*\(')
SAFE = re.compile(r'plainResultReceiver\s*\(|putPlainResultReceiver\s*\(|receiverForSending\s*\(')
def bad(path, text):
    if SUB.search(text) and FOREIGN.search(text) and PUT.search(text) and not SAFE.search(text):
        return f"X1 {path}: a custom ResultReceiver is sent to another package without CrossProcess.plainResultReceiver"
out = []
for arg in sys.argv[1:]:
    if os.path.isfile(arg):
        files = [arg]
    else:
        files = []
        for d, dirs, fs in os.walk(arg):
            dirs[:] = [x for x in dirs if x not in ('build', '.git', '.gradle', 'test', 'androidTest', 'node_modules')]
            files += [os.path.join(d, f) for f in fs if f.endswith(('.kt', '.java'))]
    for f in files:
        try: t = open(f, encoding='utf-8', errors='replace').read()
        except OSError: continue
        if 'ResultReceiver' not in t: continue
        v = bad(os.path.relpath(f, os.getcwd()), t)
        if v: out.append(v)
print("\n".join(out))
PY

CP="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/core/CrossProcess.kt"
SENDER="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/UnpackAfterInstall.kt"

# X2: the helper exists and does the round-trip
if [ -f "$CP" ] && grep -q 'writeToParcel' "$CP" && grep -q 'ResultReceiver.CREATOR.createFromParcel' "$CP"; then
    echo "ok   CrossProcess.plainResultReceiver round-trips through a Parcel"
else
    echo "FAIL X2 libs:core CrossProcess.plainResultReceiver is missing or no longer round-trips"; fail=1
fi

# the fleet
v="$(cd "$ROOT" && python3 "$T/check.py" "$ROOT")"
if [ -n "$v" ]; then echo "FAIL"; echo "$v"; fail=1; else echo "ok   no fleet source sends a custom ResultReceiver to another package raw"; fi

# fixtures: the checker itself
cat > "$T/Bad.kt" <<'K'
val r = object : ResultReceiver(Handler(Looper.getMainLooper())) {}
val i = Intent("A").setComponent(ComponentName("cld.termux", "S")).putExtra("r", r)
K
cat > "$T/Good.kt" <<'K'
val r = object : ResultReceiver(Handler(Looper.getMainLooper())) {}
val i = Intent("A").setComponent(ComponentName("cld.termux", "S")).putExtra("r", CrossProcess.plainResultReceiver(r))
K
cat > "$T/Local.kt" <<'K'
val r = object : ResultReceiver(Handler(Looper.getMainLooper())) {}
val i = Intent(ctx, Mine::class.java).putExtra("r", r)
K
python3 "$T/check.py" "$T/Bad.kt" | grep -q '^X1' && echo "ok   fixture: a raw anonymous receiver to another package is caught" || { echo "FAIL fixture Bad.kt not caught"; fail=1; }
[ -z "$(python3 "$T/check.py" "$T/Good.kt" "$T/Local.kt")" ] && echo "ok   fixture: the wrapped receiver and an in-app intent pass" || { echo "FAIL fixture false positive"; fail=1; }

# mutant: the real sender without the helper must go red
if [ -f "$SENDER" ]; then
    sed 's/CrossProcess\.plainResultReceiver(local)/local/; s/receiverForSending(it)/it/; s/fun receiverForSending/fun rawForSending/' "$SENDER" > "$T/UnpackAfterInstall.kt"
    if cmp -s "$SENDER" "$T/UnpackAfterInstall.kt"; then echo "FAIL MUTATION DID NOT APPLY: the Store sender without the plain wrapper"; fail=1
    elif python3 "$T/check.py" "$T/UnpackAfterInstall.kt" | grep -q '^X1'; then echo "ok   mutation proved: the Store's UNPACK sender without the plain wrapper goes red"
    else echo "FAIL MUTATION SURVIVED: the Store sender without the plain wrapper"; fail=1; fi
else
    echo "FAIL no UnpackAfterInstall.kt at $SENDER"; fail=1
fi

[ "$fail" -eq 0 ] && echo "PASS cross-process receiver" || { echo "FAILED cross-process receiver"; exit 1; }
