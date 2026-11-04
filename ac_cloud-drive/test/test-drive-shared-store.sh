#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #575 — cloud-drive is THE on-phone repo store: ONE declared root, every  ║
# ║ engine lands in it, no absolute /storage path anywhere, and the GitSync ║
# ║ flows (clone / open per declared repo, scheduled sync) are wired        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The audit (a0_docs/eng-specs/cloud-drive-parity-audit.md)
# found the store did not exist: the git manager cloned wherever the user typed,
# every engine's downloads sat in Android/data where no other app can read them,
# and cloud-code carried its own hand-written /storage/emulated/0/git root. All
# of that compiles. Gradle cannot see that a root is declared twice, or that a
# declared remote never reaches rclone.conf, or that the scheduler is never
# enqueued. This file can.
#
#   S1  build.json::storage.shared_root exists, is RELATIVE (no leading /, no
#       /storage/emulated), and app/build.gradle bakes it into BuildConfig.SHARED_ROOT
#       from that key — with the guard that fails the build on an absolute value.
#   S2  SharedStore resolves it at runtime from Environment.getExternalStorageDirectory()
#       and NOTHING in this app or the four engine libraries spells out
#       /storage/emulated/0 (the user id in it changes under a second profile).
#   S3  the git engine opens ON the store when the chrome names no target; #606/#608
#       the store's GIT FOLDER is declared (storage.git_subdir), baked, and the ONE
#       place a clone lands — SharedStore.repoDir goes through gitRoot(), so the
#       first-run seed and the Sync ▸ Git page's own Clone cannot disagree; each row's
#       Clone hands the DECLARED remote-mode URL (ui.sync.git.remote_modes, substituted
#       once) to that one clone path, while the seed keeps composing the manifest's
#       upstream URL through Declarations.cloneUrl; DriveActions.openEngine and the
#       activity extra carry it; the store is the hero of the Files Places sheet.
#   S4  scheduled sync: a WorkManager worker exists, is enqueued from MainActivity
#       with the interval and network rule build.json declares (baked, not literal),
#       syncs only opted-in repositories, and the library's model + Settings tab
#       carry that opt-in.
#   S5  declared rclone remotes reach rclone.conf through RcloneConfig.declare, the
#       library adds-without-overwriting (the JVM test says so by name), and a
#       job's relative local leg is resolved against the store.
#   S6  the data agrees: drive-git-repos.json declares the proof-case repository
#       (cloud-me-pvt-my-ai) and every remote with an `rclone` block carries
#       a type and NO secret-shaped key.
#
# OWN-SOURCE ONLY: everything read here is under this app or a module dir its
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BUILD_JSON="$APP/build.json"
# cloud-shared-store: the store (storage.shared_root, storage.git_subdir) is declared ONCE, by its owner.
STORE_JSON="$ROOT/ac_cloud-termux/build.json"
GRADLE="$APP/app/build.gradle"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
STORE="$SRC/SharedStore.kt"
MIGRATION="$SRC/StoreMigration.kt"
SEED="$SRC/StoreSeed.kt"
CARDS="$SRC/sync/GitReposScreen.kt"
DECL="$SRC/Declarations.kt"
PLACES="$SRC/files/Places.kt"
MAIN="$SRC/MainActivity.kt"
HOST="$SRC/EngineActivity.kt"
WORKER="$SRC/GitSyncWorker.kt"
COORD_KT="$SRC/sync/GitSyncCoordinator.kt"
GIT_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$BUILD_JSON" "$APP")"
RCLONE_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:rclone"]["dir"])))' "$BUILD_JSON" "$APP")"
GIT_SCREEN="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitSyncScreen.kt"
GIT_MODELS="$GIT_MOD/../git-core/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitModels.kt"
RCLONE_CONFIG="$RCLONE_MOD/src/main/java/com/diegonmarcos/cloudlib/rclone/RcloneConfig.kt"
RCLONE_TEST="$RCLONE_MOD/src/test/java/com/diegonmarcos/cloudlib/rclone/RcloneEngineTest.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

for required in "$BUILD_JSON" "$GRADLE" "$STORE" "$MIGRATION" "$SEED" "$CARDS" "$DECL" "$PLACES" "$MAIN" "$HOST" "$WORKER" "$COORD_KT" "$GIT_SCREEN" "$GIT_MODELS" "$RCLONE_CONFIG" "$RCLONE_TEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── S1 the ONE declaration ──"
SHARED_ROOT="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("storage") or {}).get("shared_root") or "")' "$STORE_JSON")"
if [ -n "$SHARED_ROOT" ]; then pass "ac_cloud-termux/build.json::storage.shared_root = '$SHARED_ROOT'"; else fail "ac_cloud-termux/build.json has no storage.shared_root"; fi
if python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("storage") or {}; sys.exit(1 if ("shared_root" in s or "git_subdir" in s) else 0)' "$BUILD_JSON"; then pass "this app's build.json keeps no second copy of the store declaration"; else fail "ac_cloud-drive/build.json::storage still declares shared_root/git_subdir (the store is declared once, in ac_cloud-termux/build.json)"; fi
case "$SHARED_ROOT" in
    /*|*storage/emulated*|*..*) fail "shared_root must be a relative folder under shared storage, not '$SHARED_ROOT'" ;;
    *) pass "shared_root is relative" ;;
esac
if grep -qF 'ac_cloud-termux/build.json' "$GRADLE" && grep -qE "storeOwnerStorage\.shared_root" "$GRADLE" && grep -qE 'buildConfigField "String",\s*"SHARED_ROOT",\s*"\\"\$\{sharedRoot\}\\""' "$GRADLE"; then pass "app/build.gradle bakes SHARED_ROOT from ac_cloud-termux/build.json::storage"; else fail "SHARED_ROOT is not baked from ac_cloud-termux/build.json::storage"; fi
if grep -qE "sharedRoot\.startsWith\('/'\)" "$GRADLE" && grep -qE "throw new GradleException\(\"ac_cloud-termux/build\.json::storage\.shared_root" "$GRADLE"; then pass "an absolute or missing shared_root fails the build"; else fail "build.gradle does not refuse an absolute/missing shared_root"; fi
INTERVAL="$(python3 -c 'import json,sys; print(((json.load(open(sys.argv[1])).get("storage") or {}).get("git_sync") or {}).get("interval_minutes") or "")' "$BUILD_JSON")"
if [ -n "$INTERVAL" ] && [ "$INTERVAL" -ge 15 ] 2>/dev/null; then pass "storage.git_sync.interval_minutes = $INTERVAL (WorkManager floor is 15)"; else fail "storage.git_sync.interval_minutes missing or below WorkManager's 15-minute floor ('$INTERVAL')"; fi
if grep -qE 'buildConfigField "long",\s*"GIT_SYNC_INTERVAL_MINUTES"' "$GRADLE" && grep -qE 'buildConfigField "boolean", "GIT_SYNC_REQUIRE_UNMETERED"' "$GRADLE"; then pass "the schedule is baked, not literal"; else fail "GIT_SYNC_INTERVAL_MINUTES / GIT_SYNC_REQUIRE_UNMETERED not baked"; fi

echo "── S2 the store resolves at runtime; no absolute /storage path anywhere ──"
if grep -qE "fun root\(\): File = StoreAccess\.root\(\)" "$STORE" && grep -qE "Environment\.getExternalStorageDirectory\(\), BuildConfig\.SHARED_ROOT" "$STORE"; then pass "SharedStore.root() = the store (libs:shared-store); the CloudDrive folder is only legacyRoot()"; else fail "SharedStore does not root at StoreAccess.root() (or lost legacyRoot)"; fi
if grep -qE "fun resolve\(path: String\): String" "$STORE" && grep -qE "path\.contains\(':'\) -> path" "$STORE" && grep -qE "migrateLegacy\(path" "$STORE"; then pass "SharedStore.resolve passes remote:path through, rewrites an old-location path, roots the rest"; else fail "SharedStore.resolve missing or does not implement the relative-leg + legacy rule"; fi
ABS="$(grep -rnE "/storage/emulated/0" "$APP/app/src" "$APP/data" "$GIT_MOD/src/main" "$RCLONE_MOD/src/main" \
      "$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:mounts"]["dir"])))' "$BUILD_JSON" "$APP")/src/main" \
      "$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:file-editor"]["dir"])))' "$BUILD_JSON" "$APP")/src/main" 2>/dev/null \
      | grep -vE "_doc|// |/\* |\* " || true)"
if [ -z "$ABS" ]; then pass "no /storage/emulated/0 literal in app or engine sources"; else fail "a device-absolute path is written down:"; printf '%s\n' "$ABS" | sed 's/^/        /'; fi

echo "── S3 the git manager lands in the store ──"
if grep -qE 'if \(engine == ENGINE_GIT\) SharedStore\.root\(\)\.absolutePath else null' "$HOST"; then pass "EngineActivity opens the git engine on the store when the chrome names no target"; else fail "EngineActivity does not default the git target to SharedStore.root()"; fi
if grep -qE 'val root = remember \{ SharedStore\.gitRoot\(\)\.absolutePath \}' "$CARDS"; then pass "the Sync ▸ Git page reads the store's git folder from SharedStore"; else fail "GitReposScreen does not read SharedStore.gitRoot()"; fi
# #606/#608 the git folder of the store is DECLARED (storage.git_subdir), baked, and the ONE
# place a clone lands: SharedStore.repoDir goes through it, so the first-run seed and the
# page's own Clone cannot disagree about where a repository is.
GIT_SUBDIR="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("storage") or {}).get("git_subdir") or "")' "$STORE_JSON")"
case "$GIT_SUBDIR" in
    "") fail "build.json::storage.git_subdir is missing — the page would clone into the store's root" ;;
    /*|*/*|*..*) fail "storage.git_subdir must be ONE relative folder name, not '$GIT_SUBDIR'" ;;
    *) pass "build.json::storage.git_subdir = '$GIT_SUBDIR' (one relative segment)" ;;
esac
if grep -qE 'buildConfigField "String", +"GIT_SUBDIR"' "$GRADLE" && grep -qE "gitSubdir\.startsWith\('/'\)" "$GRADLE" && grep -qE 'throw new GradleException\("ac_cloud-termux/build\.json::storage\.git_subdir' "$GRADLE"; then pass "GIT_SUBDIR is baked from the declaration and an absolute/nested value fails the build"; else fail "GIT_SUBDIR is not baked (or build.gradle does not refuse a bad git_subdir)"; fi
if grep -qE 'fun gitRoot\(\): File = File\(root\(\), BuildConfig\.GIT_SUBDIR\)' "$STORE" && grep -qE 'fun repoDir\(name: String\): File = File\(gitRoot\(\), name\)' "$STORE"; then pass "SharedStore.gitRoot() = root()/GIT_SUBDIR and every repoDir goes through it"; else fail "SharedStore does not resolve the git folder from BuildConfig.GIT_SUBDIR (or repoDir bypasses it)"; fi
if grep -qE '"shared_root" -> SharedStore\.root\(\)' "$PLACES" && grep -qE 'hero = p\.hero' "$PLACES"; then pass "the store is a Files place (the hero of the Places sheet)"; else fail "Places does not offer the store"; fi
if grep -qE 'fun openEngine\(engine: String, target: String, url: String = ""\)' "$SRC/DriveActions.kt" && grep -qE 'const val EXTRA_URL' "$HOST" && grep -qE 'GitSyncScreen\(target, onOpenFile, onClose, cloneUrl = cloneUrl\)' "$HOST"; then pass "the clone URL travels DriveActions → activity → GitSyncScreen(cloneUrl)"; else fail "the clone-url seam is broken somewhere between openEngine(engine, target, url), EXTRA_URL and GitSyncScreen(cloneUrl)"; fi
if grep -qE "cloneUrl: String\? = null" "$GIT_SCREEN" && grep -qE "prefillUrl" "$GIT_SCREEN"; then pass "libs:git-sync accepts a clone URL and prefills the Add dialog"; else fail "GitSyncScreen has no cloneUrl / prefillUrl"; fi
# The SEED's composition (#575/#603) is unchanged: the manifest's upstream instance, once.
if grep -qE 'fun cloneUrl\(repo: GitRepoDecl\): String\? = upstream\?\.let \{ "https://\$\{it\.host\}/\$\{repo\.githubOwner\}/\$\{repo\.name\}\.git" \}' "$DECL"; then pass "the seed manifest's clone URL is composed once by Declarations.cloneUrl"; else fail "Declarations.cloneUrl no longer composes the seeded repository's upstream URL"; fi
# #608 THE PAGE's composition: the DECLARED remote-mode URL shape (ui.sync.git.remote_modes),
# substituted once, and a clone that lands in SharedStore.repoDir — the same folder as the seed.
if grep -qE 'fun urlFor\(owner: String, name: String\): String = url\.replace\("\{owner\}", owner\)\.replace\("\{name\}", name\)' "$DECL" \
   && grep -qE 'fun cloneUrl\(name: String, mode: String = REMOTE_HTTPS\): String' "$DECL"; then
    pass "the page's clone URL is the declared remote-mode shape, substituted once"
else
    fail "Declarations.GitPageDecl does not compose the clone URL from the declared remote modes"
fi
# #669 a LISTED row clones the URL its own listing declared (gitea's items name the fleet's
# git host, GitHub's name github.com); only a row with NO listing item — the declared public
# set — falls back to the declared remote-mode template. Re-templating every row onto the
# declared owner/host is what sent gitea-listed repositories to github.com, silently.
if grep -qE 'url = \(if \(ssh\) listedSshUrl else listedUrl\)\.ifBlank \{ page\.cloneUrl\(name, mode\) \}' "$CARDS" && grep -qE 'coordinator\.cloneInto\(' "$CARDS"; then pass "every row's Clone hands the listing's URL (declared template only when nothing was listed) to the one clone path"; else fail "the Git page does not clone the listed URL through the coordinator"; fi
# #669 the clone's auth is assembled by the coordinator's ONE session-aware authFor
# (a fleet-session repository rides the in-memory cookie; everything else is the store's
# credentials.authFor inside it — test-drive-git-auth-chain C11 pins that wiring).
if grep -qE 'val dir = SharedStore\.repoDir\(name\)' "$COORD_KT" && grep -qE 'GitConnect\.clone\(ctx, url, dir, auth = authFor\(managed\)\)' "$COORD_KT" && grep -qE 'registry\.upsert\(managed\)' "$COORD_KT"; then pass "the page's clone lands in the store's git folder, through the engine, into the ONE registry"; else fail "the page's clone does not go through SharedStore.repoDir + GitEngine.clone + the registry"; fi

echo "── S3b one-time migration of stray root clones into the git folder ──"
# #606 a phone updated across the git_subdir change keeps its clones at the store ROOT; the
# first-run seed would re-clone them into the new git folder as a second copy. StoreMigration
# moves them, manifest-driven (no hardcoded repository name) and idempotent (a taken destination
# is left alone), BEFORE the seed loop runs.
if grep -qE 'fun migrate\(root: File, gitSubdir: String, repoNames: Set<String>\): List<Move>' "$MIGRATION" \
   && grep -qE 'File\(root, gitSubdir\)' "$MIGRATION" \
   && grep -qE 'fun isComplete\(dir: File\)' "$MIGRATION" \
   && grep -qE 'if \(destScore == COMPLETE\)' "$MIGRATION"; then
    # #629 the rule changed from POSITION to COMPLETENESS: the device carried both copies of a
    # repository, each partly complete, in either direction. See test-drive-seed-and-migration.sh.
    pass "StoreMigration.migrate relocates root strays into root/gitSubdir and deletes a copy only when the survivor is COMPLETE"
else
    fail "StoreMigration.migrate is missing, does not resolve the git folder, or can delete a copy that is not redundant"
fi
if grep -qE 'it\.name != gitSubdir' "$MIGRATION" && grep -qE 'it\.name in repoNames' "$MIGRATION"; then pass "the migration skips the git folder itself and acts only on declared repository names"; else fail "the migration is not manifest-driven / does not skip the git_subdir folder"; fi
if grep -qE 'StoreMigration\.migrate\(SharedStore\.root\(\), BuildConfig\.GIT_SUBDIR, family\.repos\.map \{ it\.name \}\.toSet\(\)\)' "$SEED" && awk '/StoreMigration\.migrate/{m=NR} /val declared = Declarations\.seedRepos/{s=NR} END{exit !(m && s && m < s)}' "$SEED"; then pass "StoreSeedWorker runs the migration (all declared names) BEFORE the seed loop"; else fail "StoreSeedWorker does not run StoreMigration before the seed loop"; fi

echo "── S4 scheduled sync ──"
if grep -qE "class GitSyncWorker\(context: Context, params: WorkerParameters\) : Worker\(" "$WORKER"; then pass "GitSyncWorker is a WorkManager Worker"; else fail "GitSyncWorker is not a Worker"; fi
# #1289 the tick is AutoSyncPlan.tickMinutes over the baked base and the opted-in repositories' own periods (floor 15).
if grep -qE "val tick = com\.diegonmarcos\.clouddrive\.sync\.AutoSyncPlan\.tickMinutes\(BuildConfig\.GIT_SYNC_INTERVAL_MINUTES, opted\)" "$WORKER" && grep -qE "PeriodicWorkRequestBuilder<GitSyncWorker>\(tick, TimeUnit\.MINUTES\)" "$WORKER" && grep -qE "BuildConfig\.GIT_SYNC_REQUIRE_UNMETERED" "$WORKER" && grep -qE "ExistingPeriodicWorkPolicy\.UPDATE" "$WORKER"; then pass "the period and the network rule come from the baked declaration; UPDATE policy"; else fail "the worker's schedule is not the baked declaration (or not UPDATE)"; fi
if grep -qE "^\s*GitSyncWorker\.schedule\(this\)" "$MAIN"; then pass "MainActivity enqueues the scheduler"; else fail "nothing enqueues GitSyncWorker (a commented-out call does not count)"; fi
if grep -qE "\.filter \{ it\.autoSync \}" "$WORKER" && grep -qE "\.sync\(" "$WORKER" && grep -qE "credentials\.authFor\(repo\)" "$WORKER"; then pass "the worker syncs opted-in repositories with their stored credential"; else fail "the worker does not filter on autoSync / call sync() with authFor()"; fi
if grep -qE "val autoSync: Boolean = false" "$GIT_MODELS" && grep -qE "Switch\(checked = autoSync" "$GIT_SCREEN" && grep -qE "autoSync = autoSync," "$GIT_SCREEN"; then pass "ManagedRepo.autoSync exists, defaults off, and the Settings tab saves it"; else fail "the per-repo opt-in is missing from the model or the Settings tab"; fi
if grep -qE "androidx\.work:work-runtime" "$GRADLE"; then pass "WorkManager linked in the app"; else fail "app/build.gradle does not link androidx.work"; fi
if grep -qE "const val REGISTRY_FILE = \"git-sync/repos\.json\"" "$WORKER" && grep -qE "RepoRegistry\(File\(context\.filesDir, \"git-sync/repos\.json\"\)\)" "$GIT_SCREEN"; then pass "worker and screen read the SAME registry file"; else fail "the worker's registry path differs from the screen's"; fi

echo "── S5 declared rclone remotes and relative legs ──"
if grep -qE "fun declare\(file: File, remotes: List<RcloneRemote>\): List<RcloneRemote>" "$RCLONE_CONFIG" && grep -qE "it\.name !in have" "$RCLONE_CONFIG"; then pass "RcloneConfig.declare adds missing remotes only"; else fail "RcloneConfig.declare missing or overwrites"; fi
if grep -qE "fun configDeclareAddsMissingRemotesAndNeverTouchesExistingOnes" "$RCLONE_TEST"; then pass "the JVM suite proves declare() keeps the user's key"; else fail "no JVM test for RcloneConfig.declare"; fi
if grep -qE "BuildConfig\.RCLONE_REMOTES_B64" "$HOST" && grep -qE "RcloneConfig\.declare\(RcloneRunner\(context\)\.configFile, declaredRemotes\)" "$HOST"; then pass "EngineActivity declares the baked remotes into the runner's rclone.conf"; else fail "declared remotes never reach rclone.conf"; fi
if grep -qE 'source = SharedStore\.resolve\(j\.optString\("source"\)\), destination = SharedStore\.resolve\(j\.optString\("destination"\)\)' "$HOST"; then pass "a job's relative local leg resolves against the store"; else fail "declared job paths are not resolved through SharedStore"; fi

echo "── S6 the data agrees ──"
python3 - "$APP/data/drive-git-repos.json" "$APP/data/drive-remotes.json" <<'PYTHON' && pass "drive-git-repos.json declares the proof-case repo; every rclone block has a type and no secret" || fail "data disagrees (see above)"
import json, sys
repos = json.load(open(sys.argv[1]))
bad = 0
names = [r["name"] for r in repos["repos"]]
if "cloud-me-pvt-my-ai" not in names:
    print("    drive-git-repos.json does not declare cloud-me-pvt-my-ai"); bad += 1
if not any(i.get("kind") == "upstream" and i.get("host") for i in repos["instances"]):
    print("    no upstream instance with a host — the page cannot compose a clone URL"); bad += 1
remotes = json.load(open(sys.argv[2]))["remotes"]
SECRET = ("pass", "secret", "token", "key_id", "access_key", "refresh")
with_block = 0
for r in remotes:
    spec = r.get("rclone")
    if not spec: continue
    with_block += 1
    if not spec.get("type"):
        print("    remote %s: rclone block without type" % r["name"]); bad += 1
    for k, v in (spec.get("options") or {}).items():
        if any(s in k.lower() for s in SECRET) and str(v).strip():
            print("    remote %s: option %s looks like a credential" % (r["name"], k)); bad += 1
if with_block == 0:
    print("    no remote carries an rclone block — nothing would be declared"); bad += 1
sys.exit(1 if bad else 0)
PYTHON

# ── #639 — the store is unreachable without All-Files-Access, so the app must
#           TAKE the user to the toggle, never describe where it lives. The
#           per-package screen first; the fleet-wide list only when no activity
#           claims the per-package one. A swallowed failure is the defect: the
#           user is left with an empty Files tab and no way given to fix it.
echo
echo "── #639 the all-files-access ask is a deep link, per-package first ──"
MAIN="$APP/app/src/main/java/com/diegonmarcos/clouddrive/MainActivity.kt"
deep_linked() {
    grep -q 'ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION' "$1" \
        && grep -q 'onFailure' "$1" \
        && grep -q 'ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)' "$1"
}
if deep_linked "$MAIN"; then
    pass "requestStorageAccess() opens this package's All-Files-Access screen and falls back to the fleet-wide list only if that is refused"
else
    fail "requestStorageAccess() has no per-package deep link with a fleet-wide fallback — a refused intent would be swallowed and the user told nothing (#639)"
fi
# MUTATION PROOF: delete the fallback and the assertion above must die. Without
# this, the grep could be satisfied by the per-package line alone and a silent
# runCatching would pass as a working ask.
MUT639="$(mktemp)"
grep -v 'ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)' "$MAIN" > "$MUT639"
if deep_linked "$MUT639"; then
    fail "MUTATION SURVIVED: removing the fleet-wide fallback left the deep-link assertion green — it proves nothing"
else
    pass "mutation proved: removing the fleet-wide fallback makes the assertion above go red"
fi
rm -f "$MUT639"
if grep -qE 'Settings *(▸|→|>)' "$APP/app/src/main/res/values/strings.xml"; then
    fail "a user-facing string walks the user through a Settings tree the app can deep-link to instead (#639)"
else
    pass "no 'Settings ▸ …' breadcrumb in cloud-drive's user-facing strings (#639)"
fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-shared-store: all checks passed"; else echo "test-drive-shared-store: $FAILURES check(s) FAILED"; exit 1; fi
