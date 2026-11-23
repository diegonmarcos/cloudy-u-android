#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-mail #1870 READER MENU - sections, order, and nothing lost         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The reading pane's overflow menu was one long list of 22+ actions. #1870 groups it under one-word section titles
# (REPLY, TRIAGE, TEXT TOOLS, SENDER, MORE). Rule 16: a reorganisation adds structure and removes nothing, so this
# tester holds the order the ticket asked for AND parity with the list before it.
#
#   S1  the sections appear in order, each exactly once, each a MenuSectionTitle (muted, small, uppercase, a heading)
#   S2  every entry sits in its section, in the ticket's order
#   S3  parity: every entry of the menu before #1870 is still there exactly once (PREVIOUS below), and nothing else
#       was dropped; new entries would have to be added here on purpose
#   S4  each entry keeps its condition or enabled logic (the guard line still sits right above the entry it guarded)
#   S5  Export... offers Markdown (.md) through the same export path
#   M   mutation-proof: dropping an entry, moving one to another section, swapping two, or losing a guard turns it red
#
# OWN-SOURCE ONLY. python3 only; no gradle, no device.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCREEN_REL=app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null || { echo "ERROR python3 missing - this tester is unrun, not passing"; exit 1; }
[ -f "$APP/$SCREEN_REL" ] || { echo "ERROR missing $SCREEN_REL - this tester is unrun, not passing"; exit 1; }

CHECK=$(cat <<'PY'
import os, re, sys
app, which = sys.argv[1], sys.argv[2]
t = open(os.path.join(app, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"), encoding="utf-8").read()

EXPECTED = [
    ("menu_section_reply", ["message_reply", "message_reply_all", "message_forward", "message_forward_attachment"]),
    ("menu_section_triage", ["message_archive", "inbox_delete_forever/message_delete", "message_unflag/message_flag",
                             "message_mark_unread", "message_snooze", "inbox_move_to_folder", "message_labels", "task_assign"]),
    ("menu_section_text_tools", ["TextTool.RESUME", "message_copy_code", "TextTool.others",
                                 "message_show_original/message_show_translated"]),
    ("menu_section_sender", ["message_images_stop_sender/message_images_always_sender", "message_show_images",
                             "message_unsubscribe_open_page/message_unsubscribe", "message_not_spam/message_report_spam"]),
    ("menu_section_more", ["message_view_headers", "message_export", "message_export_eml", "message_print"]),
]
# The menu before #1870, entry by entry (its READ-tool loop drew AI Resume and Translate: RESUME + others).
PREVIOUS = sorted([
    "message_reply", "message_reply_all", "message_forward", "message_forward_attachment", "task_assign",
    "TextTool.RESUME", "TextTool.others", "message_show_images", "message_copy_code",
    "message_images_stop_sender/message_images_always_sender", "message_archive", "inbox_delete_forever/message_delete",
    "message_unflag/message_flag", "message_labels", "message_mark_unread", "inbox_move_to_folder",
    "message_not_spam/message_report_spam", "message_snooze", "message_unsubscribe_open_page/message_unsubscribe",
    "message_show_original/message_show_translated", "message_view_headers", "message_export", "message_export_eml",
    "message_print",
])
# guard line -> the entry it must sit right above (S4)
GUARDS = [
    ("if (folders.isNotEmpty() && messageMailboxIds.isNotEmpty()) {", "message_labels"),
    ("if (folders.isNotEmpty()) {", "inbox_move_to_folder"),
    ("if (!isOutgoingFolder(folderRole)) {", "message_not_spam/message_report_spam"),
    ("if (canSnoozeIn(folderRole)) {", "message_snooze"),
    ("if (senderEmail != null) {", "message_images_stop_sender/message_images_always_sender"),
    ("if (imagesOnceOffered) {", "message_show_images"),
    ("unsubscribeOffered?.let { action ->", "message_unsubscribe_open_page/message_unsubscribe"),
    ("if (printable) {", "message_print"),
]
ENABLED = [
    ("enabled = translation.exists,", "message_show_original/message_show_translated"),
    ("enabled = !translation.running && !readerSummary.running,", "tool.label"),
]

bad = []
start = t.find("MenuSectionTitle(R.string.menu_section_reply)")
end = t.find("    // The label surface (part five).")
if start < 0 or end < 0:
    print("    the sectioned menu is gone (no MenuSectionTitle(R.string.menu_section_reply))"); sys.exit(1)
menu = t[start:end]

def call_end(txt, i):
    o = txt.index("(", i); d = 0; j = o
    while j < len(txt):
        d += {"(": 1, ")": -1}.get(txt[j], 0)
        if d == 0:
            return j
        j += 1
    return len(txt)

seq = []  # (kind, key, offset)
pat = re.compile(r"MenuSectionTitle\(R\.string\.(\w+)\)|DropdownMenuItem\(|ExportMenuItem \{|"
                 r"if \(tool == TextTool\.RESUME && resumable\) toolEntry\(tool\)|if \(tool != TextTool\.RESUME\) toolEntry\(tool\)")
for m in pat.finditer(menu):
    g = m.group(0)
    if g.startswith("MenuSectionTitle"):
        seq.append(("section", m.group(1), m.start()))
    elif g.startswith("DropdownMenuItem"):
        body = menu[m.start():call_end(menu, m.start()) + 1]
        txt = body[body.find("text = {"):]
        txt = txt[:txt.find("leadingIcon")] if "leadingIcon" in txt else txt
        names = re.findall(r"R\.string\.(\w+)", txt)
        seq.append(("entry", "/".join(names) if names else "tool.label", m.start()))
    elif g.startswith("ExportMenuItem"):
        seq.append(("entry", "message_export", m.start()))
    elif "RESUME && resumable" in g:
        seq.append(("entry", "TextTool.RESUME", m.start()))
    else:
        seq.append(("entry", "TextTool.others", m.start()))

entries = [k for kind, k, _ in seq if kind == "entry" and k != "tool.label"]

if which == "S1":
    secs = [k for kind, k, _ in seq if kind == "section"]
    if secs != [s for s, _ in EXPECTED]:
        bad.append("sections are %s, expected %s" % (secs, [s for s, _ in EXPECTED]))
    st = open(os.path.join(app, "app/src/main/kotlin/app/sterna/ui/message/MenuSections.kt"), encoding="utf-8").read()
    for need in ("stringResource(label).uppercase()", "MaterialTheme.typography.labelSmall",
                 "MaterialTheme.colorScheme.onSurfaceVariant", ".semantics { heading() }"):
        if need not in st:
            bad.append("MenuSectionTitle is no longer %s" % need)
    if "DropdownMenuItem(" in st or "onClick" in st:
        bad.append("a section title became a tappable entry")
elif which == "S2":
    got, cur = {}, None
    for kind, k, _ in seq:
        if kind == "section":
            cur = k; got[cur] = []
        elif k != "tool.label" and cur is not None:
            got[cur].append(k)
    for s, want in EXPECTED:
        if got.get(s) != want:
            bad.append("%s holds %s, expected %s" % (s, got.get(s), want))
elif which == "S3":
    if sorted(entries) != PREVIOUS:
        lost = sorted(set(PREVIOUS) - set(entries)); extra = sorted(set(entries) - set(PREVIOUS))
        dup = sorted({e for e in entries if entries.count(e) > 1})
        bad.append("parity with the menu before #1870 broken: lost %s, unexpected %s, twice %s" % (lost, extra, dup))
elif which == "S4":
    for guard, key in GUARDS + ENABLED:
        hits = [m.start() for m in re.finditer(re.escape(guard), menu)]
        if not hits:
            bad.append("guard `%s` is gone (%s)" % (guard, key)); continue
        ok = False
        inside = (guard, key) in ENABLED  # an enabled= argument sits INSIDE the entry it governs
        for h in hits:
            if inside:
                prev = [e for e in seq if e[2] < h and e[0] == "entry"]
                ok = ok or (prev and prev[-1][1] == key)
            else:
                nxt = [e for e in seq if e[2] > h and e[0] == "entry"]
                ok = ok or (nxt and nxt[0][1] == key)
        if not ok:
            bad.append("guard `%s` no longer guards %s" % (guard, key))
elif which == "S5":
    fmt = open(os.path.join(app, "app/src/main/kotlin/app/sterna/export/ExportFileName.kt"), encoding="utf-8").read()
    ex = open(os.path.join(app, "app/src/main/kotlin/app/sterna/ui/message/MessageExporter.kt"), encoding="utf-8").read()
    lst = open(os.path.join(app, "app/src/main/kotlin/app/sterna/ui/message/MessageExport.kt"), encoding="utf-8").read()
    if 'MARKDOWN("md", "text/markdown"),' not in fmt: bad.append("no MARKDOWN export format")
    if "ExportFormat.MARKDOWN -> o.write(buildMarkdown(exportMsg, labels).toByteArray(Charsets.UTF_8))" not in ex:
        bad.append("Markdown does not go through the export path")
    if "ExportFormat.MARKDOWN," not in lst: bad.append("the sheet does not offer Markdown")
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
)
check() { python3 -c "$CHECK" "$1" "$2"; }
declare -A LABEL=(
  [S1]="five sections in order, each a muted uppercase heading that cannot be tapped"
  [S2]="every entry in its section, in the ticket's order"
  [S3]="parity: every entry of the menu before #1870, exactly once"
  [S4]="every entry keeps its condition and enabled logic"
  [S5]="Export offers Markdown (.md) through the same export path"
)
echo "== the tree =="
for c in S1 S2 S3 S4 S5; do
  if check "$APP" "$c"; then pass "$c ${LABEL[$c]}"; else fail "$c ${LABEL[$c]}"; fi
done

echo "== mutations (each in a copy must turn its check red) =="
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mail-1870.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
mutate() { # <check> <python: t -> t> <what>
  local c="$1" expr="$2" what="$3"
  rm -rf -- "$WORK/app"; mkdir -p "$WORK/app/app/src/main/kotlin"
  cp -R "$APP/app/src/main/kotlin/." "$WORK/app/app/src/main/kotlin/"
  if ! python3 - "$WORK/app/$SCREEN_REL" "$expr" <<'PY'
import sys
p, expr = sys.argv[1:3]
t = open(p, encoding="utf-8").read()
n = eval(expr, {"t": t})
if n == t:
    sys.exit(3)
open(p, "w", encoding="utf-8").write(n)
PY
  then fail "M $c: the mutation '$what' no longer applies (update this tester)"; return; fi
  if check "$WORK/app" "$c" >/dev/null; then fail "M $c survives: $what"; else pass "M $c catches: $what"; fi
}
mutate S3 't.replace("ExportMenuItem { menuOpen = false; exportFor = active.emailId; exportSheet = true }\n", "", 1)' "Export... dropped"
mutate S3 't.replace("text = { Text(stringResource(R.string.message_view_headers)) },", "text = { Text(stringResource(R.string.message_view_headers)) }, enabled = false,", 1).replace("R.string.message_print)", "R.string.message_view_headers)", 1)' "Print replaced by a second View headers"
mutate S2 't.replace("            MenuSectionTitle(R.string.menu_section_more)\n", "", 1).replace("            MenuSectionTitle(R.string.menu_section_sender)\n", "            MenuSectionTitle(R.string.menu_section_sender)\n            MenuSectionTitle(R.string.menu_section_more)\n", 1)' "MORE's entries moved under SENDER"
mutate S2 '(lambda a, b: t.replace(a, "\x00").replace(b, a).replace("\x00", b))("text = { Text(stringResource(R.string.message_reply)) },", "text = { Text(stringResource(R.string.message_forward)) },")' "Reply and Forward swapped"
mutate S1 't.replace("MenuSectionTitle(R.string.menu_section_triage)", "MenuSectionTitle(R.string.menu_section_more)", 1)' "a section title renamed"
mutate S4 't.replace("if (canSnoozeIn(folderRole)) {", "if (true) {", 1)' "Snooze offered in Spam and Trash"
mutate S4 't.replace("enabled = translation.exists,", "", 1)' "Show translated enabled with no translation"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-1870-reader-menu: ALL PASS"; exit 0; fi
echo "test-mail-1870-reader-menu: $FAILURES FAILURE(S)"; exit 1
