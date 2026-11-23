#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-mail #1869 INSTANT OPEN - local first, the network only reconciles ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Opening mail lagged (docs/perf-1869.md): the reader waited up to 2 s on the core's raw-message fetch
# with the body already on disk, a swipe outside the Inbox fetched over the network, cached bodies were decoded on the
# main thread, inline images were never prefetched, the tern spun over cached rows, and the start sync was not
# expedited. Each fix below is one line that a later rewrite can undo with every other test green.
#
#   I1  the reader never awaits the core fetch (completedOrNull, no withTimeoutOrNull, no CORE_FETCH_WAIT_MS)
#   I2  neighbours are prefetched in every folder; outside the Inbox through the raw store, which imports no key
#   I3  a cached body is decoded on Dispatchers.Default, not on the reader's Main
#   I4  the full sync has an inline-image phase (unmetered, in the body cap, budget per run) and the port implements it
#       without ever opening ciphertext
#   I5  no spinner over cached rows: the tern reads ternShowing (pull gesture or empty list), a hairline otherwise
#   I6  the start runs of both sync workers are expedited on Android 12+, network-only constraints
#   I7  the window stays a declared setting with default 90 days (OfflineWindow.DEFAULT = THREE, SyncWindow.DAYS_90)
#   P   rule 16 parity against test/mail-1869-parity-freeze.json (frozen from the tree before #1869): every Configs
#       row, screen, offline window, attachment limit, sync window and attachment policy is still there
#   M   mutation-proof: each defence undone in a COPY turns its check red; the real tree is green
#
# OWN-SOURCE ONLY. python3 only; no gradle, no device.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FREEZE="$APP/test/mail-1869-parity-freeze.json"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null || { echo "ERROR python3 missing - this tester is unrun, not passing"; exit 1; }
[ -s "$FREEZE" ] || { echo "ERROR no freeze at $FREEZE - this tester is unrun, not passing"; exit 1; }

CHECK=$(cat <<'PY'
import json, os, re, sys
app, which = sys.argv[1], sys.argv[2]
K = os.path.join(app, "app/src/main/kotlin/app/sterna")
D = os.path.join(app, "core/data/src/main/kotlin/app/sterna/core/data")

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return "\n".join(l for l in t.split("\n") if not l.strip().startswith("//"))

def lines(p):
    return [re.sub(r"\s+", " ", l).strip() for l in code(read(p)).split("\n") if l.strip()]

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

if which == "I1":
    vm = code(read(os.path.join(K, "ui/message/MessageViewModel.kt")))
    need("coreFetch?.let { completedOrNull(it) }?.let { _coreDoc.value = it }" in vm, "load no longer reads the core fetch without waiting")
    need("job.await()" not in vm and "withTimeoutOrNull" not in vm, "the reader awaits a fetch again")
    need("CORE_FETCH_WAIT_MS" not in vm, "the 2 s wait constant is back")
    op = code(read(os.path.join(K, "ui/message/OpenPath.kt")))
    need("internal fun <T> completedOrNull(job: Deferred<T>): T? =" in op, "completedOrNull is gone or became a suspend function")
    need("job.isCompleted && !job.isCancelled" in op, "completedOrNull no longer checks the fetch has landed")
elif which == "I2":
    r = code(read(os.path.join(D, "mail/MailRepository.kt")))
    nb = body(r, "fun prefetchNeighbours(")
    need('val raw = mailboxDao.roleForId(credentials.id, mailboxId) != "inbox"' in nb, "the folder role no longer picks the store path")
    need('!= "inbox") return@launch' not in nb, "neighbours are Inbox-only again")
    need("bodyPrefetchPort(credentials, mailboxId, raw)" in nb, "neighbours do not use the raw port outside the Inbox")
    port = body(r, "private fun bodyPrefetchPort(")
    need("if (raw) fetchAndStoreRawBodies(credentials, ids) else fetchAndStoreBodies(credentials, ids)" in port, "the raw port does not store raw")
    rawfn = body(r, "private suspend fun fetchAndStoreRawBodies(")
    need(rawfn and not any(x in rawfn for x in ("importAutocryptKey", "originalSendersOf(", "pgpEngine")), "the raw store reaches the key import")
elif which == "I3":
    r = code(read(os.path.join(D, "mail/MailRepository.kt")))
    cm = body(r, "suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? {")
    need("withContext(Dispatchers.Default) {" in cm, "the cached body is decoded on the caller's thread again")
    need(not any(x in cm for x in ("client.", "connect(", "openEmail(")), "the cache read reaches the network")
elif which == "I4":
    fs = code(read(os.path.join(D, "mail/FullSync.kt")))
    need("internal const val FULL_SYNC_INLINE_PER_RUN = 100" in fs, "no inline budget per run")
    need("fun inlineImagesAllowed(c: PrefetchConditions): Boolean = c.connected && c.unmetered && !c.batterySaver" in fs, "the inline throttle changed")
    need("port.fetchInlineImages(id)" in fs and "port.missingInlineImages(tried.size + want)" in fs, "the engine has no inline-image phase")
    need(0 <= fs.find("port.fetchInlineImages(id)") < fs.find("port.fetchAttachment(job)"), "the inline phase does not run before the attachments")
    r = code(read(os.path.join(D, "mail/MailRepository.kt")))
    need("if (cryptoKindOf(email) != null || email.inlineImageParts().isEmpty()) continue" in r, "the inline scan opens ciphertext")
    need("if (cryptoKindOf(cached.email) != null) return" in r and "ensureInlineImages(credentials, emailId, cached)" in r, "the port does not store inline images, or does for ciphertext")
    dao = code(read(os.path.join(D, "db/EmailDao.kt")))
    need("suspend fun idsMissingInlineImages(accountId: String, cutoff: Long, limit: Int, offset: Int): List<String>" in dao, "the DAO query is gone")
elif which == "I5":
    s = lines(os.path.join(K, "ui/inbox/InboxScreen.kt"))
    need("val ternShowing = ternDrawn(ui.showRefreshIndicator, refreshGesture, listRows.itemCount)" in s, "the tern is not narrowed to the gesture or an empty list")
    need("val syncLineShowing = syncLineDrawn(ui.showRefreshIndicator, refreshGesture, listRows.itemCount)" in s, "no subtle sync line")
    need(s.count("isRefreshing = ternShowing,") == 2, "the pull box or the tern reads something other than ternShowing")
    need("isRefreshing = ui.showRefreshIndicator," not in s, "a spinner reads the bare display again (spins over cached rows)")
    need("if (syncLineShowing) {" in s and "LinearProgressIndicator(" in s, "the hairline is not drawn")
    rd = code(read(os.path.join(K, "ui/inbox/RefreshDisplay.kt")))
    need("indicator && (gesture || rows == 0)" in rd and "indicator && !gesture && rows > 0" in rd, "the display rule changed")
    vm = code(read(os.path.join(K, "ui/inbox/InboxViewModel.kt")))
    need("val refreshByGesture: StateFlow<Boolean> = refreshRun.map { it?.instant == true }" in vm, "the gesture flag is not RefreshRun.instant")
elif which == "I6":
    for f in ("mail/OfflineSyncWorker.kt", "mail/MailCoreSyncWorker.kt"):
        w = code(read(os.path.join(K, f)))
        rn = body(w, "fun runNow(context: Context) {")
        need("builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)" in rn, f + ": start run not expedited")
        need("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {" in rn, f + ": expedited below Android 12 (needs getForegroundInfo)")
    w = code(read(os.path.join(K, "mail/OfflineSyncWorker.kt")))
    need(".setConstraints(expeditedConstraints())" in body(w, "fun runNow(context: Context) {"), "the expedited request carries battery-not-low (WorkManager rejects it)")
elif which == "I7":
    oc = code(read(os.path.join(D, "mail/OfflineCache.kt")))
    need("val DEFAULT = THREE" in oc and "THREE(3, 90)" in oc, "the offline window default is not 90 days")
    sa = code(read(os.path.join(D, "account/StoredAccount.kt")))
    need("val syncWindow: SyncWindow = SyncWindow.DAYS_90," in sa, "the per-account window default is not 90 days")
elif which == "P":
    fz = json.load(open(sys.argv[3], encoding="utf-8"))
    ss = code(read(os.path.join(K, "ui/settings/SettingsScreen.kt")))
    now = {"settings_rows": set(re.findall(r"R\.string\.(settings_[a-z0-9_]+)", ss))}
    scr = set()
    for d, _, fs in os.walk(K):
        for f in fs:
            if f.endswith(".kt"):
                scr |= set(re.findall(r"@Composable\s+(?:internal |private )?fun ([A-Z][A-Za-z0-9]*Screen)\(", code(read(os.path.join(d, f)))))
    now["screens"] = scr
    oc = code(read(os.path.join(D, "mail/OfflineCache.kt")))
    def enum(src, name):
        m = re.search(r"enum class %s\b[^{]*\{(.*?);" % name, src, re.S)
        return set(re.findall(r"\b([A-Z][A-Z0-9_]+)\(", m.group(1))) if m else set()
    now["offline_windows"] = enum(oc, "OfflineWindow")
    now["attachment_limits"] = enum(oc, "OfflineAttachmentLimit")
    now["sync_windows"] = set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]+)\(limit", code(read(os.path.join(D, "account/SyncWindow.kt"))), re.M))
    m = re.search(r"enum class AttachmentPolicy \{\s*([^;]*);", code(read(os.path.join(D, "mail/FullSync.kt"))))
    now["attachment_policies"] = set(re.findall(r"\b[A-Z_]+\b", m.group(1))) if m else set()
    for k, v in fz.items():
        if not isinstance(v, list):
            continue
        if not v:
            bad.append("frozen category %s is empty (a vacuous freeze proves nothing)" % k)
        gone = sorted(set(v) - now.get(k, set()))
        if gone:
            bad.append("%s LOST: %s" % (k, ", ".join(gone)))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
)

check() { python3 -c "$CHECK" "$1" "$2" "$FREEZE"; }

declare -A LABEL=(
  [I1]="the reader never awaits the core fetch on the open path"
  [I2]="neighbours prefetched in every folder, raw (no key import) outside the Inbox"
  [I3]="a cached body is decoded off the reader's Main thread"
  [I4]="the full sync prefetches inline images (unmetered, capped, budgeted, never ciphertext)"
  [I5]="no spinner over cached rows: tern for the pull or an empty list, a hairline otherwise"
  [I6]="start sync expedited on Android 12+, network-only constraints"
  [I7]="the window is a declared setting with default 90 days"
  [P]="rule 16 parity: every Configs row, screen and window/attachment choice kept"
)

echo "== the tree =="
for c in I1 I2 I3 I4 I5 I6 I7 P; do
  if check "$APP" "$c"; then pass "$c ${LABEL[$c]}"; else fail "$c ${LABEL[$c]}"; fi
done

echo "== mutations (each in a copy must turn its check red) =="
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mail-1869.XXXXXX")"
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
VM=app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt
REPO=core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt
FS=core/data/src/main/kotlin/app/sterna/core/data/mail/FullSync.kt
SCR=app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt
OW=app/src/main/kotlin/app/sterna/mail/OfflineSyncWorker.kt
CW=app/src/main/kotlin/app/sterna/mail/MailCoreSyncWorker.kt
OC=core/data/src/main/kotlin/app/sterna/core/data/mail/OfflineCache.kt
SET=app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt
mutate I1 "$VM" 'coreFetch?.let { completedOrNull(it) }?.let { _coreDoc.value = it }' 'coreFetch?.let { job -> withTimeoutOrNull(2_000L) { job.await() } }?.let { _coreDoc.value = it }' "the 2 s await restored"
mutate I2 "$REPO" 'val raw = mailboxDao.roleForId(credentials.id, mailboxId) != "inbox"' 'if (mailboxDao.roleForId(credentials.id, mailboxId) != "inbox") return@launch
                val raw = false' "neighbours back to Inbox-only"
mutate I2 "$REPO" 'if (raw) fetchAndStoreRawBodies(credentials, ids) else fetchAndStoreBodies(credentials, ids)' 'fetchAndStoreBodies(credentials, ids)' "non-Inbox neighbours through the key-importing path"
mutate I3 "$REPO" '                withContext(Dispatchers.Default) {
                    MessageBody(' '                run {
                    MessageBody(' "decode back on the caller's thread"
mutate I4 "$FS" '                        port.fetchInlineImages(id)' '                        Unit' "inline phase fetches nothing"
mutate I4 "$REPO" 'if (cryptoKindOf(email) != null || email.inlineImageParts().isEmpty()) continue' 'if (email.inlineImageParts().isEmpty()) continue' "inline scan opens ciphertext"
mutate I5 "$SCR" 'isRefreshing = ternShowing,' 'isRefreshing = ui.showRefreshIndicator,' "the pull box spins over cached rows"
mutate I5 "$SCR" 'if (syncLineShowing) {' 'if (false) {' "the hairline never drawn"
mutate I6 "$OW" 'builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)' 'Unit' "offline sync start not expedited"
mutate I6 "$CW" 'if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {' 'if (true) {' "core sync expedited below Android 12"
mutate I7 "$OC" 'val DEFAULT = THREE' 'val DEFAULT = ONE' "the window default cut to 30 days"
mutate P "$OC" 'TWELVE(12, 365), ' '' "a keep-offline choice removed"
mutate P "$SET" 'R.string.settings_offline_attach_policy_title' 'R.string.app_name' "the Download attachments row removed"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-1869-instant-open: ALL PASS"; exit 0; fi
echo "test-mail-1869-instant-open: $FAILURES FAILURE(S)"; exit 1
