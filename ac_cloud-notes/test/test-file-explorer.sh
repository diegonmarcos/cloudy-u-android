#!/usr/bin/env bash
# ac_cloud-notes tester (#547, #1717): the import action opens a file EXPLORER, not a typed-path
# prompt, and the native side that feeds it is confined and consistent.
#
# Neither compiler can see any of this: the TS plugin type is hand-written
# against a Kotlin class Gradle never cross-checks (listDir declared in one and
# missing in the other only throws on the phone), and a path guard that tests a
# string prefix compiles fine while "/storage/emulated/0/../" walks out of it.
# So it is asserted off the sources, with no build and no device. Paths come
# from build.json::tests.file_explorer; nothing below names a method or an
# event that the code under test also states — those are DERIVED.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$ROOT" <<'PY'
import json, os, re, sys

root = sys.argv[1]
cfg = json.load(open(os.path.join(root, 'build.json')))['tests']['file_explorer']
src = {}
for key in ('entrypoint', 'plugin_ts', 'native_plugin', 'activity', 'source_menu'):
    path = os.path.join(root, cfg[key])
    if not os.path.isfile(path):
        print('  FAIL  %s missing: %s' % (key, cfg[key]))
        raise SystemExit(1)
    src[key] = open(path, encoding='utf-8').read()

fails = 0
def check(cond, msg):
    global fails
    print('  %s  %s' % ('PASS' if cond else 'FAIL', msg))
    if not cond:
        fails += 1

def braced(text, start):
    """The {...} body opening at the first '{' at/after start."""
    i = text.index('{', start)
    depth = 0
    for j in range(i, len(text)):
        depth += {'{': 1, '}': -1}.get(text[j], 0)
        if depth == 0:
            return text[i + 1:j]
    raise ValueError('unbalanced braces')

def kotlin_fun(name):
    m = re.search(r'\bfun\s+%s\s*\(' % re.escape(name), src['native_plugin'])
    return braced(src['native_plugin'], m.end()) if m else None

# ── the TS type and the Kotlin class declare the same methods ──────────────
t = re.search(r'type\s+ExternalFilePluginType\s*=\s*\{', src['plugin_ts'])
ts_methods = set(re.findall(r'^\s*(\w+)\s*\(', braced(src['plugin_ts'], t.start()), re.M)) if t else set()
kt_methods = set(re.findall(r'@PluginMethod\s+fun\s+(\w+)\s*\(', src['native_plugin']))
check('listDir' in ts_methods, 'the plugin type declares an explorer listing method: %s' % sorted(ts_methods))
check(ts_methods <= kt_methods,
      'every method the web side calls is a native @PluginMethod'
      + ('' if ts_methods <= kt_methods else ' — missing natively: %s' % sorted(ts_methods - kt_methods)))

# ── app side: the explorer replaces the typed-path prompt ──────────────────
app = src['entrypoint']
check('window.prompt' not in app, 'app.tsx never asks the user to type a path (no window.prompt)')
check(re.search(r'ExternalFile\.listDir\s*\(', app) is not None, 'app.tsx lists folders through ExternalFile.listDir')
check(re.search(r'\bopenExternalFile\s*\(\s*\w+\s*\)', app) is not None
      and re.search(r'const\s+openExternalFile\s*=\s*async\s*\(\s*path\s*:', app) is not None,
      'a file picked in the explorer is opened by path (openExternalFile(path))')
check(re.search(r'\bExternalFile\.listDir\s*\(\s*\{\s*path\s*:\s*EXTERNAL_FILE_ROOT|\bbrowse\s*\(\s*EXTERNAL_FILE_ROOT', app) is not None,
      'the FAB event starts the explorer at the storage root')

# ── the explorer's events: what it listens for is dispatched ──────────────
# #1717 the floating button is gone: the open event is fired by the Vault sidebar's Source section
# (build.json::tests.file_explorer.source_menu), the view-intent event still by MainActivity.
fired = set(re.findall(r"dispatchEvent\(new Event\('([^']+)'\)\)", src['activity']))
fired |= set(re.findall(r"dispatchEvent\(new Event\('([^']+)'\)\)", src['source_menu']))
check('FloatingActionButton' not in src['activity'], 'MainActivity draws no floating button (the Source section opens the explorer)')
check(re.search(r"onClick=\{requestImport\}", src['source_menu']) is not None, 'the Source section has the import action')
m = re.search(r'const\s+ExternalFileOpener\s*=', app)
heard = set(re.findall(r"addEventListener\('([^']+)'", braced(app, m.start()))) if m else set()
check(bool(heard) and heard <= fired,
      'every event the explorer listens for is dispatched (MainActivity or the Source section): %s' % sorted(heard)
      + ('' if heard <= fired else ' — never fired: %s' % sorted(heard - fired)))

# ── native: confined, and offers only what readFile accepts ────────────────
guard = kotlin_fun('guardedFile') or ''
check(bool(guard), 'a shared guardedFile() gate exists')
check(guard.count('.canonicalPath') >= 2,
      'the gate canonicalises BOTH the path and the root it is compared to (a prefix test alone lets ".." escape)')
check('hasExternalStorageAccess()' in guard, 'the gate checks storage access')
for name in sorted(kt_methods & {'readFile', 'listDir'}):
    check('guardedFile(' in (kotlin_fun(name) or ''), '%s goes through guardedFile()' % name)
ld = kotlin_fun('listDir') or ''
check(re.search(r'\bin\s+SUPPORTED\b', ld) is not None,
      'listDir offers only the extensions readFile accepts (SUPPORTED)')
check('isDirectory' in ld, 'listDir returns folders as well as files')

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
