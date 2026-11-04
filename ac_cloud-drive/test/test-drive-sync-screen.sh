#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #603 — CONFIGS ▸ GIT is GitSync-class: one card per repository with the     ║
# ║ live glance (branch, upstream, ahead/behind, dirty, CONFLICTS, state),   ║
# ║ the last sync, stepped one-tap sync, per-repo period / network / auth,   ║
# ║ a persisted history, conflicts surfaced not swallowed; Rclone and Mounts ║
# ║ speak the same card language                                              ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   G1  the glance carries every field the card prints, read from the ENGINE
#       (GitEngine.status) — never a second git implementation in the app.
#   G2  the card: StatusLight from the last outcome + conflicts; the conflict count
#       in the off colour; Resolve opens the engine on the repo; Sync now / Open /
#       History / Settings pills; the auto footer.
#   G3  one-tap sync is stepped (staging → committing → pulling → pushing) through
#       the engine's own verbs; pushing says it cannot be interrupted.
#   G4  per-repo settings: the library model carries syncIntervalMinutes and
#       syncRequireUnmetered; the sheet offers the DECLARED periods
#       (ui.sync.git_periods_minutes), the network rule and the auth kinds, and
#       writes the ManagedRepo the engine reads; secrets go to GitCredentialStore.
#   G5  history: SyncHistory is app-owned, capped, written by BOTH the card's sync
#       and the worker (trigger manual / scheduled) and rendered.
#   G6  the scheduler decides per repository with SyncSchedule.isDue (pure, JVM-tested)
#       against the repo's own period and network rule; conflicts are not retried.
#   G7  declared-not-cloned cards compose <root>/<name> + the upstream URL once
#       (Declarations.cloneUrl) and hand both to the engine.
#   G8  Rclone and Mounts: cards with a Test light (a LOOK at the thing), progress
#       rows from the runner's stats, the fleet's declared status shown as
#       UNVERIFIABLE, never green; badges from strings, not literals.
#   M   mutation-proof: the isDue call dropped from the worker → G6 red; the
#       conflict line dropped from the card → G2 red; unmutated stays green.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
CARDS="$SRC/sync/GitReposScreen.kt"
# #1138 the row is a table row now: its cells and the grouped actions live in these two files.
TABLE_UI="$SRC/sync/RepoTableUi.kt"
GITUI="$APP/../ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui"   # #1408 the table model and cells live in libs:git-ui
COORD="$SRC/sync/GitSyncCoordinator.kt"
HIST="$APP/../ab_cloud-libs-shared/libs/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/SyncHistory.kt"
RC="$SRC/sync/RcloneMountsScreens.kt"
RCC="$SRC/sync/RcloneCoordinator.kt"
# #1173 the Mounts and Fsync pages are their own files, over StorageCoordinator's real state
RM="$SRC/sync/MountsPage.kt"
FSP="$SRC/sync/FsyncPage.kt"
STC="$SRC/storage/StorageCoordinator.kt"
WORKER="$SRC/GitSyncWorker.kt"
GIT_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$BJ" "$APP")"
MODELS="$GIT_MOD/../git-core/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitModels.kt"
LIB_SCREEN="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitSyncScreen.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$CARDS" "$COORD" "$HIST" "$RC" "$RCC" "$RM" "$FSP" "$STC" "$WORKER" "$MODELS" "$LIB_SCREEN"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# g2 <GitReposScreen.kt> : the card prints the conflict count in the off colour and offers Resolve
g2() {
    grep -qE 'stringResource\(R\.string\.sync_conflicts, conflictCount\), color = off' "$1" &&
    grep -qE 'Pill\(stringResource\(R\.string\.sync_resolve_conflicts, conflictCount\), \{ actions\.openEngine\(EngineActivity\.ENGINE_GIT, repo\.path\) \}, filled = true\)' "$1" &&
    # #1175 the light's rule moved to RepoStatus (pure, JVM-tested): a conflict is never green.
    grep -qE 'g\.conflicts > 0 -> Kind\.CONFLICT' "$SRC/sync/RepoStatus.kt" &&
    grep -qE 'CONFLICT\(StatusLight\.State\.OFF\)' "$SRC/sync/RepoStatus.kt" &&
    grep -qE 'RepoStatus\.light\(RepoStatus\.kindOf\(' "$1"
}
# g6 <GitSyncWorker.kt> : the worker decides per repository through SyncSchedule.isDue with the repo's own period and rule
g6() {
    grep -qE 'SyncSchedule\.isDue\(' "$1" && grep -qE 'repoIntervalMinutes = repo\.syncIntervalMinutes' "$1" && grep -qE 'requireUnmetered = repo\.syncRequireUnmetered' "$1" && grep -qE 'if \(!due\) return@forEach' "$1"
}

echo "── G1 the glance is the engine's status ──"
if grep -qE 'data class Glance\(' "$COORD" && for f in branch upstream ahead behind changed conflicts repositoryState error gone; do grep -qE "val $f:" "$COORD" || exit 1; done; then pass "Glance carries branch, upstream, ahead, behind, changed, conflicts, repositoryState, error, gone"; else fail "Glance lacks a field the card prints"; fi
if grep -qE 'GitEngine\(ctx, dir\)\.use \{ e ->' "$COORD" && grep -qE 'val s = e\.status\(\)' "$COORD"; then pass "the glance is GitEngine.status(), not a second git"; else fail "the coordinator does not read GitEngine.status()"; fi
if grep -rqE 'org\.eclipse\.jgit' "$SRC"; then fail "the app imports JGit directly — the library is the engine"; else pass "no JGit in the app: every verb goes through libs:git-sync"; fi
if grep -qE 'RepoRegistry\(File\(ctx\.filesDir, GitSyncWorker\.REGISTRY_FILE\)\)' "$COORD" && grep -qE 'const val REGISTRY_FILE = "git-sync/repos\.json"' "$WORKER" && grep -qE 'RepoRegistry\(File\(context\.filesDir, "git-sync/repos\.json"\)\)' "$LIB_SCREEN"; then pass "card, worker and engine screen read the SAME registry"; else fail "registry paths differ"; fi

echo "── G1b a cloned repository never renders ? once its status is read ──"
# The page open starts NO status read (the table renders from the persisted cache; an empty cache says "not checked yet"); the checks run on pull-to-refresh / Light check all / Full check all. Every read kind is a declared non-? light, the JVM fixture asserts it.
if ! grep -qE 'statusReader\.request\(' <<<"$(sed -n '/fun onPageOpened()/,/^    }/p' "$COORD")" \
   && grep -qF 'onRefresh = { coordinator.refresh(force = true)' "$CARDS" && grep -qF 'NOT_CHECKED(StatusLight.State.UNVERIFIABLE)' "$SRC/sync/RepoStatus.kt" \
   && ! grep -E '^\s+(CLEAN|AHEAD|BEHIND|DIVERGED|DIRTY|NO_UPSTREAM|CONFLICT|ERROR)\(StatusLight\.State\.(UNKNOWN|UNVERIFIABLE)' "$SRC/sync/RepoStatus.kt" >/dev/null \
   && grep -qF 'never renders a question mark' "$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/RepoStatusTest.kt"; then pass "page open reads nothing (cache); pull-to-refresh checks; every read kind has a non-? light; JVM fixture pins it"; else fail "a cloned repo can render ? after its status was read"; fi

echo "── G1c cheap-first, bounded, cancellable status; live registry ──"
if grep -qF 'fun cancelAll()' "$SRC/sync/GitStatusReader.kt" && grep -qF 'tooLarge(t.dir)' "$SRC/sync/GitStatusReader.kt" && grep -qF 'RepoCheap.tooLarge(dir, d.maxAutoFiles, d.maxScanMs)' "$COORD" \
   && grep -qF 'onDispose { coordinator.cancelStatusReads() }' "$CARDS" && grep -qF 'fun checkWorktree(' "$COORD" && grep -qF 'refreshRegistry()' "$COORD" && grep -qF 'RepoSotLive.refresh(' "$COORD"; then pass "size guard before any worktree scan, on-demand check, cancel on close, conditional-GET registry refresh on open"; else fail "status scheduler guard / cancel / live registry wiring missing"; fi

echo "── G2 the card ──"
g2 "$CARDS" && pass "conflicts in the off colour, a red light, and a Resolve pill that opens the engine" || fail "conflicts are not surfaced on the card"
for s in sync_now sync_open sync_history sync_settings sync_last sync_never sync_auto sync_manual sync_glance_reading sync_glance_gone sync_glance_unreadable sync_clean sync_changed; do
    grep -qE "R\.string\.$s\b" "$CARDS" "$TABLE_UI" "$SRC/sync/GitDepthUi.kt" && pass "card prints $s" || fail "card lacks $s"
done
if grep -qE 'StatusLightRow|light = light' "$CARDS" && grep -qE 'tag = DriveTags\.SYNC_REPO_CARD' "$CARDS"; then pass "the card is a DriveCard with a StatusLight and its tag"; else fail "the repo card is not the shared card"; fi
if grep -qE '"↑\$ahead ↓\$behind"' "$APP/../ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui/GitTable.kt" && grep -qE 'if \(g\.repositoryState != "SAFE"\)' "$SRC/sync/RepoMessage.kt" && grep -qE 'R\.string\.git_meta_branch, glance\?\.branch' "$CARDS"; then pass "the dense row's glance (#1138 table): ↑ahead ↓behind and the dirty count in their cells, state when not SAFE; the upstream is in the Metadata panel (#608)"; else fail "the glance line is incomplete"; fi

echo "── G3 stepped one-tap sync ──"
if grep -qE 'enum class Step \{ STAGING, COMMITTING, PULLING, PUSHING \}' "$COORD" && grep -qE 'e\.stageAll\(\)' "$COORD" && grep -qE 'e\.commit\(repo\.syncMessage' "$COORD" && grep -qE 'e\.pull\(rebase = repo\.pullRebase, auth = authFor\(repo\)\)' "$COORD" && grep -qE 'e\.push\(auth = authFor\(repo\)\)' "$COORD"; then pass "stage → commit → pull → push through the engine's verbs, step published"; else fail "the stepped sync is not the engine's four verbs"; fi
if grep -qE 'GitSyncCoordinator\.Step\.values\(\)\.forEach' "$CARDS" && grep -qE 'sync_step_uninterruptible' "$CARDS"; then pass "the card names the step in flight and the uninterruptible push"; else fail "steps not rendered"; fi

echo "── G4 per-repo settings ──"
if grep -qE 'val syncIntervalMinutes: Long = 0' "$MODELS" && grep -qE 'val syncRequireUnmetered: Boolean = true' "$MODELS"; then pass "ManagedRepo carries the per-repo period and network rule"; else fail "the library model lacks the per-repo fields"; fi
if grep -qE 'syncIntervalMinutes = intervalText\.toLongOrNull\(\) \?: 0L, syncRequireUnmetered = unmetered' "$LIB_SCREEN"; then pass "the engine's own Settings tab saves them too"; else fail "the library SettingsTab does not save the new fields"; fi
if grep -qE 'val periods = Declarations.sync.gitPeriodsMinutes' "$CARDS" && grep -qE 'autoSync = autoSync, syncIntervalMinutes = interval, syncRequireUnmetered = unmetered' "$CARDS" && grep -qE 'coordinator\.setSecret\(repo, secret\)' "$CARDS"; then pass "the sheet offers the declared periods, the rule and auth; writes the ManagedRepo; secrets to the credential store"; else fail "RepoSettingsSheet incomplete"; fi
if python3 -c 'import json,sys; p=json.load(open(sys.argv[1]))["ui"]["sync"]["git_periods_minutes"]; sys.exit(0 if p and all(x>=15 for x in p) and 0 not in p else 1)' "$BJ"; then pass "declared periods ≥ 15 and never 0 (0 means the base)"; else fail "ui.sync.git_periods_minutes invalid"; fi
if grep -qE 'listOf\("none" to R\.string\.sync_auth_none, "https" to R\.string\.sync_auth_https, "ssh" to R\.string\.sync_auth_ssh\)' "$CARDS"; then pass "auth kinds: none / HTTPS token / SSH key"; else fail "auth kinds missing"; fi

echo "── G5 history ──"
if grep -qE 'class SyncHistory\(private val file: File, private val cap: Int = CAP\)' "$HIST" && grep -qE 'const val CAP = 200' "$HIST" && grep -qE 'const val TRIGGER_MANUAL' "$HIST" && grep -qE 'const val TRIGGER_SCHEDULED' "$HIST"; then pass "SyncHistory is app-owned, capped, with the two triggers"; else fail "SyncHistory shape wrong"; fi
if grep -qE 'history\.append\(SyncEvent\(now, repo\.id, repo\.name, SyncHistory\.TRIGGER_MANUAL' "$COORD" && grep -qE 'history\.append\(SyncEvent\(at, repo\.id, repo\.name, SyncHistory\.TRIGGER_SCHEDULED' "$WORKER"; then pass "both the card's sync and the worker write the history"; else fail "a sync path does not write the history"; fi
# #1258 the history is ACTIVITY LOGS: its own view (GitDepthUi ActivityLogsWindow, bounded by the declared max), opened from a top button.
if grep -qE 'private fun HistoryRow\(e: SyncEvent' "$CARDS" && grep -qF 'ActivityLogsView(events, fmt)' "$SRC/sync/GitDepthUi.kt" && grep -qF 'ActivityLog.visible(events).take(Declarations.sync.git.activityMax)' "$SRC/sync/ActivityLog.kt" && grep -qF 'activity_copy_filtered' "$SRC/sync/ActivityLog.kt" && grep -qF 'Intent.ACTION_SEND' "$SRC/sync/ActivityLog.kt" && grep -qE 'testTag\(DriveTags\.SYNC_HISTORY\)' "$CARDS" && grep -qF 'GitLogsWindow(page.topBar.logs, logsTab, coordinator, events, fmt' "$CARDS"; then pass "the activity logs are rendered (declared max, own view, per-repo inline, copy all/filtered/entry + share, empty entries dropped)"; else fail "activity logs not rendered"; fi

echo "── G6 the scheduler decides per repository ──"
g6 "$WORKER" && pass "the worker asks SyncSchedule.isDue with the repo's own period and rule and skips the rest" || fail "the worker does not decide per repository"
if grep -qE 'fun isDue\(' "$HIST" && grep -qE 'val period = if \(repoIntervalMinutes > 0\) repoIntervalMinutes else baseIntervalMinutes' "$HIST" && grep -qE 'if \(requireUnmetered && networkUnmetered != true\) return false' "$HIST"; then pass "SyncSchedule.isDue: own period or base; Wi-Fi rule refuses an unknown network"; else fail "SyncSchedule.isDue rules missing"; fi
if grep -qE 'return Result\.success\(\)' "$WORKER" && grep -qE 'NET_CAPABILITY_NOT_METERED' "$WORKER"; then pass "conflicts are not retried; the network is read from the platform"; else fail "worker outcome/network rules missing"; fi
if [ -f "$APP/../ab_cloud-libs-shared/libs/git-repos/src/test/java/com/diegonmarcos/cloudlib/gitrepos/SyncHistoryTest.kt" ] && grep -qE 'class SyncScheduleTest' "$APP/../ab_cloud-libs-shared/libs/git-repos/src/test/java/com/diegonmarcos/cloudlib/gitrepos/SyncHistoryTest.kt" && grep -qE 'ownPeriodOverridesTheBase' "$APP/../ab_cloud-libs-shared/libs/git-repos/src/test/java/com/diegonmarcos/cloudlib/gitrepos/SyncHistoryTest.kt" && grep -qE 'capDropsTheOldest' "$APP/../ab_cloud-libs-shared/libs/git-repos/src/test/java/com/diegonmarcos/cloudlib/gitrepos/SyncHistoryTest.kt"; then pass "the JVM suite proves the decision and the cap"; else fail "no JVM proof of SyncSchedule / SyncHistory"; fi

echo "── G7 declared, not cloned (#608: a DENSE ROW, not a card) ──"
# The declared public set is rendered as a compact row that offers Clone into store; the
# URL is the DECLARED remote-mode shape substituted once (ui.sync.git.remote_modes), and
# the private/fork facts ride the row as badges. test-drive-git-page.sh owns the rest.
# #669 the declared shape is the FALLBACK for rows with no listing item; a listed row clones
# its own listing's URL (test-drive-git-auth-chain.sh C10 owns that rule and its mutations).
if grep -qE 'ifBlank \{ page\.cloneUrl\(name, mode\) \}' "$CARDS" && grep -qE 'cloneLabel = stringResource\(R\.string\.sync_clone_into_store\), onClone = onClone' "$SRC/sync/RepoStatusWindow.kt" && grep -qE 'GitPill\(cloneLabel, onClone, Modifier\.testTag\(k\.tagStatusClone\), filled = true\)' "$GITUI/GitStatusWindowUi.kt" && ! grep -q 'sync_clone_into_store' <<<"$(sed -n '/private fun GitRepoRow(/,/private fun lightOf(/p' "$CARDS")"; then pass "Clone into store is the status window's action (#1238), not the table row's; the declared URL is composed once, as the no-listing fallback"; else fail "clone-into-store composition missing (window) or still in the row"; fi
if grep -qE 'testTag\(DriveTags\.SYNC_GIT_ROW\)' "$CARDS" && grep -qE 'RepoColumn\.VISIBILITY to repoVisibilityWord\(r\.isPrivate\)' "$GITUI/RepoTableUi.kt"; then pass "the dense row is tagged and carries the Pvt/Pub column (#1138; it was a private badge)"; else fail "declared cards incomplete"; fi

# g8 <MountsPage.kt> <FsyncPage.kt> <StorageCoordinator.kt> : #1173 the Rclone, FUSE and Backup pages: tagged cards whose lights are LOOKS at the thing, run state held across tabs
g8() {
    local rm="$1" fsp="$2" stc="$3" bad=0
    pass_() { echo "  PASS  $*"; }
    fail_() { echo "  FAIL  $*"; bad=1; }
    if grep -qE 'tag = DriveTags\.SYNC_REMOTE_CARD' "$rm" && grep -qE 'tag = DriveTags\.SYNC_MOUNT_CARD' "$rm" && grep -qE 'tag = DriveTags\.SYNC_MOUNT_CARD' "$RC" && grep -qE 'testTag\(DriveTags\.SYNC_CONNECTION_ROW\)' "$RC"; then pass_ "remote cards, FUSE mount cards and the Volumes connection rows are tagged (#1216: the Backup job cards left with the Backup page)"; else fail_ "rclone/mounts tree not tagged"; fi
    if grep -qE 'light = StatusLight\.of\(last\)' "$rm" && grep -qE 'light = if \(mounted \|\| isServing\) StatusLight\.State\.ON else StatusLight\.State\.OFF' "$rm" && grep -qE 'light = StatusLight\.of\(last\)' "$RC"; then pass_ "a remote's light is its last Test, a FUSE mount's light is the guest's own mount table: never a declared status"; else fail_ "rclone/mounts lights not honest"; fi
    if grep -qE 'StatusLightRow\(if \(c\.status == "ok"\) StatusLight\.State\.UNVERIFIABLE else StatusLight\.State\.OFF, c\.name\)' "$RC"; then pass_ "fleet connections (Volumes): ok → UNVERIFIABLE (the fleet's word), unreachable → OFF with its reason"; else fail_ "fleet connection lights wrong"; fi
    if grep -qE 'val backupRunning = MutableStateFlow<Set<String>>' "$stc" && grep -qE 'exec\.job\(BackupJobs\.script\(job\)' "$stc" && grep -qE 'val fuseBusy = MutableStateFlow<Set<String>>' "$stc" && grep -qE 'if \(working\) LinearProgressIndicator' "$rm"; then pass_ "a run's state is held by the coordinator across tab changes and drawn as a progress row (backup jobs and mount operations)"; else fail_ "run state not wired"; fi
    if grep -qE 'prefs\.setLastTest\("remote:\$name", r\.isSuccess\)' "$stc" && grep -qE 'prefs\.setLastTest\("mount:" \+ m\.id, ok\)' "$RCC"; then pass_ "the last Test per remote/mount is remembered"; else fail_ "test results not remembered"; fi
    if grep -qE '"\(declared\)"' "$rm" "$fsp" "$RC" "$CARDS"; then fail_ "a '(declared)' literal badge — use the string resource"; else pass_ "no '(declared)' literal badge"; fi
    if grep -qE 'Declarations\.(remotes|rcloneJobs|mirrorJobs|syncRules|unison)\b|Declarations\.connections' "$rm" "$fsp" "$stc"; then fail_ "the Fsync/Mounts pages read a DECLARED list: that is mock data (#1173)"; else pass_ "the Fsync and Mounts pages read no declared list: remotes, jobs, mounts and pairs are real state"; fi
    return $bad
}

echo "── G8 Rclone, FUSE and Backup pages, same language ──"
g8 "$RM" "$FSP" "$STC" || FAILURES=$((FAILURES + 1))

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
sed 's/if (!due) return@forEach/if (false) return@forEach/' "$WORKER" > "$TMP/worker.kt"
cmp -s "$WORKER" "$TMP/worker.kt" && fail "the worker mutation changed nothing (tester is stale)"
g6 "$TMP/worker.kt" && fail "G6 passed a worker that ignores the decision (tester is vacuous)" || pass "the per-repo decision dropped from the worker → G6 RED"
grep -v 'sync_resolve_conflicts' "$CARDS" > "$TMP/cards.kt"
cmp -s "$CARDS" "$TMP/cards.kt" && fail "the card mutation changed nothing (tester is stale)"
g2 "$TMP/cards.kt" && fail "G2 passed a card without Resolve (tester is vacuous)" || pass "Resolve dropped from the card → G2 RED"
sed 's/light = if (mounted || isServing) StatusLight.State.ON else StatusLight.State.OFF/light = StatusLight.State.ON/' "$RM" > "$TMP/mounts.kt"
cmp -s "$RM" "$TMP/mounts.kt" && fail "the FUSE light mutation changed nothing (tester is stale)"
g8 "$TMP/mounts.kt" "$FSP" "$STC" >/dev/null 2>&1 && fail "G8 passed a FUSE card that is always green (tester is vacuous)" || pass "a FUSE light not read from the guest's mount table → G8 RED"
printf '%s\n' 'val x = Declarations.remotes' >> "$TMP/mounts.kt"; sed -i 's/light = StatusLight.State.ON/light = if (mounted || isServing) StatusLight.State.ON else StatusLight.State.OFF/' "$TMP/mounts.kt"
g8 "$TMP/mounts.kt" "$FSP" "$STC" >/dev/null 2>&1 && fail "G8 passed a Mounts page that reads a declared list (tester is vacuous)" || pass "a declared list read by the Mounts page → G8 RED"
g2 "$CARDS" && g6 "$WORKER" && g8 "$RM" "$FSP" "$STC" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-sync-screen: all checks passed"; else echo "test-drive-sync-screen: $FAILURES check(s) FAILED"; exit 1; fi
