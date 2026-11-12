#!/usr/bin/env bash
# ac_cloud-notes tester (#513): the WebView must be able to BOOT.
#
# The white page Diego shipped was not a crash, a missing bundle or a missing
# .so — the APK carried all 114 JS chunks and a 51 MB libcloud_notes_mobile_native.so.
# It was ONE line of TypeScript: ExternalFileOpener, which App() renders on the
# ROOT framework provider, called `useService(ImportService)`, and ImportService
# is registered `framework.scope(WorkspaceScope).service(ImportService, …)`. The
# resolver walks the provider stack and, finding no workspace scope on it,
# executes `throw new ComponentNotFoundError(identifier)` — during the FIRST
# render, with no error boundary anywhere above it. React unmounted the whole
# tree; the WebView painted white; the FAB's window listener was never installed,
# so the button "triggered nothing". One fault, every symptom.
#
# Nothing in the pipeline could see it. TypeScript cannot: the call type-checks.
# Gradle cannot: it builds an APK around a bundle it never runs. The ship run
# went green and published a blank app.
#
# So this tester reads the invariant that was violated, off the source, with no
# build and no device:
#
#   A service resolved on the ROOT provider — useService(X), useServices({X}),
#   frameworkProvider.get(X) inside the android entrypoint — must NOT be one
#   that some module registers under a `.scope(...)`.
#
# The scoped set is DERIVED from the modules on every run (never a list kept
# here, which would rot the first time upstream moves a service between scopes).
# Paths come from build.json::tests.root_scope.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

# ── 1. no scope-bound service is resolved on the root provider ────────────
scope_report="$(python3 - "$ROOT" <<'PY'
import json, os, re, sys

root = sys.argv[1]
cfg = json.load(open(os.path.join(root, 'build.json')))['tests']['root_scope']
entry = os.path.join(root, cfg['entrypoint'])
if not os.path.isfile(entry):
    print('ERROR entrypoint missing: %s' % cfg['entrypoint'])
    raise SystemExit(2)

# Every `.scope(X).service(Y)` / .entity( / .store( / .impl( / .override( chain
# in the declared module roots. Y is then resolvable ONLY under X's scope.
CHAIN = re.compile(
    r'\.scope\(\s*[A-Za-z_]\w*\s*\)\s*'
    r'((?:\.\s*(?:service|entity|store|impl|override)\s*\(\s*[A-Za-z_]\w*)+)'
)
MEMBER = re.compile(r'\.\s*(?:service|entity|store|impl|override)\s*\(\s*([A-Za-z_]\w*)')

scoped, scanned = set(), 0
for rel in cfg['module_roots']:
    base = os.path.join(root, rel)
    if not os.path.isdir(base):
        print('ERROR module_root missing: %s' % rel)
        raise SystemExit(2)
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if not name.endswith(('.ts', '.tsx')):
                continue
            scanned += 1
            text = open(os.path.join(dirpath, name), encoding='utf-8',
                        errors='replace').read()
            for chain in CHAIN.finditer(text):
                scoped.update(MEMBER.findall(chain.group(1)))

# A derivation that found nothing would certify anything. Fail closed.
if scanned < 100 or len(scoped) < 10:
    print('ERROR derivation is empty: scanned %d files, found %d scoped services'
          % (scanned, len(scoped)))
    raise SystemExit(2)

src = open(entry, encoding='utf-8', errors='replace').read()

# Comments are prose, not resolution sites — and the comment directly above the
# fixed component quotes the broken call verbatim, so a tester that reads them
# convicts the very documentation that explains the fix. Only whole-line `//`
# comments are dropped: a trailing `//` may be inside a string ('https://…') and
# cutting the rest of that line could hide real code after it.
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
src = re.sub(r'(?m)^[ \t]*//.*$', '', src)

resolved = set(re.findall(r'\buseService\(\s*([A-Za-z_]\w*)', src))
resolved |= set(re.findall(r'\bframeworkProvider\.get\(\s*([A-Za-z_]\w*)', src))
for block in re.findall(r'\buseServices\(\s*\{([^}]*)\}', src):
    resolved.update(re.findall(r'[A-Za-z_]\w*', block))

bad = sorted(resolved & scoped)
print('SCANNED %d %d %d' % (scanned, len(scoped), len(resolved)))
for name in bad:
    print('BAD %s' % name)
PY
)"
scope_rc=$?

if [ "$scope_rc" -ne 0 ]; then
  fail "root-scope derivation could not run: $scope_report"
elif grep -q '^BAD ' <<<"$scope_report"; then
  printf '%s\n' "$scope_report" | sed -n 's/^BAD /    scope-bound service resolved at root: /p'
  fail "$(printf '%s\n' "$scope_report" | grep -c '^BAD ') scope-bound service(s) resolved on the ROOT provider in $(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['tests']['root_scope']['entrypoint'])" "$ROOT/build.json") — each one throws ComponentNotFoundError on first render and blanks the WebView"
else
  ok "no scope-bound service resolved on the root provider ($(printf '%s\n' "$scope_report" | sed -n 's/^SCANNED //p'))"
fi

# ── 2. the FAB and the listener agree on the event name ───────────────────
# The native FAB is the app's only entry to the file-explorer feature and it
# reaches the web layer through exactly one string. A rename on either side is
# silent: the button still draws, still animates, and does nothing — which is
# indistinguishable from the boot failure above and was misread as one.
# MainActivity dispatches more than one event now (view-intent, open-file, memory-pressure), so "the first one on each side"
# no longer compares like with like. Every event the native side dispatches must have a listener in the web layer.
native_events="$(grep -oE "new Event\('[^']+'\)" \
  "$ROOT/packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/MainActivity.kt" \
  | sed "s/new Event('//;s/')//" | sort -u)"
if [ -z "$native_events" ]; then
  fail "MainActivity.kt dispatches no event: the FAB has no way into the web layer"
fi
case "$native_events" in *cloud-notes:open-file*) ok "the FAB's event cloud-notes:open-file is dispatched" ;; *) fail "MainActivity.kt no longer dispatches cloud-notes:open-file (the FAB's event)" ;; esac
for ev in $native_events; do
  if grep -rqF "addEventListener('$ev'" "$ROOT/packages/frontend/apps/android/src" "$ROOT/packages/frontend/core/src/mobile"; then
    ok "native event '$ev' has a listener in the web layer"
  else
    fail "FAB/listener event mismatch: MainActivity.kt dispatches '$ev' and nothing in the web layer listens for it"
  fi
done

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-notes root-scope tester: ALL PASS\n'
else
  printf '  cloud-notes root-scope tester: FAIL\n' >&2
  exit 1
fi
