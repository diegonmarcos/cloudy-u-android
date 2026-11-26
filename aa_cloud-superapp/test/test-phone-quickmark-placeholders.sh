#!/usr/bin/env bash
# Tester: Phone > Apps > Quickmarks -- a curated entry for an app the device
# does not have RENDERS as a placeholder, an enumerated section still HIDES
# what is not installed, and every curated package id is one the repository
# actually knows.
#
# THE TWO FAILURES THIS EXISTS TO KEEP FIXED.
#
#   1. A CURATED TILE THAT WAS NOT THERE. Until 2026-09-10 resolve() returned
#      null for an uninstalled package and mapNotNull dropped it, so an entry
#      the owner had deliberately written into build.json simply was not on
#      the page, with nothing to say why. build.json's own doc admitted it
#      ("Missing packages are silently hidden") and used it to justify keeping
#      our packages out of the list. That is inverted now, and T3 is what
#      keeps it inverted: it fails if the curated path grows a filter again.
#
#   2. AN UNRESOLVABLE PACKAGE ID BECOMES PERMANENT FURNITURE. While missing
#      packages were hidden, a typo in an id was invisible -- the tile just
#      never appeared. Now it renders forever, offering to install an
#      application that does not exist. T2 is the loud version of that: every
#      curated id must be declared somewhere in this repository's own
#      taxonomy, so adding a Quickmark forces you to classify the app too.
#
# WHY THE BLAST RADIUS ASSERTION (T4) MATTERS AS MUCH AS T3. The same hiding
# behaviour protects All Apps, Smart Folders, Active Apps and Last Apps, which
# are built by ENUMERATING the device. A placeholder in a list of what the
# device has would be incoherent, so lifting the filter there would be a bug
# rather than a feature. T3 and T4 are deliberately a pair: one says the
# curated path does not filter, the other says the enumerated path still does.
#
# Usage: ./test-phone-quickmark-placeholders.sh   (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> ~/git/cloud-u-android
APP="$ROOT/aa_cloud-superapp"
BUILD_JSON="$APP/build.json"
FRAGMENT="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
INSTALLER="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AppInstall.kt"
GLYPH="$APP/app/src/main/res/drawable/ic_app_not_installed.xml"
EN="$APP/app/src/main/res/values/strings.xml"
ES="$APP/app/src/main/res/values-es/strings.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# FAIL CLOSED on tooling and on every input file, before any assertion runs.
# A missing file must never become a test result. This repository has shipped
# the has()/hasnt() idiom that discards a grep's stderr and then decides ok or
# bad from its exit status alone -- an absent file exits non-zero with the
# reason hidden, which reads as a clean verdict -- plus four testers that were
# green only because ripgrep was not installed. Nothing below suppresses
# stderr, and nothing below is reachable with an input missing.
#
# (That construct is described here rather than quoted, because the engine's
# own vacuous-assertion lint greps for it and a quotation would make this file
# match the very pattern it is explaining it does not use.)
for tool in jq python3 grep sort; do
    command -v "$tool" >/dev/null || {
        echo "FATAL: $tool is not on PATH -- refusing to report a verdict"; exit 2; }
done
for f in "$BUILD_JSON" "$FRAGMENT" "$INSTALLER" "$GLYPH" "$EN" "$ES"; do
    [ -f "$f" ] || { echo "FATAL: missing input $f -- refusing to report a verdict"; exit 2; }
done

q() {  # jq, with jq's OWN exit status fatal -- never read through a pipe
    local out
    if ! out="$(jq -r "$1" "$BUILD_JSON")"; then
        echo "FATAL: jq failed (exit $?) on filter: $1"; exit 2
    fi
    printf '%s\n' "$out"
}

# The three groups this task owns. Read from the data, not restated here.
#
# NOT NAMED `GROUPS`. That is a bash special variable holding the invoking
# user's group ids; assigning to it is ignored, so the filter expanded to a
# numeric gid, jq died with "Cannot index number with string", and T1/T2
# reported the FATAL text itself as a malformed package name. Caught by
# running the tester, which is the only way that class of bug is ever caught.
GROUP_FILTER='.ui.sections[] | select(.id=="phone") | .phone_app_groups[]'
# Every curated entry, in either shape: a bare string or {"pkg","label"}.
ENTRIES="$(q "$GROUP_FILTER
  | ((.packages // [])[], ((.folders // [])[] | (.packages // [])[]))
  | if type == \"object\" then .pkg else . end" | grep -v '^$' || true)"
[ -n "$ENTRIES" ] || { echo "FATAL: no curated entries parsed -- the filter or the data moved"; exit 2; }

echo "== T1: every curated package id is well-formed =="
MALFORMED="$(printf '%s\n' "$ENTRIES" \
  | grep -vE '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$' || true)"
if [ -z "$MALFORMED" ]; then
    ok "all $(printf '%s\n' "$ENTRIES" | grep -c .) curated ids look like package names"
else
    bad "curated entr(ies) that are not a valid package id:"
    printf '%s\n' "$MALFORMED" | sed 's/^/        /'
fi

echo "== T2: every curated package is DECLARED in this repository =="
# The universe of ids the repository knows: phone_folders keywords (the
# central classification), phone_smart_folders, external_apps identities, and
# the constellation fleet manifest. ui._doc_match_keywords is explicit that a
# third-party package named anywhere else in build.json has to appear in a
# folder's keyword list too, or that other list is describing an app the
# taxonomy has never heard of. app.sterna is the precedent it cites by name.
KNOWN="$(mktemp)"; trap 'rm -f "$KNOWN"' EXIT
{
  q '.ui.phone_folders[] | (.match_keywords // [])[] | select(startswith("pkg:")) | ltrimstr("pkg:")'
  q '.ui.phone_smart_folders[]? | (.match_keywords // [])[] | select(startswith("pkg:")) | ltrimstr("pkg:")'
  q '.ui.external_apps[] | (.hub_package // ""), (.alt_package // ""), (.install_package // "")'
  if [ -f "$APP/data/constellation-fleet.json" ]; then
      jq -r '.apps[]? | (.package // "")' "$APP/data/constellation-fleet.json" || {
          echo "FATAL: jq failed on constellation-fleet.json"; exit 2; }
  fi
} | grep -v '^$' | sort -u > "$KNOWN"
UNDECLARED=""
for pkg in $ENTRIES; do
    grep -qxF "$pkg" "$KNOWN" || UNDECLARED="$UNDECLARED $pkg"
done
if [ -z "$UNDECLARED" ]; then
    ok "every curated package is classified in the repository's own taxonomy"
else
    bad "curated package(s) the repository never declares -- each would render as a"
    bad "permanent placeholder offering to install an app nothing here knows about:"
    for pkg in $UNDECLARED; do echo "        $pkg"; done
fi

echo "== T3: the CURATED path does not filter on installed-ness =="
# curated() must exist, must fall back to a not-installed AppInfo, and the
# group/folder render must reach it through map -- not mapNotNull, which is
# the construct that used to drop the entry.
if grep -qF 'fun curated(entry: Entry): AppInfo' "$FRAGMENT"; then
    ok "curated() exists and returns a non-null AppInfo for every entry"
else
    bad "curated() is gone or changed shape -- a curated entry may be droppable again"
fi
if grep -qF 'installed = false' "$FRAGMENT"; then
    ok "the not-installed fallback AppInfo is constructed"
else
    bad "nothing constructs a not-installed AppInfo -- placeholders cannot render"
fi
# #1936 the Cloud app is no longer prepended: a folder renders its declared (third-party) entries
for site in 'group.entries.map(::curated)' 'folder to folder.entries.map(::curated)'; do
    if grep -qF "$site" "$FRAGMENT"; then
        ok "$site renders every declared entry"
    else
        bad "$site is missing -- the curated render path moved or regained a filter"
    fi
done
# CODE ONLY. The fragment's own comments explain, by name, the mapNotNull
# drop this replaced -- so a plain grep for the banned construct matches the
# very prose proving it is gone. That false failure is the mirror image of the
# false pass test-install-status-surfaced.sh documents, and the fix is the
# same: strip comments and let the assertion mean what it says.
FRAGMENT_CODE="$(sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' "$FRAGMENT")"
if printf '%s\n' "$FRAGMENT_CODE" | grep -qE 'mapNotNull[^)]*curated'; then
    bad "curated() is reached through mapNotNull -- that reintroduces the silent drop"
else
    ok "curated() is never reached through mapNotNull (comments stripped first)"
fi

echo "== T4: the ENUMERATED sections still hide what is not installed =="
# Blast-radius guard. These paths must keep going through resolve(), whose
# nullable return is what filters them.
ENUM_FILTERS="$(grep -cF 'mapNotNull { resolve(it) }' "$FRAGMENT" || true)"
if [ "${ENUM_FILTERS:-0}" -ge 2 ]; then
    ok "enumerated sections still filter through resolve() ($ENUM_FILTERS sites)"
else
    bad "expected at least 2 'mapNotNull { resolve(it) }' sites, found ${ENUM_FILTERS:-0} --"
    bad "an enumerated list may now show placeholders for apps the device lacks"
fi
if grep -qF 'fun resolve(pkg: String): AppInfo?' "$FRAGMENT"; then
    ok "resolve() still returns a nullable AppInfo (the filter itself)"
else
    bad "resolve() changed shape -- the enumerated filter's mechanism is gone"
fi

echo "== T5: the install tap can never be silent =="
# Every `return` out of AppInstall.start must carry an Outcome, and Outcome's
# message is what the caller shows. A branch that returned early, logged, or
# fell through would be the tap-into-nothing this object exists to prevent.
RETURNS="$(grep -cE '^\s+return Outcome\(|^\s+return Outcome$' "$INSTALLER" || true)"
BARE="$(grep -cE '^\s+return\s*$|^\s+return\s+(true|false|Unit)\s*$' "$INSTALLER" || true)"
if [ "${RETURNS:-0}" -ge 4 ] && [ "${BARE:-0}" -eq 0 ]; then
    ok "all ${RETURNS} exits from AppInstall.start return an Outcome, none bare"
else
    bad "AppInstall.start has ${RETURNS:-0} Outcome returns and ${BARE:-0} bare returns --"
    bad "a bare exit is a tap that does nothing and says nothing"
fi
if grep -qF 'view.snack(AppInstall.start(ctx, app.pkg, app.label).message)' "$FRAGMENT"; then
    ok "the tile tap shows AppInstall's message"
else
    bad "the tile tap discards AppInstall's message -- the outcome reaches nobody"
fi
if grep -qF 'phone_install_unavailable' "$INSTALLER"; then
    ok "the cannot-install-at-all branch has its own message"
else
    bad "no message for 'no store and no fleet URL' -- that branch is silent"
fi

echo "== T6: one tap is one dispatch =="
# Issue #195 shipped a tile in this launcher firing TWICE per tap (149
# duplicate deliveries in one trace) because two views in the same cell both
# dispatched. Each tile builder must register exactly one click listener.
PHONEUI="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneAppsUi.kt"
[ -r "$PHONEUI" ] || { echo "FATAL: missing $PHONEUI"; exit 2; }
# The tile builders are composables now: AppTile (this fragment) and ExpandedAppTile (PhoneAppsUi.kt, used by both
# folder sheets). Each must register exactly ONE click target (combinedClickable/clickable) on its cell.
for spec in "$FRAGMENT:private fun AppTile" "$PHONEUI:internal fun ExpandedAppTile"; do
    file="${spec%%:*}"; sig="${spec#*:}"
    BODY="$(python3 - "$file" "$sig" <<'PY'
import sys, re
source, sig = open(sys.argv[1], encoding="utf-8").read(), sys.argv[2]
i = source.find("\n" + sig + "(")
if i < 0:
    print("__NOT_FOUND__"); sys.exit(0)
rest = source[i + 1:]
nxt = re.search(r"\n(?:@|private |internal |/\*\*|\})", rest[1:])
print(rest[: nxt.start() + 1] if nxt else rest)
PY
)"
    if [ "$BODY" = "__NOT_FOUND__" ]; then
        bad "$sig not found -- the single-dispatch guard cannot see it"
    else
        N="$(printf '%s\n' "$BODY" | grep -cE '\.(combinedClickable|clickable)\(' || true)"
        if [ "${N:-0}" -eq 1 ]; then
            ok "$sig registers exactly one click target"
        else
            bad "$sig registers ${N:-0} click targets, expected 1 (see #195)"
        fi
    fi
done

echo "== T7: the not-installed glyph takes no colour of its own =="
# Both dark themes recolour their surfaces, so a colour chosen against the
# default gradient is legible on one and invisible on the other. The glyph is
# tinted by the caller from the palette's textSecondary role (Compose: `tint = p.textSecondary`).
if grep -qF 'ic_app_not_installed' "$FRAGMENT"; then
    ok "the fragment draws R.drawable.ic_app_not_installed"
else
    bad "nothing draws the not-installed glyph"
fi
TINTS="$(grep -cF 'tint = p.textSecondary' "$FRAGMENT" || true)"
TINTS2=0
if [ $(( ${TINTS:-0} + ${TINTS2:-0} )) -ge 2 ]; then
    ok "the glyph is tinted from LauncherPalette at every draw site"
else
    bad "the glyph is tinted from LauncherPalette at only $(( ${TINTS:-0} + ${TINTS2:-0} )) site(s), expected 2+"
fi

echo "== T8: every new user-visible string exists in BOTH locales =="
# The i18n guard (1_cicd/src/scripts/cloud-android-i18n-guard.py, policy
# requires "es" for aa_cloud-superapp/app) fails the build on a partial
# locale. This checks the same thing at the point of change, and also that the
# format specifiers agree -- a translation that drops a %1$s crashes at format
# time rather than at build time.
for key in phone_app_not_installed phone_app_not_installed_tile_hint \
           phone_install_downloading phone_install_opening_store \
           phone_install_no_source phone_install_unavailable; do
    IN_EN=no; IN_ES=no
    grep -qF "name=\"$key\"" "$EN" && IN_EN=yes
    grep -qF "name=\"$key\"" "$ES" && IN_ES=yes
    if [ "$IN_EN" = yes ] && [ "$IN_ES" = yes ]; then
        ok "$key is in values/ and values-es/"
    else
        bad "$key: values/=$IN_EN values-es/=$IN_ES -- the i18n guard fails on this"
    fi
done
DRIFT="$(python3 - "$EN" "$ES" <<'PY'
import re, sys, xml.etree.ElementTree as ET
SPEC = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")
def table(path):
    root = ET.parse(path).getroot()
    return {e.get("name"): sorted(m.group(0) for m in SPEC.finditer("".join(e.itertext())))
            for e in root.findall("string") if e.get("translatable") != "false"}
en, es = table(sys.argv[1]), table(sys.argv[2])
for key in sorted(en):
    if key in es and en[key] != es[key]:
        print(f"{key}: en={en[key]} es={es[key]}")
PY
)" || { echo "FATAL: the specifier comparison could not run"; exit 2; }
if [ -z "$DRIFT" ]; then
    ok "no format-specifier drift between the two locales"
else
    bad "format specifiers disagree between locales:"
    printf '%s\n' "$DRIFT" | sed 's/^/        /'
fi

echo "== T9: the curated lists stay THIRD-PARTY only =="
# build.json::_doc_phone_app_groups: anything the constellation offers a way
# into is reached from Cloud > Apps. The two Cloud Terminal forks were the
# only fleet packages in here and they left on 2026-09-10.
FLEET="$(q '.ui.external_apps[] | (.hub_package // ""), (.install_package // "")' \
  | grep -v '^$' | sort -u)"
# #1764 owner's folder model: a Phone folder mirrors one Cloud app and holds that app FIRST, so a fleet package is allowed when it is the
# folder's own icon_pkg (the folder tile wears the Cloud app's icon); any other fleet package is still rejected.
ICONS="$(q '.ui.sections[] | select(.id=="phone") | .phone_app_groups[] | (.folders // [])[] | (.icon_pkg // empty)' | sort -u)"
OURS=""
for pkg in $ENTRIES; do
    printf '%s\n' "$ICONS" | grep -qxF "$pkg" && continue
    printf '%s\n' "$FLEET" | grep -qxF "$pkg" && OURS="$OURS $pkg"
done
# cld.termux* are fleet packages that live in phone_folders rather than in
# external_apps, so name them from the fleet manifest as well.
for pkg in $ENTRIES; do
    printf '%s\n' "$ICONS" | grep -qxF "$pkg" && continue
    case "$pkg" in cld.*) OURS="$OURS $pkg";; esac
done
if [ -z "$OURS" ]; then
    ok "no constellation package is curated into a Quickmark group"
else
    bad "fleet package(s) in a Quickmark group -- they belong in Cloud > Apps:"
    for pkg in $OURS; do echo "        $pkg"; done
fi

echo "== T10: no curated entry names a package that can NEVER be installed =="
# #249: "a placeholder that installs nothing forever is worse than no tile."
# A curated entry has exactly two outcomes -- launch the app, or offer to
# install it. A package that is PREINSTALLED (so the placeholder never shows)
# and exposes NO launch intent (so the tile is a dead tap) has neither, and
# "Google Password" is the name that asked for one: Google Password Manager is
# a surface inside Play services, not an app. The ban is DATA, not a list
# restated here -- build.json::sections[id=phone].quickmark_never_installable
# carries the package, the reason and the honest deep link, so re-adding it is
# a red test with the explanation already attached rather than an argument.
NEVER="$(q '.ui.sections[] | select(.id=="phone")
  | (.quickmark_never_installable // [])[] | .pkg' | grep -v '^$' || true)"
if [ -z "$NEVER" ]; then
    # An empty ban list would make this test sweep nothing and still print a
    # tick -- the "ran zero assertions and passed" shape this suite exists to
    # end. The declaration is required to be present and non-empty.
    bad "quickmark_never_installable is missing or empty -- T10 would assert nothing"
else
    BANNED=""
    for pkg in $NEVER; do
        printf '%s\n' "$ENTRIES" | grep -qxF "$pkg" && BANNED="$BANNED $pkg"
    done
    if [ -z "$BANNED" ]; then
        ok "none of the $(printf '%s\n' "$NEVER" | grep -c .) never-installable package(s) is curated"
    else
        bad "curated entry names a package that can never be installed:"
        for pkg in $BANNED; do
            WHY="$(jq -r --arg p "$pkg" '.ui.sections[] | select(.id=="phone")
              | (.quickmark_never_installable // [])[] | select(.pkg == $p) | .reason' \
              "$BUILD_JSON")" || { echo "FATAL: jq failed reading the ban reason"; exit 2; }
            echo "        $pkg -- $WHY"
        done
    fi
fi

echo
echo "-- test-phone-quickmark-placeholders: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
