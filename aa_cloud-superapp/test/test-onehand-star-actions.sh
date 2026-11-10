#!/usr/bin/env bash
# Tester: Configs ▸ Launcher ▸ One-Hand — the Canopus star's ACTIONS list.
#
# WHY THIS EXISTS: "Copy Info" was removed from that list, and removing an
# entry from a data-driven menu has exactly two ways to go wrong quietly.
#
#   1. THE ENTRY COMES BACK, OR THE WRONG ONE GOES. The Canopus arc's inner
#      ring is not written anywhere in Kotlin — it is the `is_action` pages of
#      the `config` section plus `onehand.arc_menu.actions`. One list, two
#      surfaces (the star AND the Configs grid), which is the whole reason the
#      entry could only be deleted once. A future edit that re-adds it, drops a
#      neighbour, or reorders them is a UI change with no compiler opinion.
#   2. A STORED PREFERENCE STILL NAMES IT. Targets are persisted on-device
#      (recent-tiles LRU, one-hand gesture overrides).
#      A preference written before the removal outlives the APK that removed
#      it, so the dispatcher must treat an unknown action as a defined no-op.
#      An unhandled branch here is a blank slot or a crash on a device that has
#      been in use — never on a fresh install, so never in a smoke test.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin is
# checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
REPO="$(cd "$APP/.." && pwd)"                     # → repo root (libs live beside)
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
SHELL_ACT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
GROUPED="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt"
DEVCTL="$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt"
ARCMENU="$REPO/ab_cloud-libs-shared/libs/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/ArcMenu.kt"

echo "── 1. The star's actions are DATA, and Copy Info is not among them ──"

# The one assertion the removal exists for. Reads the same two sources the star
# reads, in the same order, and prints the list it would render.
STAR_ACTIONS="$(python3 - "$BJ" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
cfg = next(s for s in d["ui"]["sections"] if s["id"] == "config")
pages = [p["label"] for p in cfg["pages"] if p.get("is_action")]
extra = [a["label"] for a in d["onehand"]["arc_menu"].get("actions", [])]
print(" · ".join(pages + extra))
PY
)"
echo "  star actions = [$STAR_ACTIONS]"

case "$STAR_ACTIONS" in
  *"Copy Info"*) check "still offered by the star" "'Copy Info' is absent from the star actions list" ;;
  *)             check OK "'Copy Info' is absent from the star actions list" ;;
esac

# Nothing else moved. Copy Info is out; the shared Actions row (#1596/#1610)
# then added Sync Clipboard, Saving Mode and Focus Mode after Animations.
# The star's order is data, and a silent reshuffle of it is a UI regression no
# other assertion in this file would catch, so this list is pinned in lockstep
# with onehand.arc_menu.actions + the config section's is_action pages.
EXPECTED="Update All · KDE Connect · Animations · Sync Clipboard · Saving Mode · Focus Mode"
[ "$STAR_ACTIONS" = "$EXPECTED" ] \
  && check OK "the remaining actions are exactly [$EXPECTED], in order" \
  || check "got [$STAR_ACTIONS]" "the remaining actions are exactly [$EXPECTED], in order"

# `is_action` is what puts an entry on the inner arc. If the id survived with
# the flag stripped it would vanish from the star but reappear as a Configs
# PAGE tile pointing at nothing — a worse outcome than leaving it alone.
grep -q '"copy_info"' "$BJ" \
  && check "id copy_info still declared in build.json" "the copy_info entry is gone from build.json, not just un-flagged" \
  || check OK "the copy_info entry is gone from build.json, not just un-flagged"

# Data-driven, not a call-site list: the star must build its ring from the
# section pages + its own block, never from labels typed into Kotlin.
grep -q 'SectionPages.pagesFor(section)' "$SHELL_ACT" && grep -q 'CircularMenu.actionsOf("arc_menu")' "$SHELL_ACT" \
  && check OK "the Canopus host reads both rings from data, not a hardcoded list" \
  || check "ShellActivity no longer sources the arc from SectionPages + actionsOf" \
           "the Canopus host reads both rings from data, not a hardcoded list"

echo "── 2. A stored preference naming the removed action degrades safely ──"

# The dispatcher is where every persisted target eventually lands. Without a
# terminal else the removed id would fall off the end of the `when` and do
# nothing at all — a dead tap with no feedback, indistinguishable from a freeze.
grep -q 'else -> anchor.snack("action:\$actionType")' "$SHELL_ACT" \
  && check OK "dispatchHomeAction ends in an else, so an unknown action is a defined no-op" \
  || check "no terminal else in dispatchHomeAction" \
           "dispatchHomeAction ends in an else, so an unknown action is a defined no-op"

# The removed id must not be handled any more — a live branch for an action
# nothing can reach is the dead code this cleanup exists to prevent.
grep -q 'about_copy_all' "$SHELL_ACT" \
  && check "handler still present" "no handler survives for the removed action" \
  || check OK "no handler survives for the removed action"

grep -q 'copyOnOpen' "$DEVCTL" \
  && check "copyOnOpen flag still present" "the one-shot flag the removed action armed is gone too" \
  || check OK "the one-shot flag the removed action armed is gone too"

# The stored recent-tiles LRU is the one surface that RENDERS persisted targets
# rather than dispatching them. mapNotNull is what makes a stale entry vanish
# instead of drawing a labelless tile.
grep -q 'RecentCloudTiles.recent(ctx).mapNotNull' "$GROUPED" \
  && check OK "the recent-tiles LRU drops a stored target it can no longer resolve" \
  || check "recent targets are no longer filtered through mapNotNull" \
           "the recent-tiles LRU drops a stored target it can no longer resolve"

# The one-hand gesture editor offers circular_menu.actions, a different list.
# If the removed id were ever to appear there it would become storable again.
python3 - "$BJ" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
blob = json.dumps(d["onehand"])
sys.exit(1 if ("copy_info" in blob or "about_copy_all" in blob) else 0)
PY
[ $? -eq 0 ] \
  && check OK "no onehand block can offer the removed action to be stored again" \
  || check "onehand.* still names the removed action" \
           "no onehand block can offer the removed action to be stored again"

echo "── 3. Removing one action leaves no hole ──"

# The arc is not a fixed grid: both rings derive their radius and their angular
# step from the live item count, so the survivors re-fan across the half-moon.
grep -q 'radiusFor(inner.size)' "$ARCMENU" && grep -q '(k + 0.5) / idx.size' "$ARCMENU" \
  && check OK "the inner arc re-fans from the item count — no fixed slot to leave empty" \
  || check "arc layout is no longer count-derived" \
           "the inner arc re-fans from the item count — no fixed slot to leave empty"

echo "── 4. Shared machinery kept because it is used elsewhere ──"

# ic_p_import was the removed entry's icon but is NOT its icon alone. Deleting
# the drawable would break a launcher shortcut and the Import Configs screen.
# #773: Import Configs is Compose now; its button paints the icon from
# ImportConfigsFragment.kt (R.drawable.ic_p_import), so that is the second user.
grep -q 'ic_p_import' "$APP/app/src/main/res/xml/shortcuts.xml" \
  && grep -q 'R.drawable.ic_p_import' "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/ImportConfigsFragment.kt" \
  && check OK "ic_p_import kept — still used by shortcuts.xml and Import Configs" \
  || check "ic_p_import no longer referenced by its other users" \
           "ic_p_import kept — still used by shortcuts.xml and Import Configs"

# The clipboard snapshot itself was never the star's — the About page has its
# own button for it. Only the shortcut was removed, not the capability.
grep -q 'AboutItem.Button("Copy All Infos"' "$DEVCTL" \
  && check OK "the About page keeps its own 'Copy All Infos' button" \
  || check "the on-page copy button is gone" \
           "the About page keeps its own 'Copy All Infos' button"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
