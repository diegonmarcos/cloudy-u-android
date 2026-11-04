#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #608 — SYNC ▸ GIT is DECLARED and DENSE: two sections from build.json,    ║
# ║ a public set that is really public, a login that reuses the fleet's ONE  ║
# ║ sign-in for the `repo` scope, fourteen per-repository operations that     ║
# ║ every one reach libs:git-sync, and clones that land in the store's git    ║
# ║ folder — never a second git, never a second list, never a big card       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Gradle proves the page compiles. It cannot see that an
# operation the declaration promises has no handler (a button that does
# nothing), that a handler exists for an operation nobody declared (a verb with
# no label), that the "public" set contains a repository the API calls private
# (a clone that fails forever), that the listing endpoint was typed into Kotlin
# beside the declaration, that the force verbs quietly became synonyms for the
# plain ones, or that the "dense row" is a card again. This file can.
#
#   P1  the declaration is whole and ordered: ui.sync.git carries the two
#       sections (public, personal), the personal split (Public, Private), a
#       WebAuth way and an SSH way, an https api with {owner}/{name} in its web
#       url, the three remote modes with read-only marked, fourteen operations
#       including the six transport/commit verbs and the two DESTRUCTIVE ones,
#       an owner, the name families and a non-empty public set.
#   P2  the public set is derived, not hand-picked: every name begins with a
#       declared name family, the list is unique and alphabetical, THIS
#       repository (found in the pinned registry by its pre-rename name cloud-u-android) is in it, every repository the seed
#       manifest marks `seed` is in it, and nothing the manifest marks
#       `private: true` is.
#   P3  declaration ↔ dispatch, both directions: GitReposScreen's
#       `when (section.id)` is exactly the declared section ids, and its
#       `when (op.id)` is exactly the declared operation ids.
#   P4  every git verb is the ENGINE's: the coordinator's `when (opId)` calls
#       e.fetch / e.pull / e.commit / e.push / e.forcePush / e.forcePull,
#       GitEngine declares the two force verbs, the JVM suite EXERCISES them
#       (and proves the plain verbs refuse the same divergence first), and the
#       app still imports no JGit.
#   P5  #641 INVERTED: there is NO browser login on this page. No sign-in
#       surface, no webauth way, no GitHub App client id in either declaration,
#       exactly ONE device grant left in the shared declaration (Google's) and
#       no breadcrumb prose; the SSH way reuses the key path the per-repo sheet
#       already holds, and no token is logged or persisted here.
#   P6  the listing comes from the declaration: no endpoint literal in the
#       page's sources, the parser is pure and JVM-tested, and the two groups
#       are the PROVIDER's own `private` flag, each alphabetical.
#   P7  the reads are real reads: hooks = .git/hooks minus *.sample, workflows =
#       .github/workflows/*.y(a)ml, size walks the tree without following a
#       symlink, all three JVM-tested by name.
#   P8  DENSE: the repository row is a Row with a height floor and a status DOT,
#       NOT a DriveCard; the operations rail lives behind the row's expand
#       disclosure; and the clone lands in SharedStore.repoDir.
#   M   mutation-proof: an operation dropped from the declaration → P3 red; a
#       section id misspelt in the screen → P3 red; a private repository added
#       to the public set → P2 red; forcePush removed from the engine → P4 red;
#       the unmutated tree stays green.
#
# OWN-SOURCE ONLY: everything read here is under this app or a module dir its
# build.json declares. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
PAGE="$SRC/sync/GitReposScreen.kt"
# #642 the two files the terminal handoff must reach beyond the screen: the build that resolves
# the fleet id to a package, and the manifest that must hold the permission it needs.
GRADLE="$APP/app/build.gradle"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
COORD="$SRC/sync/GitSyncCoordinator.kt"
LIST="$SRC/sync/GitHubRepos.kt"
SCAN="$APP/../ab_cloud-libs-shared/libs/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/GitRepoScan.kt"
DECL="$SRC/Declarations.kt"
STORE="$SRC/SharedStore.kt"
REPOS="$APP/data/drive-git-repos.json"
JVM="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/GitPageTest.kt"
GIT_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$BJ" "$APP")"
ENGINE="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitEngine.kt"
ENGINE_TEST="$GIT_MOD/src/test/java/com/diegonmarcos/cloudlib/gitsync/GitEngineClientTest.kt"
AUTH_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:auth"]["dir"])))' "$BJ" "$APP")"
AUTH_UI="$AUTH_MOD/src/main/java/com/diegonmarcos/cloudlib/auth/SignInUi.kt"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
STR="$APP/app/src/main/res/values/strings.xml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$PAGE" "$COORD" "$LIST" "$SCAN" "$DECL" "$STORE" "$REPOS" "$JVM" "$ENGINE" "$ENGINE_TEST" "$AUTH_UI"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block can run them on copies ──

# p1 <build.json>
p1() {
    python3 - "$1" <<'PYTHON'
import json, sys
g = ((json.load(open(sys.argv[1]))["ui"].get("sync") or {}).get("git") or {})
bad = 0
if [s.get("id") for s in g.get("sections", [])] != ["public", "personal"]:
    print("    ui.sync.git.sections is %s, not public/personal" % [s.get("id") for s in g.get("sections", [])]); bad = 1
if any(not s.get("label") or not s.get("icon") for s in g.get("sections", [])):
    print("    a section lacks a label or an icon"); bad = 1
if not any(s.get("id") == "personal" and s.get("login_required") for s in g.get("sections", [])):
    print("    the personal section does not state login_required"); bad = 1
if [x.get("id") for x in g.get("personal_groups", [])] != ["public", "private"]:
    print("    ui.sync.git.personal_groups is %s, not public/private" % [x.get("id") for x in g.get("personal_groups", [])]); bad = 1
kinds = [w.get("kind") for w in g.get("login_ways", [])]
# #641 the ONLY declared way is the user's own SSH key. The `webauth` way drove an OAuth
# device grant against a GitHub APP client, whose Device Flow the provider ships OFF, so it
# is deleted rather than worded; the HTTPS credential comes from the vault import alone.
if kinds != ["ssh_key"]:
    print("    login_ways carries %s: the ssh_key way is the only one, and no browser login may come back" % kinds); bad = 1
if next((w for w in g.get("login_ways", []) if w.get("kind") == "ssh_key"), {}).get("lists"):
    print("    the ssh_key way claims it lists an account: SSH cannot"); bad = 1
api = g.get("api") or {}
if not (api.get("base_url") or "").startswith("https://") or not api.get("repos_path") or (api.get("max_pages") or 0) < 1:
    print("    ui.sync.git.api is incomplete: %r" % api); bad = 1
if "{owner}" not in (api.get("web_url") or "") or "{name}" not in (api.get("web_url") or ""):
    print("    api.web_url does not carry the {owner}/{name} placeholders"); bad = 1
modes = {m.get("id"): m for m in g.get("remote_modes", [])}
if sorted(modes) != ["https", "readonly", "ssh"]:
    print("    remote_modes is %s, not https/ssh/readonly" % sorted(modes)); bad = 1
for mid, m in modes.items():
    if "{owner}" not in (m.get("url") or "") or "{name}" not in (m.get("url") or ""):
        print("    remote mode %s has no {owner}/{name} url shape" % mid); bad = 1
if not (modes.get("readonly") or {}).get("read_only"):
    print("    the read-only mode does not state read_only: true, so push would be offered on it"); bad = 1
if (modes.get("ssh") or {}).get("url", "").startswith("https"):
    print("    the ssh mode's url is an https url"); bad = 1
ops = [o.get("id") for o in g.get("ops", [])]
want = ["fetch", "pull", "commit", "push", "force_push", "force_pull", "metadata", "web",
        "stats", "history", "remote", "hooks", "actions", "size"]
if ops != want:
    print("    ui.sync.git.ops is %s, not the fourteen declared operations in order %s" % (ops, want)); bad = 1
if any(not o.get("label") or not o.get("icon") for o in g.get("ops", [])):
    print("    an operation lacks a label or an icon"); bad = 1
destructive = sorted(o["id"] for o in g.get("ops", []) if o.get("destructive"))
if destructive != ["force_pull", "force_push"]:
    print("    the destructive operations are %s: exactly force_push and force_pull must be marked" % destructive); bad = 1
if not g.get("owner") or not g.get("name_families") or not g.get("public_repos"):
    print("    owner, name_families or public_repos is empty"); bad = 1
sys.exit(bad)
PYTHON
}

# p2 <build.json> <drive-git-repos.json> [registry copy, default data/repos.sot.json]
p2() {
    python3 - "$1" "$2" "${3:-$APP/data/repos.sot.json}" <<'PYTHON'
import json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
# THIS repository's current name comes from the registry copy the app pins (data/repos.sot.json), found by
# the name it had before the cloud-* -> cloudy-* rename, so a later rename follows the registry, not this file.
me = [r["name"] for r in json.load(open(sys.argv[3]))["repos"] if "cloud-u-android" in r.get("renamed_from", [])]
if len(me) != 1:
    print("    the pinned registry copy does not name exactly one repository that was cloud-u-android: %s" % me); sys.exit(1)
me = me[0]
manifest = json.load(open(sys.argv[2]))["repos"]
families = tuple(g["name_families"])
names = [r["repo"] for r in g["public_repos"]]
bad = 0
strays = [n for n in names if not n.startswith(families)]
if strays:
    print("    public_repos entries outside the declared name families %s: %s" % (list(families), strays)); bad = 1
if len(set(names)) != len(names):
    print("    public_repos has duplicates"); bad = 1
if names != sorted(names):
    print("    public_repos is not alphabetical by name: %s" % names); bad = 1
if any(not r.get("caption") for r in g["public_repos"]):
    print("    a public_repos entry has no caption"); bad = 1
if me not in names:
    print("    %s is missing from the declared public set (#608 names it explicitly)" % me); bad = 1
by_name = {r["name"]: r for r in manifest}
for r in manifest:
    if r.get("seed") and r["name"] not in names:
        print("    %s is seeded into the store but absent from the page's public set" % r["name"]); bad = 1
private_here = [n for n in names if by_name.get(n, {}).get("private")]
if private_here:
    print("    the public set carries repositories the seed manifest marks private: %s" % private_here); bad = 1
sys.exit(bad)
PYTHON
}

# p3 <build.json> <GitReposScreen.kt>
p3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
src = open(sys.argv[2], encoding="utf-8").read()
bad = 0
def dispatched(subject):
    """The quoted ids of `when (subject) { ... }`, delimited by BRACE MATCHING — a
    regex that stops at the first closing brace reads only the first branch, and
    would then report a one-branch dispatch as complete."""
    head = "when (%s) {" % subject
    at = src.find(head)
    if at < 0: return None
    i = at + len(head)
    depth = 1
    while i < len(src) and depth:
        if src[i] == "{": depth += 1
        elif src[i] == "}": depth -= 1
        i += 1
    body = src[at + len(head):i - 1]
    # Only the branch LABELS: a quoted literal at the start of a line, or after a comma
    # on that same label list. Anything inside a branch body is not a dispatched id.
    ids = []
    for line in body.splitlines():
        m = re.match(r'\s*("[a-z_]+"(?:\s*,\s*"[a-z_]+")*)\s*->', line)
        if m: ids += re.findall(r'"([a-z_]+)"', m.group(1))
    return sorted(set(ids))
sections = dispatched("section.id")
if sections is None:
    print("    GitReposScreen has no `when (section.id)` dispatch"); sys.exit(1)
declared_sections = sorted(s["id"] for s in g["sections"])
if sections != declared_sections:
    print("    sections declared %s vs dispatched %s" % (declared_sections, sections)); bad = 1
ops = dispatched("op.id")
if ops is None:
    print("    GitReposScreen has no `when (op.id)` dispatch"); sys.exit(1)
declared_ops = sorted(o["id"] for o in g["ops"])
if ops != declared_ops:
    print("    operations declared %s vs dispatched %s" % (declared_ops, ops)); bad = 1
sys.exit(bad)
PYTHON
}

# p4 <GitSyncCoordinator.kt> <GitEngine.kt>
p4() {
    grep -qE 'when \(opId\) \{' "$1" &&
    grep -qE 'OP_FETCH -> e\.fetch\(auth = auth\)' "$1" &&
    grep -qE 'OP_PULL -> e\.pull\(rebase = repo\.pullRebase, auth = auth\)' "$1" &&
    grep -qE 'e\.commit\(' "$1" &&
    grep -qE 'OP_PUSH -> e\.push\(auth = auth\)' "$1" &&
    grep -qE 'OP_FORCE_PUSH -> e\.forcePush\(auth = auth\)' "$1" &&
    grep -qE 'OP_FORCE_PULL -> e\.forcePull\(auth = auth\)' "$1" &&
    grep -qE '^\s*fun forcePush\(' "$2" &&
    grep -qE '^\s*fun forcePull\(' "$2"
}

echo "── P1 the declaration is whole and ordered ──"
p1 "$BJ" && pass "ui.sync.git declares both sections, both groups, both login ways, the https api, the three remote modes and the fourteen operations" || fail "ui.sync.git is not what #608 declares"

echo "── P2 the public set is derived, not hand-picked ──"
p2 "$BJ" "$REPOS" && pass "every declared public repository is in a declared name family, unique, alphabetical; this repository (by its registry name) is in it; the seed set is a subset; no manifest-private repository is" || fail "the declared public set disagrees with its own rule or with the seed manifest"

echo "── P3 declaration ↔ dispatch, both directions ──"
p3 "$BJ" "$PAGE" && pass "the screen dispatches exactly the declared sections and exactly the declared operations" || fail "the page's dispatch and the declaration disagree"

echo "── P4 every git verb is the engine's ──"
p4 "$COORD" "$ENGINE" && pass "the coordinator runs each transport/commit operation through GitEngine, force verbs included" || fail "an operation does not go through the engine (or the engine lacks the force verbs)"
# #871 move 11: the force verbs' behaviour on real repositories (JGit) is proven where JGit ships, by
# JGitEngineTest in libs:git-core (PIT-gated);
# this app's suite proves the client SENDS both force verbs.
if grep -qE 'forcePush\(\)' "$ENGINE_TEST" && grep -qE 'forcePull\(\)' "$ENGINE_TEST" && grep -qE '"force_push", "force_pull"|"push", "fetch", "pull", "force_push", "force_pull"' "$ENGINE_TEST"; then
    pass "the client's JVM suite sends both force verbs through the wire"
else
    fail "GitEngineClientTest does not send the force verbs"
fi
if grep -rqE 'org\.eclipse\.jgit' "$SRC"; then fail "the app imports JGit directly — libs:git-sync is the engine"; else pass "no JGit in the app: every verb goes through the engine"; fi

echo "── P5 #653 no GITHUB login surface on this page; #1083 GitHub is a shared OAuth App, never a GitHub App ──"
# The provider was a GitHub APP (`Ov23li` client_id prefix) and GitHub Apps ship with Device
# Flow OFF, so Start could never work. The client id must still be absent everywhere.
#
# #653 NARROWED, FOR THE SAME REASON A3 WAS. This forbade ANY libs:auth sign-in surface on
# the page, and that was right when the only login the page could host was a GITHUB device
# grant. That flow no longer exists anywhere in this app: GhDeviceLogin.kt is deleted, and
# the surface the page must now host is the fleet's OWN browser login — authelia_web, the
# declared session_provider of the fleet rung, which is what makes a credential-free listing
# possible at all. Forbidding it would forbid the fix.
#
# What is asserted instead is the half that always mattered: NO GITHUB SIGN-IN SURFACE. A
# sign-in surface is permitted ONLY scoped to the DECLARED session_provider, read off the
# declaration rather than typed, so the page cannot offer a second provider without the
# declaration changing and cannot name github under any spelling.
if grep -qE 'SignInWays|SignInHost|SignInResult' "$PAGE"; then
    if grep -qE 'policy = listOf\(FleetGit\.sessionProvider\(\)\)' "$PAGE"; then
        pass "the page's one sign-in surface is scoped to the DECLARED session_provider"
    else
        fail "a sign-in surface on the page is not scoped to the declared session_provider — it could offer any way, including a GitHub one"
    fi
    if grep -qE 'policy = listOf\("' "$PAGE"; then fail "the page scopes its sign-in to a LITERAL provider id instead of the declared one"; else pass "the scope is declared, not typed"; fi
else
    pass "no sign-in surface on the page at all"
fi
if grep -qiE 'policy *= *listOf\([^)]*github|Open\.Device|DeviceFlowDialog|SignIn\.Kind\.DEVICE_FLOW' "$PAGE"; then fail "a GitHub / device-flow sign-in surface is on the page"; else pass "no GitHub and no device-flow sign-in surface on the page"; fi
if grep -qE 'webauth' "$PAGE"; then fail "the page still reads a webauth way"; else pass "no webauth way is read in Kotlin"; fi
# #1083 re-declared GitHub in the SHARED declaration as the owner's own OAuth App (client ids with the
# Ov23li prefix ARE OAuth Apps, which ship Device Flow ON; the deleted #641 provider carried a GitHub
# APP id, Iv1./Iv23, whose Device Flow is OFF). What stays forbidden is a GitHub App id anywhere, and
# ANY client id in THIS app's declaration or on the page: the id lives in the shared declaration only.
if grep -qE 'Ov23li|Iv1\.|Iv23' "$BJ" "$PAGE"; then fail "a GitHub client id is typed in this app's declaration or on the page"; else pass "no GitHub client id in this app's declaration or on the page"; fi
python3 - "$SHARED_BJ" <<'PYTHON'
import json, re, sys
p = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["sign_in"]["providers"]
flows = [x for x in p if x.get("kind") == "device_flow"]
bad = 0
# NOT ">= 0": the check must not pass by finding no device grant at all.
if [x.get("id") for x in flows] != ["google", "github"]:
    print("    the shared declaration carries %r as its device grants; exactly Google's and the GitHub OAuth App's are expected"
          % [x.get("id") for x in flows]); bad = 1
for x in p:
    for k in ("client_id", "fallback_client_id"):
        if re.match(r"(Iv1\.|Iv23)", x.get(k) or ""):
            print("    provider %s carries a GitHub APP client id in %s (Device Flow is OFF on GitHub Apps)" % (x.get("id"), k)); bad = 1
gh = next((x for x in p if x.get("id") == "github"), None)
if not gh or gh.get("client_secret") or not (gh.get("client_id") or "").startswith("Ov23li") or not gh.get("fallback_client_id"):
    print("    the github provider must be a secret-less OAuth App (Ov23li client_id) with a public fallback_client_id"); bad = 1
sys.exit(1 if bad else 0)
PYTHON
if [ $? -eq 0 ]; then pass "the shared declaration keeps exactly two device grants (Google's, and GitHub's OAuth App with no secret and no GitHub App id)"; else fail "the shared declaration's device grants are not Google's plus the secret-less GitHub OAuth App"; fi
if grep -qE 'github\.com/settings/apps|Configs . Sign in' "$PAGE" "$STR"; then fail "the page still points at a provider setting or another page"; else pass "no breadcrumb prose: the page states its own state and stops"; fi
# #646 THIS ASSERTION IS INVERTED, and the reason is a measurement, not a preference.
# #641 required this line to end "there is no browser login", which was TRUE of the client it
# had just deleted: ours was a GitHub APP and GitHub Apps ship Device Flow OFF. The official gh
# binary turned out to carry GITHUB'S OWN app ids and both return live device codes, so the
# sentence became false. What is pinned now is the opposite AND the absence of the old claim:
# the page must still state the credential-absent case, must name the DECLARED CHAIN as the way
# out of it, and must NOT still deny that a browser login exists.
if grep -qE 'R\.string\.git_login_vault_absent' "$PAGE" \
    && grep -qE 'git_login_vault_absent">[^<]*declared chain' "$STR" \
    && ! grep -qE 'git_login_vault_absent">[^<]*no browser login' "$STR"; then
    pass "#646 with no vault credential the page says so and names the declared chain, and no longer denies a browser login"
else
    fail "the credential-absent line does not name the declared chain (or still claims there is no browser login)"
fi
if grep -qE 'R\.string\.sync_auth_key_path' "$PAGE" && grep -qE 'sshKeyPath = if \(ssh\) login\.sshKeyPath else ""' "$PAGE" && grep -qE 'GitSyncCoordinator\.AUTH_SSH' "$PAGE"; then pass "the SSH way reuses the key path libs:git-sync already holds — no second key mechanism"; else fail "the SSH login does not reuse the existing key mechanism"; fi
LEAK="$(grep -nE 'Log\.[a-z]+\(.*(token|accessToken)|putString\(.*token' "$PAGE" "$LIST" || true)"
if [ -z "$LEAK" ]; then pass "no token is logged or written to preferences by the page"; else fail "a token leaves memory:"; printf '%s\n' "$LEAK" | sed 's/^/        /'; fi

echo "── P6 the listing comes from the declaration ──"
URLS="$(grep -nE '"https://' "$PAGE" "$LIST" "$COORD" || true)"
if [ -z "$URLS" ]; then pass "no endpoint literal beside the declaration"; else fail "an endpoint is typed into Kotlin:"; printf '%s\n' "$URLS" | sed 's/^/        /'; fi
if grep -qE 'fun parse\(text: String\): List<Repo>' "$LIST" && grep -qE 'fun fetch\(api: Declarations\.GitApiDecl, token: String\)' "$LIST"; then pass "the parser takes TEXT (JVM-testable) and the fetch takes the declared api"; else fail "GitHubRepos is not split into a pure parser and a declared fetch"; fi
if grep -qE 'fun group\(repos: List<Repo>, wantPrivate: Boolean\): List<Repo> =\s*$' "$LIST" && grep -qE 'repos\.filter \{ it\.private == wantPrivate \}\.sortedBy \{ it\.name\.lowercase\(\) \}' "$LIST"; then pass "the two groups are the PROVIDER's own private flag, each alphabetical"; else fail "the public/private split is not the provider's flag (or is unsorted)"; fi
if ! grep -qE 'page\.personalGroups|GIT_GROUP_PRIVATE' "$PAGE" && grep -qE 'stickyHeader\(' "$PAGE" && grep -qE 'val allEntries: List<TableEntry>' "$PAGE"; then pass "ONE table over every listing: no Public/Private groups, a sticky header, one sortable entry list"; else fail "the personal section still splits into public/private groups or has no sticky header"; fi
if grep -qE 'complete = false' "$LIST" && grep -qE 'R\.string\.git_listing_truncated' "$PAGE"; then pass "a listing stopped at the declared page cap SAYS so instead of showing a silent prefix"; else fail "a truncated listing is not reported"; fi

echo "── P7 the reads are real reads ──"
if grep -qE '\.git/hooks' "$SCAN" && grep -qE '!it\.name\.endsWith\("\.sample"\)' "$SCAN"; then pass "hooks = .git/hooks minus git's own samples"; else fail "the hooks read would count git's sample files as installed hooks"; fi
if grep -qE '\.github/workflows' "$SCAN" && grep -qE 'endsWith\("\.yml"\) \|\| it\.name\.endsWith\("\.yaml"\)' "$SCAN"; then pass "workflows = .github/workflows/*.y(a)ml"; else fail "the workflow read does not look where GitHub looks"; fi
if grep -qE 'Files\.isSymbolicLink\(child\.toPath\(\)\)' "$SCAN"; then pass "the size walk counts a symlink and never follows it (a link up the tree would walk forever)"; else fail "the size walk follows symlinks"; fi
for t in hooksSkipTheSamples workflowsAreTheYamlOnes sizeCountsTheTreeAndTheGitDirSeparately listingSplitsOnTheProvidersOwnFlag declaredPublicSetMatchesItsOwnRule remoteModesComposeTheDeclaredUrls; do
    grep -qE "fun $t\(" "$JVM" && pass "the JVM suite proves $t" || fail "GitPageTest lacks $t"
done

echo "── P8 dense, not a card per repository ──"
if grep -qE 'private fun GitRepoRow\(' "$PAGE" && grep -qE 'testTag\(DriveTags\.SYNC_GIT_ROW\)' "$PAGE" && grep -qE 'RepoTableLine\(scroll, Modifier\.testTag\(DriveTags\.SYNC_GIT_ROW\)' "$PAGE" && grep -qE 'heightIn\(min = k\.rowHeight\)' "$ROOT/ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui/RepoTableUi.kt" && grep -qE 'RepoStatusCell\(icons, name, onOpenStatus' "$PAGE" && grep -qE 'StatusDot\(lo, ' "$(dirname "$PAGE")/RepoTableUi.kt"; then pass "one compact Row per repository, with a height floor and the one-character light in its own leftmost cell (#1238)"; else fail "the repository row is not the dense row #608 asked for"; fi
if python3 - "$PAGE" <<'PYTHON'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
row = re.search(r"private fun GitRepoRow\((.*?)\n@Composable", src, re.S)
if not row: print("    GitRepoRow not found"); sys.exit(1)
if "DriveCard(" in row.group(1): print("    GitRepoRow draws a DriveCard: the dense row must not be a card"); sys.exit(1)
sys.exit(0)
PYTHON
then pass "the row itself draws no card — the card is what EXPANDING it shows"; else fail "the dense row is a card again"; fi
if grep -qE 'private fun GitRepoOpsBox\(' "$PAGE" && grep -qE 'testTag\(DriveTags\.SYNC_GIT_OPS\)' "$PAGE" && grep -qE 'sortedEntries\.firstOrNull \{ it\.key == expanded \}' "$PAGE" && grep -qE 'DriveTags\.SYNC_GIT_DETAIL_SHEET' "$PAGE" && grep -qE 'Icons\.Filled\.ExpandLess else Icons\.Filled\.ExpandMore' "$PAGE"; then pass "the operations rail is collapsed behind the row's own disclosure"; else fail "the operations are not collapsed behind a disclosure"; fi
if grep -qE 'confirm = op' "$PAGE" && grep -qE 'R\.string\.git_confirm_force_push' "$PAGE" && grep -qE 'R\.string\.git_confirm_force_pull' "$PAGE"; then pass "a destructive operation asks first and says which side it destroys"; else fail "force push / force pull run on the tap"; fi
if grep -qE 'val blocked = readOnly && \(op\.id == GitSyncCoordinator\.OP_PUSH \|\| op\.id == GitSyncCoordinator\.OP_FORCE_PUSH\)' "$PAGE" && grep -qE 'R\.string\.git_readonly_blocked' "$PAGE"; then pass "a read-only remote refuses push and force push instead of offering a button that always fails"; else fail "the read-only remote mode is not enforced"; fi
if grep -qE 'val dir = SharedStore\.repoDir\(name\)' "$COORD"; then pass "a clone lands in the store's git folder, the same one the seed uses"; else fail "the page's clone does not land in SharedStore.repoDir"; fi

echo "== T #642 git is handed to cloud-terminal, and the wiring exists =="
# The DECLARATION and the four places it must reach. Every one of these is the shape where a
# feature is declared and then silently not wired: a fleet id gradle never resolves, a manifest
# without the permission (SecurityException), a screen that never reports the outcome.
t642() {
python3 - "$1" "$2" "$3" "$4" <<'PYEOF'
import json, re, sys
bj, gradle, manifest, page = sys.argv[1:5]
g = json.load(open(bj))["ui"]["sync"]["git"]
t = g.get("terminal")
bad = 0
if not isinstance(t, dict) or not t.get("fleet") or not t.get("action") or not t.get("ops", {}).get("clone"):
    print("    ui.sync.git.terminal must declare fleet + action + ops.clone"); bad = 1
else:
    if "{package}" not in t["action"] or "{package}" not in t.get("command", ""):
        print("    the action/command must be derived from {package}, never a typed package id"); bad = 1
    if t["ops"]["clone"][:2] != ["git", "clone"]:
        print("    ops.clone is not a git clone: %s" % t["ops"]["clone"]); bad = 1
    if "{url}" not in t["ops"]["clone"] or "{dest}" not in t["ops"]["clone"]:
        print("    ops.clone must carry {url} and {dest} as separate argv words"); bad = 1
gr = open(gradle).read()
if "uiDecl.sync?.git?.terminal" not in gr or "fleetById[term.fleet]" not in gr or "GradleException" not in gr:
    print("    app/build.gradle does not resolve terminal.fleet from the fleet manifest, or does not hard-fail"); bad = 1
if "manifestPlaceholders" not in gr or "terminalPackage" not in gr:
    print("    the resolved package is not handed to the manifest as a placeholder"); bad = 1
mf = open(manifest).read()
if "${terminalPackage}.permission.RUN_COMMAND" not in mf:
    print("    AndroidManifest declares no RUN_COMMAND uses-permission — the call would be refused"); bad = 1
if "<package android:name=\"${terminalPackage}\" />" not in mf:
    print("    the terminal is not in <queries> — 'not installed' and 'invisible' would be the same answer"); bad = 1
pg = open(page).read()
for token in ("TerminalGit.run(", "R.string.git_terminal_sent", "R.string.git_terminal_absent",
              "R.string.git_terminal_no_service", "DriveTags.SYNC_GIT_HANDOFF"):
    if token not in pg:
        print("    the screen never uses %s — an outcome would go unreported" % token); bad = 1
print("T642 " + ("OK" if not bad else "BAD"))
PYEOF
}
T642="$(t642 "$BJ" "$GRADLE" "$MANIFEST" "$PAGE" | tail -1)"
if [ "$T642" = "T642 OK" ]; then pass "a clone is handed to cloud-terminal: declared by fleet id, resolved in gradle, permitted in the manifest, and every outcome reported"; else t642 "$BJ" "$GRADLE" "$MANIFEST" "$PAGE" | sed -n '1,8p'; fail "the #642 terminal handoff is declared but not wired"; fi

echo "== M mutation-proof =="
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["ops"]=b["ui"]["sync"]["git"]["ops"][:-1]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-size-op.json"
p3 "$TMP/no-size-op.json" "$PAGE" >/dev/null && fail "P3 passed a declaration missing an operation the screen handles (tester is vacuous)" || pass "an operation dropped from ui.sync.git.ops → P3 RED"
p1 "$TMP/no-size-op.json" >/dev/null && fail "P1 passed a thirteen-operation declaration (tester is vacuous)" || pass "the same drop → P1 RED"
sed 's/"personal" -> {/"personnal" -> {/' "$PAGE" > "$TMP/page.kt"
cmp -s "$PAGE" "$TMP/page.kt" && fail "the section mutation changed nothing (tester is stale)"
p3 "$BJ" "$TMP/page.kt" >/dev/null && fail "P3 passed a misspelt section dispatch (tester is vacuous)" || pass "a section id misspelt in the screen → P3 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["public_repos"].append({"repo":"cloud-me-pvt-my-ai","caption":"x"}); b["ui"]["sync"]["git"]["public_repos"].sort(key=lambda r:r["repo"]); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/private-in-public.json"
p2 "$TMP/private-in-public.json" "$REPOS" >/dev/null && fail "P2 passed a PRIVATE repository in the public set (tester is vacuous)" || pass "a private repository added to the public set → P2 RED"
grep -v '    fun forcePush(' "$ENGINE" > "$TMP/engine.kt"
cmp -s "$ENGINE" "$TMP/engine.kt" && fail "the engine mutation changed nothing (tester is stale)"
p4 "$COORD" "$TMP/engine.kt" >/dev/null && fail "P4 passed an engine without forcePush (tester is vacuous)" || pass "forcePush removed from the engine → P4 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["terminal"]["action"]="cld.termux.nix.RUN_COMMAND"; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/typed-package.json"
grep -q '^T642 OK$' <<<"$(t642 "$TMP/typed-package.json" "$GRADLE" "$MANIFEST" "$PAGE")" && fail "T642 passed a hardcoded package id in the action (tester is vacuous)" || pass "the terminal action retyped as a package literal → T642 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); del b["ui"]["sync"]["git"]["terminal"]["ops"]["clone"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-clone-op.json"
grep -q '^T642 OK$' <<<"$(t642 "$TMP/no-clone-op.json" "$GRADLE" "$MANIFEST" "$PAGE")" && fail "T642 passed a terminal block with no clone argv (tester is vacuous)" || pass "ops.clone dropped → T642 RED"
grep -v 'uses-permission android:name="${terminalPackage}.permission.RUN_COMMAND"' "$MANIFEST" > "$TMP/manifest.xml"
cmp -s "$MANIFEST" "$TMP/manifest.xml" && fail "the manifest mutation changed nothing (tester is stale)"
grep -q '^T642 OK$' <<<"$(t642 "$BJ" "$GRADLE" "$TMP/manifest.xml" "$PAGE")" && fail "T642 passed a manifest without RUN_COMMAND (tester is vacuous)" || pass "RUN_COMMAND removed from the manifest → T642 RED"
grep -v 'R.string.git_terminal_no_service' "$PAGE" > "$TMP/page642.kt"
cmp -s "$PAGE" "$TMP/page642.kt" && fail "the screen mutation changed nothing (tester is stale)"
grep -q '^T642 OK$' <<<"$(t642 "$BJ" "$GRADLE" "$MANIFEST" "$TMP/page642.kt")" && fail "T642 passed a screen that swallows the no-service outcome (tester is vacuous)" || pass "the no-service line deleted from the screen → T642 RED"
p1 "$BJ" >/dev/null && p2 "$BJ" "$REPOS" >/dev/null && p3 "$BJ" "$PAGE" >/dev/null && p4 "$COORD" "$ENGINE" >/dev/null \
    && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-page: all checks passed"; else echo "test-drive-git-page: $FAILURES check(s) FAILED"; exit 1; fi
