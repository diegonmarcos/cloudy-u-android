#!/usr/bin/env bash
# cloud-mail reader actions: static proof of the wiring, WITHOUT a gradle build (this runner cannot
# build) and without an APK on a device. Same shape as test-mail-text-tools.sh.
#
#   M1  a message's mailbox membership is EDITED, never replaced -- the data-loss check, asserted
#       on the JSON patch that goes out, not on a screen
#   M2  ADD and REMOVE exist as their own operations, because "move" is not one on a set
#   T1  parts three and five agree on what a tag IS, in one function, and system keywords are not it
#   U1  the unsubscribe icon is absent when the message offers no unsubscribe path
#   U2  the body-scan fallback never fires a POST and never overrides a real header
#   P1  every AI feature's prompt comes from the registry; no prompt string is hardcoded in Kotlin
#   B1  the AI Resume box never writes back to the stored body
#   R1  the default reply action resolves to reply-all, and single reply is still reachable
#   A1  the TOP ROW is exactly Star, Move to folder, Reply-all, Mark unread (+ overflow)  (#293)
#   A2  the READING ICON ROW (AI tools, Show images, Copy Code) is drawn UNDER the tags  (#293)
#   A3  the OVERFLOW lists every function as a NAMED entry -- no icon row, no icon-only item
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
LIBS="$ROOT/ab_cloud-libs-shared"
KB="$LIBS/libs/keyboard/src/main/java"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
JMAP="$APP/core/jmap/src/main/kotlin/app/sterna/core/jmap"
DATA="$APP/core/data/src/main/kotlin/app/sterna/core/data"
SCREEN="$UI/message/MessageScreen.kt"
VM="$UI/message/MessageViewModel.kt"
META="$UI/message/MessageMetadata.kt"
STR="$APP/app/src/main/res/values/strings.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail reader: membership, tags, unsubscribe, prompts, resume, reply default =="

# ── M1 the data-loss check: an Email/set UPDATE may never write mailboxIds whole ──
# The prior defect (00127a847, reintroduced by the Kotlin rewrite) was a patch of the WHOLE
# "mailboxIds" property, which deletes every mailbox the message was in but the target. Asserted on
# what is emitted: exactly one function writes membership, and it writes RFC 8620 5.3 patch paths.
has "$JMAP/JmapClient.kt" 'internal fun JsonObjectBuilder.putMembershipPatch' \
  "M1 membership is written in exactly one place"
n=$(grep -c 'putMembershipPatch(add' "$JMAP/JmapClient.kt")
[ "$n" -ge 3 ] && ok "M1 move / add / remove all go through it ($n call sites)" \
  || bad "M1 only $n call sites use putMembershipPatch -- one of them writes its own patch"
has "$JMAP/JmapClient.kt" 'put("mailboxIds/\$add", JsonPrimitive(true))' "M1 an add is a patch path"
has "$JMAP/JmapClient.kt" 'put("mailboxIds/\$remove", JsonNull)' "M1 a remove is a patch path"
# The whole-property write may survive ONLY under create/import: a message that does not exist yet
# has no membership to preserve. Anything under "update" is the bug.
python3 - "$JMAP/JmapClient.kt" <<'PY'
import sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
bad = []
for i, l in enumerate(lines):
    if 'putJsonObject("mailboxIds")' not in l:
        continue
    verb = None
    for j in range(i - 1, max(0, i - 80), -1):
        for v in ('"create"', '"update"', '"destroy"', 'Email/import'):
            if v in lines[j]:
                verb = v
                break
        if verb:
            break
    if verb not in ('"create"', 'Email/import'):
        bad.append((i + 1, verb, l.strip()))
if bad:
    for line, verb, text in bad:
        print(f"  FAIL: M1 line {line} writes the whole mailboxIds property under {verb}: {text}")
    sys.exit(1)
print("  ok: M1 no Email/set UPDATE writes the whole mailboxIds property")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# The source folder is a REQUIRED argument: a default of null turns every move into a copy.
lacks "$JMAP/JmapClient.kt" 'sourceMailboxId: String? = null' "M1 the source folder has no default"
# and the unit test that executes the builder exists and asserts on the emitted keys
PATCHTEST="$APP/core/jmap/src/test/kotlin/app/sterna/core/jmap/MailboxMembershipPatchTest.kt"
has "$PATCHTEST" 'aMoveEditsTwoBitsAndLeavesEveryOtherMailboxUnnamed' "M1 a test executes the patch builder"
has "$PATCHTEST" 'noArgumentEverProducesAWholePropertyWrite' "M1 the whole-property write is forbidden by test"

# ── M2 add and remove are their own operations, at every layer ──
for f in "$JMAP/JmapClient.kt" "$DATA/mail/MailRepository.kt"; do
  has "$f" 'fun addToMailbox' "M2 ${f##*/} offers ADD"
  has "$f" 'fun removeFromMailbox' "M2 ${f##*/} offers REMOVE"
done
has "$VM" 'fun addMailbox' "M2 the reader can add a mailbox"
has "$VM" 'fun removeMailbox' "M2 the reader can remove a mailbox"
# The membership shown is the SERVER's, never the cached row's single mailboxId.
has "$DATA/mail/MailRepository.kt" 'client.mailboxIdsOf' "M2 membership is read from the server"

# ── T1 parts three and five agree on what a tag is ──
has "$META" 'fun messageTags' "T1 one function decides what a tag is"
n=$(grep -c 'messageTags(' "$SCREEN")
[ "$n" = 2 ] && ok "T1 the chip row and the label sheet both call it" \
  || bad "T1 messageTags called $n times in the screen, expected 2 (chip row + label sheet)"
has "$META" 'enum class TagKind' "T1 the two kinds are distinguished"
has "$META" 'MAILBOX' "T1 mailboxIds is one kind of tag"
has "$META" 'KEYWORD' "T1 keywords is the other"
# System keywords are the star's and the unread state's, and must not be offered as tags.
has "$META" 'filterNot { it.startsWith(SYSTEM_KEYWORD_PREFIX) }' "T1 \$-prefixed keywords are not tags"
# Removing a mailbox asks; removing a keyword does not.
has "$META" 'fun removalNeedsConfirming' "T1 the two removals are not equally destructive"
has "$UI/message/LabelSheet.kt" 'if (removalNeedsConfirming(tag)) confirming = tag' \
  "T1 the sheet confirms a mailbox removal and only that"

# ── U1 the unsubscribe entry is absent when there is nothing to unsubscribe from ──
# The overflow entry is gated on ONE decision, the same one the ViewModel makes for the banner. The
# bar icon it used to share this with left the bar for Mark unread at #293; the entry and the strip
# under the sender stayed.
has "$SCREEN" 'offeredUnsubscribeAction(unsubscribe, unsubscribeState)?.let { action ->' \
  "U1 the overflow entry is gated on the offer decision"
has "$VM" 'options?.preferredAction()' "U1 no options means no action means no icon"
has "$VM" 'UnsubscribeState.Sending, UnsubscribeState.Sent, UnsubscribeState.Queued -> null' \
  "U1 an already-taken way out offers nothing"

# ── U2 the fallback guesses, and is treated as a guess ──
SCAN="$DATA/mail/UnsubscribeBodyScan.kt"
has "$SCAN" 'UnsubscribeOptions(pageUrl = it)' "U2 a guessed link is only ever a page to look at"
lacks "$SCAN" 'oneClickUrl' "U2 a guess NEVER becomes a one-click POST"
has "$VM" '?: scanBodyForUnsubscribe(anchor)' "U2 the scan runs only when the headers gave nothing"
# elvis, so a real header is never overridden
has "$VM" 'UnsubscribeHeader.parse(anchor.listUnsubscribe, anchor.listUnsubscribePost)' \
  "U2 the headers are still parsed first"
# the word list is DATA, not a list in Kotlin
# CODE lines only. The KDoc above explains that a scan reading the word "unsubscribe" is reading
# the attacker's own text, and grepping the whole file made the explanation trip the assertion --
# which would have taught the next reader to delete the explanation.
if grep -qiE '"[^"]*unsubscribe[^"]*"|"[^"]*opt.?out[^"]*"' <<<"$(grep -vE '^\s*(//|\*|/\*)' "$SCAN")"; then
  bad "U2 the word list is hardcoded in the scanner"
else
  ok "U2 the word list is not hardcoded in the scanner"
fi
has "$VM" 'R.array.unsubscribe_body_words' "U2 the words come from a localised resource"
has "$STR" 'name="unsubscribe_body_words"' "U2 that resource exists"
# and nothing is contacted before the user has seen what will be contacted
has "$SCREEN" 'onClick = { menuOpen = false; viewModel.askUnsubscribe() }' "U2 the overflow entry ASKS, it does not send"
has "$SCREEN" 'onUnsubscribe = viewModel::askUnsubscribe' "U2 the strip under the sender ASKS too"
has "$VM" '_unsubscribeConfirm.value = PendingUnsubscribe(action, options)' "U2 asking puts up the confirmation"

# ── P1 every prompt is registry data; none is a Kotlin literal ──
REG="$LIBS/build.json"
python3 - "$REG" <<'PY'
import json, sys
ai = json.load(open(sys.argv[1], encoding='utf-8'))['keyboard_ai']
missing = [k for k in ('styles', 'summaries', 'rewrite_preamble', 'summary_preamble',
                       'default_style', 'default_summary') if k not in ai]
if missing:
    print("  FAIL: P1 build.json::keyboard_ai is missing " + ", ".join(missing))
    sys.exit(1)
for name in ('styles', 'summaries'):
    for k, v in ai[name].items():
        if 'prompt' not in v or 'label' not in v:
            print(f"  FAIL: P1 {name}.{k} has no prompt/label")
            sys.exit(1)
print(f"  ok: P1 the registry holds {len(ai['styles'])} enhance and {len(ai['summaries'])} summary prompts")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# The engine reads them from the registry rather than holding its own.
has "$KB/helium314/keyboard/latin/AiRouter.kt" 'promptSet("summaries")' "P1 summaries come from the registry"
has "$KB/helium314/keyboard/latin/AiRouter.kt" 'registry.optString("summary_preamble")' "P1 so does the preamble"
# THE point: no file that sends a prompt may contain one. A prompt is a sentence of instructions;
# the giveaway is an imperative addressed to a model in a string literal.
for f in "$KB/com/diegonmarcos/superapp/texttools/TextToolsService.kt" \
         "$UI/text/TextToolRun.kt" "$UI/message/ResumeBox.kt"; do
  if grep -nE '"[^"]*(Summarise|Rewrite|You are a) [^"]{20,}"' "$f" >/dev/null 2>&1; then
    bad "P1 ${f##*/} holds a prompt string literal"
  else
    ok "P1 ${f##*/} holds no prompt of its own"
  fi
done
# and the settings screens SHOW the prompt, which is why it has to be data in the first place
SCREENS="$KB/helium314/keyboard/settings/screens"
has "$SCREENS/TextEnhanceScreen.kt" 'PromptPreview(setting, AiRouter.enhanceStyle(' "P1 Text Enhancement shows its prompt"
has "$SCREENS/TextResumeScreen.kt" 'PromptPreview(setting, AiRouter.summaryStyle(' "P1 Text Resume shows its prompt"
has "$SCREENS/AiRoutingScreen.kt" 'AiRouter.rewritePreamble' "P1 AI Routing shows the shared preambles"

# ── the fourth Configs entry, and that it resolves to a real page ──
SEC="$UI/settings/TextToolsSection.kt"
NAV="$KB/helium314/keyboard/settings/SettingsNavHost.kt"
# The rows are destinations in THIS APP now. They used to be names in the keyboard's allowlist,
# opened by an intent, which is why nothing they showed could be edited from mail.
n=$(grep -c 'route = "' "$SEC")
[ "$n" = 5 ] && ok "P1 Configs > Text declares five entries" || bad "P1 declares $n entries, expected 5"
has "$SEC" 'route = "textResume"' "P1 Text Resume is one of them"
grep -qE '^\s*composable\("textResume"\)' "$UI/settings/SettingsScreen.kt" \
  && ok "P1 textResume resolves to a real page in mail's own settings graph" \
  || bad "P1 textResume is not registered -- the row would open nothing"
# ...and after Text Enhancement, as asked
python3 - "$SEC" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
order = re.findall(r'route = "([A-Za-z_]+)"', src)
want = ["textAiRouting", "textEnhance", "textResume", "textAnswer", "textTranslation"]
if order != want:
    print(f"  FAIL: P1 entry order is {order}, expected {want}")
    sys.exit(1)
print("  ok: P1 Text Resume sits after Text Enhancement, Answer Prediction right after it")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── B1 the summary never writes back to the stored body ──
BOX="$UI/message/ResumeBox.kt"
has "$BOX" 'val text = summary.text.orEmpty()' "B1 the box draws text it was handed (the cached summary), it does not edit a body"
# Nothing in the box may reach a writer, and it has no ViewModel at all.
lacks "$BOX" 'viewModel' "B1 the box holds no ViewModel, so it cannot ask for a write"
for writer in 'saveBody' 'updateBody' 'setBody' 'copy(bodyValues' 'onApply'; do
  lacks "$BOX" "$writer" "B1 the box calls no body writer ($writer)"
done
# The reader opens NO dialog any more: Translate rewrites the page in place and the summary is a box,
# so there is no panel for a result to be applied from, and nothing to apply it to.
lacks "$SCREEN" 'TextToolPanel(' "B1 the reader opens no result dialog (nothing to apply, nowhere to apply it)"
# What is SENT to the summary is the quote-free flattened copy, not the stored body.
has "$SCREEN" 'TextToolScope.receivedScope' "B1 the summary is made from a quote-free COPY"
# ...and EVERY reading tool goes through ONE function, which sends receivedTextToolSource for the
# summary. A site built from the stored body is what B1 exists to catch.
n=$(grep -c 'runReaderTool(viewModel' "$SCREEN")
[ "$n" -ge 2 ] && ok "B1 the icon row and the overflow both start tools through runReaderTool ($n sites)" \
  || bad "B1 $n runReaderTool sites -- the reader has a second, unscoped way to start a tool"
has "$SCREEN" 'summarise = { viewModel.summariseNow(receivedTextToolSource(email)) }' \
  "B1 the summary is made from receivedTextToolSource"
has "$SCREEN" 'return TextToolScope.receivedScope(if (isHtml) htmlToText(raw) else raw)' \
  "B1 that one source is the quote-free flattened copy"

# ── the AI plumbing is REUSED, not copied ──
RUN="$UI/text/TextToolRun.kt"
n=$(grep -c "when (tool)" "$RUN")
[ "$n" = 1 ] && ok "A0 tool -> engine is still mapped in exactly one place" \
  || bad "A0 tool->engine mapped in $n places -- a third copy of the routing"
# Through the SHARED client still - one binder, one engine - but carrying THIS APP'S prompt and
# model rather than an empty argument meaning "use the keyboard's". The engine is reused; the
# settings are not, which is the split the owner asked for.
has "$RUN" 'TextTool.RESUME -> client.summariseWith(' "A0 Resume routes through the shared client"
has "$RUN" 'MailTextToolsPrefs.summaryPrompt(context)' "A0 ...and sends mail's own Resume prompt"
# no second client, no second binding, no credential anywhere in mail
n=$(grep -rc 'TextToolsClient(' "$APP/app/src/main" | grep -v ':0$' | wc -l)
[ "$n" = 1 ] && ok "A0 exactly one file constructs the binder client" \
  || bad "A0 $n files construct a TextToolsClient"
# NARROWED to the credential itself. The old pattern rejected any mention of openrouter|AiRouter,
# which held while mail had no text settings of its own to name; mail now owns its routing
# registry and legitimately names providers and models. Choosing a model is not holding the key.
hits=$(grep -rlE 'PREF_AI_TOKEN|ai_token_|OPENROUTER_API|openrouter\.ai/api' "$APP/app/src" 2>/dev/null)
[ -z "$hits" ] && ok "A0 mail still reaches no AI provider credential" || bad "A0 mail reaches the credential: $hits"
# the binder method was APPENDED, never inserted: transaction codes are the wire format
python3 - "$LIBS/libs/text-tools/src/main/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
methods = re.findall(r'^\s+(?:String\[\]|String|List<String>|boolean|void)\s+(\w+)\(', src, re.M)
# A PREFIX, NOT AN EQUALITY. Transaction codes are assigned by declaration order, so the rule is
# that a method may only ever be APPENDED - the existing ones must keep their positions. Written
# as `methods == want` this check asserted that no method may ever be added at all, which is the
# opposite of the rule it is named for: the next correct append failed it, and the only way to
# clear it was to edit the expected list, which is not a check.
frozen = ['enhance', 'translate', 'enhanceProviderLabel', 'translateLanguages', 'summarise']
if methods[:len(frozen)] != frozen:
    print(f"  FAIL: A0 AIDL order changed: {methods[:len(frozen)]} != {frozen}"
          " -- an existing method moved, so old installs would answer the wrong call")
    sys.exit(1)
added = methods[len(frozen):]
print(f"  ok: A0 the {len(frozen)} original methods keep their transaction codes"
      + (f"; {len(added)} appended after them ({', '.join(added)})" if added else ""))
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── R1 the reply default is reply-all, and single reply is still reachable ──
python3 - "$SCREEN" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# The toolbar IconButton (not a DropdownMenuItem) that starts a reply.
row = re.search(r'IconButton\(onClick = \{ onReply\("(\w+)", replyTargetId, accountId\) \}\)', src)
if not row:
    print("  FAIL: R1 no reply action on the toolbar at all")
    sys.exit(1)
if row.group(1) != "replyAll":
    print(f'  FAIL: R1 the toolbar reply action is "{row.group(1)}", not "replyAll"')
    sys.exit(1)
print("  ok: R1 the toolbar's reply action resolves to reply-all")
# ...with the icon that conventionally means it
seg = src[row.start():row.start() + 400]
if "Icons.AutoMirrored.Filled.ReplyAll" not in seg:
    print("  FAIL: R1 the reply-all action does not carry the double-arrow icon")
    sys.exit(1)
print("  ok: R1 it carries the double-arrow icon")
# ...and plain reply is still one tap away, in the menu
if 'onClick = { menuOpen = false; onReply("reply", replyTargetId, accountId) }' not in src:
    print("  FAIL: R1 plain single reply is not reachable from the overflow menu")
    sys.exit(1)
print("  ok: R1 plain single reply is still reachable in the overflow")
PY
[ $? -eq 0 ] && PASS=$((PASS+3)) || FAIL=$((FAIL+1))
# The safety the default demands: a reply-all shows every recipient before a word is typed.
has "$UI/compose/ComposeViewModel.kt" 'showAllRecipients = true,' "R1 a reply-all asks for every recipient to be shown"
has "$UI/compose/ComposeScreen.kt" 'neverCollapse = showAllRecipients,' "R1 the To field honours it"
has "$UI/compose/ComposeScreen.kt" 'chips.size > 2 && !neverCollapse' "R1 ...so the chips are not folded into a +N"

# ── A1/A2/A3 THE READER'S LAYOUT CONTRACT (#293, #308) ──
# The owner's words: "as top is actions: starred, move folders, reply all, mark as unread (add this
# icon)", "reading view with the ai icons below the tags + show images + show as plain text ICONS",
# "right menu should show all functions as LISTS", "LIST NAMES NOT ICONS".
#
# This asserts WHERE each control is drawn, not merely that it exists. #293 shipped working code in
# the wrong place -- the reading icons inside the overflow menu -- the whole suite was green, and the
# owner had to report it twice. Every check below would have failed on that shape.
out=$(python3 - "$SCREEN" "$VM" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
vm = open(sys.argv[2], encoding='utf-8').read()
fails = 0
def ok(m): print(f"  ok: {m}")
def bad(m):
    global fails
    fails += 1
    print(f"  FAIL: {m}")
def code(block):
    return "\n".join(l for l in block.splitlines() if not l.strip().startswith(("//", "*", "/*")))
def body(signature):
    # A top-level function: from its signature to the first brace closing at column 0.
    start = src.index(signature)
    return code(src[start:src.index("\n}\n", start)])

# ── A1 the top row ──
actions = body("private fun MessageActions(")
bar, menu = actions[:actions.index("DropdownMenu(")], actions[actions.index("DropdownMenu("):]
menu = menu[:menu.index("if (labelSheet)")] if "if (labelSheet)" in menu else menu
buttons = bar.split("IconButton(")[1:]
glyphs = []
for b in buttons:
    m = re.search(r'Icons\.(?:AutoMirrored\.)?Filled\.(\w+)', b)
    glyphs.append(m.group(1) if m else "?")
want = ["Star", "DriveFileMove", "ReplyAll", "MarkEmailUnread", "MoreVert"]
if glyphs == want:
    ok("A1 the bar is Star, Move to folder, Reply-all, Mark unread, then the overflow")
else:
    bad(f"A1 the bar is {glyphs}, expected {want}")
if len(glyphs) <= 6:
    ok(f"A1 {len(glyphs)} actions fit the six 48dp slots of a 360dp bar")
else:
    bad(f"A1 {len(glyphs)} actions clip off a 360dp bar (6 slots)")
# Each glyph is wired to the action it names -- a Mark-unread icon that stars the message would pass
# the glyph check above.
wiring = {"Star": "viewModel.toggleFlag()", "DriveFileMove": "movePicker = true",
          "ReplyAll": 'onReply("replyAll", replyTargetId, accountId)',
          "MarkEmailUnread": "viewModel.markUnread(onBack)", "MoreVert": "menuOpen = true"}
for glyph, chunk in zip(glyphs, buttons):
    call = wiring.get(glyph)
    head = chunk.split("\n")[0]
    if call and call in head:
        ok(f"A1 the {glyph} icon calls {call}")
    elif call:
        bad(f"A1 the {glyph} icon does not call {call}: {head.strip()}")
# Mark unread is the REAL action, not a stub: the ViewModel's own function, which the list uses too.
if re.search(r'\bfun markUnread\(', vm):
    ok("A1 Mark unread reaches MessageViewModel.markUnread")
else:
    bad("A1 MessageViewModel has no markUnread -- the icon is wired to nothing")
if "TextToolIconRow(" in bar or "TextToolIconRow(" in menu:
    bad("A1 the reading icon row is on the bar or in the overflow -- it belongs under the tags")
else:
    ok("A1 no reading icon row on the bar or in the overflow")

# ── A2 the reading icon row, under the tags ──
header = body("private fun MessageHeader(")
try:
    tags_at, row_at, resume_at = (header.index("MessageTagSections(tags"), header.index("readingActions()"),
                                  header.index("ResumeBox("))
    if tags_at < row_at < resume_at:
        ok("A2 the reading row is drawn after the tags and before the Resume box")
    else:
        bad(f"A2 order is tags@{tags_at} row@{row_at} resume@{resume_at} -- the row is not under the tags")
except ValueError as missing:
    bad(f"A2 MessageHeader does not draw the tags, the reading row and the Resume box: {missing}")
# UNCONDITIONAL: the header's measured height keys the body document, so a row that comes and goes
# reloads the message under the reader.
if re.search(r'^        readingActions\(\)$', header, re.M):
    ok("A2 the row is drawn unconditionally, at the Column's own level")
else:
    bad("A2 readingActions() is nested under a condition -- the header height would change mid-read")
if header.count("readingActions()") == 1:
    ok("A2 the header draws the row exactly once")
else:
    bad(f"A2 the header draws the row {header.count('readingActions()')} times")
content = body("private fun MessageContent(")
if "val readingActions: @Composable () -> Unit = {" in content:
    row = content[content.index("val readingActions: @Composable () -> Unit = {"):]
    row = row[:row.index("\n    val ", 1)] if "\n    val " in row[1:] else row
    for needle, why in (
        ("TextToolIconRow(", "the AI tools are the shared icon row"),
        ("surface = textTools.surface", "drawn from the READ surface's own declaration"),
        ("contentDescription = stringResource(R.string.message_show_images)", "Show images is an ICON in it"),
        ("viewModel::showImagesOnce", "...wired to the one-time show"),
        ("CopyCodeIcon()", "Copy Code is an ICON in it (#438), drawn from its one declaration (#500)"),
    ):
        ok(f"A2 {why}") if needle in row else bad(f"A2 {why} -- missing {needle!r}")
    # #500: the list row draws the SAME icon, so the glyph and its spoken label live in ONE place.
    decl = src[src.index("internal fun CopyCodeIcon()"):] if "internal fun CopyCodeIcon()" in src else ""
    if "Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.message_copy_code)" in decl[:200]:
        ok("A2 CopyCodeIcon() carries the glyph and the spoken label")
    else:
        bad("A2 CopyCodeIcon() is missing, or no longer carries ContentCopy + message_copy_code")
    # The owner's row is "Translate Resume | Show images Show Plain Text | Copy Code" (#438): the
    # literal `|` is a group separator, drawn between the AI group and the display group and
    # between the display group and Copy Code -- exactly two, never a divider.
    if row.count("ReadingGroupSeparator()") == 2:
        ok("A2 the reading row draws exactly two `|` group separators")
    else:
        bad(f"A2 the reading row draws {row.count('ReadingGroupSeparator()')} group separators, expected 2")
else:
    bad("A2 MessageContent does not build the reading row slot")
n = src.count("readingActions = readingActions,")
if n == 2:
    ok("A2 the row reaches the header through ConversationBody, by name, at both hops")
else:
    bad(f"A2 'readingActions = readingActions,' appears {n} times, expected 2")

# ── A3 the overflow lists NAMES ──
if "IconButton(" in menu:
    bad("A3 the overflow holds an IconButton -- an icon-only control where the owner asked for names")
else:
    ok("A3 the overflow holds no IconButton")
items = menu.split("DropdownMenuItem(")[1:]
unnamed = [i.strip().split("\n")[0] for i in items if not i.lstrip().startswith("text = {")]
if items and not unnamed:
    ok(f"A3 all {len(items)} overflow entries open with a text label")
else:
    bad(f"A3 overflow entries without a text label first: {unnamed or 'no entries at all'}")
for label in ("message_reply", "message_reply_all", "message_forward", "message_flag", "inbox_move_to_folder",
              "message_mark_unread", "message_labels", "message_show_images",
              "message_copy_code",
              "message_archive", "message_delete", "message_unsubscribe", "message_view_headers",
              "message_export_eml", "message_print"):
    if re.search(r'R\.string\.' + label + r'\b', menu):
        ok(f"A3 the overflow names {label}")
    else:
        bad(f"A3 the overflow does not name {label}")
if "textTools.surface.tools.forEach" in menu and "text = { Text(stringResource(tool.label)) }" in menu:
    ok("A3 the AI tools are named entries too, generated from the surface")
else:
    bad("A3 the AI tools are not named overflow entries")
sys.exit(1 if fails else 0)
PY
)
rc=$?
echo "$out"
PASS=$((PASS + $(printf '%s\n' "$out" | grep -c '^  ok:')))
nfail=$(printf '%s\n' "$out" | grep -c '^  FAIL:')
FAIL=$((FAIL + nfail))
# A crash (a signature renamed, an index() that found nothing) prints no FAIL line; count it anyway.
[ "$rc" -ne 0 ] && [ "$nfail" -eq 0 ] && bad "A1-A3 the layout checker crashed (exit $rc)"

# ── the sender surface shows what exists and omits what does not ──
has "$META" 'fun messageMetadata' "S1 the metadata rows are one decision"
has "$META" 'value?.trim()?.takeIf { it.isNotEmpty() }?.let' "S1 a blank field produces NO row"
has "$META" 'lastOrNull { it.name.trim().lowercase() == name }' \
  "S1 the LAST Authentication-Results is read -- the first is an outer relay's own claim"
has "$META" 'fun formatAddress' "S1 an address is copied in name-addr form"
has "$META" 'needsQuoting' "S1 a display name holding a comma is quoted, not split into two recipients"
has "$SCREEN" 'clipboard.setText(AnnotatedString(formatAddress(it)))' "S1 long-press copies that form"
has "$SCREEN" 'onLongClick = copyTarget?.let' "S1 the long-press is on the sender row"

# every R.string the new files name must exist
miss=""
for s in $(grep -ho 'R\.string\.[a-z_0-9]*' "$UI/message/LabelSheet.kt" "$UI/message/ResumeBox.kt" \
             "$UI/settings/TextToolsSection.kt" | sed 's/R.string.//' | sort -u); do
  grep -q "name=\"$s\"" "$STR" || miss="$miss $s"
done
[ -z "$miss" ] && ok "S1 every referenced R.string exists" || bad "S1 missing strings:$miss"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
