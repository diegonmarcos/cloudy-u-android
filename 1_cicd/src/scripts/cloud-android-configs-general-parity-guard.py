#!/usr/bin/env python3
"""
cloud-android-configs-general-parity-guard -- #1852 rule 16: the minimalist Configs > General keeps EVERY entry and action of the former one.

DECLARATION  1_cicd/src/data/configs-general-parity.json (the strings recorded from the tile layout that General drew before #1852)

  F1  every `fields` needle is still in the General sources (FleetConfigsPages.kt, FleetConfigsUi.kt): each section's rows, states and
      actions, the tags the emulator walk and the testers tap, the libs:perms hand-offs
  F2  every section has a live state: FleetGeneralState.kt computes one per section id (`state`), and the page shows it (`state_shown`)
  F3  what the redesign removed on purpose (the big saturated tiles) does not come back (`forbidden`)

USAGE  cloud-android-configs-general-parity-guard.py [ROOT]      EXIT 0 ok - 1 at least one violation
"""
import json
import os
import sys


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    rule = json.load(open(os.path.join(root, "1_cicd/src/data/configs-general-parity.json")))
    bad = []

    def read(rel):
        try:
            return open(os.path.join(root, rel), encoding="utf-8").read()
        except OSError:
            bad.append("F0 missing source: " + rel)
            return ""

    src = "\n".join(read(f) for f in rule["files"])
    for fld in rule["fields"]:
        if fld["needle"] not in src:
            bad.append("F1 General lost %s: %s (%r)" % (fld["section"], fld["what"], fld["needle"]))
    st = read(rule["state_file"])
    for sec, needle in rule["state"].items():
        if needle not in st:
            bad.append("F2 General shows no live state for %s (%r missing in %s)" % (sec, needle, os.path.basename(rule["state_file"])))
    for needle in rule["state_shown"]:
        if needle not in src:
            bad.append("F2 the General page does not show the live state (%r missing)" % needle)
    for fb in rule["forbidden"]:
        if fb["needle"] in src:
            bad.append("F3 the former layout is back: %s (%r)" % (fb["why"], fb["needle"]))
    secs = sorted({f["section"] for f in rule["fields"]})
    for b in bad:
        print("FAIL  " + b)
    print("configs-general-parity guard: %d field(s) over %d section(s), %d live state(s), %d violation(s)"
          % (len(rule["fields"]), len(secs), len(rule["state"]), len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
