#!/usr/bin/env python3
"""canvas_parity (#1865): what Cloud Canvas declares, read from its Kotlin sources, so a reorganisation can prove nothing was dropped.

  canvas_parity.py snapshot APP_DIR            print the inventory of the tree as JSON
  canvas_parity.py check APP_DIR BASELINE      print one line per baseline entry the tree no longer has (nothing printed: parity holds)

The sources are app/src/main plus every module build.json::modules names under libs:canvas-* (their `dir`), so the same inventory is
read before the #1865 split (everything in the app module) and after it (Draw, Painter, Play and the shared core in their own libraries).
Inventory, per kind (every entry is a string, compared as a set: an entry may move between files or modules, never disappear):
  decl     every top-level declaration that is not private, by fully qualified name (class, object, enum, interface, fun, val, const)
  member   every non-private member (fun, val, var, const) declared directly in a class or object body, as Fqn.member
  enum     every entry of every enum class, as Fqn.Entry (the tools, instruments, waves, drum pads, engines)
  label    every user-visible literal of a screen: Text("..."), label = "...", contentDescription = "...", title = "..."
  asset    every file under app/src/main/assets (and any module's src/main/assets), by its asset path
"""
import json
import os
import re
import sys

DECL = re.compile(r"^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|data|enum|sealed|abstract|open|inline|value|annotation|fun|const|lateinit|operator|infix|tailrec|suspend)\s+)*"
                  r"(class|object|interface|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)")
MEMBER = re.compile(r"^    (?:@\w+(?:\([^)]*\))?\s+)*(?:(?:override|open|internal|public|protected|const|lateinit|inline|operator|suspend|abstract)\s+)*"
                    r"(fun|val|var|class|object|data class|enum class)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)")
LABEL = re.compile(r"""(?:\bText\(\s*|\blabel\s*=\s*|\bcontentDescription\s*=\s*|\btitle\s*=\s*)"((?:[^"\\$]|\\.)+)\"""")


def roots(app):
    out = [os.path.join(app, "app", "src", "main")]
    bj = json.load(open(os.path.join(app, "build.json")))
    for name, spec in sorted(bj.get("modules", {}).items()):
        if name.startswith("libs:canvas-") and isinstance(spec, dict) and spec.get("dir"):
            out.append(os.path.normpath(os.path.join(app, spec["dir"], "src", "main")))
    return out


def kotlin_files(app):
    for r in roots(app):
        for d, _, fs in os.walk(r):
            for f in sorted(fs):
                if f.endswith(".kt"):
                    yield os.path.join(d, f)


def snapshot(app):
    inv = {"decl": set(), "member": set(), "enum": set(), "label": set(), "asset": set()}
    for p in kotlin_files(app):
        text = open(p, encoding="utf-8").read()
        m = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = m.group(1) if m else ""
        owner = None      # the class/object whose body the 4-space lines belong to
        enum_owner = None
        for line in text.split("\n"):
            if line.startswith("private ") or line.startswith("@") and " private " in line:
                if not line.startswith(" "):
                    owner = None
                continue
            if line and not line[0].isspace():
                dm = DECL.match(line)
                owner = None
                enum_owner = None
                if dm:
                    kind, name = dm.group(1), dm.group(2)
                    inv["decl"].add(pkg + "." + name)
                    if kind in ("class", "object", "interface") and line.rstrip().endswith("{"):
                        owner = pkg + "." + name
                    if re.match(r"^(?:\w+\s+)*enum class ", line):
                        enum_owner = pkg + "." + name
                        rest = line.split("{", 1)[1] if "{" in line else ""
                        for e in re.findall(r"(?:^|,)\s*([A-Z]\w*)\s*(?=\(|,|;|$)", rest.split(";")[0]):
                            inv["enum"].add(enum_owner + "." + e)
                        if rest.strip().endswith("}"):
                            enum_owner = None
                continue
            if enum_owner and line.startswith("    ") and not line.startswith("     "):
                head = line.split(";")[0]
                if not re.match(r"^\s+(val|var|fun|companion|override|private|init)\b", head):
                    for e in re.findall(r"(?:^|,)\s*([A-Z]\w*)\s*(?=\(|,|$)", head):
                        inv["enum"].add(enum_owner + "." + e)
                if ";" in line:
                    enum_owner = None
            if owner:
                mm = MEMBER.match(line)
                if mm and " private " not in (" " + line.strip().split("(")[0] + " "):
                    inv["member"].add(owner + "." + mm.group(2))
        for lm in LABEL.finditer(text):
            inv["label"].add(lm.group(1))
    for r in roots(app):
        a = os.path.join(r, "assets")
        for d, _, fs in os.walk(a):
            for f in fs:
                inv["asset"].add(os.path.relpath(os.path.join(d, f), a))
    return {k: sorted(v) for k, v in inv.items()}


def main(argv):
    if len(argv) >= 2 and argv[0] == "snapshot":
        json.dump(snapshot(argv[1]), sys.stdout, indent=1, sort_keys=True)
        sys.stdout.write("\n")
        return 0
    if len(argv) >= 3 and argv[0] == "check":
        base = json.load(open(argv[2]))
        now = snapshot(argv[1])
        lost = 0
        for kind in ("decl", "member", "enum", "label", "asset"):
            have = set(now.get(kind, []))
            for e in base.get(kind, []):
                if e not in have:
                    print("LOST %s %s" % (kind, e))
                    lost += 1
        return 1 if lost else 0
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
