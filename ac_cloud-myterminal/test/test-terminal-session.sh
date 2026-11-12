#!/usr/bin/env bash
# Tester: MyTerminal reaches the fleet's terminals with ZERO setup, and only fleet apps can.
#
# THE DESIGN THIS PINS. MyTerminal used to ssh into 127.0.0.1:8023 / :8024, which needed a person
# to install openssh in each terminal, generate host keys, start sshd bound to loopback and paste
# MyTerminal's key into ~/.ssh/authorized_keys. Now each terminal (ac_cloud-termux,
# ac_cloud-nix-on-droid) exports CloudSessionService (ICloudSession.aidl): a login shell in a PTY,
# handed over by binder, guarded by the signature-level TERMINAL_SESSION permission and re-checked
# on every call (SessionGate). No sshd, no listening socket, no key on either side — which is also
# why there is no authorized_keys writer to test: the design removed the file from the path.
# Loopback SSH survives only as the fallback for a terminal build without the service, and the
# manual steps are shown only when both failed, with the reason (TerminalRoute).
#
#   S1  ICloudSession.aidl is byte-identical in the two servers and the client
#   S2  SessionGate.java is byte-identical in both terminals; its permission / action are the
#       ones TerminalSessions.kt binds and the manifests declare
#   S3  manifests: both terminals and MyTerminal DEFINE the permission at protectionLevel
#       "signature" (so install order never matters); the service is exported ONLY behind it;
#       MyTerminal requests it
#   S4  every ICloudSession method in both services calls gate() before anything else
#   S5  SessionGate on a JVM: own uid, no permission, wrong signature, both; ids scoped per uid
#   S6  TerminalRoute on a JVM: backend auto-detection and the fallback-reason mapping
#   S7  FsScripts under a real sh: list / read / write round-trip, names that are code stay names
#   S8  <queries> == terminal-targets.json packages + build.json links.browser_package, and those
#       packages are the fleet terminals' own applicationIds
#   S9  no key material on the zero-setup path; the bridge tries the session before SSH
#   MUT the old logic and each guard, broken on a copy, must turn S5 / S6 / S7 red
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$APP" <<'PY'
import json, os, re, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

APP = sys.argv[1]
ROOT = os.path.dirname(APP)
A = "{http://schemas.android.com/apk/res/android}"
PASS, FAIL = [], []
def ok(m): PASS.append(m); print("  PASS: " + m)
def bad(m): FAIL.append(m); print("  FAIL: " + m)
def rd(p): return open(p, encoding="utf-8").read()

HUB = os.path.join(APP, "hub/src/main")
KT = os.path.join(HUB, "java/com/diegonmarcos/ide")
FORKS = {f: os.path.join(ROOT, f, "app/src/main") for f in ("ac_cloud-termux", "ac_cloud-nix-on-droid")}
AIDL_REL = "aidl/com/diegonmarcos/cloud/terminal/ICloudSession.aidl"
GATE_REL = "java/com/termux/cloud/SessionGate.java"
SVC_REL = "java/com/termux/app/CloudSessionService.java"

print("== S1: one ICloudSession contract ==")
aidls = {"ac_cloud-myterminal": os.path.join(HUB, AIDL_REL)}
aidls.update({f: os.path.join(p, AIDL_REL) for f, p in FORKS.items()})
missing = [k for k, p in aidls.items() if not os.path.isfile(p)]
if missing:
    bad("S1 ICloudSession.aidl missing in " + ", ".join(missing))
else:
    texts = {k: rd(p) for k, p in aidls.items()}
    if len(set(texts.values())) == 1:
        ok("S1 ICloudSession.aidl is the same bytes in all three modules")
    else:
        bad("S1 ICloudSession.aidl differs between " + ", ".join(texts) + " — a client and a server reading parcels differently")
    methods = re.findall(r"^\s+[\w<>]+\s+(\w+)\(", texts["ac_cloud-myterminal"], re.M)
print()

print("== S2: one gate, one permission, one action ==")
gates = {f: os.path.join(p, GATE_REL) for f, p in FORKS.items()}
if not all(os.path.isfile(p) for p in gates.values()):
    bad("S2 SessionGate.java missing in a terminal")
    gate_src = ""
else:
    gtexts = [rd(p) for p in gates.values()]
    gate_src = gtexts[0]
    if len(set(gtexts)) == 1:
        ok("S2 SessionGate.java is the same bytes in both terminals")
    else:
        bad("S2 SessionGate.java differs between the terminals — they would refuse different callers")
const = lambda src, name: (re.search(r'%s\s*=\s*"([^"]+)"' % name, src) or [None, None])[1]
PERM, ACTION = const(gate_src, "PERMISSION"), const(gate_src, "ACTION")
sess = rd(os.path.join(KT, "TerminalSessions.kt"))
if PERM and ACTION and const(sess, "PERMISSION") == PERM and const(sess, "ACTION") == ACTION:
    ok("S2 TerminalSessions.kt binds %s behind %s, as SessionGate declares" % (ACTION, PERM))
else:
    bad("S2 TerminalSessions.kt's ACTION/PERMISSION (%s, %s) are not SessionGate's (%s, %s)"
        % (const(sess, "ACTION"), const(sess, "PERMISSION"), ACTION, PERM))
print()

print("== S3: the manifests guard it at signature level ==")
def manifest(p):
    return ET.parse(p).getroot()
def defines_signature(root):
    for e in root.findall("permission"):
        if e.get(A + "name") == PERM:
            return e.get(A + "protectionLevel")
    return None
for f, p in FORKS.items():
    root = manifest(os.path.join(p, "AndroidManifest.xml"))
    lvl = defines_signature(root)
    if lvl == "signature":
        ok("S3 %s defines %s at protectionLevel=signature" % (f, PERM))
    else:
        bad("S3 %s defines %s at protectionLevel=%r — anything but exactly 'signature' lets a non-fleet app in" % (f, PERM, lvl))
    svcs = [s for s in root.iter("service") if s.get(A + "name") == ".app.CloudSessionService"]
    if len(svcs) != 1:
        bad("S3 %s declares CloudSessionService %d times" % (f, len(svcs)))
        continue
    s = svcs[0]
    actions = [a.get(A + "name") for a in s.iter("action")]
    if s.get(A + "exported") == "true" and s.get(A + "permission") == PERM and actions == [ACTION]:
        ok("S3 %s exports CloudSessionService only behind %s, action %s" % (f, PERM, ACTION))
    else:
        bad("S3 %s CloudSessionService: exported=%s permission=%s actions=%s — exported without the signature permission is a shell for every app"
            % (f, s.get(A + "exported"), s.get(A + "permission"), actions))
hub = manifest(os.path.join(HUB, "AndroidManifest.xml"))
uses = [e.get(A + "name") for e in hub.findall("uses-permission")]
if defines_signature(hub) == "signature" and PERM in uses:
    ok("S3 MyTerminal defines (signature) and requests %s, so install order never matters" % PERM)
else:
    bad("S3 MyTerminal must define %s at signature level AND request it (defines=%r, requests=%s)"
        % (PERM, defines_signature(hub), PERM in uses))
print()

print("== S4: every binder method is gated first ==")
for f, p in FORKS.items():
    src = rd(os.path.join(p, SVC_REL))
    i = src.find("new ICloudSession.Stub()")
    stub = src[i:] if i >= 0 else ""
    impl = dict(re.findall(r"public\s+[\w<>]+\s+(\w+)\([^)]*\)\s*\{\s*\n\s*(\w+)\(\);", stub))
    declared = set(methods) if not missing else set()
    ungated = sorted(m for m in declared if impl.get(m) != "gate")
    if declared and not ungated:
        ok("S4 %s: all %d ICloudSession methods call gate() first (%s)" % (f, len(declared), ", ".join(sorted(declared))))
    else:
        bad("S4 %s: %s do not call gate() as their first statement" % (f, ungated or "the AIDL methods"))
    gate_body = re.search(r"private void gate\(\) \{(.*?)\n    \}", src, re.S)
    gb = gate_body.group(1) if gate_body else ""
    if "checkCallingPermission(SessionGate.PERMISSION)" in gb and "checkSignatures(Process.myUid(), uid)" in gb \
       and "throw new SecurityException" in gb:
        ok("S4 %s: gate() checks the calling permission AND the signature, and throws" % f)
    else:
        bad("S4 %s: gate() does not check both permission and signature" % f)
print()

work = tempfile.mkdtemp(prefix="term-session-")
def javac_run(sources, main, extra=None):
    out = os.path.join(work, "out-" + main + "-" + str(len(os.listdir(work))))
    os.makedirs(out)
    c = subprocess.run(["javac", "-d", out] + sources, capture_output=True, text=True)
    if c.returncode != 0:
        return 2, "COMPILE: " + c.stderr
    r = subprocess.run(["java", "-cp", out, main] + (extra or []), capture_output=True, text=True)
    err = "".join(l for l in r.stderr.splitlines(True) if not l.startswith("Picked up JAVA_TOOL_OPTIONS"))
    return r.returncode, r.stdout + err

GATE_HARNESS = r'''
import com.termux.cloud.SessionGate;
public class GateTest {
  static int fails = 0;
  static void t(boolean c, String m) { if (!c) { fails++; System.out.println("  FAIL " + m); } else System.out.println("  ok   " + m); }
  public static void main(String[] a) {
    t(SessionGate.refusal(10100, 10100, false, false) == null, "the terminal's own uid may call");
    String np = SessionGate.refusal(10200, 10100, false, true);
    t(np != null && np.contains(SessionGate.PERMISSION), "a caller without the permission is refused, naming it");
    t(SessionGate.refusal(10200, 10100, true, false) != null, "a caller with the permission but another signature is refused");
    t(SessionGate.refusal(10200, 10100, false, false) != null, "a caller with neither is refused");
    t(SessionGate.refusal(10200, 10100, true, true) == null, "a fleet-signed caller holding the permission may call");
    t(SessionGate.key(10200, "p1") != null && !SessionGate.key(10200, "p1").equals(SessionGate.key(10300, "p1")), "session ids are scoped per caller uid");
    t(SessionGate.key(10200, null) == null && SessionGate.key(10200, "") == null, "an empty id is refused");
    t(SessionGate.key(10200, "../p") == null && SessionGate.key(10200, "a b") == null && SessionGate.key(10200, "a;b") == null, "an id outside [A-Za-z0-9._-] is refused");
    t(SessionGate.key(10200, "x".repeat(65)) == null && SessionGate.key(10200, "x".repeat(64)) != null, "ids are capped at 64");
    t(SessionGate.cols(0) == 80 && SessionGate.rows(-1) == 24 && SessionGate.cols(132) == 132 && SessionGate.rows(5000) == 24, "pty sizes are clamped");
    t(SessionGate.PERMISSION.equals("com.diegonmarcos.cloud.permission.TERMINAL_SESSION") && SessionGate.VERSION >= 1, "permission name and version");
    System.exit(fails == 0 ? 0 : 1);
  }
}
'''
ROUTE_HARNESS = r'''
import com.diegonmarcos.ide.TerminalRoute;
import com.diegonmarcos.ide.TerminalRoute.Reason;
import java.util.*;
public class RouteTest {
  static int fails = 0;
  static void t(boolean c, String m) { if (!c) { fails++; System.out.println("  FAIL " + m); } else System.out.println("  ok   " + m); }
  static Set<String> s(String... v) { return new HashSet<>(Arrays.asList(v)); }
  public static void main(String[] a) {
    List<String> d = Arrays.asList("termux", "nix-on-droid");
    t("nix-on-droid".equals(TerminalRoute.pickBackend(d, s("nix-on-droid"), s("nix-on-droid"), null, "termux")),
      "auto-detect: only the nix terminal installed -> nix, not the build default");
    t("termux".equals(TerminalRoute.pickBackend(d, s("termux", "nix-on-droid"), s("termux", "nix-on-droid"), null, "termux")),
      "auto-detect: both installed -> the build default");
    t("nix-on-droid".equals(TerminalRoute.pickBackend(d, s("nix-on-droid"), s("termux", "nix-on-droid"), null, "termux")),
      "auto-detect: termux too old, nix current -> nix (zero setup beats fallback)");
    t("termux".equals(TerminalRoute.pickBackend(d, s(), s("termux"), null, "nix-on-droid")),
      "auto-detect: only an old termux installed -> termux (its SSH fallback)");
    t("nix-on-droid".equals(TerminalRoute.pickBackend(d, s("termux"), s("termux"), "nix-on-droid", "termux")),
      "a stored pick is never second-guessed");
    t("termux".equals(TerminalRoute.pickBackend(d, s(), s(), "gone", "termux")),
      "a stored pick no longer declared falls back; nothing installed -> the build default");
    t(TerminalRoute.sessionReason(false, false, false, 0, null) == Reason.NOT_INSTALLED, "not installed");
    t(TerminalRoute.sessionReason(true, false, false, 0, null) == Reason.TOO_OLD, "installed, no session service -> too old");
    t(TerminalRoute.sessionReason(true, true, false, 0, null) == Reason.TOO_OLD, "installed, version 0 (an older server's empty reply) -> too old");
    t(TerminalRoute.sessionReason(true, false, true, 0, null) == Reason.PERMISSION_MISSING, "bind refused by the system -> permission missing");
    t(TerminalRoute.sessionReason(true, true, false, 1, "bootstrap failed") == Reason.SESSION_FAILED, "bound but cannot start -> session failed");
    t(TerminalRoute.sessionReason(true, true, false, 1, null) == Reason.OK, "bound, current, prepared -> ok");
    t(TerminalRoute.sshReason("java.net.ConnectException: failed to connect to /127.0.0.1 (port 8024): connect failed: ECONNREFUSED (Connection refused)") == Reason.SSHD_NOT_RUNNING, "ECONNREFUSED -> sshd not running");
    t(TerminalRoute.sshReason("connect failed: ECONNREFUSED") == Reason.SSHD_NOT_RUNNING, "a bare ECONNREFUSED -> sshd not running");
    t(TerminalRoute.sshReason("Auth fail for methods 'publickey'") == Reason.KEY_NOT_AUTHORIZED, "Auth fail -> key not authorized");
    t(TerminalRoute.sshReason("timeout: socket is not established") == Reason.SSH_FAILED, "anything else -> ssh failed");
    t(TerminalRoute.sshReason(null) == Reason.OK, "no error -> ok");
    t(TerminalRoute.trySshAfter(Reason.TOO_OLD) && !TerminalRoute.trySshAfter(Reason.NOT_INSTALLED)
      && !TerminalRoute.trySshAfter(Reason.PERMISSION_MISSING) && !TerminalRoute.trySshAfter(Reason.OK),
      "SSH is tried only for a terminal too old for sessions");
    String m = TerminalRoute.explain(Reason.TOO_OLD, Reason.SSHD_NOT_RUNNING, "Cloud Terminal (nix)", "127.0.0.1", 8024, "ECONNREFUSED");
    t(m.contains("Cloud Terminal (nix)") && m.contains("127.0.0.1:8024") && m.contains("too old") && m.contains("sshd not running"), "explain names the terminal, its port, too old and sshd not running: " + m);
    m = TerminalRoute.explain(Reason.TOO_OLD, Reason.KEY_NOT_AUTHORIZED, "Cloud Terminal (termux)", "127.0.0.1", 8023, null);
    t(m.contains("key not authorized"), "explain: key not authorized");
    t(TerminalRoute.explain(Reason.NOT_INSTALLED, Reason.OK, "Cloud Terminal (nix)", "127.0.0.1", 8024, null).contains("not installed"), "explain: not installed");
    t(TerminalRoute.explain(Reason.PERMISSION_MISSING, Reason.OK, "X", "h", 1, null).contains("fleet key"), "explain: permission missing names the signing key");
    System.exit(fails == 0 ? 0 : 1);
  }
}
'''
FS_HARNESS = r'''
import com.diegonmarcos.ide.FsScripts;
import java.nio.file.*;
public class FsTest {
  public static void main(String[] a) throws Exception {
    String op = a[0];
    if (op.equals("list")) System.out.print(FsScripts.list(a[1]));
    else if (op.equals("read")) System.out.print(FsScripts.read(a[1]));
    else if (op.equals("write")) System.out.print(FsScripts.write(a[1], new String(Files.readAllBytes(Paths.get(a[2])), "UTF-8")));
    else if (op.equals("parse")) { for (java.util.Map.Entry<String, Boolean> e : FsScripts.parseList(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"))) System.out.println(e.getKey() + "|" + e.getValue()); }
  }
}
'''

def write(dirn, name, text):
    os.makedirs(dirn, exist_ok=True)
    p = os.path.join(dirn, name)
    open(p, "w").write(text)
    return p

def gate_verdict(gate_text):
    d = tempfile.mkdtemp(dir=work)
    g = write(os.path.join(d, "com/termux/cloud"), "SessionGate.java", gate_text)
    h = write(d, "GateTest.java", GATE_HARNESS)
    return javac_run([g, h], "GateTest")

def route_verdict(route_text):
    d = tempfile.mkdtemp(dir=work)
    r = write(os.path.join(d, "com/diegonmarcos/ide"), "TerminalRoute.java", route_text)
    h = write(d, "RouteTest.java", ROUTE_HARNESS)
    return javac_run([r, h], "RouteTest")

def fs_verdict(fs_text):
    """Runs FsScripts' scripts under sh in a sandbox HOME. Returns (problems list)."""
    d = tempfile.mkdtemp(dir=work)
    f = write(os.path.join(d, "com/diegonmarcos/ide"), "FsScripts.java", fs_text)
    h = write(d, "FsTest.java", FS_HARNESS)
    out = os.path.join(d, "out"); os.makedirs(out)
    c = subprocess.run(["javac", "-d", out, f, h], capture_output=True, text=True)
    if c.returncode != 0:
        return ["COMPILE: " + c.stderr]
    home = os.path.join(d, "home"); os.makedirs(home)
    tricky = os.path.join(home, "it's a dir")
    os.makedirs(os.path.join(tricky, "sub dir"))
    open(os.path.join(tricky, "$(touch PWNED)"), "w").write("x")
    open(os.path.join(tricky, ".hidden"), "w").write("x")
    env = {"HOME": home, "PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    def script(*args):
        return subprocess.run(["java", "-cp", out, "FsTest"] + list(args), capture_output=True, text=True, env=dict(os.environ)).stdout
    def sh(text):
        return subprocess.run(["sh", "-s"], input=text, capture_output=True, text=True, env=env, cwd=d)
    problems = []
    r = sh(script("list", "~/it's a dir"))
    lst = os.path.join(d, "list.out"); open(lst, "w").write(r.stdout)
    got = set(script("parse", lst).split("\n")) - {""}
    want = {"sub dir|true", "$(touch PWNED)|false", ".hidden|false"}
    if got != want:
        problems.append("list of ~/it's a dir gave %s, want %s (stderr: %s)" % (sorted(got), sorted(want), r.stderr.strip()))
    content = "line 1 with 'quotes' and \"dq\" $HOME `id` \\ back\nñ ünïcode ✓\n\nlast"
    cf = os.path.join(d, "content.txt"); open(cf, "w", encoding="utf-8").write(content)
    target = "~/it's a dir/$(touch PWNED2).txt"
    r = sh(script("write", target, cf))
    r2 = sh(script("read", target))
    if r2.stdout != content:
        problems.append("write+read round-trip changed the content: %r (stderr: %s %s)" % (r2.stdout[:80], r.stderr.strip(), r2.stderr.strip()))
    for p, _, names in os.walk(d):
        for n in names:
            if n in ("PWNED", "PWNED2"):
                problems.append("a file name was executed as code: %s exists" % os.path.join(p, n))
    if sh(script("list", "~/no such dir")).returncode == 0:
        problems.append("listing a missing directory exits 0 — the browser would show it as empty instead of failing")
    return problems

print("== S5: SessionGate refuses non-fleet callers (JVM) ==")
gate_path = gates["ac_cloud-termux"]
rc, out = gate_verdict(rd(gate_path)) if os.path.isfile(gate_path) else (2, "missing")
sys.stdout.write(out)
(ok if rc == 0 else bad)("S5 SessionGate behaviour" + ("" if rc == 0 else ": " + out.strip().splitlines()[-1] if out.strip() else ""))
print()

print("== S6: backend auto-detection and the fallback reasons (JVM) ==")
route_path = os.path.join(KT, "TerminalRoute.java")
rc, out = route_verdict(rd(route_path))
sys.stdout.write(out)
(ok if rc == 0 else bad)("S6 TerminalRoute behaviour")
print()

print("== S7: the file browser's scripts under a real sh ==")
fs_path = os.path.join(KT, "FsScripts.java")
probs = fs_verdict(rd(fs_path))
if probs:
    for p in probs: bad("S7 " + p)
else:
    ok("S7 list / read / write round-trip through sh; ~ expands; quotes, $( ) and backticks in names and content stay data")
print()

print("== S8: package visibility is the declaration ==")
tgt = json.load(open(os.path.join(APP, "data/terminal-targets.json")))
pkgs = {b.get("package") for b in tgt["backends"].values()}
browser = json.load(open(os.path.join(APP, "build.json"))).get("links", {}).get("browser_package")
queries = {p.get(A + "name") for q in hub.findall("queries") for p in q.findall("package")}
qactions = {a.get(A + "name") for q in hub.findall("queries") for i in q.findall("intent") for a in i.findall("action")}
store = json.load(open(os.path.join(APP, "build.json"))).get("links", {}).get("store_package")   # #1675 installer of record
want = pkgs | {browser} | ({store} if store else set())
if None in pkgs or not browser:
    bad("S8 a backend has no `package`, or build.json has no links.browser_package")
elif queries == want and ACTION in qactions:
    ok("S8 <queries> lists exactly %s and the session action" % ", ".join(sorted(want)))
else:
    bad("S8 <queries> %s != declared %s (or the session action is missing)" % (sorted(queries), sorted(want)))
fork_ids = {
    json.load(open(os.path.join(ROOT, "ac_cloud-termux/build.json")))["forks"]["termux"]["app_id"],
    json.load(open(os.path.join(ROOT, "ac_cloud-nix-on-droid/build.json")))["forks"]["nixdroid"]["app_id"],
}
if pkgs == fork_ids:
    ok("S8 the declared packages are the fleet terminals' own applicationIds (%s)" % ", ".join(sorted(fork_ids)))
else:
    bad("S8 terminal-targets.json packages %s are not the terminals' applicationIds %s (#605)" % (sorted(pkgs), sorted(fork_ids)))
print()

print("== S9: no keys on the zero-setup path; the bridge tries it first ==")
KEYLIKE = re.compile(r"BEGIN [A-Z ]*PRIVATE KEY|authorized_keys|ssh-(ed25519|rsa) AAAA|ecdsa-sha2")
native_files = [os.path.join(KT, "TerminalSessions.kt"), os.path.join(HUB, AIDL_REL)] + \
    [os.path.join(p, SVC_REL) for p in FORKS.values()] + list(gates.values())
def code_only(t):
    # Prose may explain what the design removed; only what compiles is checked.
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"^\s*//.*$", "", t, flags=re.M)
leaks = [os.path.relpath(p, ROOT) for p in native_files if KEYLIKE.search(code_only(rd(p)))]
if leaks:
    bad("S9 key material or authorized_keys handling on the native path: " + ", ".join(leaks))
else:
    ok("S9 no key, key-like string or authorized_keys on the native path (%d files)" % len(native_files))
bridge = rd(os.path.join(KT, "TerminalBridge.kt"))
start = bridge[bridge.find("fun ptyStart("):bridge.find("fun ptyWrite(")]
if 0 <= start.find("TerminalSessions.open(") < start.find("ssh.openShell(") and "TerminalRoute.trySshAfter(" in start:
    ok("S9 ptyStart opens the native session first and reaches SSH only through TerminalRoute.trySshAfter")
else:
    bad("S9 ptyStart does not try the native session before SSH")
if all(re.search(r"fun %s\([^)]*\) \{\s*executor\.submit \{ if \(TerminalSessions\.owns\(id\)\)" % f, bridge) for f in ("ptyWrite", "ptyResize", "ptyKill")):
    ok("S9 write / resize / kill route by which path opened the shell")
else:
    bad("S9 write / resize / kill do not route native shells to TerminalSessions")
print()

print("== MUT: the old logic and each guard, broken, must go red ==")
def mutate(src, old, new):
    return src.replace(old, new, 1) if old in src else None
muts = []
g = rd(gate_path) if os.path.isfile(gate_path) else ""
muts += [("gate", "signature not checked", g, '        if (!signatureMatches) return "uid " + callingUid + " is not signed with this terminal\'s key";\n', ""),
         ("gate", "permission not checked", g, '        if (!permissionGranted) return "uid " + callingUid + " does not hold " + PERMISSION;\n', ""),
         ("gate", "ids not scoped per uid", g, 'return callingUid + ":" + id;', "return id;")]
r = rd(route_path)
muts += [("route", "OLD LOGIC: stored pick or the build default, installed or not",
          r, "        if (stored != null && declared.contains(stored)) return stored;\n",
          "        if (true) return stored != null ? stored : buildDefault;\n"),
         ("route", "ECONNREFUSED not read as sshd-not-running", r, 'e.contains("econnrefused") || ', ""),
         ("route", "SSH tried after every failure", r, "return session == Reason.TOO_OLD;", "return true;"),
         ("route", "version 0 counted as current", r, "if (!bound || version < 1) return Reason.TOO_OLD;", "if (!bound) return Reason.TOO_OLD;")]
f = rd(fs_path)
muts += [("fs", "path unquoted", f, 'return "p=" + quote(path) + "\\n"', 'return "p=\\"" + path + "\\"\\n"'),
         ("fs", "~ not expanded", f, '"case \\"$p\\" in \\"~\\") p=\\"$HOME\\" ;; \\"~/\\"*) p=\\"$HOME/${p#\\"~/\\"}\\" ;; esac\\n"', '""')]
hollow = 0
for kind, label, src, old, new in muts:
    m = mutate(src, old, new)
    if m is None:
        bad("MUT stale: '%s' — the text it breaks is no longer in the source" % label); hollow += 1; continue
    if kind == "gate": red = gate_verdict(m)[0] != 0
    elif kind == "route": red = route_verdict(m)[0] != 0
    else: red = bool(fs_verdict(m))
    if red: print("  MUT-RED    " + label)
    else: bad("MUT hollow: '%s' leaves the tester green" % label); hollow += 1
if not hollow:
    ok("MUT %d mutations, every one red (incl. the old backend logic)" % len(muts))

shutil.rmtree(work, ignore_errors=True)
print()
print("── terminal sessions: %d passed, %d failed ──" % (len(PASS), len(FAIL)))
sys.exit(1 if FAIL else 0)
PY
