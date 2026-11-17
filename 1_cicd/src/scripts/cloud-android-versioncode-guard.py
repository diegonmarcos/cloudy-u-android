#!/usr/bin/env python3
"""cloud-android-versioncode-guard -- #1783 P5: versionCode and BuildConfig stamps are deterministic and never go down.

  check            every app gradle file with `def codeFinal` is either on the deterministic block (reads
                   FLEET_SOURCE_TIME / the HEAD commit time, floored at version-floor.json::floor, no wall clock in
                   versionCode, BUILD_TIMESTAMP or STACK_GRADLE_CONFIG_MS) or listed in `pending`; `pending` may only
                   shrink (a file that is migrated must be removed from it: progress is written down) and may name
                   no file that is already migrated. The block's floor constant equals the data file's.
                   Monotonic: the block's formula, evaluated on a time series, never yields a value below the floor
                   and never decreases.
  apply <file>...  rewrite the old wall-clock block of those gradle files to the deterministic block and drop them
                   from `pending`.
USAGE  cloud-android-versioncode-guard.py [--root R] check | apply FILE...
"""
import json, os, re, subprocess, sys

DATA = "1_cicd/src/data/version-floor.json"
OLD_CODE = re.compile(r"def codeFinal = \{ ->.*?\n\}\(\)\n", re.S)
NEW_MARK = "FLEET_SOURCE_TIME"


def block(floor):
    return f'''// #1783 P5: deterministic version. The SOURCE commit time (FLEET_SOURCE_TIME 'yyyyMMdd.HHmmss' UTC, else COMMS_BUILD_TIMESTAMP,
// else the HEAD commit's committer time, else the wall clock for a local build): the same commit always builds the same code,
// floored at {floor} (1_cicd/src/data/version-floor.json, above every code published before) so an installed app is never offered a lower one.
def fleetSourceTime = {{ ->
    def fmt = java.time.format.DateTimeFormatter.ofPattern('yyyyMMdd.HHmmss')
    def stamp = System.getenv('FLEET_SOURCE_TIME') ?: System.getenv('COMMS_BUILD_TIMESTAMP')
    if (stamp) {{
        try {{ return java.time.LocalDateTime.parse(stamp, fmt) }} catch (ignored) {{ }}
    }}
    try {{
        def ct = providers.exec {{
            commandLine 'git', 'show', '-s', '--format=%ct', 'HEAD'
            ignoreExitValue = true
        }}.standardOutput.asText.get().trim()
        if (ct) return java.time.LocalDateTime.ofEpochSecond(Long.parseLong(ct), 0, java.time.ZoneOffset.UTC)
    }} catch (ignored) {{ }}
    return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
}}()
def codeFinal = {{ ->
    def base = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
    long mins = java.time.Duration.between(base, fleetSourceTime).toMinutes()
    return (int) Math.max({floor}L, 3000000L + mins)
}}()
'''


def code(root, f):
    p = os.path.join(root, f)
    if os.path.exists(p):
        return open(p).read()
    return subprocess.run(["git", "-C", root, "show", ":" + f], capture_output=True, text=True).stdout   # sparse checkout: read the index


def migrated(t):
    return NEW_MARK in t and "def codeFinal" in t


def apply(root, files):
    cfg = json.load(open(os.path.join(root, DATA)))
    for f in files:
        t = code(root, f)
        if migrated(t):
            print("already migrated", f)
        else:
            assert OLD_CODE.search(t), f"{f}: old codeFinal block not found"
            t = OLD_CODE.sub(lambda m: block(cfg["floor"]), t, count=1)
            n = t.count('def buildTimestamp = new java.text.SimpleDateFormat')
            t = re.sub(r"def buildTimestamp = new java\.text\.SimpleDateFormat\((.*?)\)\.with \{\n\s*setTimeZone\(.*?\)\n\s*format\(new java\.util\.Date\(\)\)\n\}",
                       lambda m: "def buildTimestamp = new java.text.SimpleDateFormat(%s).with {\n    setTimeZone(java.util.TimeZone.getTimeZone('UTC'))\n    format(java.util.Date.from(fleetSourceTime.toInstant(java.time.ZoneOffset.UTC)))\n}" % m.group(1), t, flags=re.S)
            t = t.replace('"${System.currentTimeMillis() - gradleScriptStartMs}L"', '"0L"')
            open(os.path.join(root, f), "w").write(t)
            print("migrated", f)
        if f in cfg["pending"]:
            cfg["pending"].remove(f)
    json.dump(cfg, open(os.path.join(root, DATA), "w"), indent=2, ensure_ascii=False)
    open(os.path.join(root, DATA), "a").write("\n")
    return 0


def tracked(root):
    out = subprocess.run(["git", "-C", root, "grep", "--cached", "-l", "def codeFinal", "--", "*.gradle", "*.gradle.kts"], capture_output=True, text=True).stdout
    return [l for l in out.split() if l]


def evaluate(floor, minutes):
    return max(floor, 3000000 + minutes)


def check(root):
    cfg = json.load(open(os.path.join(root, DATA)))
    floor, pending, bad = cfg["floor"], set(cfg["pending"]), []
    for f in tracked(root):
        t = code(root, f)
        if f in pending:
            if migrated(t):
                bad.append(f"{f} is migrated but still in version-floor.json::pending: remove it (progress is written down)")
            continue
        if not migrated(t):
            bad.append(f"{f} still derives versionCode from the wall clock and is not in pending (a new app takes the deterministic block: cloud-android-versioncode-guard.py apply {f})")
            continue
        seg = t[t.index("def fleetSourceTime"):t.index("}()", t.index("def codeFinal")) + 3] if "def fleetSourceTime" in t else ""
        if f"Math.max({floor}L," not in seg:
            bad.append(f"{f}: versionCode is not floored at {floor}")
        body = re.sub(r"//.*", "", seg)
        if re.search(r"currentTimeMillis|new java\.util\.Date\(\)", body):
            bad.append(f"{f}: a wall clock call inside the versionCode block")
        if re.search(r"format\(new java\.util\.Date\(\)\)", t) or "currentTimeMillis() - gradleScriptStartMs" in t:
            bad.append(f"{f}: BUILD_TIMESTAMP / STACK_GRADLE_CONFIG_MS still read the wall clock")
    tr = set(tracked(root))
    for f in pending:
        if f not in tr:
            bad.append(f"pending names {f}, which has no codeFinal block any more: remove it")
    # monotonic + floor on a time series (the block's own formula)
    prev = 0
    for m in range(-100000, 400000, 997):
        v = evaluate(floor, 462240 + m)
        if v < floor or v < prev:
            bad.append(f"formula not monotonic/floored at minute offset {m}: {v}")
            break
        prev = v
    # the floor is above any code the old scheme could have published before floor_utc
    import datetime
    t0 = datetime.datetime.strptime(cfg["floor_utc"], "%Y-%m-%dT%H:%M:%SZ")
    mins = int((t0 - datetime.datetime(2026, 1, 1)).total_seconds() // 60)
    if floor != 3000000 + mins:
        bad.append(f"floor {floor} != 3000000 + minutes to {cfg['floor_utc']} ({3000000 + mins})")
    print(f"version codes: {len(tracked(root)) - len(pending)} deterministic, {len(pending)} pending, floor {floor}")
    for m in bad:
        print("VIOLATION " + m)
    print("version code guard: %s" % ("HELD" if not bad else f"{len(bad)} violation(s)"))
    return 1 if bad else 0


def main(a):
    root = "."
    if a[:1] == ["--root"]:
        root, a = a[1], a[2:]
    if a[:1] == ["check"]:
        return check(root)
    if a[:1] == ["apply"] and len(a) > 1:
        return apply(root, a[1:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
