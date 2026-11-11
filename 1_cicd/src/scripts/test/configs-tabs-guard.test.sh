#!/usr/bin/env bash
# configs-tabs-guard.test — prove the Configs General | Perms | About guard (#1439) passes on the real tree and FAILS,
# naming the rule, on each way an app or the shared lib can break the order. python3 and coreutils only; no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-configs-tabs-guard.py"
DATA=1_cicd/src/data/configs-pages.json
LIB=ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigsPages.kt
EXT=ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigsExtensions.kt
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$LIB" "$ROOT/$EXT"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1
FAILURES=0; PROVEN=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
BASE="$WORK/base"; T="$WORK/t"

out="$(cd "$ROOT" && python3 "$GUARD" .)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"; else fail "the real tree is red"; printf '%s\n' "$out"; fi

mkdir -p "$BASE"
( cd "$ROOT" && for f in $(for d in a[acd]_*/; do [ -L "${d%/}" ] || [ ! -f "${d}build.json" ] || echo "${d}build.json"; done) $(find a[acd]_* \( -name "build.gradle" -o -name "build.gradle.kts" -o -name "build-extras.gradle" -o -name "settings.gradle" -o -name "settings.gradle.kts" -o -name "*.patch" \) -not -path "*/node_modules/*" -not -path "*/build/*" -not -path "*/src/*") "$DATA" "$LIB" "$EXT" ac_cloud-media-center/web/assets/configs.js ac_cloud-c3-webserver/server/ui/shell.html $(grep -rlE 'FleetConfigsPageBody\(|FleetGeneralPage\(|FleetConfigsScreen\(|withTail\(|ConfigsTail' --include=*.kt --include=*.java a[acd]_* 2>/dev/null | grep -vE '/(test|androidTest)/'); do mkdir -p "$BASE/$(dirname "$f")"; cp "$f" "$BASE/$f"; done )
python3 "$GUARD" "$BASE" >/dev/null || fail "the fixture is red before any mutation"

# mutant NAME RULE PYTHON — PYTHON edits the fixture (cwd = fixture) and must change a file; the guard must go red naming RULE.
mutant() {
    local name="$1" rule="$2" code="$3"
    rm -rf "$T"; cp -r "$BASE" "$T"
    (cd "$T" && python3 -c "$code") || { fail "$name: the mutation did not apply"; return; }
    if diff -rq "$BASE" "$T" >/dev/null; then fail "$name: the mutation changed nothing"; return; fi
    local o; o="$(python3 "$GUARD" "$T")"
    if [ $? -ne 0 ] && grep -q "FAIL  $rule" <<<"$o"; then ok "mutation proven red: $name ($rule)"; PROVEN=$((PROVEN + 1))
    else fail "$name: the guard stayed green or did not name $rule"; printf '%s\n' "$o" | tail -3; fi
}
J='import json
def load(p): return json.load(open(p))
def save(p, d): json.dump(d, open(p, "w"), indent=2)
'
mutant "Cloud Me Configs opens on Perms" T1 "$J
d = load('ac_cloud-me/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
s['pages'][0], s['pages'][1] = s['pages'][1], s['pages'][0]; save('ac_cloud-me/build.json', d)"
mutant "Cloud Me Configs loses About" T1 "$J
d = load('ac_cloud-me/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
s['pages'] = [p for p in s['pages'] if p['id'] != 'about']; save('ac_cloud-me/build.json', d)"
mutant "Calc Configs mode order" T1 "$J
d = load('ac_cloud-calc/build.json'); m = [x for x in d['ui']['modes'] if x.get('tab') == 'configs']
i, j = d['ui']['modes'].index(m[1]), d['ui']['modes'].index(m[2]); d['ui']['modes'][i], d['ui']['modes'][j] = m[2], m[1]
save('ac_cloud-calc/build.json', d)"
mutant "Dialer About back at the end" T1 "$J
d = load('ac_cloud-dialer/build.json'); t = d['configs_pages']['top_nav']; a = [x for x in t if x['id'] == 'about'][0]
t.remove(a); t.append(a); save('ac_cloud-dialer/build.json', d)"
mutant "two Configs declarations disagree" T1 "$J
d = load('ac_cloud-me/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
d['configs_pages']['top_nav'] = s['pages'][:3]; save('ac_cloud-me/build.json', d)"
mutant "the shared screen puts About before Perms" T0 "
p = '$LIB'; s = open(p).read()
a = '    Perms(\"perms\", \"Perms\", \"security\"),\n'; b = '    About(\"about\", \"About\", \"info\"),\n'
assert a in s and b in s; open(p, 'w').write(s.replace(a + b, b + a))"
mutant "the shared screen drops its tab strip" T0 "
p = '$LIB'; s = open(p).read(); n = 'FleetConfigsTabs(shown, dark)'
assert n in s; open(p, 'w').write(s.replace(n, 'Unit // tabs gone'))"
mutant "a conforming app stays pending (stale)" T3 "$J
d = load('$DATA'); d['tabs']['pending']['ac_cloud-me'] = 'x' * 40; save('$DATA', d)"
mutant "pending names no app" T3 "$J
d = load('$DATA'); d['tabs']['pending']['ac_cloud-nope'] = 'x' * 40; save('$DATA', d)"
mutant "pending reason too short" T3 "$J
d = load('$DATA'); d['tabs']['pending']['ac_cloud-me'] = 'later'; save('$DATA', d)"
mutant "Cloud Browser declares the tabs but stops drawing the shared pages" T4 "
p = 'ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/MainActivity.kt'; s = open(p).read()
assert 'FleetConfigsPageBody(' in s; open(p, 'w').write(s.replace('FleetConfigsPageBody(', 'SomethingElse('))"
mutant "Cloud Drive Configs opens on its own page" T1 "$J
d = load('ac_cloud-drive/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
s['pages'].insert(0, s['pages'].pop(3)); save('ac_cloud-drive/build.json', d)"
mutant "Cloud News falls back to one Configs page" T1 "$J
d = load('ac_cloud-news/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
s['pages'] = s['pages'][3:]; save('ac_cloud-news/build.json', d)"
mutant "an app folder without a build.json escapes" T5 "
import os; os.makedirs('ac_cloud-ghost'); open('ac_cloud-ghost/readme.md', 'w').write('x')"
mutant "Media Center draws General before Apify" T4 "
p = 'ac_cloud-media-center/web/assets/configs.js'; s = open(p).read()
a = \"    { id: 'apify', label: 'Apify'\"; assert a in s
b = \"    { id: 'general', label: 'General'\"; assert b in s
i, j = s.index(a), s.index(b); l1 = s[i:s.index('\\n', i) + 1]; l2 = s[j:s.index('\\n', j) + 1]
open(p, 'w').write(s.replace(l1 + l2, l2 + l1))"
mutant "Media Center build.json declares another order" T1 "$J
d = load('ac_cloud-media-center/build.json'); t = d['configs_pages']['top_nav']; t.reverse(); save('ac_cloud-media-center/build.json', d)"
mutant "the Media Center exception drops a shared page" T6 "$J
d = load('$DATA'); o = d['tabs']['app_order']['ac_cloud-media-center']['order']; o.remove('perms'); save('$DATA', d)"
mutant "an app that no longer reaches the shared screen" T1 "$J
d = load('$DATA'); d['pending']['ac_cloud-tasks'] = 'x' * 40; save('$DATA', d)"

# #1630 the fleet-default tail: Agents, Perms, Logcat, X-Ray App, About after a | (T7)
mutant "the lib reorders the tail (Logcat before Perms)" T7 "
p = '$EXT'; s = open(p).read(); a = 'listOf(\"agents\", \"perms\", \"logcat\"'; assert a in s
open(p, 'w').write(s.replace(a, 'listOf(\"agents\", \"logcat\", \"perms\"'))"
mutant "the lib drops the custom X-Ray hook" T7 "
p = '$EXT'; s = open(p).read(); a = 'public fun registerXrayPage('; assert a in s
open(p, 'w').write(s.replace(a, 'public fun registerXrayPageGone('))"
mutant "an app is wired without the Agents lib and not pending" T7 "$J
d = load('ad_Capital-Stack-FinTerminal/build.json'); del d['modules']['libs:fleetconfigs-agents']; save('ad_Capital-Stack-FinTerminal/build.json', d)
g = 'ad_Capital-Stack-FinTerminal/app/build.gradle'; t = open(g).read().split('\\n'); open(g, 'w').write('\\n'.join(x for x in t if 'fleetconfigs-agents' not in x))"
mutant "a wired app stays in tail.pending (stale)" T7 "$J
d = load('$DATA'); d['tabs']['tail']['pending']['ad_Capital-Stack-FinTerminal'] = 'x' * 40; save('$DATA', d)"
mutant "tail.pending names no app" T7 "$J
d = load('$DATA'); d['tabs']['tail']['pending']['ac_cloud-nope'] = 'x' * 40; save('$DATA', d)"
mutant "tail.pending reason too short" T7 "$J
d = load('$DATA'); d['tabs']['tail']['pending']['ac_cloud-calc'] = 'later'; save('$DATA', d)"

# #1630 the WebView hosts (Tauri, no Kotlin): the web strip draws the | and the three native pages, or the guard names the app
mutant "Media Center's web strip loses the | before the tail" T7 "
p = 'ac_cloud-media-center/web/assets/configs.js'; s = open(p).read(); assert 'sep: true' in s
open(p, 'w').write(s.replace('sep: true', 'sep: false'))"
mutant "Media Center's web strip stops opening the native pages" T7 "
p = 'ac_cloud-media-center/web/assets/configs.js'; s = open(p).read(); a = 'action=com.diegonmarcos.fleetconfigs.OPEN'; assert a in s
open(p, 'w').write(s.replace(a, 'action=x'))"
mutant "C3 Webserver's Configs strip drops the tail" T7 "
p = 'ac_cloud-c3-webserver/server/ui/shell.html'; s = open(p).read(); a = \"['logcat', 'Logcat'], ['xray', 'X-Ray App'], \"; assert a in s
open(p, 'w').write(s.replace(a, ''))"
mutant "C3 Webserver's tail ends with Logcat after X-Ray App" T7 "
p = 'ac_cloud-c3-webserver/server/ui/shell.html'; s = open(p).read(); a = \"['logcat', 'Logcat'], ['xray', 'X-Ray App']\"; assert a in s
open(p, 'w').write(s.replace(a, \"['xray', 'X-Ray App'], ['logcat', 'Logcat']\"))"


# #1630 T8 the fleet conformance: every host that draws its own strip ends it with the five, in order, after the |
mutant "Cloud Drive draws its own Configs strip without the tail" T7 "
p = 'ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/configs/ConfigsScreen.kt'; s = open(p).read()
a = 'FleetConfigsExtensions.withTail('; assert a in s; open(p, 'w').write(s.replace(a, 'listOf<Any>('))"
mutant "Cloud Dialer draws its own Configs strip without the tail" T7 "
p = 'ac_cloud-dialer/app/src/main/kotlin/org/fossify/phone/configs/DialerConfigsActivity.kt'; s = open(p).read()
a = 'FleetConfigsExtensions.withTail('; assert a in s; open(p, 'w').write(s.replace(a, 'listOf<Any>('))"
mutant "Cloud Calc orders its Configs modes with Logcat before Perms" T7 "
p = 'ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc/ui/CalcShell.kt'; s = open(p).read()
a = '\"fleet_agents\", \"fleet_perms\", \"fleet_logcat\"'; assert a in s
open(p, 'w').write(s.replace(a, '\"fleet_agents\", \"fleet_logcat\", \"fleet_perms\"'))"
mutant "Cloud Calc stops declaring its X-Ray App mode" T7 "$J
d = load('ac_cloud-calc/build.json'); d['ui']['modes'] = [m for m in d['ui']['modes'] if m.get('kind') != 'fleet_xray']; save('ac_cloud-calc/build.json', d)"
mutant "the lib loses withTail (no | for the own hosts)" T7 "
p = '$EXT'; s = open(p).read(); a = 'public fun withTail('; assert a in s
open(p, 'w').write(s.replace(a, 'public fun withTailGone('))"
mutant "the lib stops drawing the | before the tail" T7 "
p = '$EXT'; s = open(p).read(); a = 'separatorBefore = true'; assert a in s
open(p, 'w').write(s.replace(a, 'separatorBefore = false'))"
mutant "Cloud Store (own settings host) drops the tail" T7 "
p = 'ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt'; s = open(p).read()
a = 'FleetConfigsExtensions.withTail('; assert a in s; open(p, 'w').write(s.replace(a, 'listOf<Any>('))"

echo "configs-tabs-guard.test: $PROVEN mutation(s) proven red, $FAILURES failure(s)"
[ "$FAILURES" -eq 0 ]
