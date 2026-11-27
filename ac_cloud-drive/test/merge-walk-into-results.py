#!/usr/bin/env python3
"""#1934 fills the EMULATOR column of the observed result table (JVM column from the Ship run's JUnit results) with the walk's per-control results.
Usage: merge-walk-into-results.py <observed.md> <walk.tsv[,walk.tsv...]> <out.md> [<ship run id> <ship sha>]. Walk rows: tab<TAB>control id<TAB>label<TAB>tapped|shown|NOT FOUND|NOT OPENED<TAB>screenshot."""
import sys
md, walk, out = sys.argv[1:4]
meta = " (JVM column from Ship run %s at %s; emulator column from this walk)" % (sys.argv[4], sys.argv[5]) if len(sys.argv) > 5 else ""
GOOD = ("tapped", "shown", "greyed with its reason", "greyed (disabled by state)", "greyed (declared disabled)", "shown (value ok)", "tapped (sheet closed)")
w = {}   # (bulk?, control id) -> every walk status seen (the cloned and the not-cloned phase both walk the per-repo ids)
for path in walk.split(","):
    for l in open(path):
        c = l.rstrip("\n").split("\t")
        if len(c) >= 4: w.setdefault((c[0].startswith("bulk"), c[1]), []).append(c[3])
def verdict(scope, cid):
    s = w.get((scope.startswith("all/"), cid))
    return "not run" if not s else ("PASS" if all(x in GOOD for x in s) else "FAIL")
rows = []; jvm = {"PASS": 0, "FAIL": 0, "DISABLED": 0}; emu = {"PASS": 0, "FAIL": 0, "not run": 0}; lines = []
for l in open(md).read().splitlines():
    if l.startswith("| ") and not l.startswith("| scope") and not l.startswith("|---"):
        c = [x.strip() for x in l.strip().strip("|").split(" | ")]
        if len(c) >= 9:
            c[8] = verdict(c[0], c[1]); emu[c[8]] += 1; jvm[c[7]] = jvm.get(c[7], 0) + 1
            l = "| " + " | ".join(c) + " |"
    lines.append(l)
lines = [("Combined totals" + meta + ": JVM %d PASS, %d FAIL, %d DISABLED; emulator %d PASS, %d FAIL, %d not run" % (jvm["PASS"], jvm["FAIL"], jvm["DISABLED"], emu["PASS"], emu["FAIL"], emu["not run"])) if l.startswith("Observed JVM:") else l for l in lines]
open(out, "w").write("\n".join(lines) + "\n")
print(lines[[i for i, l in enumerate(lines) if l.startswith("Combined totals")][0]] if any(l.startswith("Combined totals") for l in lines) else "merged")
