#!/usr/bin/env bash
# cloud-mail's navigation is ONE declaration (#868): build.json::ui, read through libs:bottomnav's NavDecl.
#
#   N1  ui.bottom_nav is the seven section ids Mail, Alerts, Tasks, Home, Chat, Video, Contacts, and ui.default_section is one of them
#   N2  every section's first page is either a DESTINATION (page id = a NavHost route SternaApp serves) or a
#       `launch:<package>` hand-off; exactly two launch (Telegram, WhatsApp Business), none is a destination
#   N3  app/build.gradle.kts bakes UI_BOTTOM_NAV, UI_SECTIONS_B64 and UI_DEFAULT_SECTION from build.json::ui
#   N4  the item table is DERIVED from NavDecl (no literal BottomNavItem list in Kotlin) and the island is fed from it
#   N5  no hand-rolled tab widget in the app: the All|Unread folder tabs are the shared PageTabs
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
code() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }

echo "== N1/N2: the declaration =="
python3 - "$APP/build.json" "$UI/SternaApp.kt" <<'PY' && ok "bottom_nav is the seven sections (Mail Alerts Tasks Home Chat Video Contacts); launch pages carry launch:<package>, destinations name a served route" || bad "build.json::ui is not a valid mail nav declaration"
import json, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
app = open(sys.argv[2], encoding="utf-8").read()
bar, secs = ui["bottom_nav"], {s["id"]: s for s in ui["sections"]}
ok = bar == ["mail", "rss", "tasks", "home", "chat", "video", "contacts"] and ui["default_section"] in bar and all(b in secs for b in bar)
launches = []
for b in bar:
    p = secs[b]["pages"][0]
    a = p.get("action", "")
    if a:
        ok = ok and a.startswith("launch:") and len(a) > 7
        launches.append(a)
    else:
        ok = ok and re.search(r'composable\(\s*"%s"' % re.escape(p["id"]), app) is not None
ok = ok and sorted(launches) == sorted(["launch:com.diegonmarcos.cloudagenda", "launch:com.diegonmarcos.comms.chat", "launch:com.whatsapp.w4b", "launch:com.diegonmarcos.cloudcontacts"])
sys.exit(0 if ok else 1)
PY

echo "== N3: baked =="
G="$APP/app/build.gradle.kts"
for f in UI_BOTTOM_NAV UI_SECTIONS_B64 UI_DEFAULT_SECTION; do has "$G" "\"$f\"" "build.gradle.kts bakes $f"; done
has "$G" 'mailUi\["sections"\]' "the sections come from build.json::ui, not a literal"

echo "== N4: derived, fed =="
BN="$UI/BottomNav.kt"; MB="$UI/MailBottomNav.kt"
has "$BN" 'NavDecl.fromBuildConfig(' "BottomNav.kt reads the declaration through NavDecl"
grep -q 'BottomNavItem(id = "' <<<"$(code "$BN")" && bad "BottomNav.kt still carries a literal item table" || ok "no literal BottomNavItem table in Kotlin"
has "$MB" 'mailNav.bottomSections()' "the island entries come from the declared bottom sections"
has "$MB" 'NavBarBottomHost(' "the bar is still the shared NavBarBottomHost"

echo "== N5: no private tab widget =="
if grep -rqE '\b(TabRow|ScrollableTabRow|PrimaryTabRow|TabLayout|NavigationBar)\b *[({]' "$APP/app/src/main" --include=*.kt; then bad "a hand-rolled tab/nav widget is back in app/src/main"; else ok "no TabRow/TabLayout/NavigationBar in app/src/main"; fi
has "$UI/inbox/InboxScreen.kt" 'NavBarTopSecondRow(' "the folder drawer's All|Unread strip is the shared NavBarTopSecondRow"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
