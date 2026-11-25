#!/usr/bin/env python3
"""#1921 every Compose drawText(textMeasurer, ...) must be bounded: use safeDrawText (libs:ui-kit) or an origin from
GraphCamera.labelOrigin / safeTextOrigin within the 4 lines above. An origin at or past the canvas right/bottom edge
makes Compose throw "maxWidth must be >= than minWidth" (Cloud Wiki crash). Upstream forks are skipped."""
import os, re, sys
root = sys.argv[1] if len(sys.argv) > 1 else "."
SKIP = ("ac_cloud-matrix", "ac_cloud-chat", "keyboard", "/test/", "/androidTest/", "SafeDrawText.kt", "/build/", "node_modules", ".git/")
CALL = re.compile(r"(?<![.\w])drawText\(\s*(?:textMeasurer\s*=\s*)?(\w*[mM]easurer|tm)\b")
OK = re.compile(r"labelOrigin\(|safeTextOrigin\(")
bad = []
for d, dirs, files in os.walk(root):
    dirs[:] = [x for x in dirs if x not in (".git", "node_modules", "build")]
    for f in files:
        p = os.path.join(d, f)
        if not f.endswith(".kt") or any(k in p + "/" for k in SKIP) or any(k in p for k in SKIP[3:5]):
            continue
        lines = open(p, encoding="utf-8", errors="replace").read().split("\n")
        for i, l in enumerate(lines):
            if l.lstrip().startswith("//") or not CALL.search(l):
                continue
            if not any(OK.search(x) for x in lines[max(0, i - 4):i + 1]):
                bad.append("%s:%d: %s" % (p, i + 1, l.strip()))
if bad:
    print("drawText(measurer, ...) without safeDrawText/labelOrigin (#1921):")
    print("\n".join(bad))
    sys.exit(1)
print("draw-text guard: ok")
