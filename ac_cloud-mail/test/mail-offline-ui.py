#!/usr/bin/env python3
"""uiautomator dump helpers for the #1837 offline emulator proof (workflow test-mail-core-offline-emulator.yml). Stdlib only.

  mail-offline-ui.py has  DUMP.xml TEXT...       exit 1 naming every TEXT no node shows (text or content-desc, substring match)
  mail-offline-ui.py lacks DUMP.xml TEXT...      exit 1 naming every TEXT that a node DOES show (the opposite of has)
  mail-offline-ui.py tap  DUMP.xml TEXT [exact]  print "x y", the centre of the first node whose text / content-desc starts with TEXT
  mail-offline-ui.py texts DUMP.xml              every visible text, one per line (non-ASCII kept: the XML is parsed, not grepped)
  mail-offline-ui.py selftest
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    raw = open(path, encoding="utf-8", errors="replace").read()
    start = raw.find("<?xml")
    root = ET.fromstring(raw[start:] if start >= 0 else raw)
    for n in root.iter("node"):
        yield n


def values(n):
    return [v for v in (n.get("text", ""), n.get("content-desc", "")) if v]


def centre(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if not m:
        return None
    a, b, c, d = map(int, m.groups())
    return (a + c) // 2, (b + d) // 2


def main(argv):
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "selftest":
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "u.xml")
            open(p, "w", encoding="utf-8").write(
                '<?xml version="1.0"?><hierarchy><node text="Espa&#241;a: reunión" content-desc="" bounds="[0,100][200,300]"/>'
                '<node text="" content-desc="Archive" bounds="[10,10][30,30]"/></hierarchy>')
            assert main(["x", "has", p, "España: reunión", "Archive"]) == 0
            assert main(["x", "has", p, "Nope"]) == 1
            assert main(["x", "lacks", p, "Nope"]) == 0
            assert main(["x", "lacks", p, "Archive"]) == 1
        print("mail-offline-ui selftest ok")
        return 0
    if cmd == "has":
        seen = [v for n in nodes(argv[2]) for v in values(n)]
        missing = [w for w in argv[3:] if not any(w in v for v in seen)]
        for w in missing:
            print("missing: %s" % w)
        return 1 if missing else 0
    if cmd == "lacks":
        seen = [v for n in nodes(argv[2]) for v in values(n)]
        shown = [w for w in argv[3:] if any(w in v for v in seen)]
        for w in shown:
            print("shown: %s" % w)
        return 1 if shown else 0
    if cmd == "tap":
        want, exact = argv[3], len(argv) > 4 and argv[4] == "exact"
        for n in nodes(argv[2]):
            for v in values(n):
                if (v == want) if exact else v.startswith(want):
                    c = centre(n)
                    if c:
                        print("%d %d" % c)
                        return 0
        return 1
    if cmd == "texts":
        for n in nodes(argv[2]):
            for v in values(n):
                print(v)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
