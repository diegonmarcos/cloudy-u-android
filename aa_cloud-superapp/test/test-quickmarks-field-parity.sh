#!/usr/bin/env bash
# Tester (#1627, #1628): FIELD PARITY for the Cloud > Apps quickmarks (ui.sections[id=cloud].tile_groups).
# Rule 16 — a reorder/rename/redefine DROPS NOTHING. This pins the pre-#1627/#1628 surface and proves every
# app/page it carried is still present after the reorg, while asserting the new order, renames and dividers.
#
#   T1  #1627 group order + renames: the cloud tile_groups read Primary, Communication, Data, Me,
#       Weby Services & Markets, AGI, Lnktree, Configs; 'Tools Primary' and 'Data Apps' are gone.
#   T2  #1627 field parity: every app the old 'Tools Primary' ∪ 'Data Apps' launched is still launched by the
#       new Primary ∪ Data (nothing dropped; Drive moved Data->Primary, Vault moved Primary->Data, Me added).
#   T3  #1627 Communication is byte-for-byte unchanged (the six tiles, same ids and targets, same order).
#   T4  #1627 dividers: Primary = Drive, Browser | Calc, Navigation, Camera, Keyboard, Writer || Wallet and
#       Data = Note(s), Tasks, Office | Gallery, Media Center || Me, Vault, with single '|' (separator:true) and
#       DOUBLE '||' (separator:true + double:true) tiles, neither carrying a target.
#   T5  #1628 Configs row = Quick Actions, UI | Firewall, WiFi | Store | Account, C3 | Watchdog | About, every
#       tile on a real page/app id, and the five #1611 tiles (Quick Actions, Watchdog, Account, C3, Store) retained.
#   T6  the renderer learned the double divider: Sections parses "double" and TileDivider takes a double flag.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }

RES="$(python3 - "$BJ" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
cloud = next(s for s in d["ui"]["sections"] if s.get("id") == "cloud")
tg = cloud["tile_groups"]
by = {g["title"]: g for g in tg}
out = []
def emit(t, msg): out.append("%s\t%s" % (t, msg))

# T1
titles = [g.get("title") for g in tg]
want = ["Primary", "Communication", "Data", "Me", "Weby Services & Markets", "AGI", "Lnktree", "Configs"]
if titles != want:
    emit("T1", "group order is %r, want %r" % (titles, want))
for gone in ("Tools Primary", "Data Apps"):
    if gone in by:
        emit("T1", "a group is still titled %r (should be renamed)" % gone)

def apptargets(group):
    return [t.get("target", "") for t in group.get("tiles", []) if not t.get("separator")]

# T2 — field parity for #1627 (the old Tools Primary ∪ Data Apps targets)
PRE_PRIMARY_DATA = {
    "extapp:cloud-browser", "extapp:cloud-nav", "extapp:cloud-camera", "extapp:cloud-calc",
    "extapp:cloud-keyboard", "extapp:cloud-keyboard#page:writer", "extapp:cloud-wallet", "extapp:cloud-vault",
    "extapp:cloud-drive", "extapp:cloud-notes", "extapp:cloud-tasks", "extapp:cloud-gallery",
    "extapp:cloud-media-center", "extapp:cloud-office",
}
now = set()
if "Primary" in by: now |= set(apptargets(by["Primary"]))
if "Data" in by: now |= set(apptargets(by["Data"]))
missing = PRE_PRIMARY_DATA - now
if missing:
    emit("T2", "apps dropped from Primary∪Data vs the pre-#1627 quickmarks: %s" % ", ".join(sorted(missing)))

# T3 — Communication unchanged
COMM = [
    ("mail", "extapp:cloud-mail"), ("mattermost", "extapp:cloud-chat"), ("element", "extapp:cloud-matrix"),
    ("dialer", "extapp:cloud-dialer"), ("c3-bot", None), ("contacts", "extapp:cloud-contacts"),
]
if "Communication" in by:
    ct = by["Communication"]["tiles"]
    got = [(t.get("id"), t.get("target")) for t in ct]
    if [i for i, _ in got] != [i for i, _ in COMM]:
        emit("T3", "Communication ids changed: %r" % [i for i, _ in got])
    for (gid, gt), (wid, wt) in zip(got, COMM):
        if wt is not None and gt != wt:
            emit("T3", "Communication tile %s target %r changed (want %r)" % (gid, gt, wt))
else:
    emit("T3", "Communication group is gone")

# T4 — #1627 dividers
def row(group): return [(t.get("label"), t.get("separator", False), t.get("double", False), t.get("target", "")) for t in by[group]["tiles"]]
if "Primary" in by:
    labels = [l for l, s, dbl, tg_ in row("Primary")]
    if labels != ["Drive", "Browser", "|", "Calc", "Navigation", "Camera", "Keyboard", "Writer", "||", "Wallet"]:
        emit("T4", "Primary row = %r" % labels)
if "Data" in by:
    labels = [l for l, s, dbl, tg_ in row("Data")]
    if labels != ["Notes", "Tasks", "Office", "|", "Gallery", "Media Center", "||", "Me", "Vault"]:
        emit("T4", "Data row = %r" % labels)
for grp in ("Primary", "Data"):
    if grp not in by: continue
    for l, s, dbl, tgt in row(grp):
        if l == "|" and not (s and not dbl):
            emit("T4", "%s single '|' is not separator-only (%s/%s)" % (grp, s, dbl))
        if l == "||" and not (s and dbl):
            emit("T4", "%s double '||' is not separator+double (%s/%s)" % (grp, s, dbl))
        if l in ("|", "||") and tgt:
            emit("T4", "%s divider %r carries a target %r" % (grp, l, tgt))
    singles = sum(1 for l, s, dbl, _ in row(grp) if l == "|")
    doubles = sum(1 for l, s, dbl, _ in row(grp) if l == "||")
    if (singles, doubles) != (1, 1):
        emit("T4", "%s has %d single + %d double dividers, want 1 + 1" % (grp, singles, doubles))

# T5 — #1628 Configs row
if "Configs" in by:
    ct = by["Configs"]["tiles"]
    labels = [t.get("label") for t in ct]
    WANT = ["Quick Actions", "UI", "|", "NG Firewall", "WiFi", "|", "Store", "|", "Account", "C3", "|", "Watchdog", "|", "About"]
    if labels != WANT:
        emit("T5", "Configs row = %r" % labels)
    bytgt = {t.get("label"): t.get("target") for t in ct if not t.get("separator")}
    EXP = {
        "Quick Actions": "page:config/controls", "UI": "page:config/panels-ui",
        "NG Firewall": "page:config/firewall", "WiFi": "page:config/wifi",
        "Store": "extapp:cloud-store", "Account": "extapp:cloud-account",
        "C3": "extapp:cloud-c3", "Watchdog": "extapp:c3-watchdog", "About": "page:config/app-about",
    }
    for lab, tgt in EXP.items():
        if bytgt.get(lab) != tgt:
            emit("T5", "Configs tile %r targets %r, want %r" % (lab, bytgt.get(lab), tgt))
    for retained in ("Quick Actions", "Watchdog", "Account", "C3", "Store"):
        if retained not in labels:
            emit("T5", "#1611 Configs tile %r was dropped (field parity)" % retained)
else:
    emit("T5", "Configs group is gone")

for line in out: print(line)
PY
)"

for t in T1 T2 T3 T4 T5; do
  lines="$(printf '%s\n' "$RES" | awk -F'\t' -v k="$t" '$1==k{print $2}')"
  if [ -z "$lines" ]; then
    case "$t" in
      T1) ok "T1: #1627 group order Primary, Communication, Data, Me, Weby…, AGI, Lnktree, Configs; old titles gone" ;;
      T2) ok "T2: #1627 field parity — every app the old Tools Primary ∪ Data Apps launched is still in Primary ∪ Data" ;;
      T3) ok "T3: #1627 Communication is unchanged (six tiles, same ids and targets)" ;;
      T4) ok "T4: #1627 dividers — single '|' and DOUBLE '||' placed per owner order, separators only" ;;
      T5) ok "T5: #1628 Configs row reads per owner order; every tile a real id; the five #1611 tiles retained" ;;
    esac
  else
    printf '%s\n' "$lines" | sed 's/^/    /'; bad "$t"
  fi
done

echo "== T6: the renderer learned the double divider =="
grep -q 'double    = t.optBoolean("double", false)' "$KT/Sections.kt" && ok "Sections.kt parses the \"double\" separator flag" || bad "Sections.kt does not parse \"double\""
grep -qE 'fun TileDivider\(.*double: Boolean' "$KT/LauncherTiles.kt" && ok "TileDivider takes a double flag" || bad "TileDivider has no double parameter"
grep -q 'double = it.double' "$KT/GroupedTilesFragment.kt" && ok "TileStrip passes the separator's double flag to TileDivider" || bad "TileStrip does not forward double"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
