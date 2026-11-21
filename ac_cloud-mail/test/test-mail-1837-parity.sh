#!/usr/bin/env bash
# #1837 Cloud Mail redesign, phase 1 (the Rust mail core under the existing UI): NOTHING the app does today is lost (rule 16).
#
# test/mail-1837-feature-freeze.json is the app's feature list FROZEN from the tree before the redesign (written from git,
# `--write <rev>`, never from the tree under test): every screen, NavHost route, repository action, view-model action,
# setting, folder role, account type and auth type, background worker, Configs page, navigation section and UI string.
# This tester re-extracts the same lists from the current tree and fails on any frozen item that is gone. New items are
# allowed (organisation only adds); a removal must be an owner decision, recorded by re-freezing in the same commit.
#
#   P1  the freeze exists and is not empty in any category (a vacuous freeze proves nothing)
#   P2  every frozen item of every category is still in the tree
#   P3  the #1837 wiring: the JMAP Inbox syncs through the Rust core with the Kotlin sync kept as its fallback, IMAP keeps
#       the old path (said so in code), the core is driven by a periodic + on-open WorkManager job, the list rows are
#       projected (never blanked: removals only of what the store removed), and the ship workflow runs the Rust tests
#
#   bash test/test-mail-1837-parity.sh            check the tree against the freeze
#   bash test/test-mail-1837-parity.sh --write REV re-freeze from git revision REV (an owner-approved removal only)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
FREEZE="$APP/test/mail-1837-feature-freeze.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { if grep -q -- "$2" "$1"; then ok "$3"; else bad "$3 ($1)"; fi; }

# The extractor: one function per category, over a file reader that is either the working tree or a git revision.
EXTRACT=$(cat <<'PY'
import json, os, re, subprocess, sys

def reader(root, rev):
    if rev is None:
        def files(prefix):
            out = []
            for d, _, fs in os.walk(os.path.join(root, prefix)):
                if "/build/" in d + "/" or "/.gradle" in d:
                    continue
                out += [os.path.relpath(os.path.join(d, f), root) for f in fs]
            return sorted(out)
        def read(p):
            with open(os.path.join(root, p), encoding="utf-8", errors="replace") as f:
                return f.read()
    else:
        def files(prefix):
            r = subprocess.run(["git", "-C", root, "ls-tree", "-r", "--name-only", rev, prefix], capture_output=True, text=True, check=True)
            return sorted(r.stdout.split())
        def read(p):
            r = subprocess.run(["git", "-C", root, "show", f"{rev}:{p}"], capture_output=True, check=True)
            return r.stdout.decode("utf-8", "replace")
    return files, read

def code(text):
    # comments out, so a feature that survives only in a comment does not count
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in text.split("\n") if not l.strip().startswith("//"))

def public_funs(text, cls):
    m = re.search(r"\nclass %s\b" % cls, text)
    if not m:
        return set()
    body = text[m.start():]
    return set(re.findall(r"\n    (?:override |open |operator )?(?:suspend )?fun (?:<[^>]+> )?(?:[A-Za-z_.]+\.)?([a-zA-Z_][A-Za-z0-9_]*)\(", body))

def extract(root, rev):
    files, read = reader(root, rev)
    A = "ac_cloud-mail"
    kt = [p for p in files(A + "/app/src/main") if p.endswith(".kt")]
    data_kt = [p for p in files(A + "/core/data/src/main") if p.endswith(".kt")]
    out = {}
    src = {p: code(read(p)) for p in kt}
    dsrc = {p: code(read(p)) for p in data_kt}
    allsrc = "\n".join(list(src.values()) + list(dsrc.values()))
    out["screens"] = sorted({n for t in src.values() for n in re.findall(r"@Composable\s+(?:internal |private )?fun ([A-Z][A-Za-z0-9]*Screen)\(", t)})
    out["routes"] = sorted({r for t in src.values() for r in re.findall(r'composable\(\s*(?:route\s*=\s*)?"([^"]+)"', t)})
    repo = dsrc.get(A + "/core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt", "")
    out["repository_actions"] = sorted(public_funs(repo, "MailRepository") - {n for n in re.findall(r"\n    private (?:suspend )?fun ([A-Za-z0-9_]+)\(", repo)})
    vms = {}
    for p, t in src.items():
        for cls in re.findall(r"\nclass ([A-Z][A-Za-z0-9]*ViewModel)\b", t):
            priv = set(re.findall(r"\n    private (?:suspend )?fun ([A-Za-z0-9_]+)\(", t))
            for f in public_funs(t, cls) - priv:
                vms[f"{cls}.{f}"] = True
    out["viewmodel_actions"] = sorted(vms)
    sr = dsrc.get(A + "/core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt", "")
    out["settings"] = sorted(set(re.findall(r"\n    val ([a-zA-Z][A-Za-z0-9]*)\s*:\s*Flow<", sr)) | set(re.findall(r"\n    (?:suspend )?fun (set[A-Z][A-Za-z0-9]*)\(", sr)))
    out["settings_keys"] = sorted(set(re.findall(r'(?:booleanPreferencesKey|stringPreferencesKey|intPreferencesKey|longPreferencesKey|floatPreferencesKey|stringSetPreferencesKey)\("([^"]+)"\)', allsrc)))
    out["folder_roles"] = sorted(set(re.findall(r'role\s*==\s*"([a-z]+)"', allsrc)) | set(re.findall(r'"(inbox|archive|drafts|sent|junk|trash|important|all|flagged|scheduled|snoozed|outbox)"\s*->', allsrc)))
    proto = "\n".join(t for p, t in dsrc.items() if p.endswith("/account/AccountStore.kt") or p.endswith("/account/MailProtocol.kt") or p.endswith("/account/AuthType.kt"))
    enums = {}
    for name, body in re.findall(r"enum class (MailProtocol|AuthType)\b[^{]*\{([^}]*)\}", allsrc + "\n" + proto):
        for e in re.findall(r"\b([A-Z][A-Z0-9_]+)\b", body.split(";")[0]):
            enums[f"{name}.{e}"] = True
    out["account_types"] = sorted(enums)
    out["workers"] = sorted({c for t in src.values() for c in re.findall(r"class ([A-Z][A-Za-z0-9]*)\([^)]*\)\s*:\s*(?:CoroutineWorker|Worker|ListenableWorker)\(", t, flags=re.S)})
    bj = json.loads(read(A + "/build.json"))
    out["configs_pages"] = sorted(k for k in bj.get("configs_pages", {}) if not k.startswith("_"))
    out["nav_sections"] = sorted({s["id"] + "/" + p["id"] for s in bj.get("ui", {}).get("sections", []) for p in s.get("pages", [])})
    strings = set()
    for p in files(A + "/app/src/main/res"):
        if re.search(r"/values/strings[^/]*\.xml$", p):
            strings |= set(re.findall(r'<string name="([^"]+)"', read(p)))
            strings |= {"plurals:" + n for n in re.findall(r'<plurals name="([^"]+)"', read(p))}
    out["ui_strings"] = sorted(strings)
    # The reader's own machinery (phase 2 changes WHAT draws the page, never what the reader can do): every pass of the old
    # document builder must still be in the tree, whichever document the core hands over.
    reader_names = ["buildHtmlDocument", "buildPrintDocument", "readerBody", "sanitiseReceivedHtml", "embedInlineImages",
                    "neutraliseDarkModeStyles", "markDeceptiveLinks", "foldTopLevelQuotes", "wrapEmoji", "linkifyEscaped",
                    "reflowFormatFlowed", "einkReaderCss", "showRemoteImages", "applyFitSettings", "FIT_CSS", "CSP_META",
                    "BlockingWebViewClient", "bodySpacerCss", "EmailWebView"]
    out["reader_features"] = sorted(n for n in reader_names if re.search(r"\b" + n + r"\b", allsrc))
    return out

if __name__ == "__main__":
    mode, root = sys.argv[1], sys.argv[2]
    if mode == "write":
        rev = sys.argv[3]
        sha = subprocess.run(["git", "-C", root, "rev-parse", rev], capture_output=True, text=True, check=True).stdout.strip()
        data = {"_doc": "#1837 Cloud Mail's feature list frozen before the phase-1 redesign (the Rust mail core under the existing UI), "
                        "read from git revision `frozen_from` by test/test-mail-1837-parity.sh --write. That tester fails on any item "
                        "missing from the tree (rule 16). Re-freeze only for an owner-approved removal.",
                "frozen_from": sha, **extract(root, rev)}
        with open(sys.argv[4], "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
            f.write("\n")
        print("froze", {k: len(v) for k, v in data.items() if isinstance(v, list)})
    else:
        frozen = json.load(open(sys.argv[3], encoding="utf-8"))
        now = extract(root, None)
        empty = [k for k, v in frozen.items() if isinstance(v, list) and not v]
        print("P1 " + ("EMPTY " + ",".join(empty) if empty else "ok " + " ".join(f"{k}={len(v)}" for k, v in frozen.items() if isinstance(v, list))))
        for k, v in frozen.items():
            if not isinstance(v, list):
                continue
            gone = sorted(set(v) - set(now.get(k, [])))
            print(f"P2 {k} " + ("ok %d kept, %d now" % (len(v), len(now.get(k, []))) if not gone else "LOST " + ", ".join(gone)))
PY
)

if [ "${1:-}" = "--write" ]; then
    [ -n "${2:-}" ] || { echo "usage: $0 --write <git-rev>"; exit 2; }
    python3 -c "$EXTRACT" write "$ROOT" "$2" "$FREEZE"
    exit $?
fi

echo "== P1/P2: the frozen feature list is all still here =="
if [ ! -s "$FREEZE" ]; then
    bad "no freeze at $FREEZE"
else
    out="$(python3 -c "$EXTRACT" check "$ROOT" "$FREEZE")"
    rc=$?
    [ "$rc" = 0 ] || bad "the extractor failed (exit $rc)"
    while IFS= read -r line; do
        case "$line" in
            "P1 ok "*) ok "${line#P1 ok }" ;;
            "P1 "*)    bad "a frozen category is empty: ${line#P1 }" ;;
            "P2 "*" ok "*) ok "${line#P2 }" ;;
            "P2 "*)    bad "${line#P2 }" ;;
        esac
    done <<<"$out"
    grep -q '^P2 ' <<<"$out" || bad "the extractor checked no category"
fi

echo "== P3: the #1837 wiring =="
D="$APP/core/data/src/main/kotlin/app/sterna/core/data"
R="$D/mail/MailRepository.kt"
E="$D/mail/RustMailCore.kt"
W="$APP/app/src/main/kotlin/app/sterna/mail/MailCoreSyncWorker.kt"
has "$R" 'val pass = coreInbox(session, accountId, auth, mailboxId, localAccountId, RustMailEngine.INTERACTIVE_BUDGET_MS)' "the JMAP Inbox syncs through the Rust core first"
has "$R" 'if (pass != null && (isInbox || pass.complete)) return pass.sync' "the Inbox answers from the core's pass (as before)"
has "$R" 'JmapBacklogPlan.next(serverIds, cached)' "the Kotlin Inbox backlog is kept as the core's fallback"
has "$R" 'if (credentials.protocol == MailProtocol.IMAP) return null' "IMAP accounts are not driven by the core in phase 1"
has "$E" 'IMAP accounts keep the old path in phase 1' "the code says IMAP keeps the old path in phase 1"
has "$E" 'e.toEntity(localAccountId, filedUnder)' "list rows are projected through the shared toEntity mapping"
has "$D/mail/FolderFiling.kt" 'removed.filter { it in inRoom && it !in keptIds && !isProtected(it) }' "a projection removes only what the store removed (never blanks the list)"
has "$E" 'if (count.complete) {' "rows the store lacks are removed only after the store's walk is complete"
has "$W" 'PeriodicWorkRequestBuilder<MailCoreSyncWorker>' "a periodic WorkManager job drives the core"
has "$APP/app/src/main/kotlin/app/sterna/MainActivity.kt" 'MailCoreSyncWorker.runNow(this)' "the app's open runs a core pass"
has "$APP/app/src/main/kotlin/app/sterna/SternaApplication.kt" 'MailCoreSyncWorker.ensureScheduled(this)' "the periodic core pass is scheduled at start"
has "$D/DataFactory.kt" 'RustMailEngine.create(appContext)' "the app builds the repository with the core"
has "$APP/build.json" '"libs:mail-core-rs"' "build.json links libs:mail-core-rs"
SHIP="$ROOT/1_cicd/src/cicd/ship-cloud-mail.yml"
has "$SHIP" 'working-directory: ab_cloud-libs-shared/libs/mail-core-rs' "the ship workflow runs the Rust core's tests"
has "$SHIP" 'libmail_core' "the ship workflow checks libmail_core.so is in the APK"
has "$SHIP" 'MAIL_CORE_RS_REQUIRED: "1"' "CI refuses an APK without the core"

echo "== P4: the #1837 phase 2 reader (Rust MIME/charset/HTML core, Gmail-like document, images on by default, offline open) =="
CORE="$ROOT/ab_cloud-libs-shared/libs/mail-core-rs"
MV="$APP/app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt"
MS="$APP/app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
SR="$D/settings/SettingsRepository.kt"
has "$CORE/Cargo.toml" 'mail-parser' "the core parses MIME with mail-parser (every charset, QP, base64, nested multipart)"
has "$CORE/Cargo.toml" 'ammonia' "the core sanitises HTML with ammonia"
has "$CORE/src/reader.rs" 'pub fn parse(raw: &\[u8\])' "the core has the raw-message parser"
has "$CORE/src/reader.rs" 'pub fn wrap(kind: &str, fragment: &str, o: &Opts)' "the core builds the Gmail-like reader document"
has "$CORE/src/reader.rs" 'font:14px/1.5 Roboto' "the stylesheet is Gmail's (Roboto 14 px)"
has "$CORE/src/reader.rs" 'pre.ascii' "plain-text ASCII tables keep their alignment"
has "$CORE/src/sync.rs" 'fn prefetch_raw' "the prefetch stores the raw message and its parsed document (open is offline)"
has "$CORE/src/tests.rs" 'budget_message_open_from_the_store_under_100_ms' "the open-from-store budget (100 ms) is asserted"
has "$CORE/src/reader.rs" 'latin1_body_decodes_without_replacement_characters' "Latin-1 fixture (Espana, no replacement character)"
has "$CORE/src/reader.rs" 'windows_1252_smart_quotes_and_dashes' "Windows-1252 smart quotes fixture"
has "$CORE/src/reader.rs" 'nested_multipart_with_an_inline_cid_image' "nested multipart + inline cid image fixture"
has "$CORE/src/reader.rs" 'a_marketing_email_is_sanitised_but_keeps_its_look' "marketing HTML fixture (sanitised, look kept)"
has "$CORE/src/reader.rs" 'pub fn sanitize_css' "mail <style> blocks are kept (sanitised and scoped under the message container), as Gmail keeps them"
has "$CORE/src/reader.rs" 'a_newsletter_whose_layout_lives_in_a_style_block_keeps_it_scoped' "newsletter <style> fixture (rules, @media, scoping)"
has "$CORE/src/reader.rs" 'hostile_css_is_stripped' "hostile CSS fixture (@import, @font-face, remote url, expression, behavior, position)"
has "$MV" 'repo.coreReaderStored' "the reader reads the core's store first (no network)"
has "$MS" 'buildCoreDocument(coreDoc' "the reader screen draws the core's document first"
has "$MS" 'fromCore ?: buildHtmlDocument(' "the old document is the fallback"
has "$MS" 'the core document could not be built, old reader path' "the fallback is logged (tag MailCore)"
has "$SR" 'const val REMOTE_IMAGES_DEFAULT = true' "remote image download defaults to on"
has "$SR" 'fun migrateRemoteImagesToDefaultOn' "a stored off flips to on once (migration)"
has "$SR" 'val imageAllowlist' "the sender image allowlist is kept (rule 16)"
has "$MS" 'showRemoteImages(manualShow, senderAllowed, autoLoad)' "the setting, the per-message override and the allowlist all decide the images"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/settings/RemoteImagesDefaultTest.kt" 'a stored off flips to on once' "the Kotlin test of the default and the migration exists"

echo "== P5: the #1837 phase 3 every folder from the core's store, one filing per message, the offline proof, the lock =="
FF="$D/mail/FolderFiling.kt"
has "$R" 'if (!isInbox) {' "a folder the core has not walked whole is also window-synced (the old sync stays as the fallback)"
has "$R" 'private class CoreMailboxPass(val sync: MailboxSync, val complete: Boolean)' "the core pass reports whether the store walked the whole mailbox"
has "$R" 'for (box in listOf(inbox) + mailboxes.filter { it.id != inbox.id })' "the background pass projects every JMAP folder, not only the Inbox"
has "$R" 'inboxServerIds = emptySet()' "the Kotlin window sync of a folder is kept (rule 16)"
has "$E" 'FolderFiling.plan(mailboxId, page.emails, page.moved, page.removed, page.primary, inRoom, isProtected)' "every mailbox's rows are written through the shared filing rule"
has "$E" 'count.primaryLocal == roomIds.size' "a mailbox owns the rows the filing rule gives it, not every member"
has "$E" 'roomIds.any { it in inStore && it !in filedHere }' "a row filed under the wrong mailbox by an old path is re-fed to its primary"
has "$E" 'if (count.complete) {' "folder rows the store lacks are removed only after the store's walk is complete (kept)"
has "$FF" 'primary\[it.id\] ?: mailboxId' "a message is filed under the core's primary mailbox from whichever folder is synced"
has "$FF" 'primary.getValue(it.id)' "a membership that ended on a message still held elsewhere re-files it instead of deleting it"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/mail/FolderFilingTest.kt" 'in the inbox and a label is filed under the core' "the Kotlin test of the folder filing exists"
has "$CORE/src/query.rs" 'pub fn folder_rank' "the filing rule is a pure function of the memberships (Rust)"
has "$CORE/src/query.rs" 'pub fn primary_mailbox' "the primary mailbox of a message is answered by the store"
has "$CORE/src/tests.rs" 'the_filing_rule_is_a_pure_function_of_the_memberships' "the Rust test of the filing rule exists"
has "$CORE/src/tests.rs" 'a_message_in_the_inbox_and_a_label_is_filed_once_and_a_move_refiles_it' "the Rust test of the feed's filing and re-filing exists"
OFF="$ROOT/1_cicd/src/cicd/test-mail-core-offline-emulator.yml"
has "$OFF" 'fake-jmap-server.py' "the offline emulator workflow runs the fake JMAP server"
has "$OFF" 'MAIL_CORE_RS_REQUIRED: "1"' "the offline proof refuses a build without the core"
has "$OFF" 'name: mail-core-offline-evidence' "the offline proof uploads its screenshots as an artifact"
has "$APP/test/walk-mail-core-offline.sh" 'adb shell cmd connectivity airplane-mode enable' "the proof cuts the network before the relaunch"
has "$APP/test/walk-mail-core-offline.sh" 'the offline reader does not show España' "the proof asserts the Latin-1 reader"
has "$APP/test/fake-jmap-server.py" 'Bienvenidos a Espa' "the fake server seeds the Latin-1 España message"
has "$APP/test/fake-jmap-server.py" 'Weekly Digest 42' "the fake server seeds the HTML newsletter"
has "$APP/app/src/testApp/res/xml/network_security_config.xml" '10.0.2.2' "only the test variant allows cleartext to the emulator's host"
if grep -q '10.0.2.2' "$APP/app/src/main/res/xml/network_security_config.xml"; then bad "the production network security config must not allow 10.0.2.2"; else ok "the production network security config does not allow 10.0.2.2"; fi
has "$ROOT/1_cicd/src/cicd/mail-core-cargo-lock.yml" 'cargo generate-lockfile' "the mail core's Cargo.lock is generated in CI"
if python3 -I "$APP/test/fake-jmap-server.py" --selftest >/dev/null 2>&1; then ok "the fake JMAP server passes its own selftest"; else bad "the fake JMAP server selftest fails"; fi
if python3 -I "$APP/test/mail-offline-ui.py" selftest >/dev/null 2>&1; then ok "the dump helper passes its own selftest"; else bad "the dump helper selftest fails"; fi

echo "== P6: the #1837 membership table (a label lists a message also in the Inbox; unread counts per membership; one core sync per refresh) =="
DB="$D/db"
has "$DB/EmailMailboxEntity.kt" 'tableName = "email_mailboxes"' "the membership entity exists"
has "$DB/SternaDatabase.kt" 'SCHEMA_VERSION = 33' "the schema version moved to 33"
has "$DB/SternaDatabase.kt" 'MIGRATION_32_33' "the 32 to 33 migration is registered"
has "$DB/Migrations.kt" 'CREATE TABLE IF NOT EXISTS `email_mailboxes`' "the migration creates the membership table"
has "$DB/EmailDao.kt" 'suspend fun applyProjection(' "the core's feed writes rows and memberships in one transaction"
has "$DB/EmailDao.kt" 'fun observeExtraScopes()' "the folders that list extra messages are observed"
has "$DB/EmailDao.kt" 'JOIN emails e ON e.accountId = m.accountId AND e.id = m.emailId WHERE m.mailboxId <> e.mailboxId' "the unread badges count memberships"
has "$R" 'memberPairs: Set<Int> = emptySet()' "the folder scope takes the membership term only for the folders that need it (legacy plan kept)"
has "$R" 'extraScopes().flatMapLatest' "the pagers follow the folders that list extra messages"
has "$R" 'coalesceMs = RustMailEngine.COALESCE_MS' "the per-folder core syncs of one refresh are coalesced"
has "$E" 'emailDao.applyProjection(' "the projection writes every fed message's mailbox set"
has "$E" 'coalesceMs: Long = 0L' "the core sync can answer a folder sync from a pass that just completed"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/db/FolderMembershipMigrationSqlTest.kt" 'the table has the columns, NOT NULL and primary key order Room generates' "the migration test exists"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/mail/FolderMembershipListSqlTest.kt" 'with the membership term the label lists the message that is also in the Inbox' "the list SQL test exists"
has "$APP/app/src/main/kotlin/app/sterna/ui/inbox/RowsGuard.kt" 'memberFolder' "the rows guard knows a label holds rows filed under another mailbox"
has "$APP/app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt" 'viewModel.extraFolders' "the screen tells the guard which folders list extra messages"
has "$APP/test/walk-mail-core-offline.sh" 'must list its own message AND the Inbox message that is also in the label' "the emulator proof asserts the label lists the Inbox message too"

echo "== P7: the #1837 polish (offline append ends quietly, the breadcrumb names the folder, one filing for the window sync, no orphan memberships) =="
UI="$APP/app/src/main/kotlin/app/sterna/ui"
has "$D/mail/OfflineAppend.kt" 'internal fun offlineAppendEnds(online: Boolean, t: Throwable, folderHeld: Boolean): Boolean' "the offline append rule is a pure function"
has "$R" 'if (offlineAppendEnds(isOnline(), t,' "the pager's append ends quietly offline instead of Error + Retry"
has "$R" 'MediatorResult.Error(t)' "a real online failure still ends the append in an error (Retry kept)"
has "$APP/app/src/main/kotlin/app/sterna/SternaApplication.kt" 'mailRepository.isOnline = {' "the app tells the repository whether the phone has a network"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/mail/OfflineAppendTest.kt" 'offline with the folder held ends the append quietly' "the offline append test exists"
has "$UI/inbox/BreadcrumbFolder.kt" 'internal fun breadcrumbFolder(' "the breadcrumb's folder crumb is a pure function"
has "$UI/inbox/InboxScreen.kt" 'BreadcrumbLeaf(breadcrumbFolder(' "the open folder is the last crumb of the top bar"
has "$UI/../../../../../test/kotlin/app/sterna/ui/inbox/BreadcrumbFolderTest.kt" 'a label names itself in the path' "the breadcrumb test exists"
has "$FF" 'fun windowFiling(' "the window sync files by the core's primary when the core knows the message"
has "$R" 'FolderFiling.windowFiling(mailboxId, emails, primary)' "the Kotlin window sync writes through the shared filing rule"
has "$R" 'emailDao.applyDelta(localAccountId, toDelete, windowEntities(localAccountId, mailboxId, fetched))' "the delta window sync files by the shared rule"
has "$E" 'suspend fun primaries(' "the core is asked for the filing of the messages the window sync fetched"
has "$CORE/src/jni_api.rs" '"primaries" =>' "the core answers the filing of a list of messages"
has "$CORE/src/tests.rs" 'the app.s window sync asks the same filing' "the Rust test of the primaries op exists"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/mail/FolderFilingTest.kt" 'the window sync files a message by the core' "the window filing test exists"
has "$DB/EmailDao.kt" 'suspend fun deleteOrphanMemberships(accountId: String)' "orphan memberships are swept"
has "$DB/EmailDao.kt" 'deleteMembershipsForAccount(accountId)' "an account's memberships go with the account"
has "$DB/EmailDao.kt" 'deleteAllMemberships()' "delete-all takes the memberships"
has "$APP/core/data/src/test/kotlin/app/sterna/core/data/mail/MembershipOrphanSqlTest.kt" 'an account.s memberships go with the account' "the orphan membership test exists"
has "$APP/test/walk-mail-core-offline.sh" 'offline Receipts ($d) shows the load-more error footer' "the proof asserts no load-more error offline"
has "$APP/test/walk-mail-core-offline.sh" 'does not name the open Receipts label' "the proof asserts the breadcrumb names the label"
if python3 -I "$APP/test/mail-offline-ui.py" selftest >/dev/null 2>&1; then ok "the dump helper (with lacks) passes its selftest"; else bad "the dump helper selftest fails"; fi

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
