#!/usr/bin/env bash
# ac_cloud-code tester (#562): the 7-tab bottom nav, its seam, its link-out
# target, the English base and the licence boundary — all without a build.
#
# Every value asserted is READ from what the app renders or derives: the tab
# order is the label sequence the real tabs.js builds from the real nav.json;
# the MyTerminal package is what tools/resolve-targets.py derives from the fleet
# and is then checked against the target app's own source; the language default
# is read out of Acode's settings and compared with the fleet's i18n policy.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0
ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

# ── the bar, rendered through the real module ──────────────────────────────
nav_out="$(node "$ROOT/test/cloud_nav.mjs" "$ROOT" 2>&1)"
status=$?
[ "$status" -eq 0 ] || fail "node driver crashed (exit $status): $nav_out"
checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; checks=$((checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; checks=$((checks + 1)) ;;
  esac
done <<< "$nav_out"
[ "$checks" -ge 15 ] || fail "the nav driver made only $checks checks — it is not seeing the module"

# ── #1699 the Backlog root index (live / offline last-good / reason) + rule-16 field parity ──
idx_out="$(node "$ROOT/test/backlog_index.mjs" "$ROOT" 2>&1)"
idx_status=$?
[ "$idx_status" -eq 0 ] || fail "backlog index driver crashed (exit $idx_status): $idx_out"
idx_checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; idx_checks=$((idx_checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; idx_checks=$((idx_checks + 1)) ;;
  esac
done <<< "$idx_out"
[ "$idx_checks" -ge 30 ] || fail "the backlog index driver made only $idx_checks checks"

# ── #1855 P1 Cloud Drive owns git: repoUrl asks Drive first, the Git tab lists Drive's repos, the Backlog offers 'Clone with Cloud Drive' (+ rule-16 parity) ──
ws_out="$(node "$ROOT/test/workspace.mjs" "$ROOT" 2>&1)"
ws_status=$?
[ "$ws_status" -eq 0 ] || fail "workspace driver crashed (exit $ws_status): $ws_out"
ws_checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; ws_checks=$((ws_checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; ws_checks=$((ws_checks + 1)) ;;
  esac
done <<< "$ws_out"
[ "$ws_checks" -ge 40 ] || fail "the workspace driver made only $ws_checks checks"

# ── #1855 P2 Cloud SuperIntel holds the AI engines: the AI tab prefers the engine, keeps its direct gateway road, shows the install hint (+ rule-16 parity) ──
ae_out="$(node "$ROOT/test/aiengine.mjs" "$ROOT" 2>&1)"
ae_status=$?
[ "$ae_status" -eq 0 ] || fail "aiengine driver crashed (exit $ae_status): $ae_out"
ae_checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; ae_checks=$((ae_checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; ae_checks=$((ae_checks + 1)) ;;
  esac
done <<< "$ae_out"
[ "$ae_checks" -ge 60 ] || fail "the aiengine driver made only $ae_checks checks"

# ── #1855 P4b the agentic loop: edit parsing, path validation, the pending-change state machine, the session log, the chips and CI follow-up, proven with mutations ──
ag_out="$(node "$ROOT/test/agent.mjs" "$ROOT" 2>&1)"
ag_status=$?
[ "$ag_status" -eq 0 ] || fail "agent driver crashed (exit $ag_status): $ag_out"
ag_checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; ag_checks=$((ag_checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; ag_checks=$((ag_checks + 1)) ;;
  esac
done <<< "$ag_out"
[ "$ag_checks" -ge 100 ] || fail "the agent driver made only $ag_checks checks"
# Each mutation breaks one rule in a COPY of the sources; the driver must then report a failed check.
ag_root="$(mktemp -d)"
ag_mutate() { # name file old new
  rm -rf "$ag_root/copy" && mkdir -p "$ag_root/copy/src" && cp -r "$ROOT/src/cloud" "$ag_root/copy/src/cloud"
  python3 - "$ag_root/copy/src/cloud/$2" "$3" "$4" <<'PY' || { fail "agent mutation '$1' could not be applied"; return; }
import sys
p, old, new = sys.argv[1:4]
s = open(p).read()
assert old in s, "mutation target not found: " + old
open(p, "w").write(s.replace(old, new, 1))
PY
  mut_out="$(node "$ROOT/test/agent.mjs" "$ag_root/copy" 2>&1)"
  if grep -q '^CHECK bad ' <<<"$mut_out"; then ok "agent mutation '$1' is caught"; else fail "agent mutation '$1' was NOT caught: the agent test is blind to it"; fi
}
ag_mutate "dot segments allowed" agent/edits.js 'parts.some((p) => p === "." || p === "..")' 'false'
ag_mutate "absolute paths allowed" agent/edits.js 'if (rel.startsWith("/") ||' 'if ('
ag_mutate ".git allowed" agent/edits.js 'p.toLowerCase() === ".git"' 'false'
ag_mutate "backslash allowed" agent/edits.js 'if (rel.includes("\\")) return "backslash in path";' ''
ag_mutate "root check dropped" agent/edits.js 'if (pathProblem(rel)) return null;' ''
ag_mutate "relative root accepted" agent/edits.js '!root.startsWith("/") ||' ''
ag_mutate "size cap off" agent/edits.js 'if (bytes > MAX_FILE_BYTES)' 'if (false)'
ag_mutate "cap counts characters" agent/edits.js 'const bytes = byteLength(e.newContent);' 'const bytes = e.newContent.length;'
ag_mutate "edit count cap off" agent/edits.js 'if (out.edits.length >= MAX_EDITS)' 'if (false)'
ag_mutate "total cap off" agent/edits.js 'if (total + bytes > MAX_TOTAL_BYTES)' 'if (false)'
ag_mutate "duplicate paths allowed" agent/edits.js 'if (seen.has(key))' 'if (false)'
ag_mutate "extra keys allowed" agent/edits.js 'if (keys !== "newContent,path")' 'if (false)'
ag_mutate "two edits blocks allowed" agent/edits.js 'if (blocks.length !== 1)' 'if (blocks.length < 1)'
ag_mutate "auto-accept" agent/pending.js 'if (to === "accepted" && by !== "user")' 'if (false)'
ag_mutate "pending jumps to written" agent/pending.js 'pending: ["accepted", "rejected"]' 'pending: ["accepted", "rejected", "written"]'
ag_mutate "written can be rejected" agent/pending.js 'written: []' 'written: ["rejected"]'
ag_mutate "rejected can be accepted" agent/pending.js 'rejected: []' 'rejected: ["accepted"]'
ag_mutate "unchanged can be accepted" agent/pending.js 'if (to === "accepted" && change.unchanged)' 'if (false)'
ag_mutate "stale check blind" agent/pending.js 'change.baseHash === hash(current)' 'true'
ag_mutate "diff drops the added lines" agent/pending.js 'ops.push(["+", y[j++]])' 'j++'
ag_mutate "diff has no hunk header" agent/pending.js 'out.push(`@@ -${ac ? ai : ai - 1},${ac} +${bc ? bi : bi - 1},${bc} @@`);' ''
ag_mutate "log accepts any kind" agent/log.js 'if (!KINDS.includes(kind)) return null;' ''
ag_mutate "log unbounded" agent/log.js 'if (session.log.length > MAX_ENTRIES)' 'if (false)'
ag_mutate "context unbounded" agent/context.js 'const cut = t.length > max;' 'const cut = false;'
ag_mutate "run url regex loosened" agent/context.js 'm = s.match(/^\d{5,}$/);' 'm = s.match(/^\d+$/);'
ag_mutate "private repo shows runs" agent/context.js 'if (status === 404 || status === 401)' 'if (false)'
ag_mutate "slug from any host" agent/context.js 'github\.com[:/]' '[:/]'
ag_mutate "ticket note drops the run" agent/context.js 'run ? `CI run: ${run.id}' 'false ? `CI run: ${run.id}'
ag_mutate "UI accepts without the user" agent/ui.js 'move(s.changes[i], "accepted", { by: "user" })' 'move(s.changes[i], "accepted", { by: "agent" })'
ag_mutate "UI skips the stale check" agent/ui.js 'stillBase(a.change, now)' 'true'
ag_mutate "UI loses Review in Changes" agent/ui.js '"Review in Changes"' '"Review"'
ag_mutate "host skips path validation" index.js 'const url = resolveInRoot(rootPath, rel);' 'const url = `file://${rootPath}/${rel}`;'
ag_mutate "format instruction always sent" chat/view.js 'extra: s.repo ? FORMAT_INSTRUCTION : ""' 'extra: FORMAT_INSTRUCTION'
ag_mutate "Backlog loses Start agent session" index.js 'startFromTicket(ticket)' 'void ticket'
rm -rf "$ag_root"

# ── #1855 P3 IDE shell: every pre-P3 tab, sub-page, drawer item and deep-link id still lands (rule 16), proven with mutations ──
np_out="$(node "$ROOT/test/nav_parity.mjs" "$ROOT" 2>&1)"
np_status=$?
[ "$np_status" -eq 0 ] || fail "nav parity driver crashed (exit $np_status): $np_out"
np_checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; np_checks=$((np_checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; np_checks=$((np_checks + 1)) ;;
  esac
done <<< "$np_out"
[ "$np_checks" -ge 60 ] || fail "the nav parity driver made only $np_checks checks"
# Each mutation drops or misplaces one thing in a COPY of the sources; the parity driver must then report a failed check.
mut_root="$(mktemp -d)"
mutate() { # name python-snippet (edits files under $1 = the copy)
  rm -rf "$mut_root/copy" && mkdir -p "$mut_root/copy/src" && cp -r "$ROOT/src/cloud" "$mut_root/copy/src/cloud" && cp "$ROOT/build.json" "$mut_root/copy/build.json"
  python3 - "$mut_root/copy" "$2" <<'PY' || { fail "mutation '$1' could not be applied"; return; }
import json, sys
root, code = sys.argv[1], sys.argv[2]
def nav_edit(fn):
    p = root + "/src/cloud/nav.json"
    d = json.load(open(p)); fn(d); json.dump(d, open(p, "w"))
def text_edit(rel, old, new=""):
    p = root + "/" + rel
    s = open(p).read()
    assert old in s, f"{rel}: mutation target not found"
    open(p, "w").write(s.replace(old, new, 1))
exec(code)
PY
  mut_out="$(node "$ROOT/test/nav_parity.mjs" "$mut_root/copy" 2>&1)"
  if grep -q '^CHECK bad ' <<<"$mut_out"; then ok "parity mutation '$1' is caught"; else fail "parity mutation '$1' was NOT caught: the parity test is blind to it"; fi
}
mutate "git redirect and alias dropped" 'nav_edit(lambda d: (d["redirects"].pop("git"), [t.__setitem__("aliases", []) for t in d["tabs"] if t["id"] == "workspace"]))'
mutate "agents lands on Models" 'nav_edit(lambda d: d["redirects"].__setitem__("agents", {"tab": "agent", "page": "mdl"}))'
mutate "ai lands on a bottom page without chat" 'nav_edit(lambda d: d["redirects"].__setitem__("ai", {"tab": "run", "page": "browser"}))'
mutate "browser redirect to Workspace" 'nav_edit(lambda d: d["redirects"].__setitem__("browser", {"tab": "workspace"}))'
mutate "Models sub-page dropped" 'nav_edit(lambda d: [t["pages"].pop() for t in d["tabs"] if t["id"] == "agent"])'
mutate "Effort sub-page dropped" 'nav_edit(lambda d: d["parents"][0]["pages"].pop())'
mutate "Backlog parent removed" 'nav_edit(lambda d: d.__setitem__("parents", []))'
mutate "Backlog put on the bottom bar" 'nav_edit(lambda d: (d["tabs"].append(d["parents"].pop())))'
mutate "Browser page dropped from Run" 'nav_edit(lambda d: [t["pages"].pop(0) for t in d["tabs"] if t["id"] == "run"])'
mutate "default tab moved" 'nav_edit(lambda d: d.__setitem__("default_tab", "editor"))'
mutate "git tab drawer item dropped" 'text_edit("src/cloud/index.js", "id: \"rescan\"", "id: \"rescan-x\"")'
mutate "Backlog drawer item dropped" 'text_edit("src/cloud/index.js", "id: \"docs-folder\"", "id: \"docs-folder-x\"")'
mutate "sub-page re-render dropped" 'text_edit("src/cloud/index.js", "if (view !== was) return showTab(tabId, pageId);", "")'
mutate "Changes status read dropped" 'text_edit("src/cloud/index.js", "driveStatus(bridge(), r.path)", "null")'
mutate "dropdown stops listing parents" 'text_edit("src/cloud/bars.js", "const parents = tabs.filter((t) => t.placement === \"dropdown\");", "const parents = [];")'
mutate "bottom bar draws every page" 'text_edit("src/cloud/bars-dom.js", "for (const t of bottomTabs(tabs)) {", "for (const t of tabs) {")'
rm -rf "$mut_root"

# ── #1158 the shared bars (first row, second row, bottom bar, gradient), through the real bars.js ──
bars_out="$(node "$ROOT/test/cloud_bars.mjs" "$ROOT" 2>&1)"
status=$?
[ "$status" -eq 0 ] || fail "bars driver crashed (exit $status): $bars_out"
bchecks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; bchecks=$((bchecks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; bchecks=$((bchecks + 1)) ;;
  esac
done <<< "$bars_out"
[ "$bchecks" -ge 40 ] || fail "the bars driver made only $bchecks checks — it is not seeing the module"

# ── the editor's text must not bleed through the other pages (Playwright, real css + nav.json) ──
bleed_out="$(node "$ROOT/test/cloud_bleed.mjs" "$ROOT" 2>&1)"
status=$?
[ "$status" -eq 0 ] || fail "bleed driver crashed (exit $status): $bleed_out"
gchecks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; gchecks=$((gchecks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; gchecks=$((gchecks + 1)) ;;
  esac
done <<< "$bleed_out"
[ "$gchecks" -ge 4 ] || fail "the bleed driver made only $gchecks checks"

# ── the seam: Acode is touched in exactly one place for the nav ────────────
main="$ROOT/src/main.js"
imports="$(grep -c '^import mountCloudNav from "./cloud";$' "$main")"
calls="$(grep -c 'mountCloudNav();' "$main")"
[ "$imports" = 1 ] && [ "$calls" = 1 ] && ok "main.js imports and mounts the nav exactly once" \
  || fail "main.js seam: $imports import(s), $calls call(s) of mountCloudNav (need 1 and 1)"
after="$(awk '/root.appendOuter\(/{seen=1; next} seen && /mountCloudNav\(\);/{print "yes"; exit}' "$main")"
[ "$after" = yes ] && ok "the nav mounts after Acode renders its root" \
  || fail "mountCloudNav() is not after root.appendOuter(...) — the bar would mount before the editor exists"

# ── the seam markers: every stub export is marked ──────────────────────────
seams="$ROOT/src/cloud/seams.js"
exports="$(grep -c '^export async function ' "$seams")"
marked="$(grep -c '^// SEAM: ' "$seams")"
[ "$exports" -ge 1 ] && [ "$exports" = "$marked" ] && ok "$exports seam functions, each marked SEAM:" \
  || fail "seams.js has $exports exported functions but $marked SEAM: markers"

# ── MyTerminal: the target is derived from the fleet and really exists ─────
targets="$(python3 "$ROOT/tools/resolve-targets.py" "$ROOT" --print 2>&1)"
status=$?
if [ "$status" -ne 0 ]; then
  fail "resolve-targets.py could not resolve the MyTerminal target: $targets"
else
  verdict="$(python3 - "$ROOT" "$targets" <<'PY'
import json, os, sys
root, t = sys.argv[1], json.loads(sys.argv[2])["myterminal"]
repo = os.path.dirname(root)
cls = t["activity"].rsplit(".", 1)[1]
found = [os.path.join(d, f) for d, _, fs in os.walk(os.path.join(repo, t["app_dir"]))
         for f in fs if f in (cls + ".kt", cls + ".java")]
print("ok" if found else "bad", t["id"], t["package"], t["activity"], (found or ["<no source>"])[0].replace(repo + "/", ""))
PY
)"
  case "$verdict" in
    ok*)  ok "MyTerminal → ${verdict#ok } (activity source exists)" ;;
    *)    fail "MyTerminal target resolves to an activity with no source: ${verdict#bad }" ;;
  esac
fi
declared="$(python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
target = json.load(open(os.path.join(root, "src/cloud/nav.json")))["myterminal"]["target"]
def values(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if not k.startswith("_"):
                yield from values(v)
    elif isinstance(node, list):
        for v in node:
            yield from values(v)
    elif isinstance(node, str):
        yield node
hits = []
for d, _, fs in os.walk(os.path.join(root, "src")):
    for f in fs:
        p = os.path.join(d, f)
        if f == "targets.gen.json":
            continue  # the resolver's OUTPUT, not a declaration
        if f.endswith(".json"):
            try:
                n = sum(1 for v in values(json.load(open(p))) if v == target)
            except ValueError:
                continue
        elif f.endswith((".js", ".ts")):
            n = len(re.findall(r"""['"`]%s['"`]""" % re.escape(target), open(p, errors="replace").read()))
        else:
            continue
        hits += [os.path.relpath(p, root)] * n
print(len(hits), " ".join(hits))
PY
)"
read -r count where <<< "$declared"
[ "$count" = 1 ] && ok "the MyTerminal target id is a value in exactly one place ($where)" \
  || fail "the MyTerminal target id is a value in $count places under src/ ($where) — it must be declared once"

# ── English base (#299): first run keeps the declared default ──────────────
lang="$(python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
src = open(os.path.join(root, "src/lib/settings.js")).read()
default = re.search(r'^\s*lang:\s*"([^"]+)"', src, re.M).group(1)
first = re.search(r'if \(!\(await fs\.exists\(\)\)\) \{(.*?)\n\t\t\}', src, re.S).group(1)
policy = json.load(open(os.path.join(os.path.dirname(root), "1_cicd/src/i18n-policy.json")))
base = policy["base_language"]["locale"]
print(default, base, "navigator.language" in first)
PY
)"
read -r default base leaks <<< "$lang"
[ "${default%%-*}" = "$base" ] && ok "Acode's default language $default is the fleet base '$base'" \
  || fail "Acode's default language $default is not the fleet base '$base'"
[ "$leaks" = False ] && ok "first run does not adopt the device locale" \
  || fail "first run sets lang from navigator.language — a Spanish phone boots in Spanish (#299)"

# ── licence boundary: the GPL binaries stay pruned ─────────────────────────
grep -q 'Permission is hereby granted, free of charge' <<<"$(head -3 "$ROOT/license.txt")" \
  && ok "license.txt is the MIT grant" || fail "license.txt is not the MIT text Acode was vendored under"
for d in $(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["upstream"]["pruned"]))' "$ROOT/build.json"); do
  [ ! -e "$ROOT/$d" ] && ok "pruned: $d" || fail "$d is back in the tree — build.json::upstream.pruned says it must not be"
done
blobs="$(find "$ROOT" -path "$ROOT/node_modules" -prune -o -path "$ROOT/platforms" -prune -o -path "$ROOT/plugins" -prune -o \( -name '*.so' -o -name '*.rootfs' \) -print | head -5)"
[ -z "$blobs" ] && ok "no prebuilt native binaries or rootfs images are vendored" \
  || fail "prebuilt binaries vendored without source: $blobs"

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-code nav tester: ALL PASS\n'
else
  printf '  cloud-code nav tester: FAIL\n' >&2
  exit 1
fi
