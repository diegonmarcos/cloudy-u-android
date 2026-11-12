#!/bin/sh
# #747 — /api/terminal/exec and /api/terminal/selftest are wired, and run what a session runs.
#
# The route exists to test this terminal from outside the UI, so it is only worth anything while
# it (a) is registered from the application start path, which runs whenever the process does —
# no activity, no session; (b) enters through $PREFIX/bin/login, the entry a typed session
# takes, not a shell picked by hand; (c) bootstraps headlessly through the same installer the
# activity uses; and (d) runs the declared selftest list, which both terminals carry and must
# keep equal. Each assertion below goes red when one of those is undone.
#
# Static, offline, no Android SDK: it reads source only.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP="$DIR/app/src/main/java/com/termux/app"
API="$APP/TerminalDebugApi.java"
SELFTEST="$DIR/../data/fleet/app/cld.termux/terminal-selftest.json"   # #1547 served at runtime (Cloud Store dataset), not an asset
SIBLING="$DIR/../data/fleet/app/cld.termux.nix/terminal-selftest.json"   # #1547 the sibling terminal serves its list the same way

fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── #747 terminal debug API assertions [$(basename "$DIR")] ──"

grep -q 'TerminalDebugApi.register(this)' "$APP/TermuxApplication.java" \
    && ok "registered from TermuxApplication.onCreate (every process start, no activity needed)" \
    || bad "TermuxApplication does not register TerminalDebugApi: the routes never exist"

grep -q 'AppDebugServer.INSTANCE.route("terminal"' "$API" \
    && ok "a group on the shared fleet debug server (its token gate, no second server)" \
    || bad "TerminalDebugApi does not register the 'terminal' group on AppDebugServer"

for op in '"exec"' '"selftest"'; do
    grep -q "case $op:" "$API" && ok "serves $op" || bad "does not serve $op"
done

grep -q 'TERMUX_BIN_PREFIX_DIR_PATH + "/login"' "$API" \
    && ok "enters through \$PREFIX/bin/login, the entry a typed session takes" \
    || bad "the command does not go through login: it would run outside the session's rootfs/env"

grep -q 'TermuxInstaller.ensureInstalled(app)' "$API" \
    && grep -q 'static void ensureInstalled(Context context)' "$APP/TermuxInstaller.java" \
    && ok "bootstraps headlessly through TermuxInstaller.ensureInstalled" \
    || bad "no headless bootstrap: the route fails on a phone where the terminal was never opened"

grep -q 'synchronized (INSTALL_LOCK)' "$APP/TermuxInstaller.java" \
    && [ "$(grep -c 'synchronized (INSTALL_LOCK)' "$APP/TermuxInstaller.java")" -ge 2 ] \
    && ok "the activity's install and the route's share one lock" \
    || bad "the activity and the route can extract into one \$PREFIX at once"

grep -q 'PARTIAL_WAKE_LOCK' "$API" && grep -q 'startForegroundService' "$API" \
    && ok "holds a wake lock and the foreground service while a command runs (screen locked)" \
    || bad "nothing keeps the CPU or the process up with the screen locked"

# #786 — a phone ran the pre-#771 list against a pre-#771 tree while every published asset was
# current; nothing in the answer could say so. The list is read from THIS APK, every route call
# re-stages when the installed lib's digest moves (enter.sh then re-unpacks; $HOME is bound from
# outside the tree, so it is kept), and the answer names the builds that produced it.
ROOTFS_JAVA="$DIR/app/src/main/java/com/termux/cloud/CloudRootfs.java"
[ ! -e "$DIR/app/src/main/assets/terminal-selftest.json" ] && ok "terminal-selftest.json is not baked into the APK (#1547)" || bad "terminal-selftest.json is still an APK asset (#1547)"
grep -q 'FleetData.INSTANCE.get(app, SELFTEST_DATASET' "$API" && ! grep -q 'getAssets().open(SELFTEST' "$API" \
    && ok "the selftest list is read through FleetData.get (libs:fleet-data-client), never an unpacked copy" \
    || bad "TerminalDebugApi does not read the selftest list through FleetData"
python3 - "$DIR/../data/fleet/manifest.json" "$SELFTEST" <<'PY2' && ok "manifest lists terminal-selftest for cld.termux; the served file has schemaVersion" || bad "manifest/schemaVersion for terminal-selftest"
import json, sys
m = json.load(open(sys.argv[1]))
assert "terminal-selftest" in m["apps"]["cld.termux"]
assert json.load(open(sys.argv[2]))["schemaVersion"] == 1
PY2
grep -q 'CloudRootfs.stage(context);' "$APP/TermuxInstaller.java" \
    && grep -q 'String want = field(libManifest(context), "sha256");' "$ROOTFS_JAVA" \
    && ok "every route call re-stages when the installed lib's digest differs from the staged one" \
    || bad "a newly installed rootfs lib is not adopted: the route keeps the tree it staged first"
for key in app_apk_sha256 lib_version_code lib_version lib_sha256 staged_sha256 unpacked_sha256; do
    grep -q "\"$key\"" "$ROOTFS_JAVA" || bad "CloudRootfs.installed() does not report $key"
done
grep -q '.put("installed", CloudRootfs.installed(app))' "$API" \
    && ok "the selftest answer names the app APK, lib and staged/unpacked digests it ran against" \
    || bad "the selftest answer does not say which builds produced it"

python3 - "$SELFTEST" "$SIBLING" "$DIR/../ab_cloud-terminal-store/pty-selftest.json" "$DIR/../ab_cloud-terminal-store/store.json" <<'PY' && ok "selftest declares the #747/#771/#797 checks, each under sh, identical in both terminals" || bad "terminal-selftest.json is wrong or the two terminals' copies differ"
import json, sys
mine, sibling = (json.load(open(p))["checks"] for p in sys.argv[1:3])
problems = [] if mine == sibling else ["the two terminals' lists differ"]
# #771: the login shell is fish; one `sh -c '...'` with no ' or \ inside parses the same in fish, bash, zsh.
problems += [f"not `sh -c '<script>'` with no quote/backslash inside: {c}" for c in mine
             if not (c.startswith("sh -c '") and c.endswith("'") and len(c) > 8
                     and "'" not in c[7:-1] and "\\" not in c[7:-1])]
script = [c[7:-1] for c in mine]
def has(pred, what):
    if not any(pred(s) for s in script):
        problems.append("no check " + what)
for t in ("claude", "goose", "hermes"):
    has(lambda s, t=t: s == f"{t} --version", f"runs `{t} --version`")
    has(lambda s, t=t: s.startswith(t + " ") and s != f"{t} --version", f"makes a functional {t} call")
for t in ("node", "gh", "git", "zsh"):
    has(lambda s, t=t: s == f"{t} --version", f"runs `{t} --version`")
# #790: a non-network auth check per agent CLI, and no check can print a key.
has(lambda s: s == "claude auth status", "asks claude whether it is logged in (#790)")
for t, f in (("goose", "~/.config/goose/"), ("hermes", "~/.hermes/")):
    has(lambda s, f=f: s.startswith('test -n "$OPENROUTER_API_KEY" || grep -q') and f in s,
        f"finds a {t} provider key, the Account's or the tool's own (#790)")
problems += [f"a check could print an agent key: {c}" for c in script
             for v in ("$OPENROUTER_API_KEY", "$CLAUDE_CODE_OAUTH_TOKEN") if v in c.replace(f'test -n "{v}"', "")]
has(lambda s: s == "ls ~/emulated | head", "lists ~/emulated (#736)")
has(lambda s: s == "ls ~/cloud-shared-store/git | head", "lists the shared store (#736)")
has(lambda s: s == "test ! -e ~/storage", "proves no upstream ~/storage (#736)")
store = [s for s in script if "~/cloud-shared-store/git/*/.git" in s]
has(lambda s: s in store and "git status" in s and "|" not in s.split("git status")[1], "runs git status in a shared-store repo, unpiped")
has(lambda s: s in store and "echo one > $f" in s and "sed -i" in s and "rm $f" in s, "creates, edits and deletes a file in a shared-store repo")
has(lambda s: s in store and "#!/usr/bin/env sh" in s and "./$f" in s and "rm -f $f" in s, "runs a #! script as ./script in a shared-store repo")
# #797: every check pty-selftest.json declares runs ON THE PHONE, by name, through pty-check at the
# store's install dir (store.json), the one path both terminals bind alike. The pipe-fed checks above
# were all green on the phone whose sessions had no usable tty; only a check inside a real pty sees that.
pty, store = json.load(open(sys.argv[3])), json.load(open(sys.argv[4]))
pty_bin = "/" + store["store"]["install_dir"].strip("/") + "/pty-check"
for c in pty["checks"]:
    has(lambda s, c=c: s == f"zsh {pty_bin} {c['name']}",
        f"runs pty-selftest.json's `{c['name']}` in a real pty on the phone through {pty_bin} (#797)")
for p in problems:
    print("  " + p)
sys.exit(1 if problems else 0)
PY

# TerminalDebugApi.java constructs AppDebugServer.Op(op, params, description) from JAVA: the lib's
# Kotlin data class carries a default (maxBody, #802) that Java cannot see unless the constructor
# is @JvmOverloads — the terminal did not compile from f7478b5ca to e9769ab44 for exactly that.
DEBUG_SRV="$(dirname "$0")/../../ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
grep -q 'data class Op @JvmOverloads constructor(' "$DEBUG_SRV" \
    && ok "AppDebugServer.Op is @JvmOverloads, so this app's Java caller keeps its 3-argument constructor" \
    || bad "AppDebugServer.Op lost @JvmOverloads: TerminalDebugApi.java cannot construct it"

[ "$fails" -eq 0 ] || { echo "$fails assertion(s) failed"; exit 1; }
