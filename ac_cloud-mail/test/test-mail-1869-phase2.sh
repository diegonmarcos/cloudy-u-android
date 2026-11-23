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
  [P5]="rule 16 parity: every email_bodies column, body query and migration step kept"
)
CHECKS="S5 P5"

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
mutate P5 "$DBD/EmailBodyEntity.kt" '    val fetchedAt: Long,
' '' "the fetchedAt column dropped"
mutate P5 "$DBD/EmailBodyDao.kt" 'suspend fun pruneForAccount(' 'suspend fun pruneGone(' "a body DAO query removed"
mutate P5 "$DBD/SternaDatabase.kt" 'MIGRATION_9_10, ' '' "an old registered step dropped"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-1869-phase2: ALL PASS"; exit 0; fi
echo "test-mail-1869-phase2: $FAILURES FAILURE(S)"; exit 1
