#!/usr/bin/env python3
"""Every AI registry has a home of its own, that home is DECLARED, and no application reads
another application's block.

Called by test-keyboard-text-resume.sh's T1. It lives in its own file rather than inside that
tester because it takes the WHOLE declared set as its subject: the question "does anybody read
somebody else's prompts" is a cross-product over participants, and the shell that asked it used to
answer for one hand-written pair.

ZERO POLICY HERE. Every participant, its registry key and its source directories come from
test/ai-registries.json. A fourth application is one entry in that file and no edit to this one -
which is the whole point, because the version of this check that named its two participants in the
script went red the day a third arrived, inside a release that third application must not be able
to fail.

EXIT 0 = every claim held. EXIT 1 = at least one did not, and each one printed why.
"""

import json
import os
import re
import subprocess
import sys

MARKER = '"summary_preamble"'

# Source trees we do not own or did not write.
SKIP_DIRS = {".git", "z_archive", "build", "node_modules", ".gradle"}


def main(manifest_path, root):
    declared = json.load(open(manifest_path))["registries"]
    fails = 0

    def ok(message):
        print("  ok: " + message)

    def bad(message):
        nonlocal fails
        fails += 1
        print("  FAIL: " + message)

    # ── the set of homes is exactly the declared set ────────────────────────
    # A build.json that grows summary prompts with no entry in the manifest is the failure the
    # manifest's own note names: "until it is listed, nothing anywhere checks it". A declared
    # entry whose prompts have gone is a registry pointing at nothing.
    found = set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        if "build.json" not in filenames:
            continue
        path = os.path.join(dirpath, "build.json")
        with open(path, encoding="utf-8", errors="replace") as handle:
            if MARKER in handle.read():
                found.add(os.path.relpath(path, root))

    listed = {r["path"] for r in declared}
    if found == listed:
        ok("T1 the %d application(s) carrying summary prompts are exactly the declared "
           "registries: %s" % (len(listed), ", ".join(sorted(r["label"] for r in declared))))
    else:
        for extra in sorted(found - listed):
            bad("T1 %s carries summary prompts and is not in ai-registries.json - nothing checks "
                "its prices, its prompts or its independence until it is listed" % extra)
        for missing in sorted(listed - found):
            bad("T1 %s is declared as a registry but no longer carries summary prompts" % missing)

    # ── each home actually holds the key it declares ────────────────────────
    for registry in declared:
        path = os.path.join(root, registry["path"])
        if not os.path.exists(path):
            bad("T1 %s declares %s, which is not in this repository"
                % (registry["label"], registry["path"]))
            continue
        if registry["key"] in json.load(open(path)):
            ok("T1 %s declares its own summary prompts in %s::%s"
               % (registry["label"], registry["path"], registry["key"]))
        else:
            bad("T1 %s has no %s block - it would be reading somebody else's prompts"
                % (registry["label"], registry["key"]))

    # ── nobody resolves anybody else's block ────────────────────────────────
    # THE ASSERTION THAT CARRIES THE REQUIREMENT, over every ordered pair rather than the one pair
    # somebody happened to write down. Separate stores mean an edit to one cannot reach the other.
    #
    # A DEREFERENCE, NOT THE WORD. Each of these build files deliberately NAMES the others in
    # prose - each says its registry began as a copy, and cloud-writer's missing-block error tells
    # whoever hits it not to repoint the build at keyboard_ai or mail_ai. Those sentences are what
    # stop the next reader from "fixing" the duplication, so this matches only the forms that
    # actually resolve a block, ["x"] / .x / ("x"), which is what a build or a parser would write.
    # A directory another registry lists in its own `sources` is THAT application's home even when it sits
    # inside a broader directory of mine (#1652: libs:writer-ui is cloud-writer's whole UI, shared by reference
    # into cloud-keyboard's Writer tab, and bakes cloud-writer's writer_ai by design). Its files are checked
    # under their owner, never counted as mine; everything else under my directories still is.
    def claimed_by_others(mine):
        out = []
        for other in declared:
            if other["label"] != mine["label"]:
                out += [os.path.normpath(os.path.join(root, d)) for d in other.get("sources", [])]
        return out

    def resolves(block, directory, skip=()):
        pattern = r'[\[(]"%s"|\.%s\b' % (re.escape(block), re.escape(block))
        hit = subprocess.run(
            ["grep", "-rlE", "--include=*.gradle", "--include=*.gradle.kts",
             "--include=*.kt", "--include=*.java", "--", pattern, directory],
            capture_output=True, text=True)
        files = [os.path.normpath(f) for f in hit.stdout.splitlines() if f.strip()]
        base = os.path.normpath(directory)
        skip = [d for d in skip if d.startswith(base + os.sep)]   # only a narrower home INSIDE the scanned directory
        return any(not any(f == d or f.startswith(d + os.sep) for d in skip) for f in files)

    for mine in declared:
        sources = mine.get("sources", [])
        if not sources:
            bad("T1 %s declares no `sources`, so nothing checks whether it reads another "
                "application's prompts - add them to ai-registries.json" % mine["label"])
            continue
        absent = [d for d in sources if not os.path.isdir(os.path.join(root, d))]
        if absent:
            bad("T1 %s declares source director(ies) not in this repository: %s - the cross-read "
                "check would read nothing and agree with itself"
                % (mine["label"], ", ".join(absent)))
            continue
        for theirs in declared:
            if theirs["label"] == mine["label"]:
                continue
            if any(resolves(theirs["key"], os.path.join(root, d), claimed_by_others(mine)) for d in sources):
                bad("T1 %s resolves %s - editing %s's prompts would change what %s sends"
                    % (mine["label"], theirs["key"], theirs["label"], mine["label"]))
            else:
                ok("T1 %s never resolves %s" % (mine["label"], theirs["key"]))

    return 1 if fails else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: ai-registry-homes.py <ai-registries.json> <repo-root>", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
