#!/usr/bin/env python3
"""
terminal-target-sdk-parity -- #1708: nothing the published terminal does is lost by the build under test.

    terminal-target-sdk-parity.py APP BASE.tsv NEW.tsv [POLICY.json]

BASE.tsv and NEW.tsv are the results.tsv files 1_cicd/src/scripts/emulator-terminal-target-sdk.sh wrote for the
published build and for the new one (check <TAB> ok|FAIL|skip <TAB> detail), on the same emulator image.

RULES
  P1  every check that is ok in BASE is ok in NEW                     (a feature that worked must still work)
  P2  every check that ran in BASE (ok or FAIL) is present in NEW     (a check cannot disappear)
  P3  a check listed in POLICY known_differences[APP] is the one exception to P1, and it must really be
      failing in NEW while ok in BASE; an entry that no longer differs is stale and red
  P4  known_differences holds at most POLICY max entries in all       (the list may only shrink)
meta.* and info.* rows describe the build (targetSdk, SELinux domain, paths), differ by design and are not compared.

EXIT 0 parity holds, 1 a rule is broken, 2 usage
"""
import json
import sys

POLICY = "1_cicd/src/data/terminal-target-sdk-parity.json"


def read(path):
    rows = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2 and parts[0]:
                rows[parts[0]] = (parts[1], parts[2] if len(parts) > 2 else "")
    return rows


def compared(name):
    return not name.startswith(("meta.", "info."))


def main(argv):
    if len(argv) not in (4, 5):
        print(__doc__)
        return 2
    app, base_path, new_path = argv[1:4]
    policy = json.load(open(argv[4] if len(argv) == 5 else POLICY, encoding="utf-8"))
    known = (policy.get("known_differences") or {}).get(app) or {}
    total_known = sum(len(v or {}) for v in (policy.get("known_differences") or {}).values())
    base, new = read(base_path), read(new_path)
    bad = []
    if not any(compared(n) for n in base):
        bad.append(f"P2 {base_path} holds no check at all: the published build was not measured, so nothing is proven")
    if total_known > int(policy.get("max", total_known)):
        bad.append(f"P4 {total_known} known differences > max {policy.get('max')} (the list may only shrink)")
    print(f"{'check':34} {'published':10} {'new':6} detail (new)")
    for name in sorted(set(base) | set(new)):
        b = base.get(name, ("-", ""))[0]
        n, detail = new.get(name, ("-", ""))
        mark = ""
        if compared(name):
            if b in ("ok", "FAIL") and name not in new:
                bad.append(f"P2 {name}: ran on the published build ({b}) and is missing from the new one")
                mark = "  << MISSING"
            elif b == "ok" and n != "ok":
                if name in known:
                    mark = "  (known difference)"
                else:
                    bad.append(f"P1 {name}: ok on the published build, {n} on the new one: {detail}")
                    mark = "  << LOST"
        print(f"{name:34} {b:10} {n:6} {detail[:110]}{mark}")
    for name, why in sorted(known.items()):
        b, n = base.get(name, ("-", ""))[0], new.get(name, ("-", ""))[0]
        if not (b == "ok" and n != "ok"):
            bad.append(f"P3 known difference {name} is stale (published {b}, new {n}): drop it from {POLICY}")
        else:
            print(f"known difference  {name}: {why}")
    if bad:
        print(f"\nFAIL terminal target-sdk parity [{app}]: {len(bad)} problem(s)")
        for line in bad:
            print("  " + line)
        return 1
    n_ok = sum(1 for k, v in new.items() if compared(k) and v[0] == "ok")
    print(f"\nOK terminal target-sdk parity [{app}]: every check that passes on the published build passes on the new one "
          f"({n_ok} ok, {len(known)} known difference(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
