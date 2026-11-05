#!/bin/sh
# #787 — this terminal holds its wake lock natively: taken when a session opens, dropped when the
# last one ends, ON by default (build.json::wake_lock.default_on), the in-app toggle still able to
# turn it off, the battery-optimization exemption asked once, and the state on /api/terminal.
#
#   B  behaviour: the app's REAL CloudWakeLock.java is compiled and driven on a JVM — no session,
#      first session (held, since stamped), second session and one closing (still held, since
#      unchanged), last one closing (released), toggle off/on, service destroyed.
#   W  wiring: the service / shell backend feeds that class the live session count from the one
#      place every session change passes, does the lock work it asks for, and the default comes
#      from build.json through gradle — read from source, nothing to install.
#   MUT each property, broken on a copy, turns this tester red; a mutation that leaves it green
#      fails it ("hollow"), and so does one whose text is no longer in the source ("stale").
#
# The SAME file is in ac_cloud-termux, ac_cloud-nix-on-droid and ac_cloud-myterminal; it picks its
# app's paths from the directory it lives in.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$DIR" <<'PY'
import json, os, re, shutil, subprocess, sys, tempfile

DIR = sys.argv[1]
APP = os.path.basename(DIR)
TERMUX_FORK = {
    "policy": "app/src/main/java/com/termux/cloud/CloudWakeLock.java",
    "owner": "app/src/main/java/com/termux/app/TermuxService.java",
    "api": "app/src/main/java/com/termux/app/TerminalDebugApi.java",
    "gradle": "app/build.gradle",
}
LAYOUT = {
    "ac_cloud-termux": dict(TERMUX_FORK, sessions="mTermuxSessions.size()"),
    "ac_cloud-nix-on-droid": dict(TERMUX_FORK, sessions="mShellManager.mTermuxSessions.size()"),
    "ac_cloud-myterminal": {
        "policy": "hub/src/main/java/com/diegonmarcos/ide/CloudWakeLock.java",
        "owner": "hub/src/main/java/com/diegonmarcos/ide/TerminalWakeLock.kt",
        "backend": "hub/src/main/java/com/diegonmarcos/ide/SshBackend.kt",
        "api": "hub/src/main/java/com/diegonmarcos/ide/App.kt",
        "configs": "hub/src/main/java/com/diegonmarcos/ide/ConfigsActivity.kt",
        "prefs": "hub/src/main/java/com/diegonmarcos/ide/IdePrefs.kt",
        "gradle": "hub/build.gradle",
    },
}[APP]
FILES = dict(LAYOUT, build_json="build.json")
FILES.pop("sessions", None)

def body(src, signature):
    """The brace-balanced body of the first method whose header contains `signature`."""
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    depth = 0
    for k in range(j, len(src)):
        depth += {"{": 1, "}": -1}.get(src[k], 0)
        if depth == 0:
            return src[j:k + 1]
    return ""

HARNESS = r'''
import %(pkg)s.CloudWakeLock;
import static %(pkg)s.CloudWakeLock.Transition.*;
public class Harness {
    static int fails = 0;
    static void check(boolean c, String m) { System.out.println((c ? "  ok   B " : "  FAIL B ") + m); if (!c) fails++; }
    public static void main(String[] a) {
        CloudWakeLock w = new CloudWakeLock();
        check(w.update(true, 0, 500) == NONE && !w.held(), "no session open: not held");
        check(w.update(true, 1, 1000) == ACQUIRE && w.held(), "first session opens: ACQUIRE, held");
        check(w.json(true).equals("{\"held\":true,\"since\":1000,\"sessions\":1,\"wanted\":true,\"wifi_lock\":true}"),
            "/api/terminal while held: " + w.json(true));
        check(w.update(true, 2, 2000) == NONE && w.held() && w.json(true).contains("\"since\":1000,"),
            "second session: no second acquire, since unchanged");
        check(w.update(true, 1, 3000) == NONE && w.held(), "one of two sessions ends: still held");
        check(w.update(true, 0, 4000) == RELEASE && !w.held(), "last session ends: RELEASE, not held");
        check(w.json(true).equals("{\"held\":false,\"since\":null,\"sessions\":0,\"wanted\":true,\"wifi_lock\":false}"),
            "/api/terminal after release: " + w.json(true));
        check(w.update(false, 1, 5000) == NONE && !w.held(), "toggle OFF: an open session does not take it");
        check(w.update(true, 1, 6000) == ACQUIRE && w.json(false).contains("\"since\":6000,"), "toggle ON with a session: ACQUIRE");
        check(w.update(false, 1, 7000) == RELEASE && !w.held(), "toggle OFF while held: RELEASE");
        w.update(true, 1, 8000);
        check(w.destroyed() == RELEASE && !w.held(), "service destroyed while held: RELEASE");
        check(w.destroyed() == NONE, "destroyed again: nothing left to release");
        System.exit(fails == 0 ? 0 : 1);
    }
}
'''

def behaviour(root):
    """Compile the app's CloudWakeLock and run the harness on it. Returns failure lines."""
    src = open(os.path.join(root, FILES["policy"])).read()
    pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
    work = tempfile.mkdtemp()
    try:
        os.makedirs(os.path.join(work, "src", *pkg.split(".")))
        shutil.copy(os.path.join(root, FILES["policy"]), os.path.join(work, "src", *pkg.split("."), "CloudWakeLock.java"))
        open(os.path.join(work, "src", "Harness.java"), "w").write(HARNESS % {"pkg": pkg})
        files = [os.path.join(work, "src", "Harness.java"), os.path.join(work, "src", *pkg.split("."), "CloudWakeLock.java")]
        c = subprocess.run(["javac", "-d", os.path.join(work, "out")] + files, capture_output=True, text=True)
        if c.returncode != 0:
            return ["B CloudWakeLock does not compile on a plain JVM (it must stay Android-free):\n" + c.stderr]
        r = subprocess.run(["java", "-cp", os.path.join(work, "out"), "Harness"], capture_output=True, text=True)
        if root == DIR:
            sys.stdout.write(r.stdout)
        return [l.strip() for l in r.stdout.splitlines() if l.startswith("  FAIL")] or (
            [] if r.returncode == 0 else ["B harness exited %d: %s" % (r.returncode, r.stderr)])
    finally:
        shutil.rmtree(work)

def wiring(root):
    """Static checks of this app's own wiring. Returns (label, ok) pairs."""
    rd = lambda k: open(os.path.join(root, FILES[k])).read()
    decl = json.loads(rd("build_json")).get("wake_lock") or {}
    owner, api, gradle = rd("owner"), rd("api"), rd("gradle")
    out = [("build.json wake_lock.default_on is true (ON by default)", decl.get("default_on") is True)]
    if APP == "ac_cloud-myterminal":
        backend, configs, prefs = rd("backend"), rd("configs"), rd("prefs")
        sync = body(owner, "fun sync(")
        open_shell = body(backend, "fun openShell(")
        out += [
            ("gradle bakes CLOUD_WAKE_LOCK_DEFAULT_ON from build.json wake_lock.default_on",
             '"CLOUD_WAKE_LOCK_DEFAULT_ON", "${buildJson.wake_lock.default_on}"' in gradle),
            ("the user's choice defaults to BuildConfig.CLOUD_WAKE_LOCK_DEFAULT_ON",
             "getBoolean(KEY_WAKE_LOCK, BuildConfig.CLOUD_WAKE_LOCK_DEFAULT_ON)" in prefs),
            ("sync() hands CloudWakeLock the wanted flag and the shell count",
             "CloudWakeLock.STATE.update(IdePrefs.wakeLockWanted(app), count," in sync),
            ("ACQUIRE takes a PARTIAL_WAKE_LOCK", "Transition.ACQUIRE -> acquire(app)" in sync
             and "PowerManager.PARTIAL_WAKE_LOCK" in body(owner, "private fun acquire(")),
            ("RELEASE releases it", "Transition.RELEASE -> lock?.release()" in sync),
            ("a shell opening is counted", "shells[id] = ShellEntry(ch, stdin, sess)\n        TerminalWakeLock.sync(ctx, shells.size)" in open_shell),
            ("a shell closing on its own is counted", "shells.remove(id)\n                TerminalWakeLock.sync(ctx, shells.size)" in open_shell),
            ("a shell killed is counted", "TerminalWakeLock.sync(ctx, shells.size)" in body(backend, "fun kill(")),
            ("disconnectAll drops it", "TerminalWakeLock.sync(ctx, 0)" in body(backend, "fun disconnectAll(")),
            ("Configs toggles the choice and re-syncs", "IdePrefs.setWakeLockWanted(this, !wakeWanted) TerminalWakeLock.sync(this)" in " ".join(configs.split())),
            ("the exemption is the Perms battery_opt row, never asked by the wake lock (#1515)", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in owner
             and "battery_opt" in json.loads(rd("build_json"))["perms"]["needs"]),
            ("/api/terminal and /api/terminal/wakelock answer the state",
             'if (op == "" || op == "wakelock") CloudWakeLock.STATE.json(false)' in api),
        ]
        return out
    update_note = body(owner, "private synchronized void updateNotification()")
    sync = body(owner, "private synchronized void syncWakeLock()")
    acquire = body(owner, "private void acquireLocks()")
    destroy = body(owner, "public void onDestroy()")
    out += [
        ("build.json wake_lock.wifi_lock is a declared boolean", isinstance(decl.get("wifi_lock"), bool)),
        ("gradle bakes both flags from build.json wake_lock",
         '"CLOUD_WAKE_LOCK_DEFAULT_ON", "${cloudWakeLock().default_on}"' in gradle
         and '"CLOUD_WIFI_LOCK", "${cloudWakeLock().wifi_lock}"' in gradle),
        ("the user's choice defaults to BuildConfig.CLOUD_WAKE_LOCK_DEFAULT_ON",
         "getBoolean(KEY_WANTED, BuildConfig.CLOUD_WAKE_LOCK_DEFAULT_ON)" in owner),
        ("updateNotification (every session add/exit passes it) syncs the lock first",
         update_note.lstrip("{ \n").split("\n", 2)[1].strip() == "syncWakeLock();"),
        ("a session opening and one exiting both reach updateNotification",
         "updateNotification();" in body(owner, "public synchronized TermuxSession createTermuxSession(ExecutionCommand")
         and "updateNotification();" in body(owner, "public void onTermuxSessionExited(")),
        ("syncWakeLock hands CloudWakeLock the wanted flag and the LIVE session count",
         "CloudWakeLock.STATE.update(wakeLockWanted(this), %s," % LAYOUT["sessions"] in sync),
        ("ACQUIRE / RELEASE do the lock work", "case ACQUIRE: acquireLocks();" in sync and "case RELEASE: releaseLocks();" in sync),
        ("acquireLocks takes a PARTIAL_WAKE_LOCK, and the Wi-Fi lock when declared",
         "PowerManager.PARTIAL_WAKE_LOCK" in acquire and "if (BuildConfig.CLOUD_WIFI_LOCK) {" in acquire),
        ("the exemption is requested once", "!prefs.getBoolean(KEY_EXEMPTION_ASKED, false)" in acquire
         and "putBoolean(KEY_EXEMPTION_ASKED, true)" in acquire),
        ("the notification action still turns it off, and on",
         "ACTION_WAKE_UNLOCK intent received\");\n                    setWakeLockWanted(false);" in owner
         and "ACTION_WAKE_LOCK intent received\");\n                    setWakeLockWanted(true);" in owner),
        ("onDestroy releases", "CloudWakeLock.STATE.destroyed();\n        releaseLocks();" in destroy),
        ("/api/terminal and /api/terminal/wakelock answer the state",
         'case "":\n                    case "wakelock": return CloudWakeLock.STATE.json(BuildConfig.CLOUD_WIFI_LOCK);' in api),
    ]
    return out

def verdict(root):
    return behaviour(root) + ["W " + l for l, ok in wiring(root) if not ok]

print("── #787 wake lock [%s] ──" % APP)
failures = behaviour(DIR)
for label, ok in wiring(DIR):
    print(("  ok   W " if ok else "  FAIL W ") + label)
    if not ok:
        failures.append("W " + label)
if APP == "ac_cloud-termux":
    # The copies are one class: the sibling forks ship this file, package line aside.
    strip = lambda p: re.sub(r"^package [\w.]+;\n", "", open(p).read(), flags=re.M)
    mine = strip(os.path.join(DIR, FILES["policy"]))
    for sib in ("../ac_cloud-nix-on-droid/app/src/main/java/com/termux/cloud/CloudWakeLock.java",
                "../ac_cloud-myterminal/hub/src/main/java/com/diegonmarcos/ide/CloudWakeLock.java"):
        same = strip(os.path.join(DIR, sib)) == mine
        print(("  ok   P " if same else "  FAIL P ") + "CloudWakeLock.java equals " + sib.split("/")[1] + "'s copy")
        if not same:
            failures.append("P " + sib)

T = {"policy": [
    ("held with no session open", "boolean hold = wanted && sessions > 0;", "boolean hold = wanted;"),
    ("since restamped on every update", "if (hold == held) return Transition.NONE;", "if (hold == held) { sinceMs = nowMs; return Transition.NONE; }"),
    ("destroyed() leaves it held", "        held = false;\n        sinceMs = 0;\n        return Transition.RELEASE;", "        sinceMs = 0;\n        return Transition.RELEASE;"),
    ("api hides the session count", '+ ",\\"sessions\\":" + sessions', '+ ",\\"sessions\\":" + 0'),
], "build_json": [
    ("default OFF", '"default_on": true', '"default_on": false'),
]}
if APP == "ac_cloud-myterminal":
    T.update({
        "backend": [("an opened shell is not counted", "shells[id] = ShellEntry(ch, stdin, sess)\n        TerminalWakeLock.sync(ctx, shells.size)\n", "shells[id] = ShellEntry(ch, stdin, sess)\n"),
                    ("a closed shell keeps it held", "shells.remove(id)\n                TerminalWakeLock.sync(ctx, shells.size)\n", "shells.remove(id)\n")],
        "owner": [("RELEASE does nothing", "CloudWakeLock.Transition.RELEASE -> lock?.release().also { lock = null }", "CloudWakeLock.Transition.RELEASE -> Unit"),
                  ("asks for the exemption itself", "private fun acquire(app: Context) {", "private fun acquire(app: Context) { Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;")],
        "configs": [("Configs cannot turn it off", "IdePrefs.setWakeLockWanted(this, !wakeWanted)", "IdePrefs.setWakeLockWanted(this, true)")],
        "gradle": [("default baked as a literal", '"${buildJson.wake_lock.default_on}"', '"true"')],
        "api": [("/api/terminal silent", 'if (op == "" || op == "wakelock")', 'if (op == "wakelock")')],
    })
else:
    T.update({
        "owner": [("updateNotification no longer syncs", "        syncWakeLock();\n        if (mWakeLock == null", "        if (mWakeLock == null"),
                  ("session count frozen at one", "CloudWakeLock.STATE.update(wakeLockWanted(this), %s," % LAYOUT["sessions"], "CloudWakeLock.STATE.update(wakeLockWanted(this), 1,"),
                  ("the unlock action cannot turn it off", "                    setWakeLockWanted(false);", "                    setWakeLockWanted(true);"),
                  ("onDestroy keeps the lock", "CloudWakeLock.STATE.destroyed();\n        releaseLocks();", "CloudWakeLock.STATE.destroyed();"),
                  ("asks for the exemption every time", " && !prefs.getBoolean(KEY_EXEMPTION_ASKED, false)", ""),
                  ("wifi lock taken regardless", "if (BuildConfig.CLOUD_WIFI_LOCK) {", "if (true) {")],
        "gradle": [("default baked as a literal", '"${cloudWakeLock().default_on}"', '"true"')],
        "api": [("/api/terminal silent", 'case "":\n                    case "wakelock":', 'case "wakelock":')],
    })

print("── planted mutations ──")
mut_bad = 0
if failures:
    print("  MUT-VOID — the unmutated tree already fails, so no mutation verdict means anything")
else:
    for key, cases in T.items():
        for name, old, new in cases:
            src = open(os.path.join(DIR, FILES[key])).read()
            if old not in src:
                print("  MUT-STALE  %s — %r is no longer in %s" % (name, old[:60], FILES[key]))
                mut_bad += 1
                continue
            root = tempfile.mkdtemp()
            try:
                for k, rel in FILES.items():
                    os.makedirs(os.path.dirname(os.path.join(root, rel)) or root, exist_ok=True)
                    shutil.copy(os.path.join(DIR, rel), os.path.join(root, rel))
                open(os.path.join(root, FILES[key]), "w").write(src.replace(old, new, 1))
                red = verdict(root)
            finally:
                shutil.rmtree(root)
            print(("  MUT-RED    " if red else "  MUT-HOLLOW ") + name + (" — " + red[0].splitlines()[0] if red else " — still green: that property is untested"))
            mut_bad += 0 if red else 1

n = sum(len(c) for c in T.values())
if failures or mut_bad:
    print("RED — %d failing check(s), %d hollow/stale mutation(s) of %d" % (len(failures), mut_bad, n))
    for f in failures:
        print("::error::FAIL — " + f)
    sys.exit(1)
print("ALL GREEN — %d mutations, every one red" % n)
PY
