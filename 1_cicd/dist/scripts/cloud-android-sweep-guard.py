# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-sweep-guard.py ───
#!/usr/bin/env python3
"""Anti-sweep guard: a commit must not delete or revert another ticket's work.

WHY THIS EXISTS
Twice on 2026-11-17 (12083c125, then 0cb23c7ed) a commit tagged for one ticket
was built from a stale working tree and silently reverted ~1,500 lines that
three other tickets (#1774, #1775, #1776) had shipped. Every other guard stayed
green because the reverted tree was internally consistent.

WHAT IT CHECKS
For every non-merge commit in the range:
  * the commit's own ticket tags are the `#NNNN` tokens in its SUBJECT line;
  * every path the commit removes lines from is looked up: the LAST commit that
    touched the path before this one and carries a ticket tag in its subject is
    the path's owner (untagged commits are looked through, so an untagged
    "Restore ..." commit does not launder ownership);
  * removed lines in paths whose owner shares none of the commit's tags are
    "foreign" and summed over the commit;
  * a commit whose foreign removals reach THRESHOLD (200) lines fails.
A deliberate revert passes only with an explicit `revert-of:<sha>` marker in the
message naming an existing commit. A subject with no ticket tag has no own tag,
so every owned path it strips counts as foreign.

Regenerated artefacts have no human owner whose work a regeneration reverts, so
they are exempt (EXEMPT below): the committed generated copies and catalogues.
Nothing else is exempt: no path allowlist, no size escape.

Usage:
  cloud-android-sweep-guard.py --range BASE..HEAD [--threshold N]
Exit 0 clean, 1 on a sweep, 2 on a usage error.
"""
import fnmatch
import re
import subprocess
import sys

THRESHOLD = 200
HISTORY_DEPTH = 40  # commits walked back per path to find a tagged owner

# Regenerated artefacts (a regeneration legitimately rewrites what another
# ticket's last commit wrote). Globs against the repo-relative path.
EXEMPT = (
    "1_cicd/dist/*",
    ".github/workflows/*",
    "*/favourites_catalog.json",
    "*/test-coverage.json",
    "*/mesh-slices*.json",
)

TAG = re.compile(r"#(\d{3,6})\b")
MARKER = re.compile(r"(?m)^[ \t]*revert-of:[ \t]*([0-9a-fA-F]{7,40})\b")


def git(*args, check=True):
    p = subprocess.run(("git",) + args, capture_output=True, text=True, errors="replace")
    if check and p.returncode != 0:
        raise RuntimeError("git %s: %s" % (" ".join(args), p.stderr.strip()))
    return p.stdout


def tags_of(subject):
    return set(TAG.findall(subject))


def exempt(path):
    return any(fnmatch.fnmatch(path, g) for g in EXEMPT)


def owner_of(path, parent):
    """(sha, tags) of the last tagged commit touching `path` at or before `parent`."""
    out = git("log", "-n", str(HISTORY_DEPTH), "--no-renames", "--format=%H%x00%s", parent, "--", path, check=False)
    for line in out.splitlines():
        sha, _, subject = line.partition("\x00")
        t = tags_of(subject)
        if t:
            return sha, t
    return None, set()


def revert_marker_ok(message):
    for m in MARKER.finditer(message):
        if subprocess.run(["git", "cat-file", "-e", m.group(1) + "^{commit}"], capture_output=True).returncode == 0:
            return True
    return False


def check_commit(sha, threshold):
    parents = git("rev-list", "--parents", "-n", "1", sha).split()[1:]
    if len(parents) != 1:  # root or merge: no single tree to compare
        return None
    parent = parents[0]
    message = git("show", "-s", "--format=%B", sha)
    subject = message.splitlines()[0] if message.strip() else ""
    if revert_marker_ok(message):
        return None
    own = tags_of(subject)
    foreign, per_owner = 0, {}
    # -z with rename detection: a moved file reports only the lines that changed
    # (a rebrand that renames a tree is not a deletion of it). The owner of a
    # moved file is looked up under its OLD path.
    toks = git("show", "-M", "--numstat", "-z", "--format=", sha).split("\0")
    entries, k = [], 0
    while k < len(toks):
        head = toks[k]
        k += 1
        parts = head.lstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        if parts[2] == "":  # rename: the two paths follow as separate tokens
            old_path, k = toks[k], k + 2
            entries.append((parts[0], parts[1], old_path))
        else:
            entries.append((parts[0], parts[1], parts[2]))
    for _added, removed_s, path in entries:
        if not removed_s.isdigit():  # binary: "-"
            continue
        removed = int(removed_s)
        if removed == 0 or exempt(path):
            continue
        osha, otags = owner_of(path, parent)
        if not otags or (own & otags):
            continue
        foreign += removed
        key = "#" + "/#".join(sorted(otags)) + " (" + osha[:9] + ")"
        per_owner.setdefault(key, []).append((path, removed))
    if foreign >= threshold:
        return foreign, per_owner
    return None


def main(argv):
    threshold, rng = THRESHOLD, None
    i = 1
    while i < len(argv):
        if argv[i] == "--range" and i + 1 < len(argv):
            rng = argv[i + 1]; i += 2
        elif argv[i] == "--threshold" and i + 1 < len(argv):
            threshold = int(argv[i + 1]); i += 2
        else:
            print(__doc__); return 2
    if not rng:
        print(__doc__); return 2
    bad = 0
    for sha in git("rev-list", "--reverse", rng).split():
        r = check_commit(sha, threshold)
        if r is None:
            continue
        bad += 1
        foreign, per_owner = r
        subject = git("show", "-s", "--format=%s", sha).strip()
        print("SWEEP  %s %s" % (sha[:9], subject[:100]))
        print("       removes %d lines from paths whose last ticket commit belongs to another ticket (limit %d):" % (foreign, threshold))
        for key, items in sorted(per_owner.items()):
            print("       owner %s: %d file(s), %d lines" % (key, len(items), sum(n for _, n in items)))
            for path, n in sorted(items, key=lambda x: -x[1])[:6]:
                print("         -%d %s" % (n, path))
        print("       A deliberate revert says so in the message: revert-of:<sha of the reverted commit>.")
        print("       A commit that really edits several tickets' files names every ticket in its subject (#1 #2 ...).")
        print("       Otherwise rebuild the commit from a fresh pull; the tree was stale.")
    if bad:
        print("anti-sweep guard: %d commit(s) revert another ticket's work" % bad)
        return 1
    print("anti-sweep guard: %s clean" % rng)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
