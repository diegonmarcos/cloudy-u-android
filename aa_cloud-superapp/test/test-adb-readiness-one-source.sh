#!/usr/bin/env bash
# #1678 ONE source of truth for "ADB usable now": system/AdbReadiness.kt. Static: no build, no device.
#   R1  nobody but AdbReadiness takes the broker's change callback (ShellBroker.onStatusChange =)
#   R2  no readiness verdict outside AdbReadiness: `ShellBroker.status(x).armed`, `ShellChannels.active(x) != null / == null`
#       (exec gates that then run on the channel they looked up are listed in EXEC_GATES)
#   R3  the status dropdown draws a needs-ADB row as an AdbReadiness link (testTag adblink, AdbReadiness.connect) and re-reads on its flow
#   R4  the dropdown's probes queue (synchronized) and check AdbReadiness before running; the old "arm the channel" text is gone
#   R5  the ADB Shell Status tab ends with the Tester card, which runs over ShellBroker.exec
#   R6  #1682 P3 step 5 the source is the client-safe libs:adb-readiness (ShellClient.status, no owner class), its owner half (broker push) is libs:adb-shell-core's AdbReadinessOwner; before: P2a the owner lives in libs:adb-shell-core (one copy, shared with the ADB owner app), never again in the SuperApp's app/,
#       and the SuperApp installs its Connect chain / page opener there (AdbReadiness.connector / hostOpener in App.kt)
# Then each violation is planted and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
python3 - "$APP" <<'PY'
import os, re, sys
APP = sys.argv[1]; SRC = os.path.join(APP, "app/src/main/java/com/diegonmarcos/superapp")
LIBSRC = os.path.join(APP, "../ab_cloud-libs-shared/libs/adb-readiness/src/main/java/com/diegonmarcos/superapp")
OWNSRC = os.path.join(APP, "../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp")
OWN = "system/AdbReadiness.kt"
OWNER_HALF = "system/AdbReadinessOwner.kt"   # #1682 P3 step 5: the owner process's push + channel lookup (libs:adb-shell-core)
EXEC_GATES = {"system/QuickDisplay.kt", "system/PrivilegedPlaneWorker.kt"}
def strip(s): return re.sub(r"//[^\n]*|/\*.*?\*/", "", s, flags=re.S)
def load():
    files = {}
    for root, tag in ((SRC, ""), (LIBSRC, "lib:"), (OWNSRC, "")):
        for d, _, fs in os.walk(root):
            for f in fs:
                if f.endswith(".kt"):
                    p = os.path.join(d, f); rel = os.path.relpath(p, root)
                    files[rel] = strip(open(p, encoding="utf-8").read())
                    if tag == "lib:": files[tag + rel] = ""
    # #1682 P3 step 5: the owner hooks (connector / hostOpener / AdbReadinessOwner.install) are installed by the owner AdbSide facade
    files["owner:AdbSide.kt"] = strip(open(os.path.join(APP, "app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt"), encoding="utf-8").read())
    return files
CB = re.compile(r"onStatusChange\s*=(?!=)")
VERDICT = re.compile(r"ShellBroker\.status\([^)]*\)\.armed|ShellChannels\.active\([^)]*\)\s*[!=]=\s*null")
def checks(F):
    out = []
    out.append(("R1 only AdbReadiness takes ShellBroker.onStatusChange", [k for k, v in F.items() if k not in (OWN, OWNER_HALF) and CB.search(v)]))
    out.append(("R2 no readiness verdict outside AdbReadiness", [k for k, v in F.items() if k not in (OWN, OWNER_HALF) and k not in EXEC_GATES and VERDICT.search(v)]))
    dd = F.get("system/StatusDropdowns.kt", "")
    out.append(("R3 dropdown rows link through AdbReadiness and re-read on its flow",
                [] if all(t in dd for t in ("AdbReadiness.state.collectAsState()", "adblink", "AdbReadiness.connect(", "ready.usable)")) else ["system/StatusDropdowns.kt"]))
    sg = F.get("system/SystemGroups.kt", "")
    bad = [] if ("synchronized(probeLock)" in sg and "AdbReadiness.check(ctx)" in sg) else ["system/SystemGroups.kt"]
    bad += [k for k, v in F.items() if "arm the channel on Configs" in v]
    out.append(("R4 probes queue, gate on AdbReadiness, no stale instruction text", bad))
    ck = F.get("configs/AdbCockpit.kt", ""); te = F.get("configs/AdbTester.kt", "")
    out.append(("R5 Status ends with the Tester over ShellBroker.exec",
                [] if ("AdbTesterCard" in ck and "tail?.invoke()" in ck and "ShellBroker.exec(" in te and "AdbReadiness.check(" in te) else ["configs/AdbCockpit.kt"]))
    out.append(("R6 AdbReadiness lives in libs:adb-readiness only (client-safe: over ShellClient, no owner class), the SuperApp installs its hooks, the owner half is libs:adb-shell-core's",
                [] if ("lib:" + OWN in F and not F.get("app:" + OWN) and "ShellClient.status(" in F.get(OWN, "")
                       and not re.search(r"adbdebug|shellbroker", F.get(OWN, ""))
                       and "ShellBroker.onStatusChange" in F.get(OWNER_HALF, "") and "AdbReadinessOwner.install()" in F.get("owner:AdbSide.kt", "")
                       and "AdbReadiness.connector =" in F.get("owner:AdbSide.kt", "")
                       and "AdbReadiness.hostOpener =" in F.get("owner:AdbSide.kt", "")) else [OWN]))
    return out
fails = 0
F = load()
if os.path.exists(os.path.join(SRC, OWN)): F["app:" + OWN] = "present"
for name, bad in checks(F):
    print(("  PASS: " if not bad else "  FAIL: ") + name + ("" if not bad else " -> " + ", ".join(sorted(bad)))); fails += 1 if bad else 0
# mutation: every plant must turn its check red
plants = [
    ("R1", ("launcher/X.kt", "fun a() { ShellBroker.onStatusChange = null }")),
    ("R2", ("network/X.kt", "val up = ShellChannels.active(ctx) != null")),
    ("R3", ("system/StatusDropdowns.kt", "")),
    ("R4", ("system/X.kt", "val s = \"needs ADB: arm the channel on Configs > Network > ADB Shell\"")),
    ("R5", ("configs/AdbTester.kt", "")),
    ("R6", ("app:" + OWN, "object AdbReadiness {}")),
    ("R6", (OWN, "import com.diegonmarcos.superapp.shellbroker.ShellBroker\nobject AdbReadiness { fun x() = ShellBroker.status() }")),
]
for rid, (path, body) in plants:
    G = dict(F); G[path] = body
    red = [n for n, b in checks(G) if n.startswith(rid) and b]
    print(("  PASS: " if red else "  FAIL: ") + f"planted {rid} violation turns red"); fails += 0 if red else 1
print("FAIL" if fails else "PASS: one ADB readiness source"); sys.exit(1 if fails else 0)
PY
