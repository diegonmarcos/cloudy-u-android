#!/usr/bin/env bash
# A trailing lambda binds to the LAST parameter. Nothing else.
#
# WHAT THIS EXISTS FOR — run 34446987272, three errors on one line:
#
#   TextEnhancer.kt:184   fun rewrite(context, style, text,
#                                     progress: (Int, Int) -> Unit = { _, _ -> },
#                                     route: AiRouter.Route = AiRouter.route(context))
#   EnhanceBarView.kt:380 TextEnhancer.rewrite(context, style, t.text) { done, total -> ... }
#
# That call was correct the day it was written. A later commit appended `route`
# AFTER `progress` — a change whose own message said "the keyboard's callers are
# unchanged", and which touched neither the call site nor any file near it.
# Kotlin silently re-aimed the trailing lambda at `route`, and the call broke
# without being edited. Review reads the diff, and the diff did not contain the
# broken line, so the first thing that noticed was a ship run: no Cloud Keyboard
# APK for the fleet until someone read the compiler output.
#
# THE GENERAL SHAPE is a call site invalidated by an edit somewhere else, which
# normally only a compiler can see. This sees the one variant that is decidable
# WITHOUT one: a function whose LAST parameter is not a function type while an
# EARLIER one is, called with trailing-lambda syntax. There the lambda provably
# lands on the wrong parameter — there is no reading in which that call compiles.
#
# THE FIX IS ALWAYS AT THE CALL SITE and is always to pass the lambda BY NAME
# (`progress = { ... }`), which is immune to the next parameter being appended.
# Reordering the declaration back would only re-arm the trap for the next caller.
#
# SCOPE: every app in the repo, each resolved against the modules IT compiles,
# read from that app's build.json::modules — `dir` when a module is shared by
# reference, the gradle path otherwise, exactly as each settings.gradle does it.
# Not a list in here, and deliberately not one flat repo-wide scan either:
# resolving names across app boundaries matches ac_cloud-matrix's IconButton
# against the keyboard's Compose one and reports twenty-odd calls that compile
# perfectly well. A checker that cries wolf gets muted, and a muted checker is
# worth exactly as much as no checker.
#
# It lives under aa_cloud-superapp/test/ because that is a directory CI actually
# executes; ac_cloud-keyboard — the app that went red — has no test/ directory
# and no build.json::tests block, so a tester placed there would run nowhere.
#
# CEILING, stated rather than hidden: only calls whose `(` and `) {` sit on the
# SAME line are matched, and names resolve by name alone within an app. This
# does not replace compiling. It replaces waiting for CI to compile.
#
# Run from anywhere:  bash aa_cloud-superapp/test/test-kotlin-lambda-arg-position.sh
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1   # repo root

command -v python3 >/dev/null 2>&1 || {
    echo "FAIL — python3 absent; without it this tester would print a pass it never made"; exit 1; }

SCANNER=$(mktemp) || exit 1
FIXTURE=$(mktemp -d) || exit 1
trap 'rm -rf "$SCANNER" "$FIXTURE"' EXIT

cat > "$SCANNER" <<'PY'
import json, os, re, sys

SKIP = ('/.git/', '/build/', '/.gradle/', '/.cache/', '/vendor/')
OPEN, CLOSE = '([{<', ')]}>'


def strip_comments(s):
    # Newlines are PRESERVED. Collapsing a doc block to a single space also
    # collapses its line count, and every line number printed after that point
    # is then wrong — which is worse than printing none at all.
    s = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), s, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', s)


def walk(s):
    """Yield (index, token, depth-in). '->' is ONE token, never a bracket.

    Counting the '>' of an arrow as a closing bracket drives nesting depth
    negative, after which no comma is ever seen at top level again and a whole
    parameter list reads as one parameter. That bug made this tester report a
    clean tree while the tree was red; it is why walk() exists.
    """
    d, i = 0, 0
    while i < len(s):
        if s[i:i + 2] == '->':
            yield i, '->', d; i += 2; continue
        yield i, s[i], d
        i += 1


def split_top(s):
    out, last, d = [], 0, 0
    for i, ch, _ in walk(s):
        if ch in OPEN: d += 1
        elif ch in CLOSE: d = max(0, d - 1)
        elif ch == ',' and d == 0:
            out.append(s[last:i]); last = i + 1
    out.append(s[last:])
    return [p.strip() for p in out if p.strip()]


def _paren_closes_at_end(t):
    d = 0
    for i, ch, _ in walk(t):
        if ch == '(': d += 1
        elif ch == ')':
            d -= 1
            if d == 0: return i == len(t) - 1
    return False


def is_functional(param):
    """True when the parameter's declared TYPE accepts a lambda.

    The TYPE is what sits between the first ':' and the default value. Judging
    the whole parameter would read `progress: (Int,Int)->Unit = {_,_->}` and
    `mode: Mode = pick { it }` as the same thing, and only one of those takes a
    trailing lambda.
    """
    if ':' not in param: return False
    t = param.split(':', 1)[1]
    d = 0
    for i, ch, _ in walk(t):
        if ch in OPEN: d += 1
        elif ch in CLOSE: d = max(0, d - 1)
        elif ch == '=' and d == 0 and t[i:i + 2] != '==':
            t = t[:i]; break
    # A nullable or parenthesised function type is still a function type, and
    # Kotlin still takes a trailing lambda for it: ((Long, Long) -> Unit)? keeps
    # its arrow two levels down. Unwrap the redundant layers before looking.
    t = t.strip()
    while t.endswith('?') or (t.startswith('(') and _paren_closes_at_end(t)):
        t = (t[:-1] if t.endswith('?') else t[1:-1]).strip()
    d = 0
    for i, ch, _ in walk(t):
        if ch == '->' and d == 0: return True
        if ch in OPEN: d += 1
        elif ch in CLOSE: d = max(0, d - 1)
    return False


FUN = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(')


def scan(roots):
    files = []
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            marked = dirpath.replace(os.sep, '/') + '/'
            if any(s in marked for s in SKIP):
                dirnames[:] = []; continue
            files += [os.path.join(dirpath, f) for f in filenames if f.endswith('.kt')]

    cache, traps, private_traps = {}, {}, {}
    for path in files:
        try: src = open(path, encoding='utf-8', errors='replace').read()
        except OSError: continue
        cache[path] = src
        code = strip_comments(src)
        for m in FUN.finditer(code):
            name = m.group(1)
            i, d = m.end() - 1, 0
            while i < len(code):
                if code[i] == '(': d += 1
                elif code[i] == ')':
                    d -= 1
                    if d == 0: break
                i += 1
            else:
                continue
            params = split_top(code[m.end():i])
            if len(params) < 2 or is_functional(params[-1]):
                continue                          # the lambda lands where meant
            if any(is_functional(p) for p in params[:-1]):
                # A PRIVATE function is visible to its own file only (#1728: ui-nav-bars' test helper `private fun show(dark, ...)`
                # re-aimed nothing in an app that merely links the lib): its trap is armed for that file alone.
                line_start = code.rfind('\n', 0, m.start()) + 1
                if re.search(r'\bprivate\b', code[line_start:m.start()]):
                    private_traps.setdefault(name, []).append((path, code[:m.start()].count('\n') + 1))
                else:
                    traps[name] = (path, code[:m.start()].count('\n') + 1)

    hits = []
    if not traps and not private_traps:
        return traps, hits
    # a private trap is a trap for its own file only: it joins the scan under that restriction
    for name, decls in private_traps.items():
        for dpath, dline in decls:
            for path, src in cache.items():
                if path != dpath: continue
                for lineno, line in enumerate(src.split('\n'), 1):
                    stripped = line.lstrip()
                    if stripped.startswith(('//', '*', '/*')) or 'fun ' + name in line: continue
                    if re.search(r'(?<![\w"])' + re.escape(name) + r'\s*\([^()]*\)\s*\{', line):
                        hits.append((path, lineno, name, (dpath, dline), stripped))
    if not traps:
        return traps, hits
    # A leading '"' is excluded as well as a word character: a tester asserting
    # on the string "SettingTextField(" is talking ABOUT a call, not making one.
    rx = re.compile(r'(?<![\w"])(' + '|'.join(re.escape(n) for n in traps) +
                    r')\s*\([^()]*\)\s*\{')
    for path, src in cache.items():
        for lineno, line in enumerate(src.split('\n'), 1):
            stripped = line.lstrip()
            if stripped.startswith(('//', '*', '/*')): continue
            m = rx.search(line)
            if not m: continue
            name = m.group(1)
            if 'fun ' + name in line: continue    # the declaration itself
            hits.append((path, lineno, name, traps[name], stripped))
    return traps, hits


def roots_of(app):
    """The dirs this app compiles, from ITS build.json — never a list in here."""
    try:
        spec = json.load(open(os.path.join(app, 'build.json'), encoding='utf-8'))
    except (OSError, ValueError):
        return []
    out = []
    for name, mod in (spec.get('modules') or {}).items():
        if name.startswith('_'): continue
        rel = mod.get('dir') if isinstance(mod, dict) else None
        rel = rel or name.replace(':', '/')
        p = os.path.normpath(os.path.join(app, rel))
        if os.path.isdir(p): out.append(p)
    return sorted(set(out))


def apps():
    return sorted(d for d in os.listdir('.')
                  if os.path.isdir(d) and roots_of(d))


if __name__ == '__main__':
    if sys.argv[1] == 'dir':                       # self-check fixture
        scopes = [('fixture', [sys.argv[2]])]
    else:
        scopes = [(a, roots_of(a)) for a in apps()]
    if not scopes:
        print('NO-SCOPES', file=sys.stderr); sys.exit(3)
    for app, roots in scopes:
        traps, hits = scan(roots)
        print(f"{app}: {len(roots)} modules, {len(traps)} declarations of the risky shape",
              file=sys.stderr)
        for path, lineno, name, (dfile, dline), code in hits:
            print(f"{app}|{path}:{lineno}|{name}|{dfile}:{dline}|{code}")
PY

fail=0
hits=$(python3 "$SCANNER" all)
case $? in
  0) : ;;
  3) echo "FAIL   the scanner found no app to scope against — build.json::modules moved?"; exit 1 ;;
  *) echo "FAIL   the scanner crashed; its verdict is not a pass"; exit 1 ;;
esac

if [ -n "$hits" ]; then
    fail=1
    echo "FAIL   a trailing lambda is aimed at a parameter that is not a lambda:"
    while IFS='|' read -r app where name decl code; do
        printf '         [%s] %s calls %s() with a trailing lambda\n' "$app" "$where" "$name"
        printf '           declared at %s — its LAST parameter is not a function type\n' "$decl"
        printf '           %s\n' "$code"
        printf '           fix: name the lambda argument at the CALL SITE\n'
    done <<< "$hits"
else
    echo "ok     no call site in any app aims a trailing lambda at a non-lambda parameter"
fi

# SELF-CHECK. An assertion never seen failing is a decoration, and a scanner that
# silently matches nothing looks exactly like a clean tree — which is how an
# earlier draft of this file passed while the tree it scanned was red. The
# fixture IS the bug from run 34446987272, reduced. If the scanner stops catching
# it, every "ok" above is worthless, and this says so instead of staying quiet.
cat > "$FIXTURE/Fixture.kt" <<'KT'
object Sample {
    fun rewrite(text: String, progress: (Int, Int) -> Unit = { _, _ -> }, route: Route = here()): String = text
    fun caller() {
        rewrite("x") { done, total -> report(done, total) }
    }
}
KT
if grep -q '|rewrite|' <<<"$(python3 "$SCANNER" dir "$FIXTURE" 2>/dev/null)"; then
    echo "ok     self-check: the scanner still catches the shape it was written for"
else
    echo "FAIL   self-check: the scanner no longer catches its own fixture"
    fail=1
fi

[ "$fail" -eq 0 ] && echo "PASS — every trailing lambda in the fleet lands on a lambda parameter." \
                  || echo "FAIL — see above."
exit "$fail"
