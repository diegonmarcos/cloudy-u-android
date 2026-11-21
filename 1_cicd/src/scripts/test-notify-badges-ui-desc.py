#!/usr/bin/env python3
"""The notify-badges emulator walk's ui.py `desc`/`count-desc` must skip nodes with empty bounds ([0,0][0,0]):
a row scrolled fully out of view (run 44938538866) sorted first and the walker tapped (0,0), not the arrow."""
import os, re, subprocess, sys, tempfile
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..")
wf = open(os.path.join(root, ".github/workflows/test-superapp-notify-badges-emulator.yml"), encoding="utf-8").read()
src = re.search(r"cat > ui\.py <<'PY'\n(.*?)\n\s*PY\n", wf, re.S).group(1)
src = "\n".join(l[10:] if l.startswith(" " * 10) else l for l in src.split("\n"))
def node(desc, b): return '<node class="android.view.View" content-desc="%s" bounds="%s" />' % (desc, b)
dump = "<hierarchy>" + node("Expand", "[0,0][0,0]") + node("Expand", "[960,747][1022,809]") + node("Expand", "[960,1079][1022,1141]") + "</hierarchy>"
with tempfile.TemporaryDirectory() as d:
    open(d + "/ui.py", "w").write(src); open(d + "/d.xml", "w").write(dump)
    run = lambda *a: subprocess.check_output([sys.executable, "-I", d + "/ui.py", *a], text=True).strip()
    assert run("desc", d + "/d.xml", "Expand", "0") == "991 778", run("desc", d + "/d.xml", "Expand", "0")
    assert run("count-desc", d + "/d.xml", "Expand") == "2"
print("ok")
