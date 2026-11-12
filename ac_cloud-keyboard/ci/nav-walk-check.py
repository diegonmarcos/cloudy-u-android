#!/usr/bin/env python3
"""#1698 content check of one page of the keyboard settings nav walk (keyboard-screenshots.sh).

usage: nav-walk-check.py <uiautomator dump.xml> <page id> <route> <signatures file> <chrome labels, '|' separated>

A page passes when, besides the chrome (top bar title, bottom tab labels, dropdown labels), it shows at least MIN
texts of its own, no placeholder wording, and its content differs from every page walked before it that has a
different route (two declared pages opening one shared screen is the defect this walk exists to catch).
Prints one line; exit 1 on a failure. The signature is appended to the signatures file.
"""
import hashlib, re, sys
import xml.etree.ElementTree as ET

MIN = 3
PLACEHOLDER = re.compile(r"\b(coming soon|placeholder|not implemented|todo|lorem ipsum|stub)\b", re.I)

dump, page, route, sigs, chrome = sys.argv[1:6]
chrome_set = {c.strip().lower() for c in chrome.split("|") if c.strip()}
try:
    root = ET.parse(dump).getroot()
except Exception as e:  # an unreadable dump is a failure, said plainly
    print(f"FAIL {page}: no readable screen dump ({e})"); sys.exit(1)
texts = []
for n in root.iter("node"):
    if n.get("package", "").startswith("com.android.systemui"):
        continue
    for k in ("text", "content-desc"):
        v = (n.get(k) or "").strip()
        if v and v.lower() not in chrome_set and v not in texts:
            texts.append(v)
sig = hashlib.sha1("\n".join(sorted(texts)).encode()).hexdigest()[:12]
seen = {}
try:
    for line in open(sigs):
        s, r, p = line.rstrip("\n").split(" ", 2)
        seen.setdefault(s, []).append((r, p))
except FileNotFoundError:
    pass
with open(sigs, "a") as f:
    f.write(f"{sig} {route} {page}\n")
problems = []
if len(texts) < MIN:
    problems.append(f"only {len(texts)} texts of its own ({texts})")
ph = [t for t in texts if PLACEHOLDER.search(t)]
if ph:
    problems.append(f"placeholder wording {ph[:3]}")
clash = [p for r, p in seen.get(sig, []) if r != route]
if clash:
    problems.append(f"same content as {clash} (a different route)")
head = "; ".join(t.replace("\n", " ")[:40] for t in texts[:6])
if problems:
    print(f"FAIL {page} [{route}]: " + "; ".join(problems)); sys.exit(1)
print(f"ok   {page} [{route}] {len(texts)} texts sig={sig}: {head}")
