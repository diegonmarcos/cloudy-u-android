#!/usr/bin/env python3
"""#1682 P3 step 6: THE ADB owner flip, as one deterministic edit of the tree (the flip commit is exactly this script's output, and the
CI workflow c3-webserver-adb-owner-verify.yml runs the SAME script before it builds the SuperApp as a client and the webserver as the
owner, so what ships is what was built). Usage: python3 1_cicd/src/data/adb-owner-flip.py [<repo root>] [--check].

It changes, nothing else:
  1. 1_cicd/src/data/adb-owner.json      adb_owner -> ac_cloud-c3-webserver, package -> com.diegonmarcos.cloudwebserver (next_owner dropped)
  2. (ShellBroker.SELF is the logical identity "owner" since the architect decision: nothing to flip)
  3. ShellWire.BROKER_PKG                what every client (libs:shell-client) talks to (AUTHORITY derives from it)
  4. libs:shell-client <queries>         package visibility of the owner
  5. api-ipc-map.json                    the shellbroker provider authority; the shell routes' app_class -> the owner, /ipc/adb-owner/* -> the Webserver, the old /ipc/aa_cloud-superapp/* kept as an alias (also_apps)
  6. mesh-shell-relay.json               the declared relay routes follow (app_class, path, alias_paths)
Exit 0 = applied (or already flipped); with --check, 0 only when the tree is already flipped.
"""
import json, os, re, sys

args = [a for a in sys.argv[1:] if not a.startswith("--")]
ROOT = os.path.abspath(args[0]) if args else os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CHECK = "--check" in sys.argv
OLD, NEW = "com.diegonmarcos.superapp", "com.diegonmarcos.cloudwebserver"
changed = []

def edit(rel, fn):
    p = os.path.join(ROOT, rel)
    s = open(p, encoding="utf-8").read()
    t = fn(s)
    if t != s:
        changed.append(rel)
        if not CHECK:
            open(p, "w", encoding="utf-8").write(t)

def owner_json(s):
    d = json.loads(s)
    d["adb_owner"], d["package"] = "ac_cloud-c3-webserver", NEW
    d.pop("next_owner", None)
    return json.dumps(d, indent=2) + "\n"

def one(old, new):
    def f(s):
        if old not in s and new not in s:
            sys.exit("flip: neither %r nor %r found" % (old, new))
        return s.replace(old, new)
    return f

edit("1_cicd/src/data/adb-owner.json", owner_json)
edit("ab_cloud-libs-shared/libs/shell-client/src/main/java/com/diegonmarcos/superapp/shellclient/ShellWire.kt",
     one('const val BROKER_PKG = "%s"' % OLD, 'const val BROKER_PKG = "%s"' % NEW))
edit("ab_cloud-libs-shared/libs/shell-client/src/main/AndroidManifest.xml",
     one('<package android:name="%s" />' % OLD, '<package android:name="%s" />' % NEW))
edit("ac_cloud-c3-webserver/data/api-ipc-map.json",
     one('"authority": "%s.shellbroker"' % OLD, '"authority": "%s.shellbroker"' % NEW))

def registry(s):
    """#1682 (c): the shell routes' app_class -> the owner, the old SuperApp path kept through also_apps, /ipc/adb-owner -> the Webserver."""
    d = json.loads(s)
    reg = d["ipc_registry"]
    reg["app_aliases"]["adb-owner"] = "ac_cloud-c3-webserver"
    for e in reg["entries"]:
        if e["ipc"]["endpoint"] == "shellbroker":
            e["app_class"] = "package:" + NEW
            e["also_apps"] = ["aa_cloud-superapp"]
    return json.dumps(d, indent=1, ensure_ascii=False) + "\n"
edit("ac_cloud-c3-webserver/data/api-ipc-map.json", registry)

def relay(s):
    d = json.loads(s)
    for a in d["allowed"]:
        a["app_class"] = "package:" + NEW
        a["path"] = a["path"].replace("/ipc/aa_cloud-superapp/", "/ipc/adb-owner/")
        a["alias_paths"] = [a["path"].replace("/ipc/adb-owner/", "/ipc/aa_cloud-superapp/")]
    return json.dumps(d, indent=2, ensure_ascii=False) + "\n"
edit("ac_cloud-c3-webserver/data/mesh-shell-relay.json", relay)
print(("flip %s: " % ("pending" if CHECK and changed else "applied" if changed else "already in place")) + ", ".join(changed))
sys.exit(1 if (CHECK and changed) else 0)
