#!/usr/bin/env bash
# Tester: a notification is read because the user SWIPED it, and swiping did
# not cost the page its vertical scroll.
#
# BEFORE: "unread" was derived — `ts > previous visit`. Nothing recorded that
# a specific row had been read, so the whole list flipped to read the moment
# you left the page, and Show=Unread answered "arrived recently" rather than
# "not dealt with".
#
# NOW: read state is an explicit per-entry set in StackFilters, keyed by each
# stream's own stable id and namespaced per stream so it can be pruned. The
# watermark stays, but only to answer a DIFFERENT question — the "N new" chip.
#
# SHAPE (#773): the page is Compose. The predicate and the arithmetic live in
# AggregatorModel/AggregatorState (pure, also pinned by AggregatorModelTest); the
# swipe is detectHorizontalDragGestures + an Animatable in AggregatorNotify.kt —
# the framework's axis lock replaces the hand-rolled dx-vs-dy decision, so the
# vertical scroll is preserved by construction and T4 asserts that nothing else
# claims a drag.
#
# These are the invariants that would regress silently, so they are pinned:
#   T1  the unread predicate reads the stored set, not the watermark
#   T2  the "N new" chip still counts arrival (the watermark keeps ONE job)
#   T3  Show=Unread and the swipe agree (one predicate, not two)
#   T4  the swipe only claims the gesture when it is HORIZONTAL
#   T5  read keys are namespaced and pruned against a COMPLETE enumeration
#   T6  an unavailable ntfy poll prunes nothing
#   T7  prefs are committed, and the page key is set even without a filter row
#   T8  still zero RecyclerView / ItemTouchHelper / child fragments on this path
#   T9  right swipe toggles read, left swipe dismisses; read state stays reversible
#   T10 the page does not narrate itself — no section subtitle describing what
#       a section is, no empty state written as a paragraph
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

DIR="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
FIL="$DIR/StackFilters.kt"

# CODE-ONLY view of the page's files. The counting assertions below must not
# see the KDoc, which necessarily NAMES the things it explains it does not use —
# "no RecyclerView on this path" is a sentence containing "RecyclerView".
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
python3 - "$DIR" "$TMP" <<'PY'
import glob, io, os, re, sys
for f in glob.glob(os.path.join(sys.argv[1], "Aggregator*.kt")):
    s = io.open(f, encoding="utf-8").read()
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)     # block + KDoc
    s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)  # whole-line comments
    s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)    # trailing comments
    io.open(os.path.join(sys.argv[2], os.path.basename(f)), "w", encoding="utf-8").write(s)
PY
MODEL="$TMP/AggregatorModel.kt"; STATE="$TMP/AggregatorState.kt"; NOTIFY="$TMP/AggregatorNotify.kt"
FRAG="$TMP/AggregatorStackFragment.kt"
cat "$TMP"/Aggregator*.kt > "$TMP/ALL.kt"; CODE="$TMP/ALL.kt"
for f in "$MODEL" "$STATE" "$NOTIFY" "$FRAG"; do
  [ -f "$f" ] || { echo "  FAIL: $(basename "$f") not found"; exit 1; }
done

echo "== read state is per entry =="
has "$MODEL" 'if (r.id.isBlank()) r.ts > visitSeenAt else r.id !in readIds' \
  && has "$STATE" 'fun isUnread(r: NotifRow): Boolean = AggregatorModel.isUnread(r, readIds, visitSeenAt)' \
  && ok "T1: isUnread consults the stored read set (watermark only as fallback)" \
  || bad "T1: the unread predicate no longer reads readIds"

has "$MODEL" 'val fresh = g.rows.count { it.ts > visitSeenAt }' \
  && ok "T2: the 'N new' chip still counts arrival, not attention" \
  || bad "T2: the N-new chip lost its watermark basis"

has "$MODEL" 'if (showMode == "unread") rows.filter { isUnread(it, readIds, visitSeenAt) }' \
  && ok "T3: Show=Unread uses the same predicate the swipe writes" \
  || bad "T3: Show=Unread drifted away from isUnread"

echo "== the swipe does not eat the scroll =="
# The drag may be claimed ONLY by detectHorizontalDragGestures, which takes a
# gesture past the touch slop when it is clearly sideways and leaves a vertical
# drag to the page's scroll. Any other drag detector on a row, or a consume()
# outside the horizontal callback, would hand every vertical drag to the row and
# freeze the page.
nh=$(grep -c 'detectHorizontalDragGestures(' "$NOTIFY")
nd=$(grep -cE 'detectDragGestures\(|detectVerticalDragGestures\(|detectDragGesturesAfterLongPress\(' "$CODE")
nc=$(grep -c 'change.consume()' "$NOTIFY")
if [ "$nh" -eq 1 ] && [ "$nd" -eq 0 ] && [ "$nc" -eq 1 ]; then
  ok "T4: the only drag claim is detectHorizontalDragGestures, consumed in its horizontal callback"
else
  bad "T4: $nh horizontal detector(s), $nd other drag detector(s), $nc consume() - the row may claim a vertical drag"
fi
if grep -q 'change.consume()' <<<"$(awk '/onHorizontalDrag = /,/^            },/' "$NOTIFY")"; then
  ok "T4: the consume() sits inside onHorizontalDrag, the horizontal-only branch"
else
  bad "T4: the horizontal-only guard is gone - consume() is outside onHorizontalDrag"
fi
if grep -q 'verticalScroll\|scrollable(' <<<"$(awk '/private fun NotifRowItem/,/^private suspend fun settle/' "$NOTIFY")"; then
  bad "T4: the row owns a scroll of its own - it would fight the page"
else
  ok "T4: the row owns no scroll of its own"
fi
has "$NOTIFY" 'abs(total) > max(64.dp.toPx(), width * 0.22f)' \
  && ok "T4: the commit threshold is max(64 dp, 22% of the row width)" \
  || bad "T4: the swipe threshold drifted from max(64 dp, 22% of the width)"

echo "== read keys stay bounded =="
for ns in 'PHONE_NS = "phone:"' 'APP_NS = "app:"' 'fun ntfyNs(topic: String) = "ntfy:$topic:"'; do
  has "$MODEL" "$ns" && ok "T5: namespace declared — $ns" || bad "T5: missing namespace $ns"
done
p=$(grep -cF 'StackFilters.pruneRead(' "$CODE")
[ "$p" -eq 3 ] \
  && ok "T5: three prunes, one per namespace (phone, in-app, ntfy-per-topic)" \
  || bad "T5: $p pruneRead calls — expected one per namespace"

# T6 — the ntfy prune must sit behind the poll's OK check, so a failed poll
# cannot prune a topic to empty and resurrect every swiped row.
if grep -q 'if (result.ok) StackFilters.pruneRead(ctx, page, AggregatorModel.ntfyNs(topic)' "$STATE"; then
  ok "T6: the ntfy prune is behind result.ok — an unavailable poll prunes nothing"
else
  bad "T6: an unavailable ntfy poll can reach the prune — swiped rows would come back"
fi

echo "== persistence =="
has "$FIL" 'fun readKeys' && has "$FIL" 'fun markRead' && has "$FIL" 'fun pruneRead' \
  && ok "T7: StackFilters owns the read set (readKeys/markRead/pruneRead)" \
  || bad "T7: the read-set API is missing from StackFilters"
if grep -q '\.apply()' "$FIL"; then
  bad "T7: apply() in StackFilters — a launcher restart silently drops the write"
else
  ok "T7: every write commits"
fi
has "$FIL" 'HashSet(prefs(ctx).getStringSet' \
  && ok "T7: getStringSet is copied, never mutated in place" \
  || bad "T7: the stored set may be mutated in place (undefined behaviour)"
# The page key is the page's mode, handed to the state BEFORE any filter-row
# branch: a page with no toggle row (Inboxes, C3 Observability) still carries
# notification boxes, and their swipes must not land in a blank shared bucket.
# #1672: the key may be the page's SHARED state page (state_pages), which is derived from the mode alone and is
# never blank - still set before, and independent of, any filter-row branch.
st=$(grep -n 'AggregatorState(ctx, mode, panels, filters)' "$FRAG" | head -1 | cut -d: -f1)
if [ -z "$st" ] && grep -q 'val stateKey = remember { Sections.statePageFor(sec, mode) }' "$FRAG"; then
  st=$(grep -n 'AggregatorState(ctx, stateKey, panels, filters)' "$FRAG" | head -1 | cut -d: -f1)
fi
fr=$(grep -n 'filters.isNotEmpty()' "$FRAG" | head -1 | cut -d: -f1)
if [ -n "$st" ] && [ -n "$fr" ] && [ "$st" -lt "$fr" ]; then
  ok "T7: the page key is set even on a page with no filter row"
else
  bad "T7: filterPage is conditional again — swipes land in a shared blank bucket"
fi

echo "== nothing heavier was pulled in =="
if grep -qE 'RecyclerView|ItemTouchHelper' "$CODE"; then
  bad "T8: a RecyclerView/ItemTouchHelper appeared on the notification path"
else
  ok "T8: no RecyclerView, no ItemTouchHelper"
fi
# A hosted child fragment renders blank when a body re-runs; the notification
# path must never own one.
if grep -qE 'EmbeddedFragment|FragmentContainerView|childFragmentManager' "$NOTIFY"; then
  bad "T8: the notification centre now embeds a child fragment"
else
  ok "T8: no child fragment in the notification centre"
fi

echo "== the gesture is answered, and reversible =="
# Swipe RIGHT toggles read/unread (and springs back); swipe LEFT dismisses
# (archives). The long-press sheet also offers Mark read / Mark unread.
has "$NOTIFY" 'if (total > 0 && !inArchive) {' && has "$NOTIFY" 'state.markRead(r.id, state.isUnread(r))' \
  && ok "T9: a right swipe toggles read, it does not archive" \
  || bad "T9: the right swipe no longer toggles read"
has "$NOTIFY" 'state.markRead(r.id, unread)' && has "$NOTIFY" '"Mark unread"' \
  && ok "T9: the long-press sheet TOGGLES read — a read row can be marked unread again" \
  || bad "T9: read state can no longer be flipped back"
has "$NOTIFY" 'val unread = state.isUnread(r)' \
  && ok "T9: the row is repainted into its new state (the cues are derived from the read set)" \
  || bad "T9: nothing repaints the row"
has "$NOTIFY" 'offset.animateTo(out, tween(160))' && has "$NOTIFY" 'state.archiveRow(r.id, !inArchive)' \
  && ok "T9: the row visibly slides out in the direction it was thrown and is archived" \
  || bad "T9: the dismiss animation is gone"

echo "== the page does not narrate itself =="
# A notification page STATES; it does not explain itself. The section subtitles
# that described what each section is are gone, and the empty states that were
# written as paragraphs are short neutral labels now. The half of that prose a
# DEVELOPER needs — why an ntfy card matched no channel — was relocated, not
# destroyed: logcat at the moment it bites, plus a _doc key beside the panels in
# build.json. This is what fails when a sentence creeps back onto the card.
T10N=0
while IFS="	" read -r kind msg; do
  T10N=$((T10N+1))
  [ "$kind" = "OK" ] && ok "$msg" || bad "$msg"
done < <(python3 - "$NOTIFY" "$MODEL" "$APP/build.json" <<'PY'
import io, json, re, sys

code  = io.open(sys.argv[1], encoding="utf-8").read()
model = io.open(sys.argv[2], encoding="utf-8").read()
build = json.load(io.open(sys.argv[3], encoding="utf-8"))
out   = []

# Everything the notification-centre path puts on screen, including the strings
# written as several concatenated literals across lines — that spelling is how
# a paragraph gets past a one-line grep.
region = code[code.index("internal fun NotificationCenterBody"):
              code.index("internal fun GroupList")]
LIT  = r'"(?:[^"\\]|\\.)*"'
CALL = re.compile(r"\b(?:AggCaption|AggEmptyHint|AggStateLine)\(\s*(" + LIT +
                  r"(?:\s*\+\s*" + LIT + r")*)")
prose = []
for m in CALL.finditer(region):
    text = "".join(re.findall(LIT, m.group(1))).replace('"', "")
    if len(text) > 48 or ". " in text:
        prose.append(text)
if prose:
    for t in prose:
        out.append(("BAD", "T10: a sentence is back on the card: " + t[:70]))
else:
    out.append(("OK", "T10: every state on this path is a short label, not a sentence"))

notify = next(s for s in build["ui"]["sections"] if s["id"] == "communication")
subs = [p["subtitle"] for k, v in notify.items() if k.startswith("stack_")
        for p in v if p.get("subtitle")]
out.append(("OK", "T10: no Notify panel declares a describe-the-section subtitle")
           if not subs else
           ("BAD", "T10: %d panel subtitle(s) came back, e.g. %s" % (len(subs), subs[0][:60])))

out.append(("OK", "T10: the scopes explanation survives in build.json::_doc_scopes_my-rss")
           if "_doc_scopes_my-rss" in notify else
           ("BAD", "T10: the scopes explanation was destroyed rather than relocated"))
out.append(("OK", "T10: an out-of-scope ntfy card reports itself to logcat")
           if "matched no channel" in region else
           ("BAD", "T10: nothing tells a developer why an ntfy card came up empty"))
out.append(("OK", "T10: a panel with no stream reports itself to logcat")
           if "declares no " in region else
           ("BAD", "T10: a stream-less panel is silent in the log as well as on screen"))

# Counts are DATA and stay. The label lost its paragraph, not its total.
note = re.search(r"fun filteredAwayNote\(total: Int\): String =\s*(" + LIT + r")", model)
out.append(("OK", "T10: the filtered-away label still carries its count")
           if note and "$total" in note.group(1) and len(note.group(1)) < 40 else
           ("BAD", "T10: filteredAwayNote lost its count or grew back into a sentence"))

for kind, msg in out:
    sys.stdout.write(kind + "\t" + msg + "\n")
PY
)
# A parser that died before printing a single verdict must not read as a pass.
[ "$T10N" -ge 6 ] || bad "T10: only $T10N prose check(s) ran — the region parser stopped finding the notification path"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
