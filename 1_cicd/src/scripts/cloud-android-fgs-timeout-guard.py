#!/usr/bin/env python3
"""
cloud-android-fgs-timeout-guard -- #fgs-timeout: no worker or service in the fleet may run as a
dataSync foreground service without timeout handling.

WHY. Android 15+ caps dataSync foreground services at 6 h per 24 h. At the cap the system calls
Service.onTimeout(int, int); a service still running seconds later crashes the app
(RemoteServiceException$ForegroundServiceDidNotStopInTimeException, Cloud Store on an SM-A376B,
Android 16, from WorkManager's SystemForegroundService). WorkManager < 2.10 ignores onTimeout, and
the periodic Store / Gallery passes held the service on every unattended run.

RULES (ROOT is the repository root)
  W1  every androidx.work:work-runtime[-ktx] pin -- a Gradle literal or a libs.versions.toml
      version a work-runtime library entry refers to -- is >= 2.10.0
  W2  every Kotlin/Java worker that calls setForeground / setForegroundAsync goes through the shared
      gate: the same file calls DataSyncBudget.mayHold( or holdDataSync( (libs:core / libs:updater)
  W3  a worker enqueued with PeriodicWorkRequestBuilder<X> never asks for the hold as user-initiated
      with a literal true (its file has no `userInitiated = true`)
  W4  every Service that starts a foreground with FOREGROUND_SERVICE_TYPE_DATA_SYNC overrides the
      two-argument onTimeout(startId, fgsType)
  W5  every exemption in 1_cicd/src/data/fgs-timeout-guard.json names a file that still needs it

EXIT 0 green, 1 a violation, 2 usage
"""
import json, os, re, sys

SKIP_DIRS = {".git", "node_modules", "build", ".gradle", "test", "androidTest", "testFixtures", "dist", "out"}
DATA = "1_cicd/src/data/fgs-timeout-guard.json"
PIN = re.compile(r"androidx\.work:work-runtime(?:-ktx)?:(\d+(?:\.\d+)*)")
TOML_REF = re.compile(r'module\s*=\s*"androidx\.work:work-runtime(?:-ktx)?"\s*,\s*version\.ref\s*=\s*"([^"]+)"')
TOML_LIT = re.compile(r'module\s*=\s*"androidx\.work:work-runtime(?:-ktx)?"\s*,\s*version\s*=\s*"([\d.]+)"')
SETFG = re.compile(r"\bsetForeground(?:Async)?\s*\(")
GATE = re.compile(r"\bDataSyncBudget\.mayHold\s*\(|\bholdDataSync\s*\(")
PERIODIC = re.compile(r"PeriodicWorkRequestBuilder\s*<\s*(\w+)\s*>")
CLASS = re.compile(r"\bclass\s+(\w+)")
DATASYNC = re.compile(r"FOREGROUND_SERVICE_TYPE_DATA_SYNC")
START_FG = re.compile(r"\bstartForeground\s*\(")
SERVICE = re.compile(r"class\s+\w+[^{]*?:\s*(?:\w+\.)*Service\s*\(|class\s+\w+\s+extends\s+(?:\w+\.)*Service\b")
TIMEOUT2 = re.compile(r"\bonTimeout\s*\(\s*(?:\w+\s*:\s*Int|int\s+\w+)\s*,\s*(?:\w+\s*:\s*Int|int\s+\w+)\s*\)")


def strip_comments(src):
    """Line-based on purpose: a block-comment regex eats code after a `/*` inside a string
    ('**/*.so', "image/*"), which hid real pins and calls. Drops comment-only lines (//, /*, *)
    and a trailing ` // ...` (a `://` URL is kept)."""
    out = []
    for line in src.splitlines():
        t = line.lstrip()
        if t.startswith(("//", "/*", "*")):
            continue
        out.append(re.sub(r"(?<![:\"'])\s//\s.*$", "", line))
    return "\n".join(out)


def vers(v):
    return tuple(int(x) for x in v.split("."))


def walk(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS and not x.startswith("z-archived")]
        for f in files:
            yield os.path.join(d, f)


def main(argv):
    if len(argv) != 2:
        print(__doc__); return 2
    root = os.path.abspath(argv[1])
    exempt = json.load(open(os.path.join(root, DATA))).get("exempt_workers", {})
    bad, sources, periodic = [], {}, set()
    for p in walk(root):
        rel = os.path.relpath(p, root)
        base = os.path.basename(p)
        if base.endswith((".gradle", ".gradle.kts")):
            for m in PIN.finditer(strip_comments(open(p, errors="replace").read())):
                if vers(m.group(1)) < (2, 10, 0):
                    bad.append(f"W1 {rel}: androidx.work {m.group(1)} < 2.10.0 (its SystemForegroundService ignores onTimeout)")
        elif base == "libs.versions.toml":
            t = open(p, errors="replace").read()
            for ref in TOML_REF.findall(t):
                m = re.search(r'(?m)^\s*' + re.escape(ref) + r'\s*=\s*"([\d.]+)"', t)
                if m and vers(m.group(1)) < (2, 10, 0):
                    bad.append(f"W1 {rel}: [versions] {ref} = {m.group(1)} < 2.10.0 (androidx.work)")
            for v in TOML_LIT.findall(t):
                if vers(v) < (2, 10, 0):
                    bad.append(f"W1 {rel}: androidx.work {v} < 2.10.0")
        elif base.endswith((".kt", ".java")):
            src = strip_comments(open(p, errors="replace").read())
            sources[rel] = src
            periodic.update(PERIODIC.findall(src))
    for rel, src in sorted(sources.items()):
        if SETFG.search(src) and rel not in exempt:
            if not GATE.search(src):
                bad.append(f"W2 {rel}: a worker enters a foreground service without DataSyncBudget.mayHold / holdDataSync")
            if set(CLASS.findall(src)) & periodic and re.search(r"userInitiated\s*=\s*true\b", src):
                bad.append(f"W3 {rel}: a periodic worker holds the dataSync service as user-initiated (literal true)")
        if DATASYNC.search(src) and START_FG.search(src) and SERVICE.search(src) and not TIMEOUT2.search(src):
            bad.append(f"W4 {rel}: a dataSync foreground Service without onTimeout(startId, fgsType)")
    for rel in sorted(exempt):
        if not (rel in sources and SETFG.search(sources[rel])):
            bad.append(f"W5 {DATA}: stale exemption {rel} (no worker foreground call there)")
    for b in bad:
        print("FAIL " + b)
    print(f"fgs-timeout-guard: {len(sources)} sources, {len(periodic)} periodic worker(s), {len(bad)} violation(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
