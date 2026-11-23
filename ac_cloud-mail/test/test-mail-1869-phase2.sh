#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-mail #1869 PHASE 2 - design steps 5-11 (fable-open-path-design.md)  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Phase 1 made the open path one local read; phase 2 makes "cached" true for the whole window. Each step
# below is a few lines a later rewrite can undo with every other test green, so each is pinned here and proved by a
# mutation in a COPY of the tree.
#
#   S5  Room v34, additive: email_bodies gains fragment/fragmentVersion/inlineState/bytes, the email_parts table, the
#       step REGISTERED (the destructive fallback would wipe the outbox and drafts), caps are SUM(bytes)
#   S6  inline images as FILES in the message's offline-attachments directory (the attachments cap), never for ciphertext;
#       the reader keeps `cid:` and BlockingWebViewClient serves the file (before the remote gate, volatile state, no
#       Android types in the resolver); export and print get data: URIs back; old rows upgraded lazily in the background
#   S7  the reader fragment (the one sanitiser policy's answer) is stored with every plain body, under the cursor-window
#       guard, never for ciphertext; a stored fragment seeds the reader's memo; a version mismatch is rebuilt lazily on
#       Default one row at a time (never a purge); the app's sanitiser goes through the same memo
#   S8  neighbours in the PAGER's order (2 each side, (account, id) pairs, nearest first) on settle; the folder order is the
#       lone-message fallback; PrefetchTrigger.VISIBLE: the rows on screen, debounced 150 ms, at most 12, metered allowed;
#       raw outside the Inbox (no key import); no body fetched twice at once
#   P5  rule 16 parity: every email_bodies column, every body DAO query and every registered migration kept
#   M   mutation-proof: each defence undone in a COPY turns its check red; the real tree is green
#
# OWN-SOURCE ONLY. python3 only; no gradle, no device.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null || { echo "ERROR python3 missing - this tester is unrun, not passing"; exit 1; }

CHECK=$(cat <<'PY'
import os, re, sys
app, which = sys.argv[1], sys.argv[2]
K = os.path.join(app, "app/src/main/kotlin/app/sterna")
D = os.path.join(app, "core/data/src/main/kotlin/app/sterna/core/data")

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return "\n".join(l for l in t.split("\n") if not l.strip().startswith("//"))

def body(text, header):
    at = text.find(header)
    if at < 0:
        return ""
    o = text.find("{", at); depth = 0; i = o
    while i < len(text):
        depth += {"{": 1, "}": -1}.get(text[i], 0); i += 1
        if depth == 0:
            break
    return text[at:i]

bad = []
def need(cond, msg):
    if not cond:
        bad.append(msg)

if which == "S5":
    db = code(read(os.path.join(D, "db/SternaDatabase.kt")))
    need("internal const val SCHEMA_VERSION = 34" in db, "the schema version is not 34")
    m = re.search(r"internal val ALL_MIGRATIONS: Array<Migration> = arrayOf\((.*?)\)", db, re.S)
    reg = m.group(1) if m else ""
    need("MIGRATION_33_34" in reg, "the 33 to 34 step is not registered (destructive fallback: outbox and drafts wiped)")
    need("EmailPartEntity::class" in db and "abstract fun emailPartDao(): EmailPartDao" in db, "email_parts is not in the database")
    mg = code(read(os.path.join(D, "db/Migrations.kt")))
    step = body(mg, "val MIGRATION_33_34 = object : Migration(33, 34) {")
    for col in ("`fragment` TEXT\"", "`fragmentVersion` INTEGER NOT NULL DEFAULT 0\"", "`inlineState` INTEGER NOT NULL DEFAULT 0\"", "`bytes` INTEGER NOT NULL DEFAULT 0\""):
        need(("ALTER TABLE `email_bodies` ADD COLUMN " + col) in step, "the migration does not add " + col)
    need("db.execSQL(EMAIL_PARTS_CREATE_SQL)" in step and "db.execSQL(EMAIL_PARTS_INDEX_SQL)" in step, "the migration does not create email_parts")
    need("UPDATE `email_bodies` SET `bytes` = LENGTH(`bodyJson`) + LENGTH(`inlineImagesJson`)" in step, "bytes is not backfilled once")
    need(not re.search(r"DROP|DELETE", step, re.I), "the v34 step deletes or drops something")
    dao = code(read(os.path.join(D, "db/EmailBodyDao.kt")))
    need('"SELECT COALESCE(SUM(bytes), 0) FROM email_bodies WHERE accountId = :accountId"' in dao and '"SELECT COALESCE(SUM(bytes), 0) FROM email_bodies"' in dao, "the caps are not SUM(bytes)")
    need("@Query(BODY_BYTES_FOR_ACCOUNT_SQL)" in dao and "@Query(BODY_BYTES_TOTAL_SQL)" in dao, "the cap queries do not use the shared SQL")
    need("LENGTH(" not in dao, "a body DAO query measures body text again")
    need('"b.bytes AS bytes " +' in dao, "the eviction plan does not read the stored size")
    ent = code(read(os.path.join(D, "db/EmailBodyEntity.kt")))
    need("val bytes: Long = storedBodyBytes(bodyJson, inlineImagesJson, fragment)," in ent, "a new row's bytes is not computed from what it stores")
elif which == "S6":
    r = code(read(os.path.join(D, "mail/MailRepository.kt")))
    fi = body(r, "private suspend fun fetchInlineImages(")
    need("val root = offlineAttachmentsDir?.takeIf { cryptoKindOf(email) == null }" in fi, "inline files are written for ciphertext too")
    need("InlineImageFiles.write(it, credentials.id, emailId, key, type, bytes)" in fi and "map[cid] = ref" in fi, "inline images are not stored as files")
    need('map[cid] = "data:$type;base64,$base64"' in fi, "the in-memory fallback (ciphertext, no directory) is gone")
    cm = body(r, "suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? {")
    need("upgradeInlineImagesLater(accountId, emailId)" in cm, "old rows are never upgraded")
    need("InlineImageFiles.present(offlineAttachmentsDir, decoded.inlineImages)" in cm, "an evicted file still reads as present")
    up = body(r, "private fun upgradeInlineImagesLater(")
    need("bgScope.launch {" in up and "if (cryptoKindOf(email) != null) return@runCatching" in up, "the upgrade runs on the caller or opens ciphertext")
    need(r.count("runCatching { emailPartDao?.deleteOrphans() }") >= 2, "an eviction path leaves the parts index behind")
    f = code(read(os.path.join(D, "mail/InlineImageFiles.kt")))
    need("if (!file.path.startsWith(base.path + File.separator)) return null" in f, "a ref can leave the offline-attachments root")
    need('"${safe(accountId)}/${safe(emailId)}/$FILE_PREFIX${safe(partKey)}"' in f, "inline files are not in the message's directory (attachments cap, eviction)")
    ms = code(read(os.path.join(K, "ui/message/MessageScreen.kt")))
    si = body(ms, "override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {")
    need("InlineCid.respond(inlineRoot, inlineImages, cidUrl.schemeSpecificPart)" in si, "the reader does not serve cid: files")
    need(0 <= si.find("InlineCid.respond(") < si.find("if (!blockRemote) return null"), "cid: files are served only with remote content blocked")
    need("@Volatile var inlineRoot: java.io.File? = null" in ms and "@Volatile var inlineImages: Map<String, String> = emptyMap()" in ms, "the interception state is not volatile")
    need("inlineImages = msg.inlineImages," in ms and "client.inlineImages = inlineImages" in ms, "the reader WebView does not get the message's images")
    need("InlineImageFiles.selfContained(" in body(ms, "LaunchedEffect(printRequested) {"), "print loses file-stored images")
    ex = code(read(os.path.join(K, "ui/message/MessageExporter.kt")))
    need("repo.selfContainedInlineImages(msg.inlineImages)" in ex and "buildSelfContainedHtml(exportMsg, labels, selfContained.inlineImages)" in ex, "export loses file-stored images")
    pd = code(read(os.path.join(K, "ui/message/PrintDocument.kt")))
    need("if (app.sterna.core.data.mail.InlineImageFiles.isRef(dataUri)) return@forEach" in pd, "a file ref is pasted into the document")
    ic = code(read(os.path.join(K, "ui/message/InlineCid.kt")))
    need("import android." not in ic and "InlineImageFiles::isRef" in ic, "the resolver uses Android types or serves non-refs")
elif which == "S7":
    r = code(read(os.path.join(D, "mail/MailRepository.kt")))
    pb = body(r, "private suspend fun persistBody(")
    need("val fragment = storedFragmentOf(email, bodyJson, inlineJson)" in pb and "fragment = fragment," in pb, "the fragment is not stored with the body")
    need("fragmentVersion = ReaderFragment.VERSION," in pb, "the stored fragment carries no version")
    sf = body(r, "private suspend fun storedFragmentOf(")
    need("if (cryptoKindOf(email) != null) return null" in sf, "a fragment is made of ciphertext")
    need("withContext(Dispatchers.Default) { ReaderFragment.build(email) }" in sf, "the fragment is built on the caller's thread")
    need("fragment.takeIf { fitsBodyCache(bodyJson, inlineJson + it) }" in sf, "the fragment escapes the cursor-window guard")
    cm = body(r, "suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? {")
    need("ReaderFragment.remember(decoded.email, row.fragment)" in cm, "a stored fragment does not reach the reader")
    need("rebuildFragmentLater(accountId, emailId)" in cm, "a version mismatch is never rebuilt")
    rb = body(r, "private fun rebuildFragmentLater(")
    need("bgScope.launch {" in rb and "emailBodyDao.setFragment(" in rb, "the rebuild is not a background one-row update")
    need("deleteAll" not in rb and "deleteById" not in rb and "clearCachedBodies" not in rb, "the rebuild purges")
    dao = code(read(os.path.join(D, "db/EmailBodyDao.kt")))
    need('"UPDATE email_bodies SET fragment = :fragment, fragmentVersion = :version, bytes = :bytes " +' in dao and '"WHERE accountId = :accountId AND id = :id",' in dao, "the rebuild is not one row")
    rf = code(read(os.path.join(D, "mail/ReaderFragment.kt")))
    need("fun build(email: Email): String? = email.htmlContent()?.takeIf { it.isNotBlank() }?.let(::sanitised)" in rf, "the fragment is not the policy's answer")
    need("val clean = sanitiseReceivedHtml(html)" in rf, "the memo does not call the one policy")
    rbk = code(read(os.path.join(K, "ui/message/ReaderBody.kt")))
    need("private fun sanitiseReceivedHtml(html: String): String = app.sterna.core.data.mail.ReaderFragment.sanitised(html)" in rbk, "the reader does not read the stored fragment memo")
    need("ReaderBody(sanitiseReceivedHtml(it), richHtml = true)" in rbk, "readerBody is no longer the one place markup becomes a fragment")
elif which == "S8":
    bp = code(read(os.path.join(D, "mail/BodyPrefetch.kt")))
    need("enum class PrefetchTrigger { LIST_SYNC, NEIGHBOUR, VISIBLE }" in bp, "no VISIBLE trigger (or a trigger lost)")
    vis = bp[bp.find("trigger == PrefetchTrigger.VISIBLE -> when {"):]
    vis = vis[:vis.find("}") + 1]
    need("c.batterySaver && !c.unmetered -> PrefetchBudget.NONE" in vis and "else -> PrefetchBudget(PREFETCH_VISIBLE_MAX, PREFETCH_VISIBLE_MAX, 1)" in vis, "the VISIBLE throttle changed")
    need("internal const val PREFETCH_VISIBLE_MAX = 12" in bp and "const val PREFETCH_VISIBLE_DEBOUNCE_MS = 150L" in bp, "the VISIBLE bounds changed")
    need("if (page + d < pageCount) at(page + d)?.let(::add)" in bp and "if (page - d >= 0) at(page - d)?.let(::add)" in bp, "the pager order is not nearest first, after then before")
    r = code(read(os.path.join(D, "mail/MailRepository.kt")))
    nb = body(r, "fun prefetchNeighbours(")
    need('pagerAnchors.containsKey("${credentials.id}/$emailId")' in nb, "the folder order runs over the pager's")
    pm = body(r, "private fun prefetchMessages(")
    need('if (mailboxDao.roleForId(accountId, mailboxId) == "inbox") inbox += id else raw += id' in pm, "a non-Inbox prefetch can import a key")
    need('val taken = ids.filter { prefetchInFlight.add("$accountId/$it") }' in pm, "a body can be fetched twice at once")
    need("if (credentials.protocol == MailProtocol.IMAP) continue" in pm, "IMAP enters the JMAP prefetch")
    need("prefetchMessages(rows.take(PREFETCH_VISIBLE_MAX), PrefetchTrigger.VISIBLE)" in r, "the visible rows are not bounded or not VISIBLE")
    ms = code(read(os.path.join(K, "ui/message/MessageScreen.kt")))
    need("application.container.mailRepository.prefetchPagerNeighbours(settled.second to settled.first, near)" in ms, "the pager does not hand its order over")
    need("app.sterna.core.data.mail.pagerPrefetchOrder(pagerState.currentPage, pageCount) { i ->" in ms, "the pager order is not the pager's")
    ib = code(read(os.path.join(K, "ui/inbox/InboxScreen.kt")))
    need("delay(app.sterna.core.data.mail.PREFETCH_VISIBLE_DEBOUNCE_MS)" in ib and "}.distinctUntilChanged().collectLatest { rows ->" in ib, "the visible rows are not debounced")
    need("viewModel.prefetchVisible(rows)" in ib and "listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->" in ib, "the visible rows are not prefetched")
elif which == "P5":
    ent = code(read(os.path.join(D, "db/EmailBodyEntity.kt")))
    for col in ("val id: String", "val accountId: String", "val bodyJson: String", "val inlineImagesJson: String", "val fetchedAt: Long"):
        need(col in ent, "email_bodies LOST " + col)
    dao = code(read(os.path.join(D, "db/EmailBodyDao.kt")))
    for fn in ("bodyRows", "deleteIds", "fetchedBefore", "bytesForAccount", "totalBytes", "upsert", "byId", "cachedIds", "countForAccount",
               "pruneForAccount", "deleteById", "deleteForIdPrefix", "deleteForAccount", "accountIds", "deleteAll"):
        need(re.search(r"suspend fun %s\(" % fn, dao), "EmailBodyDao LOST " + fn)
    db = code(read(os.path.join(D, "db/SternaDatabase.kt")))
    m = re.search(r"internal val ALL_MIGRATIONS: Array<Migration> = arrayOf\((.*?)\)", db, re.S)
    reg = m.group(1) if m else ""
    for a, b in [(9, 10)] + [(n, n + 1) for n in range(11, 34)]:
        need("MIGRATION_%d_%d" % (a, b) in reg, "the registered step %d to %d is gone" % (a, b))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
)

check() { python3 -c "$CHECK" "$1" "$2"; }

declare -A LABEL=(
  [S5]="Room v34 is additive, registered, and the caps are SUM(bytes)"
  [S6]="inline images are files served by cid: interception; export, print and old rows kept"
  [S7]="the fragment is stored with the body, seeds the reader, and is rebuilt lazily on a version change"
  [S8]="neighbours in pager order; VISIBLE rows debounced, capped, metered allowed"
  [P5]="rule 16 parity: every email_bodies column, body query and migration step kept"
)
CHECKS="S5 S6 S7 S8 P5"

echo "== the tree =="
for c in $CHECKS; do
  if check "$APP" "$c"; then pass "$c ${LABEL[$c]}"; else fail "$c ${LABEL[$c]}"; fi
done

echo "== mutations (each in a copy must turn its check red) =="
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mail-1869p2.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
copy_tree() {
  rm -rf -- "$WORK/app"; mkdir -p "$WORK/app"
  for d in app/src/main/kotlin core/data/src/main/kotlin; do
    mkdir -p "$WORK/app/$d"; cp -R "$APP/$d/." "$WORK/app/$d/"
  done
}
# mutate <check> <file relative to APP> <python old> <python new> <what>
mutate() {
  local c="$1" f="$2" old="$3" new="$4" what="$5"
  copy_tree
  if ! python3 - "$WORK/app/$f" "$old" "$new" <<'PY'
import sys
p, old, new = sys.argv[1:4]
t = open(p, encoding="utf-8").read()
if old not in t:
    sys.exit(3)
open(p, "w", encoding="utf-8").write(t.replace(old, new, 1))
PY
  then fail "M $c: the mutation '$what' no longer applies (the source moved; update this tester)"; return; fi
  if check "$WORK/app" "$c" >/dev/null; then fail "M $c survives: $what"; else pass "M $c catches: $what"; fi
}
DBD=core/data/src/main/kotlin/app/sterna/core/data/db
mutate S5 "$DBD/SternaDatabase.kt" 'MIGRATION_32_33, MIGRATION_33_34,' 'MIGRATION_32_33,' "the v34 step written but not registered"
mutate S5 "$DBD/Migrations.kt" '        db.execSQL("ALTER TABLE `email_bodies` ADD COLUMN `inlineState` INTEGER NOT NULL DEFAULT 0")
' '' "one v34 column not added"
mutate S5 "$DBD/Migrations.kt" '        db.execSQL(EMAIL_PARTS_CREATE_SQL)
' '        db.execSQL("DELETE FROM `email_bodies`")
' "the step purges the bodies instead of creating email_parts"
mutate S5 "$DBD/EmailBodyDao.kt" '"SELECT COALESCE(SUM(bytes), 0) FROM email_bodies"' '"SELECT COALESCE(SUM(LENGTH(bodyJson) + LENGTH(inlineImagesJson)), 0) FROM email_bodies"' "the total cap measures body text again"
mutate S5 "$DBD/EmailBodyDao.kt" '"b.bytes AS bytes " +' '"(LENGTH(b.bodyJson) + LENGTH(b.inlineImagesJson)) AS bytes " +' "the eviction plan measures body text again"
mutate S5 "$DBD/EmailBodyEntity.kt" 'val bytes: Long = storedBodyBytes(bodyJson, inlineImagesJson, fragment),' 'val bytes: Long = 0,' "new rows count zero against the cap"
MD=core/data/src/main/kotlin/app/sterna/core/data/mail
UI=app/src/main/kotlin/app/sterna/ui/message
mutate S6 "$MD/MailRepository.kt" 'val root = offlineAttachmentsDir?.takeIf { cryptoKindOf(email) == null }' 'val root = offlineAttachmentsDir' "ciphertext images written to disk"
mutate S6 "$MD/MailRepository.kt" '                    if (decoded.inlineImages.values.any { it.startsWith("data:") }) upgradeInlineImagesLater(accountId, emailId)
' '' "old rows never upgraded"
mutate S6 "$MD/InlineImageFiles.kt" '        if (!file.path.startsWith(base.path + File.separator)) return null
' '' "a ref escapes the root"
mutate S6 "$UI/MessageScreen.kt" '            InlineCid.respond(inlineRoot, inlineImages, cidUrl.schemeSpecificPart)?.let { (type, stream) ->' '            null?.let { (type: String, stream: java.io.InputStream) ->' "cid: requests not served"
mutate S6 "$UI/MessageExporter.kt" 'buildSelfContainedHtml(exportMsg, labels, selfContained.inlineImages)' 'buildSelfContainedHtml(exportMsg, labels, msg.inlineImages)' "HTML export without the file images"
mutate S6 "$UI/PrintDocument.kt" '        if (app.sterna.core.data.mail.InlineImageFiles.isRef(dataUri)) return@forEach
' '' "a ref pasted as an image source"
mutate S6 "$MD/MailRepository.kt" '        runCatching { emailPartDao?.deleteOrphans() }
        drop.size' '        drop.size' "body eviction leaves parts rows"
mutate S7 "$MD/MailRepository.kt" '                fragment = fragment,
' '' "the fragment computed but not stored"
mutate S7 "$MD/MailRepository.kt" '        if (cryptoKindOf(email) != null) return null
        val fragment' '        val fragment' "a fragment made of ciphertext"
mutate S7 "$MD/MailRepository.kt" 'fragment.takeIf { fitsBodyCache(bodyJson, inlineJson + it) }' 'fragment' "the fragment past the cursor window"
mutate S7 "$MD/MailRepository.kt" '                        ReaderFragment.remember(decoded.email, row.fragment)' '                        Unit' "the stored fragment never reaches the reader"
mutate S7 "$MD/MailRepository.kt" '                        rebuildFragmentLater(accountId, emailId)' '                        clearCachedBodies()' "a version change purges every body"
mutate S7 "$UI/ReaderBody.kt" 'private fun sanitiseReceivedHtml(html: String): String = app.sterna.core.data.mail.ReaderFragment.sanitised(html)' 'private fun sanitiseReceivedHtml(html: String): String = app.sterna.core.data.text.sanitiseReceivedHtml(html)' "the reader sanitises every open again"
mutate S8 "$MD/BodyPrefetch.kt" 'else -> PrefetchBudget(PREFETCH_VISIBLE_MAX, PREFETCH_VISIBLE_MAX, 1)' 'else -> PrefetchBudget(PREFETCH_PASS_MAX_IDS, PREFETCH_BATCH, 2)' "VISIBLE takes the whole window on a metered link"
mutate S8 "$MD/BodyPrefetch.kt" '        if (page - d >= 0) at(page - d)?.let(::add)
' '' "only the pages after are prefetched"
mutate S8 "$MD/MailRepository.kt" 'if (mailboxDao.roleForId(accountId, mailboxId) == "inbox") inbox += id else raw += id' 'inbox += id' "non-Inbox rows through the key-importing path"
mutate S8 "$MD/MailRepository.kt" 'val taken = ids.filter { prefetchInFlight.add("$accountId/$it") }' 'val taken = ids' "the same body fetched twice at once"
mutate S8 "$UI/MessageScreen.kt" '        application.container.mailRepository.prefetchPagerNeighbours(settled.second to settled.first, near)
' '' "the pager order dropped"
mutate S8 "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt" '            delay(app.sterna.core.data.mail.PREFETCH_VISIBLE_DEBOUNCE_MS)
' '' "a request per scroll frame"
mutate P5 "$DBD/EmailBodyEntity.kt" '    val fetchedAt: Long,
' '' "the fetchedAt column dropped"
mutate P5 "$DBD/EmailBodyDao.kt" 'suspend fun pruneForAccount(' 'suspend fun pruneGone(' "a body DAO query removed"
mutate P5 "$DBD/SternaDatabase.kt" 'MIGRATION_9_10, ' '' "an old registered step dropped"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-1869-phase2: ALL PASS"; exit 0; fi
echo "test-mail-1869-phase2: $FAILURES FAILURE(S)"; exit 1
