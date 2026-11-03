#!/usr/bin/env python3
"""Tiny uiautomator driver for the emulator tests: tap/wait/has/type by text regex. Exit 1 on timeout."""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

def adb(*a):
    return subprocess.run(["adb", "-e", *a], capture_output=True, text=True).stdout

def dump():
    for _ in range(3):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        i, j = out.find("<?xml"), out.rfind("</hierarchy>")
        if i >= 0 and j >= 0:
            return out[i:j + len("</hierarchy>")]
        time.sleep(1)
    return ""

def nodes():
    try:
        return list(ET.fromstring(dump()).iter("node"))
    except ET.ParseError:
        return []

def center(n):
    l, t, r, b = map(int, re.findall(r"-?\d+", n.get("bounds", "[0,0][0,0]")))
    return (l + r) // 2, (t + b) // 2

def hit(n, rx):
    return re.search(rx, n.get("text", "") + "\n" + n.get("content-desc", ""), re.I) is not None

PROMPT_RX = r"Make Cloud Vault your passkeys & autofill provider"

def find(rx):
    ns = nodes()
    # #1412: the launch-time provider prompt covers the app on an emulator that cannot select
    # Cloud Vault as provider; dismiss it ("Not now") unless the caller is looking for it.
    if not re.search(rx, PROMPT_RX, re.I) and any(hit(n, PROMPT_RX) for n in ns):
        later = next((n for n in ns if hit(n, r"^Not now$")), None)
        if later is not None:
            x, y = center(later)
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1)
            ns = nodes()
    for n in ns:
        if hit(n, rx) and n.get("bounds") != "[0,0][0,0]":
            return n

def poll(fn, timeout):
    end = time.time() + timeout
    while True:
        v = fn()
        if v is not None:
            return v
        if time.time() > end:
            return None
        time.sleep(1.5)

def fail(msg):
    d = os.environ.get("OUT_DIR", "/tmp")
    try:
        open(os.path.join(d, "fail-ui.xml"), "w").write(dump())
    except OSError:
        pass
    print("FAIL: " + msg, file=sys.stderr)
    try:
        print("last screen texts: " + " | ".join(filter(None, (n.get("text") or n.get("content-desc") for n in nodes())))[:600], file=sys.stderr)
    except Exception:
        pass
    sys.exit(1)

def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "tap":
        n = poll(lambda: find(args[0]), float(args[1]) if len(args) > 1 else 25)
        (n is not None) or fail("nothing to tap: " + args[0])
        x, y = center(n)
        adb("shell", "input", "tap", str(x), str(y))
    elif cmd == "wait":
        (poll(lambda: find(args[0]), float(args[1]) if len(args) > 1 else 60) is not None) or fail("never saw: " + args[0])
    elif cmd == "has":
        sys.exit(0 if find(args[0]) is not None else 1)
    elif cmd == "type":  # tap the first text field, clear it, type, and verify (retry: the CI emulator drops key events)
        want = args[0]
        for _ in range(4):
            n = poll(lambda: next((x for x in nodes() if x.get("class") == "android.widget.EditText"), None), 25)
            (n is not None) or fail("no text field to type into")
            x, y = center(n)
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1)
            adb("shell", "input", "keycombination", "113", "29")  # CTRL+A
            adb("shell", "input", "keyevent", "67")
            for _ in range(3):
                adb("shell", "input", "keyevent", "123", *(["67"] * 40))  # move to end, delete backwards
                time.sleep(0.5)
            adb("shell", "input", "text", want.replace(" ", "%s"))
            time.sleep(1.5)
            n = next((m for m in nodes() if m.get("class") == "android.widget.EditText"), None)
            if n is None or n.get("password") == "true" or n.get("text") == want:
                return
        fail("typed text never matched: " + (n.get("text", "") if n is not None else "?"))
    elif cmd == "texts":
        print(" | ".join(filter(None, (n.get("text") or n.get("content-desc") for n in nodes())))[:500])
    elif cmd == "dump":
        open(args[0], "w").write(dump())
    else:
        fail("unknown command " + cmd)

main()
