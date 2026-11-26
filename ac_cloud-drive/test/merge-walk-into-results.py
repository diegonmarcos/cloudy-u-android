#!/usr/bin/env python3
"""#1934 fills the EMULATOR column of the observed result table (JVM column from the Ship run's JUnit results) with the walk's per-control results.
Usage: merge-walk-into-results.py <observed.md> <walk.tsv> <out.md> [<ship run id> <ship sha>]. Walk rows: tab<TAB>control id<TAB>label<TAB>tapped|shown|NOT FOUND|NOT OPENED<TAB>screenshot."""
import sys
md, walk, out = sys.argv[1:4]
meta = " (JVM column from Ship run %s at %s; emulator column from this walk)" % (sys.argv[4], sys.argv[5]) if len(sys.argv) > 5 else ""
w = {}
for l in open(walk):
    c = l.rstrip("\n").split("\t")
    if len(c) >= 4: w[c[1]] = "PASS" if c[3] in ("tapped", "shown") else "FAIL"
rows = []; jvm = {"PASS": 0, "FAIL": 0, "DISABLED": 0}; emu = {"PASS": 0, "FAIL": 0, "not run": 0}; lines = []
for l in open(md).read().splitlines():
    if l.startswith("| ") and not l.startswith("| scope") and not l.startswith("|---"):
        c = [x.strip() for x in l.strip().strip("|").split(" | ")]
        if len(c) >= 9:
            c[8] = w.get(c[1], "not run"); emu[c[8]] += 1; jvm[c[7]] = jvm.get(c[7], 0) + 1
            l = "| " + " | ".join(c) + " |"
    lines.append(l)
lines = [("Combined totals" + meta + ": JVM %d PASS, %d FAIL, %d DISABLED; emulator %d PASS, %d FAIL, %d not run" % (jvm["PASS"], jvm["FAIL"], jvm["DISABLED"], emu["PASS"], emu["FAIL"], emu["not run"])) if l.startswith("Observed JVM:") else l for l in lines]
open(out, "w").write("\n".join(lines) + "\n")
print(lines[[i for i, l in enumerate(lines) if l.startswith("Combined totals")][0]] if any(l.startswith("Combined totals") for l in lines) else "merged")
