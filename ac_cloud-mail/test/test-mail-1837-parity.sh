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
has "$R" 'coreInbox(session, accountId, auth, mailboxId, localAccountId, RustMailEngine.INTERACTIVE_BUDGET_MS)?.let { return it }' "the JMAP Inbox syncs through the Rust core first"
has "$R" 'JmapBacklogPlan.next(serverIds, cached)' "the Kotlin Inbox backlog is kept as the core's fallback"
has "$R" 'if (credentials.protocol == MailProtocol.IMAP) return null' "IMAP accounts are not driven by the core in phase 1"
has "$E" 'IMAP accounts keep the old path in phase 1' "the code says IMAP keeps the old path in phase 1"
has "$E" 'emailDao.applyDelta(localAccountId, gone, rows.map { it.toEntity(localAccountId, mailboxId) })' "list rows are projected through the shared toEntity mapping"
has "$E" 'page.removed.filter { it in inRoom && !isProtected(it) }' "a projection removes only what the store removed (never blanks the list)"
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

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
