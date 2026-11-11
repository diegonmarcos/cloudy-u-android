#!/usr/bin/env bash
# ac_cloud-notes tester (#547): an HTML file opened from device storage must be
# CONVERTED into a BlockSuite doc, never imported as markdown text.
#
# ExternalFilePlugin.kt always accepted .html, and app.tsx then wrapped every
# file as `new File([content], name, { type: 'text/markdown' })` for the
# Obsidian importer — so an HTML page landed as a doc showing its own literal
# markup. Neither compiler can see that: it type-checks and it builds. So the
# route is asserted here, off the source, with no build and no device:
#
#   ext is html (per the MIME table) and the device reads it  =>  app.tsx's
#   HTML_EXTENSIONS routes it, and that branch calls upstream's
#   HtmlTransformer.importHTMLToDoc with options the converter declares, and
#   no text/markdown wrapper is reachable from it.
#
# NOTHING IS WRITTEN HERE THAT THE CODE UNDER TEST ALSO STATES. The extensions
# the device reads come from the Kotlin SUPPORTED set; which of them are HTML
# comes from Python's built-in MIME table (not the host's /etc/mime.types, so
# the answer cannot vary by runner); the options come from the converter's own
# ImportHTMLToDocOptions type. Paths come from build.json::tests.html_import.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$ROOT" <<'PY'
import json, mimetypes, os, re, sys

root = sys.argv[1]
cfg = json.load(open(os.path.join(root, 'build.json')))['tests']['html_import']
src = {}
for key in ('entrypoint', 'native_plugin', 'converter'):
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
            return text[i + 1:j], j
    raise ValueError('unbalanced braces')

# ── device side: what ExternalFilePlugin will read ─────────────────────────
m = re.search(r'SUPPORTED\s*=\s*setOf\s*\((.*?)\)', src['native_plugin'], re.S)
native = set(re.findall(r'"([^"]+)"', m.group(1))) if m else set()
check(bool(native), 'native SUPPORTED set parsed (%d extensions)' % len(native))

mime = mimetypes.MimeTypes(filenames=())
is_html = lambda ext: mime.guess_type('x.' + ext)[0] == 'text/html'
native_html = {e for e in native if is_html(e)}
# The feature is "open HTML": a device that reads no HTML extension has no
# HTML to route, and every check below would pass over an empty set.
check(bool(native_html), 'device reads HTML: %s' % sorted(native_html))

# ── app side: which extensions app.tsx sends to the converter ──────────────
app = src['entrypoint']
m = re.search(r'const\s+HTML_EXTENSIONS\s*=\s*new\s+Set\s*\(\s*\[(.*?)\]', app, re.S)
routed = set(re.findall(r"""['"]([^'"]+)['"]""", m.group(1))) if m else set()
check(bool(routed), 'app.tsx HTML_EXTENSIONS parsed: %s' % sorted(routed))
check(native_html <= routed,
      'every HTML extension the device reads is routed to the converter'
      + ('' if native_html <= routed else ' — imported AS MARKDOWN: %s' % sorted(native_html - routed)))
check(routed <= native,
      'every routed extension is one the device reads'
      + ('' if routed <= native else ' — dead route, native rejects: %s' % sorted(routed - native)))
check(all(is_html(e) for e in routed),
      'every routed extension is HTML' + ''.join(' — not html: %s' % e for e in sorted(routed) if not is_html(e)))
check(re.search(r'const\s+isHtmlFile\s*=.*?HTML_EXTENSIONS\.has\(', app, re.S) is not None,
      'isHtmlFile() decides by HTML_EXTENSIONS')

# ── the branch: converter on the html side, markdown only on the other ─────
g = re.search(r'if\s*\(\s*isHtmlFile\s*\(\s*file\.name\s*\)\s*\)', app)
check(g is not None, 'openExternalFile branches on isHtmlFile(file.name)')
if g:
    then_body, end = braced(app, g.end())
    rest = app[end + 1:]
    e = re.match(r'\s*else\s*', rest)
    else_body = braced(rest, e.end())[0] if e else ''
    call = re.search(r'HtmlTransformer\.importHTMLToDoc\s*\(', then_body)
    check(call is not None, 'html branch calls HtmlTransformer.importHTMLToDoc')
    check('text/markdown' not in then_body and 'importObsidianVault' not in then_body,
          'html branch has no markdown wrapper / Obsidian importer')
    check('importObsidianVault' in else_body,
          'non-html files still reach the Obsidian importer (else branch)')
    # every markdown wrapper in the opener must sit in that else branch
    o = re.search(r'const\s+importExternalFile\s*=\s*async\s*\([^)]*\)\s*=>', app)
    opener = braced(app, o.end())[0] if o else ''
    check(bool(opener) and opener.count('text/markdown') == else_body.count('text/markdown'),
          'no text/markdown File is built in importExternalFile (#1642: the shared import of openExternalFile and Open-with) outside the non-html branch')
    if call:
        args = braced(then_body, call.end() - 1)[0]
        passed = set(re.findall(r'^\s*(\w+)\s*:', args, re.M))
        t = re.search(r'type\s+ImportHTMLToDocOptions\s*=\s*\{(.*?)\};', src['converter'], re.S)
        fields = dict(re.findall(r'^\s*(\w+)(\??)\s*:', t.group(1), re.M)) if t else {}
        required = {k for k, opt in fields.items() if not opt}
        check(bool(fields), 'converter options derived: %s' % sorted(fields))
        check(passed <= set(fields), 'every option passed is one the converter declares'
              + ('' if passed <= set(fields) else ': unknown %s' % sorted(passed - set(fields))))
        check(required <= passed, 'every required converter option is passed'
              + ('' if required <= passed else ': missing %s' % sorted(required - passed)))
        check(re.search(r'\bhtml\s*:\s*file\.content\b', args) is not None,
              'the file read from the device is what gets converted (html: file.content)')

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
