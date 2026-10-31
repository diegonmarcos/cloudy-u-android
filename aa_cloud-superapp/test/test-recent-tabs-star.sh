#!/usr/bin/env bash
# Tester: the Recent Tabs star (4th home star) and the midway row it shares
# with Centauri, plus the Configs order it sits next to.
#
# WHY THIS EXISTS: this change has four ways to be wrong that no compiler and
# no screenshot would catch.
#
#   1. THREE LISTS THAT LOOK ALIKE. This app already has "last 9 Android apps"
#      (Centauri) and "Active Apps" (the system Overview). Recent Tabs is the
#      owner's history INSIDE this app. If the external-app entries of the
#      shared LRU leak onto this star, the owner gets three near-identical
#      lists that disagree, and nothing fails.
#   2. TWO STARS ON ONE TAP TARGET. Centauri and Recent Tabs share a row. Each
#      star's touch box is its glyph plus tap_pad_dp on EVERY side, so if their
#      separation drops below that width the boxes overlap and the wrong menu
#      opens. The floor is arithmetic, so it can be asserted.
#   3. A HISTORY WIRED AT THE CALL SITES. Recording has to stay at the ONE
#      navigation chokepoint. A history that depends on every future screen
#      remembering to report itself is wrong within a month.
#   4. A SECOND DECLARATION OF THE CONFIGS ORDER. The Configs grid and the
#      Canopus arc are fed by ONE list. If a Kotlin-side order ever appears
#      beside it, "Home before About" has to be fixed twice and will drift.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin
# is checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
LIBS="$APP/../ab_cloud-libs-shared/libs"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SHELL_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
LAYOUT="$APP/app/src/main/res/layout/activity_main.xml"
STRINGS="$APP/app/src/main/res/values/strings.xml"
STRINGS_ES="$APP/app/src/main/res/values-es/strings.xml"
RECENT="$LIBS/launcher-apptabs/src/main/java/com/diegonmarcos/superapp/apptabs/RecentTabs.kt"
PREFS="$LIBS/launcher-apptabs/src/main/java/com/diegonmarcos/superapp/apptabs/AppTabPrefs.kt"
CANOPUS="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/CanopusStar.kt"
CENTAURI="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/CentaurusStar.kt"

echo "== T1: build.json is the source of every number this star uses =="
jq_get() { python3 -c "import json,sys;d=json.load(open('$BJ'));print(eval(sys.argv[1]))" "$1" 2>/dev/null; }

STAR_CAP=$(jq_get "d['ui']['app_tabs']['star_cap']")
STORE_CAP=$(jq_get "d['ui']['app_tabs']['cap']")
OFFSET=$(jq_get "d['onehand']['circular_menu']['star']['pair_offset_x_dp']")
TAP_PAD=$(jq_get "d['onehand']['circular_menu']['star']['tap_pad_dp']")
SIZE_SP=$(jq_get "d['onehand']['circular_menu']['star']['size_sp']")

[ "$STAR_CAP" = "9" ] \
  && ok "the nine is declared in data (ui.app_tabs.star_cap = 9)" \
  || bad "ui.app_tabs.star_cap is '$STAR_CAP', expected 9"

if [ -n "$STAR_CAP" ] && [ -n "$STORE_CAP" ] && [ "$STAR_CAP" -le "$STORE_CAP" ]; then
  ok "star_cap ($STAR_CAP) <= store cap ($STORE_CAP), so the arc can actually fill"
else
  bad "star_cap ($STAR_CAP) exceeds the store cap ($STORE_CAP) — the star can never show its full nine"
fi

grep -qF 'BuildConfig.UI_APP_TABS_STAR_CAP' "$SHELL_KT" \
  && ok "the star reads the cap from BuildConfig, not a literal in a loop" \
  || bad "the nine is not coming from build.json — look for a literal 9 in ShellActivity"
grep -qF 'star_cap ?: 9' "$GRADLE" \
  && ok "app/build.gradle bakes ui.app_tabs.star_cap into BuildConfig" \
  || bad "no build.gradle bridge for ui.app_tabs.star_cap"
grep -qF 'BuildConfig.UI_APP_TABS_STAR_CAP' "$SHELL_KT" && ! grep -qE 'pickRecentTabs\([^)]*, *9\)' "$SHELL_KT" \
  && ok "no hardcoded 9 passed to pickRecentTabs" \
  || bad "a literal 9 is being passed to pickRecentTabs"

echo "== T2: the two midway stars cannot land on each other's tap target =="
# Each star's touch box ~= glyph (size_sp, roughly 1em wide) + tap_pad_dp both
# sides. Separation is 2 * pair_offset_x_dp. Overlap = the wrong menu opens.
if [ -n "$OFFSET" ] && [ -n "$TAP_PAD" ] && [ -n "$SIZE_SP" ]; then
  BOX=$(( SIZE_SP + 2 + TAP_PAD * 2 ))     # +2 = Centauri/Recent Tabs size bump
  SEP=$(( OFFSET * 2 ))
  if [ "$SEP" -ge "$BOX" ]; then
    ok "separation ${SEP}dp >= touch box ${BOX}dp — the tap targets clear each other"
  else
    bad "separation ${SEP}dp < touch box ${BOX}dp — Centauri and Recent Tabs overlap on touch"
  fi
else
  bad "could not read pair_offset_x_dp / tap_pad_dp / size_sp from build.json"
fi
# 320dp is the narrowest screen this app supports (minSdk 26 phones).
if [ -n "$OFFSET" ] && [ -n "$TAP_PAD" ] && [ -n "$SIZE_SP" ]; then
  HALF=$(( (SIZE_SP + 2 + TAP_PAD * 2) / 2 ))
  EDGE=$(( 160 + OFFSET + HALF ))
  [ "$EDGE" -le 320 ] \
    && ok "on a 320dp screen the right star's outer edge lands at ${EDGE}dp — on screen" \
    || bad "on a 320dp screen the right star reaches ${EDGE}dp — off screen"
fi
grep -qF 'COMPLEX_UNIT_DIP' "$CENTAURI" && grep -qF 'star.translationX' "$CENTAURI" \
  && ok "Centauri's shift is applied in dp, not raw pixels" \
  || bad "Centauri's horizontal shift is missing or not density-independent"
grep -qF 'star.translationX = -TypedValue.applyDimension' "$CENTAURI" \
  && ok "Centauri takes the NEGATIVE offset (left of centre)" \
  || bad "Centauri is not shifted left"
grep -qF 'offsetXDp = CircularMenu.config().starPairOffsetXDp' "$SHELL_KT" \
  && ok "Recent Tabs takes the POSITIVE offset from the same single value" \
  || bad "the pair no longer shares one offset value — they can drift apart"

echo "== T3: Recent Tabs is this app's PAGES, never the phone's apps =="
grep -qF 'filterNot { it is AppTabPrefs.Entry.ExternalAppEntry }' "$RECENT" \
  && ok "external Android apps are dropped — distinct from Centauri and Active Apps" \
  || bad "external apps can reach the Recent Tabs arc — three lists that look alike"
! grep -qF 'recents:cards' "$APP/build.json" \
  && ok "Active Apps is gone from Centauri's inner ring — it needed Accessibility" \
  || bad "recents:cards is back in onehand.recents_menu — it needs an Accessibility service"
grep -qF 'RecentAppsSource.last9' "$CENTAURI" \
  && ok "Centauri still sources Android apps, not the page history" \
  || bad "Centauri's content source changed — the two stars may now show the same list"

echo "== T4: one chokepoint records the history, and it excludes itself =="
# The star has no recorder of its own: it reads what navigation already wrote.
hits=$(grep -c 'host.record\(Section\|Page\)(' "$NAV")
[ "$hits" -ge 2 ] \
  && ok "navigation records at the controller ($hits call sites in LauncherNavController)" \
  || bad "the recording calls left LauncherNavController — history now depends on call sites"
grep -qF 'if (id != "apptabs")' "$NAV" && grep -qF 'if (sectionId != "apptabs")' "$NAV" \
  && ok "the tabs surface is excluded from its own history" \
  || bad "the tabs surface records itself — its own entry would sit on top"
grep -qF 'recordSection\|recordPage' "$SHELL_KT" >/dev/null 2>&1
grep -qE 'recentTabsStar.*record|record.*recentTabsStar' "$SHELL_KT" \
  && bad "the Recent Tabs star writes to the history — opening it would enter its own list" \
  || ok "the Recent Tabs star only READS the history, never writes to it"

echo "== T5: restart, duplicates and dead pages =="
grep -qF 'getSharedPreferences("app_tabs"' "$PREFS" \
  && ok "history persists in SharedPreferences — survives closing the app" \
  || bad "the history store is no longer persistent"
grep -qF 'addAll(all().filter { it.key != key })' "$PREFS" \
  && ok "revisiting moves an entry to the top instead of adding a second" \
  || bad "the LRU move-to-top is gone — a page could appear twice"
grep -qF 'distinctBy { it.key }' "$RECENT" \
  && ok "the arc de-duplicates by key as well" \
  || bad "the arc no longer de-duplicates"
grep -qF '.filter(isAlive)' "$RECENT" \
  && ok "an entry is checked against the live catalogue before it is drawn" \
  || bad "dead entries are drawn — a tap would navigate into nothing"
grep -qF 'includeHidden = true' "$SHELL_KT" \
  && ok "hidden pages count as alive (page:config/controls is real, just untiled)" \
  || bad "hidden pages would be judged dead the moment they are recorded"
grep -qF 'R.string.recent_tabs_empty' "$SHELL_KT" \
  && ok "fresh install shows an empty-state entry, not a star that does nothing" \
  || bad "no empty state — ArcMenu.open refuses an empty list and the star reads as broken"

echo "== T6: strings and colours =="
for k in star_recent_tabs_desc recent_tabs_empty onehand_stars_intro onehand_star_recent_tabs_what; do
  grep -qF "name=\"$k\"" "$STRINGS" \
    && ok "string resource $k declared in values/" \
    || bad "string resource $k missing from values/"
done
# The Spanish locale is no longer a scheduled task: it landed in 75089bd57 and
# aa_cloud-superapp/app/src/main/res/values-es/ exists. The assertion that used
# to live here REFUSED that directory ("a values-es/ appeared — that is its own
# scheduled task"), so from the moment the locale was deliberately added this
# tester was asserting the absence of work the owner had asked for. It is gone.
#
# What replaces it is the rule that is actually in force now: the owner reads
# these apps in Spanish, so every user-visible string this star adds needs a
# resource in BOTH values/ and values-es/. Checking only values/ is how a
# string ships English-only to a Spanish phone, which is exactly the regression
# 75089bd57 was written to repair.
for k in star_recent_tabs_desc recent_tabs_empty onehand_stars_intro onehand_star_recent_tabs_what; do
  grep -qF "name=\"$k\"" "$STRINGS_ES" \
    && ok "string resource $k translated in values-es/" \
    || bad "string resource $k missing from values-es/ — a Spanish phone would draw the English string"
done
grep -qF 'android:contentDescription="@string/star_recent_tabs_desc"' "$LAYOUT" \
  && ok "the new star's content description is a resource, not a literal" \
  || bad "the new star carries a hardcoded content description"

echo "== T7: the Configs section order is declared ONCE, About last and no Home page (#580) =="
ORDER=$(python3 -c "
import json
d=json.load(open('$BJ'))
c=[s for s in d['ui']['sections'] if s['id']=='config'][0]
print(' '.join(p['id'] for p in c['pages']))
")
# TILED = the entries that actually draw a tile in the grid, in grid order: a
# `hidden` page is a tab of another page and an `is_action` page is appended to
# the Actions heading whatever its position, so neither can be the entry About
# has to follow. #649 made that distinction load-bearing: the three action
# entries now sit at the TAIL of the array, so testing the raw tail would read
# `animations` and call a correct declaration wrong.
TILED=$(python3 -c "
import json
d=json.load(open('$BJ'))
c=[s for s in d['ui']['sections'] if s['id']=='config'][0]
print(' '.join(p['id'] for p in c['pages']
                if not p.get('hidden') and not p.get('is_action')))
")
# #580 deleted Configs ▸ Home (its two tabs merged into Launcher ▸ Notify), so
# the page that used to sit immediately before About is gone. What is left to
# pin is the other half of the old rule: About is opened once ever and stays the
# LAST tiled entry, and no `home` page creeps back into the list.
if case " $ORDER " in *" home "*) true ;; *) false ;; esac; then
  bad "a Configs page named home is declared again — #580 deleted it; its tabs live in Launcher"
elif [ "${TILED##* }" = "app-about" ]; then  # About is app-about since the X-Ray regroup (about aliases X-Ray)
  ok "no Home page in ui.sections[config].pages, and About is its last tiled entry"
else
  bad "About is not the last tiled Configs entry — tiled tail is: $(echo "$TILED" | tr ' ' '\n' | tail -4 | tr '\n' ' ')"
fi
# Both surfaces read that one list. A Kotlin-side order would be a second truth.
grep -qF 'SectionPages.pagesFor(section)' "$SHELL_KT" \
  && ok "the Canopus arc reads the same SectionPages list as the Configs grid" \
  || bad "the Canopus arc no longer reads SectionPages — a second order may exist"
if grep -qE '"(home|about)"[[:space:]]*,[[:space:]]*"(home|about)"' \
     "$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/"*.kt 2>/dev/null; then
  bad "a Kotlin-side Configs order appeared — the page order now has two declarations"
else
  ok "no Kotlin-side Configs order — build.json remains the single source of truth"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
