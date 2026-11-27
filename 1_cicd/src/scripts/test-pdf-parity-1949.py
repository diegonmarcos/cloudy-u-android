#!/usr/bin/env python3
"""#1949 PDF reader parity: every Drive reader control and feature is in the shared lib, Drive hosts it, Notes hosts the same screen.
Reads 1_cicd/src/data/pdf-reader-parity-1949.json. --root R reads another checkout (the mutation runs use a scratch copy);
--junit FILE writes one testcase per feature, a <failure> element where one is missing (the CI verdict is read from that XML)."""
import json, os, sys
import xml.etree.ElementTree as ET

def main(argv):
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    junit = None
    it = iter(argv[1:])
    for a in it:
        if a == "--root": root = next(it)
        elif a == "--junit": junit = next(it)
    spec = json.load(open(os.path.join(root, "1_cicd/src/data/pdf-reader-parity-1949.json"), encoding="utf-8"))
    cases = []  # (name, message-or-None)
    def read(rel):
        try: return open(os.path.join(root, rel), encoding="utf-8").read()
        except OSError: return None
    def check(name, ok, msg): cases.append((name, None if ok else msg))
    lib = spec["lib_dir"]
    for f in spec["lib_features"]:
        text = read(f"{lib}/{f['file']}")
        check(f"lib_{f['id']}", text is not None and f["needle"] in text, f"{f['file']} lacks {f['needle']!r}")
    for app in ("drive", "notes"):
        s = spec[app]
        text = read(s["activity_file"])
        for i, n in enumerate(s["needles"]):
            check(f"{app}_activity_{i}", text is not None and n in text, f"{s['activity_file']} lacks {n!r}")
        man = read(s["manifest"])
        check(f"{app}_manifest", man is not None and s["manifest_needle"] in man, f"{s['manifest']} does not declare {s['manifest_needle']}")
        src = os.path.join(root, s["source_dir"])
        names = [] if not os.path.isdir(src) else [n for _, _, fs in os.walk(src) for n in fs]
        for fn in s.get("must_not_contain_files", []):
            check(f"{app}_no_own_{fn}", fn not in names, f"{app} carries its own {fn} again (the reader lives in libs:pdf)")
        if app == "notes":
            g = read(s["gradle"])
            check("notes_gradle_lib", g is not None and s["gradle_needle"] in g, "Notes does not depend on libs:pdf")
            body = ""
            for d, _, fs in os.walk(src):
                for fn in fs:
                    if fn.endswith(".kt"): body += open(os.path.join(d, fn), encoding="utf-8").read()
            tg = s.get("takeover_guard")
            if tg:
                t = read(tg["file"])
                check("notes_takeover_guard", t is not None and tg["needle"] in t, "Notes' appUrlOpen closes the auth browser for any URL again (it relaunches MainActivity with CLEAR_TOP over the PDF reader)")
            for n in s["own_reader_forbidden"]:
                check(f"notes_no_second_reader_{n}", n not in body, f"Notes carries its own reader code ({n})")
    bad = [(n, m) for n, m in cases if m]
    suite = ET.Element("testsuite", name="pdf-parity-1949", tests=str(len(cases)), failures=str(len(bad)))
    for n, m in cases:
        tc = ET.SubElement(suite, "testcase", name=n, classname="pdf-parity-1949")
        if m: ET.SubElement(tc, "failure", message=m)
    if junit: ET.ElementTree(suite).write(junit, encoding="utf-8", xml_declaration=True)
    for n, m in bad: print("FAIL  ", n, "-", m)
    print(f"{'FAIL' if bad else 'PASS'}   pdf-parity-1949: {len(cases) - len(bad)}/{len(cases)}")
    return 1 if bad else 0

sys.exit(main(sys.argv))
