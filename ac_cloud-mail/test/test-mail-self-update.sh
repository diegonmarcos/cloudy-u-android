#!/usr/bin/env bash
# cloud-mail self-update: static proof that the app can update ITSELF, that it does
# it through the constellation's ONE updater rather than a second one of its own,
# and that the three rules this fleet paid for are actually expressed in the code.
#
#   W1  the wiring exists: the shared module is declared and linked, the schedule is
#       armed, and Configs has an Update page that is reachable
#   M1  an AUTOMATIC pass never downloads over a metered connection
#   M2  a MANUAL press may spend mobile data, but only after saying the number
#   I1  no install path can report success it did not observe
#   D1  an APK older than the installed one is refused, before anything is staged
#   T1  mail owns no second update mechanism, and no second copy of the settings
#   S1  every R.string the page names exists
#
# Static only: this runner cannot build and has no device. Everything below is a
# property of the source, which is exactly what a reviewer can check and what
# rots silently otherwise.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
UPD="$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"
WORKER="$UPD/UpdateWorker.kt"
UPDATER="$UPD/Updater.kt"
PREFS="$UPD/AutoUpdatePrefs.kt"
INST="$UPD/install/UpdateInstaller.kt"
RCV="$UPD/PackageInstallerReceiver.kt"
CHECKER="$UPD/source/UpdateChecker.kt"
UI="$APP/app/src/main/kotlin/app/sterna/ui/settings"
PAGE="$UI/UpdateScreen.kt"
HUB="$UI/SettingsScreen.kt"
STR="$APP/app/src/main/res/values/strings.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF -- "$2" "$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasre() { grep -qE -- "$2" "$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
# CODE ONLY. Every rule below is also EXPLAINED in a comment next to the code that
# keeps it, so a plain grep for the construct matches the prose about it and would
# pass on a file whose code had been gutted. Strip comments first, always.
code()  { sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' "$1" 2>/dev/null; }
# Same job, different blade: DROP whole comment lines instead of truncating at the
# first '//'. code() cannot be used to look for a URL, because the '//' it cuts at
# is the one inside https:// - every literal address it is shown arrives as the
# harmless fragment `"https:` and the check passes. Verified: a deliberately
# hardcoded repository URL scored green through code(). This keeps the line whole,
# so a real address can be told from a scheme being stripped for display.
codeln() { grep -v -E '^[[:space:]]*(//|\*|/\*)' "$1" 2>/dev/null; }
# Every .kt under a directory, comments stripped - for the "mail owns no second
# copy of this" rules, which have to look at a whole tree rather than one file.
codetree() { find "$1" -name '*.kt' -o -name '*.kts' 2>/dev/null \
             | xargs sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' 2>/dev/null; }
# NEVER `grep -q` on the RIGHT of a pipe in this file. This script runs under
# `set -o pipefail`, and -q makes grep exit the instant it matches - which hands
# the still-writing sed a SIGPIPE, and pipefail then reports the whole pipeline
# as failed. The result is an assertion that fails PRECISELY WHEN IT SHOULD PASS,
# and only for matches early enough in a long file that sed has not finished. Six
# of these read as real failures before the cause was found. Redirect instead, so
# grep drains its input.
has_code()   { code "$1" | grep -F -- "$2" >/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt_code() { code "$1" | grep -F -- "$2" >/dev/null && bad "$3 ($1)" || ok "$3"; }
# Same as has_code, for the assertions whose subject is a function's ARGUMENT
# rather than its whole spelling. See the M2 block for why that distinction had
# to be made and what it cost to learn.
hasre_code() { code "$1" | grep -E -- "$2" >/dev/null && ok "$3" || bad "$3 ($1)"; }

echo "== cloud-mail self-update: wiring, metered rule, observed install, downgrade refusal =="

# ── W1 the wiring ────────────────────────────────────────────────────────────
# Each of these is load-bearing on its own: the module has to be in the graph,
# the app has to link it, something has to arm the schedule, and the page has to
# be reachable. Any one missing leaves an app that cannot update itself while
# every other check here still passes.
# The declared half of the wiring, read as data rather than grepped, because it
# IS data - a JSON shape, not a spelling.
#
# M1-declared is the rule as data: automatic passes are Wi-Fi only. A build that
# shipped require_unmetered_network false would start spending mobile data on its
# own, and no amount of correct Kotlin downstream would stop it.
#
# W1-image-once: the GHCR image must be RESOLVABLE and named ONCE. Two copies
# drift, and a wrong image 404s - which the updater deliberately reads as "no
# build for this ABI yet, up to date", so the app would report itself current
# forever without ever having reached its own artefact.
while read -r name verdict; do
  case "$name:$verdict" in
    W1-module:ok)     ok "W1 build.json declares libs:updater shared by reference" ;;
    W1-module:*)      bad "W1 build.json does not share libs:updater from ab_cloud-libs-shared" ;;
    W1-core:ok)       ok "W1 libs:core comes from the same shared tree" ;;
    W1-core:*)        bad "W1 libs:core missing or pointing at a local copy" ;;
    W1-au:ok)         ok "W1 release.auto_update declares enabled/tag/interval" ;;
    W1-au:*)          bad "W1 release.auto_update incomplete - :libs:updater cannot configure" ;;
    M1-declared:ok)   ok "M1 release.auto_update.require_unmetered_network is true" ;;
    M1-declared:*)    bad "M1 the shipped default would let an automatic pass use mobile data" ;;
    W1-image:ok)      ok "W1 the GHCR image resolves" ;;
    W1-image:*)       bad "W1 no GHCR image resolves - self-update would 404 and read as up to date" ;;
    W1-image-once:ok) ok "W1 the GHCR image is named exactly once" ;;
    W1-image-once:*)  bad "W1 release.ghcr.image AND image_from_fork both set - two copies to drift" ;;
  esac
done < <(python3 - "$APP/build.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
m=d.get('modules',{}); r=d.get('release',{}) or {}
u=m.get('libs:updater'); c=m.get('libs:core'); au=r.get('auto_update') or {}; g=r.get('ghcr',{}) or {}
fork=g.get('image_from_fork')
resolved=g.get('image') or ((d.get('forks',{}) or {}).get(fork,{}) or {}).get('image')
def v(b): return "ok" if b else "FAIL"
print("W1-module", v(isinstance(u,dict) and u.get('dir','').endswith('ab_cloud-libs-shared/libs/updater')))
print("W1-core",   v(isinstance(c,dict) and c.get('dir','').endswith('ab_cloud-libs-shared/libs/core')))
print("W1-au",     v(au.get('enabled') is True and au.get('tag') and au.get('interval_hours')))
print("M1-declared", v(au.get('require_unmetered_network') is True))
print("W1-image",  v(bool(resolved)))
print("W1-image-once", v(not (g.get('image') and fork)))
PY
)
has "$APP/app/build.gradle.kts" 'project(":libs:updater")' "W1 the app module links :libs:updater"
has_code "$APP/app/src/main/kotlin/app/sterna/SternaApplication.kt" "Updater.start(this)" \
  "W1 the periodic self-update is armed at Application start"
has_code "$HUB" 'composable("update")' "W1 Configs has an update destination"
has_code "$HUB" "UpdateScreen(" "W1 that destination renders the Update page"
has_code "$HUB" "onBack = { entry.navigateOnce { nav.popBackStack() } }," "W1 the page can be left again"
has_code "$HUB" "onOpenUpdate" "W1 a hub row navigates to it"
# THE PAGE MUST SHOW THE THREE FACTS. A page that only carries a button is the
# hollow one the owner already had.
for s in settings_update_installed_label settings_update_available_label settings_update_last_checked_label; do
  has_code "$PAGE" "R.string.$s" "W1 the page shows $s"
done
has_code "$PAGE" "BuildConfig.VERSION_CODE" "W1 the installed version is read from the build, not typed in"
has_code "$PAGE" "AutoUpdatePrefs.lastCheck(context)" "W1 the last check is read from where the checker records it"
has_code "$CHECKER" "AutoUpdatePrefs.recordCheck(" "W1 the checker records what it found"
# A check that FAILED must not refresh the clock, or "we have not been able to
# reach GHCR since Tuesday" renders as "checked just now, all fine".
n_record=$(code "$CHECKER" | grep -c "AutoUpdatePrefs.recordCheck(")
n_fail=$(code "$CHECKER" | awk '/State.Failed/{print}' | grep -c "recordCheck")
[ "$n_record" -ge 3 ] && [ "$n_fail" -eq 0 ] \
  && ok "W1 every answering path records, no failing path does ($n_record records)" \
  || bad "W1 recordCheck coverage wrong (records=$n_record, on-failure=$n_fail)"

# ── M1 an automatic pass never downloads over mobile data ────────────────────
# The gate is one decision taken once and used for both halves of a pass. The
# assertion that carries the weight is that the DOWNLOAD is behind it: a check
# that merely computes a reason and logs it is the shape this rule had when it
# was already considered "done" and was still spending data.
has_code "$WORKER" "AutoUpdatePrefs.deferredReason(applicationContext)" \
  "M1 the worker asks whether an automatic pass may spend data"
has_code "$WORKER" "if (deferred == null) updateFleet()" \
  "M1 the fleet half is behind the gate"
has_code "$WORKER" "if (deferred != null) {" "M1 the self half is behind the gate"
has_code "$WORKER" "UpdateProgress.State.UpdateAvailable(available.remoteSize)" \
  "M1 a withheld download publishes the SIZE, so the page can ask rather than go quiet"
# The gate must sit BEFORE the download call, not after it.
gate_line=$(code "$WORKER" | grep -n "State.UpdateAvailable(available.remoteSize)" | head -1 | cut -d: -f1)
dl_line=$(code "$WORKER" | grep -n "UpdateChecker(applicationContext).download(" | head -1 | cut -d: -f1)
[ -n "$gate_line" ] && [ -n "$dl_line" ] && [ "$gate_line" -lt "$dl_line" ] \
  && ok "M1 the metered gate returns before the download is started" \
  || bad "M1 the download is reachable without passing the metered gate (gate=$gate_line download=$dl_line)"
# Unknown network ⇒ metered. Guessing the other way costs the owner's data.
has_code "$PREFS" "?: return true" "M1 an unreadable network counts as metered"
has_code "$PREFS" "requireUnmetered(ctx) && isMetered(ctx)" "M1 the deferral is exactly Wi-Fi-only AND metered"

# ── M2 a manual press says what it will spend ────────────────────────────────
# THE ASSERTION THAT WOULD HAVE CAUGHT THE OLD SHAPE. checkNow and downloadNow
# used to be the same call, so the manual button downloaded whatever it found
# over mobile data in silence. They must differ, and they must differ in CONSENT.
# THE SUBJECT OF THESE TWO IS THE ARGUMENT, NOT THE SIGNATURE. They were fixed
# strings naming the whole declaration down to the `=` of an expression body,
# and 06c7bfac3 had to brace both bodies so their inferred return type stayed
# Unit instead of leaking androidx.work.Operation onto consumers' compile
# classpaths (it broke cloud-wallet and cloud-me, runs 35043275088 /
# 35043275146). `consented = false` and `consented = true` were never touched,
# yet both assertions went red — a green-to-red on a commit that changed no
# behaviour, which is the shape that gets a real assertion deleted for being
# noisy. The regex binds the function to its consent value and says nothing
# about how the body is written, because how the body is written is a compiler
# concern and consent is a spending one.
hasre_code "$UPDATER" '^[[:space:]]*fun checkNow\(context: Context\).*enqueueUserInitiated\(context, consented = false\)' \
  "M2 a manual check carries no spending consent"
hasre_code "$UPDATER" '^[[:space:]]*fun downloadNow\(context: Context\).*enqueueUserInitiated\(context, consented = true\)' \
  "M2 only the answered prompt carries consent"
# AND NOTHING ELSE IN THE MODULE'S PUBLIC API GRANTS IT. The two above are
# satisfiable by adding a third entry point that consents — which is exactly
# what #280 nearly did: requestCheck() is a NEW path, reachable from the
# on-device debug server's POST /api/system/update with no human present at
# all, and a remote caller has by definition not seen a size and not said yes.
# It passes consented = false today and nothing but this counted it.
n_consent=$(code "$UPDATER" | grep -c "consented = true")
[ "$n_consent" -eq 1 ] && ok "M2 exactly one entry point in Updater grants spending consent" \
  || bad "M2 consent is granted in $n_consent places - a path spends data without asking"
has_code "$UPDATER" "enqueueUserInitiated(app, consented = false)" \
  "M2 the unattended remote check (requestCheck) carries no consent either"
has_code "$WORKER" "val deferred = if (consented) null else AutoUpdatePrefs.deferredReason(applicationContext)" \
  "M2 the metered gate is keyed on CONSENT"
hasnt_code "$WORKER" "if (force) null else AutoUpdatePrefs.deferredReason" \
  "M2 the gate is not keyed on force - a tap is not a blank cheque"
# The page turns the withheld download into a question that names the number.
has_code "$PAGE" "UpdateProgress.State.UpdateAvailable" "M2 the page reacts to a withheld download"
has_code "$PAGE" "R.string.settings_update_metered_body, megabytes(available.totalBytes)" \
  "M2 the question states the download size"
has_code "$PAGE" "Updater.downloadNow(context)" "M2 answering it is what spends the data"
# ...and NOTHING else on the page may spend it.
n_dl=$(code "$PAGE" | grep -c "downloadNow(")
[ "$n_dl" -eq 1 ] && ok "M2 downloadNow has exactly one call site, the confirm button" \
  || bad "M2 downloadNow is called $n_dl times - some path spends data without asking"
grep -q '%1\$s' "$STR" && grep -q 'name="settings_update_metered_body"' "$STR" \
  && ok "M2 the metered question takes the size as an argument" \
  || bad "M2 settings_update_metered_body does not interpolate a size"

# ── I1 success is never reported unobserved ──────────────────────────────────
# mail must not grow its own installer. The shared one is the only path whose
# terminal statuses are read, and test-install-status-surfaced.sh guards THAT;
# this file guards that mail actually goes through it.
# CODE, not mentions. The page's own comments explain what PackageInstaller does
# and why committing proves nothing, so an un-stripped grep matches the very prose
# that documents the rule. And it must ban OPENING an install, not naming one.
for banned in "packageManager.packageInstaller" "PackageInstaller.Session" "createSession" "ACTION_INSTALL_PACKAGE"; do
  n=$(codetree "$APP/app/src/main" | grep -cF -- "$banned")
  [ "$n" -eq 0 ] && ok "I1 mail's own code never uses $banned" \
    || bad "I1 mail has its own install path ($banned, $n occurrence(s))"
done
# Nothing in mail may WRITE a terminal success state - reading it to render a
# label is exactly what the page is for. Only the receiver may write it, and only
# from the system's own callback.
n_done=$(codetree "$APP/app/src/main" | grep -cF -- "UpdateProgress.update(UpdateProgress.State.Done")
[ "$n_done" -eq 0 ] && ok "I1 mail never writes UpdateProgress.State.Done itself" \
  || bad "I1 mail writes State.Done in $n_done place(s) - success it did not observe"
has_code "$RCV" "PackageInstaller.STATUS_SUCCESS -> {" "I1 the shared receiver has a STATUS_SUCCESS branch"
# The ONLY Done in the receiver is inside that branch. Walk from STATUS_SUCCESS to
# the next top-level branch and require the Done to be in there.
succ=$(code "$RCV" | grep -n "PackageInstaller.STATUS_SUCCESS ->" | head -1 | cut -d: -f1)
nxt=$(code "$RCV" | awk -v s="$succ" 'NR>s && /^            else -> \{/{print NR; exit}')
donel=$(code "$RCV" | grep -n "UpdateProgress.State.Done" | head -1 | cut -d: -f1)
[ -n "$succ" ] && [ -n "$nxt" ] && [ -n "$donel" ] && [ "$donel" -gt "$succ" ] && [ "$donel" -lt "$nxt" ] \
  && ok "I1 State.Done is written only under STATUS_SUCCESS" \
  || bad "I1 State.Done is not confined to the STATUS_SUCCESS branch (success=$succ done=$donel else=$nxt)"
# And the page renders Failed as a failure rather than swallowing it.
has_code "$PAGE" "is UpdateProgress.State.Failed -> state.message" "I1 the page shows the failure's own words"
has_code "$PAGE" "MaterialTheme.colorScheme.error" "I1 a failure is rendered as one"

# ── D1 an older build is refused ─────────────────────────────────────────────
# The update signal is a DIGEST, which is an identity and not an ordering - so
# "different" is equally "older", and republishing a stale artefact under the
# moving tag reads as an update. The ordering has to come from the APKs.
has_code "$INST" "refuseDowngrade(apk, targetPackage)" "D1 the choke point checks before staging"
has_code "$INST" "ApkIntegrity.identify(context, apk)" "D1 the candidate's own versionCode is read from its manifest"
has_code "$INST" "installedVersionCode(targetPackage)" "D1 compared against what the device actually has"
has_code "$INST" "if (!VersionOrder.isDowngrade(candidate.versionCode, installed)) return" \
  "D1 the ordering is asked of VersionOrder, the one definition Fleet.commit also uses"
# WHERE THAT BOUNDARY NOW LIVES. The comparison used to be a literal `>=` here and
# an identical `<` inside Fleet.commit: two copies of one decision, neither
# reachable by a test, free to disagree about the case that matters. It is now a
# pure function with no imports, driven through NEWER / SAME / OLDER / UNKNOWN by
# VersionOrderTest. Equal must NOT be refused - a same-versionCode rebuild is how
# a damaged install is repaired.
has_code "$UPD/VersionOrder.kt" "compare(candidateCode, installedCode) == Order.OLDER" \
  "D1 only a strictly OLDER candidate is a downgrade"
has_code "$UPD/VersionOrder.kt" "candidateCode == null || installedCode == null -> Order.UNKNOWN" \
  "D1 a version that could not be read is UNKNOWN, never coerced to zero"
hasnt_code "$UPD/VersionOrder.kt" "?: 0" \
  "D1 no null is defaulted to version 0 - that inverts the guard onto every unreadable APK"
has_code "$UPD/Fleet.kt" "VersionOrder.isDowngrade(identity.versionCode, installedCode)" \
  "D1 the fleet path asks the same function, so the two cannot drift apart"
# The refusal has to happen before the session is opened, or the phone has already
# paid for the staging it was about to refuse.
r_line=$(code "$INST" | grep -n "refuseDowngrade(apk, targetPackage)" | head -1 | cut -d: -f1)
c_line=$(code "$INST" | grep -n "InstallSessions.open(port" | head -1 | cut -d: -f1)
[ -n "$r_line" ] && [ -n "$c_line" ] && [ "$r_line" -lt "$c_line" ] \
  && ok "D1 the refusal precedes createSession" \
  || bad "D1 the refusal happens after the session is opened (refuse=$r_line create=$c_line)"
# A refusal upstream of commit() has no installer callback behind it, so it must
# put itself on screen or it is indistinguishable from a hang.
has_code "$INST" "UpdateProgress.update(UpdateProgress.State.Failed(" "D1 a refusal names itself on screen"
has_code "$INST" "throw InstallRefused(why)" "D1 and stops the install"
# Permanent, so it must not be re-armed. A retry loop against a fact that cannot
# change is the background loop this repo does not allow.
has_code "$WORKER" "catch (refused: InstallRefused)" "D1 the worker knows the refusal is terminal"
refused_ln=$(code "$WORKER" | grep -n "catch (refused: InstallRefused)" | head -1 | cut -d: -f1)
retry_ln=$(code "$WORKER" | grep -n "Result.retry()" | head -1 | cut -d: -f1)
fail_ln=$(code "$WORKER" | awk -v s="$refused_ln" 'NR>s && /Result.failure\(\)/{print NR; exit}')
[ -n "$fail_ln" ] && { [ -z "$retry_ln" ] || [ "$fail_ln" -lt "$retry_ln" ]; } \
  && ok "D1 a refusal fails once instead of retrying" \
  || bad "D1 the refusal falls through to Result.retry() - an endless loop (fail=$fail_ln retry=$retry_ln)"

# ── T1 exactly one update mechanism ──────────────────────────────────────────
# A second path is the maintenance trap this work exists to avoid, and it would
# diverge from the store the owner actually uses.
# DECLARATIONS, not uses. The Update page necessarily CALLS AutoUpdatePrefs and
# Updater - that is the point of sharing them. What must not exist is a second
# one of them, or a second route to the registry.
for decl in "object AutoUpdatePrefs" "class GhcrClient" "class UpdateWorker" "class UpdateInstaller" "https://ghcr.io" "/v2/"; do
  n=$(codetree "$APP/app/src/main" | grep -cF -- "$decl")
  [ "$n" -eq 0 ] && ok "T1 mail declares no '$decl'" || bad "T1 mail rebuilt '$decl' ($n occurrence(s))"
done
# The image mail updates itself from must be the one the store distributes for it.
fleet_img=$(python3 -c "
import json;d=json.load(open('$ROOT/aa_cloud-superapp/data/constellation-fleet.json'))
print(next((a['image'] for a in d['apps'] if a['id']=='mail'),''))")
own_img=$(python3 -c "
import json;d=json.load(open('$APP/build.json'))
g=d['release']['ghcr'];f=g.get('image_from_fork')
print(g.get('image') or (d.get('forks',{}).get(f,{}) or {}).get('image',''))")
[ -n "$fleet_img" ] && [ "$fleet_img" = "$own_img" ] \
  && ok "T1 mail self-updates from the same image the store distributes ($own_img)" \
  || bad "T1 mail updates from '$own_img' but the store distributes '$fleet_img'"

# ── L1 the page says WHERE the build comes from ──────────────────────────────
# "gh apk and release links all". A page that only carries a button is the hollow
# one the owner already had; these are the facts and the addresses he asked it to
# surface, and each is load-bearing on its own.
#
# THE MANIFEST THIS PAGE READS. :libs:updater bakes its fleet from the CONSUMING
# app's own data/ dir - and ac_cloud-mail has no data/ dir, so the library's
# CONSTELLATION_FLEET_B64 is the EMPTY STRING in every cloud-mail build. A page
# reading it finds no entry for itself and draws no rows at all, which from a
# screenshot is indistinguishable from a page that works. Mail bakes its OWN
# entry in app/build.gradle.kts, and that is what must be read.
has_code "$PAGE" "Fleet.parse(BuildConfig.MAIL_FLEET_B64)" \
  "L1 the page reads the manifest this app actually bakes"
hasnt_code "$PAGE" "CONSTELLATION_FLEET_B64" \
  "L1 the page does not read the library's copy, which is empty here"
has_code "$APP/app/build.gradle.kts" 'it["package"] == commsApplicationId' \
  "L1 the baked entry is selected by package id, not by the name 'mail'"
# ONE ENTRY, NOT SIXTY. Baking the whole fleet would put 60 other apps' install
# addresses in a mail APK and hand Fleet.installAll a fleet to walk - and
# build.json says it outright: mail "is not a fleet host and must not behave like
# one".
has_code "$APP/app/build.gradle.kts" 'mapOf("apps" to listOf(entry))' \
  "L1 exactly one entry is baked - mail is not a fleet host"
# The facts he reasons in: a filename, a byte count and a timestamp.
for s in settings_update_asset_label settings_update_size_label settings_update_published_at_label; do
  has_code "$PAGE" "R.string.$s" "L1 the page shows $s"
done
has_code "$PAGE" "Fleet.releaseAsset(app)" \
  "L1 the size and the publish time are PROBED, not baked - baking them ships a number CI invalidates"
has_code "$PAGE" "withContext(Dispatchers.IO)" "L1 that probe is off the main thread"
# A refusal must name its status code. #257 was a week spent on a generic
# "failed" that could have said 403.
has_code "$PAGE" "R.string.settings_update_published_refused, asset.status" \
  "L1 a server that refuses is reported WITH its HTTP status"
# The four addresses, each from the fleet entry and none composed from literals.
has_code "$PAGE" "UpdateLinks.releasePageUrl(app.releaseUrl)" "L1 the GitHub release page"
has_code "$PAGE" "app.abiReleaseUrl.takeIf" "L1 the APK download, resolved for THIS device's ABI"
has_code "$PAGE" "app.repoUrl.takeIf" "L1 the repository"
has_code "$PAGE" "app.ghcrPage.takeIf" "L1 the GHCR package page"
# ...and no fifth address invented in Kotlin. A literal URL here is one the fleet
# manifest cannot correct: change it in constellation-fleet.json and this row keeps
# pointing at the old place until someone notices.
#
# codeln, not code: see the note on both. And the pattern requires a HOST CHARACTER
# after the scheme, which is what separates an address from `removePrefix("https://")`
# - the display trim that shortens a row's subtitle and names no host at all.
n_lit=$(codeln "$PAGE" | grep -c -E 'https://[A-Za-z0-9]')
[ "$n_lit" -eq 0 ] && ok "L1 the page hardcodes no URL of its own" \
  || bad "L1 the page carries $n_lit literal URL(s) - the manifest cannot correct those"
# ANTI-#156. Six ad-hoc startActivity(ACTION_VIEW) sites were counted in this app
# and treated as the bug they are. The page takes an opener as a PARAMETER and no
# Context for links, so a row added later has nothing to fire an intent with.
has_code "$PAGE" "onOpenUrl: (String) -> Unit" "L1 the opener is a parameter"
has_code "$HUB" "onOpenUrl = { url -> leaveOnce { openUrl(context, url) } }," \
  "L1 wired to the same guarded opener the About rows use"
for banned in "ACTION_VIEW" "startActivity" "Uri.parse"; do
  n=$(code "$PAGE" | grep -cF -- "$banned")
  [ "$n" -eq 0 ] && ok "L1 the page never calls $banned itself" \
    || bad "L1 the page fires its own intent ($banned, $n occurrence(s))"
done

# ── S1 every R.string the page names exists ──────────────────────────────────
miss=""
for s in $(grep -ho 'R\.string\.[a-z_0-9]*' "$PAGE" | sed 's/R.string.//' | sort -u); do
  grep -q "name=\"$s\"" "$STR" || miss="$miss $s"
done
[ -z "$miss" ] && ok "S1 every referenced R.string exists" || bad "S1 missing strings:$miss"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
