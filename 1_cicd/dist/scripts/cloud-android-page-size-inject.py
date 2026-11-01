# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-page-size-inject.py ───
#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-page-size-inject — put the 16 KB page-size guard   ║
# ║ in front of EVERY ship workflow's APK upload                     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   cloud-android-page-size-inject.py inject <repo-root>   rewrite 1_cicd/src/cicd/ship-*.yml
#   cloud-android-page-size-inject.py check  <repo-root>   exit 1 if any would change
#
# Called by cloud-android-ship-repo-workflow-engine.sh after the published-
# nothing guard, so generated-up-to-date holds it on every push. The anchor is
# the step every APK-producing ship already has: `actions/upload-artifact` with
# a `path:` ending in `.apk`. The guard step is inserted right BEFORE it, takes
# the SAME single-line `if:` (so a gate-skipped run, which built nothing, is
# not judged) and the SAME path glob, and runs the guard's mutation test first.
# A ship with no APK upload (wasm, aar, watchface) gets nothing. A new APK ship
# is guarded on its first generation, with no list to remember.
import glob
import os
import re
import sys

BEG = "      # ── MANAGED-PAGE-SIZE-GUARD: injected by cloud-android-page-size-inject.py ──"
END = "      # ── end MANAGED-PAGE-SIZE-GUARD ──"
DOC = [
    "      # 16 KB page size (Android 15+): every lib/{arm64-v8a,x86_64}/*.so in the APK about",
    "      # to be uploaded must have LOAD segments aligned >= 16 KB and sit stored at a 16 KB",
    "      # zip offset (zipalign -c -P 16). Policy and per-app exemptions:",
    "      # 1_cicd/src/data/page-size-guard.json. The mutation test runs first.",
    "      # Edit 1_cicd/src/scripts/cloud-android-page-size-inject.py, never this block.",
]


def strip_managed(lines):
    out, i = [], 0
    while i < len(lines):
        if lines[i] == BEG:
            while i < len(lines) and lines[i] != END:
                i += 1
            i += 1
            if i < len(lines) and lines[i].strip() == "":
                i += 1
            continue
        out.append(lines[i]); i += 1
    return out


def render(text, name, bad):
    lines = strip_managed(text.split("\n"))
    m = re.search(r"^\s*WORK_DIR:\s*(\S+)\s*$", text, re.M)
    starts = [i for i, l in enumerate(lines) if l.startswith("      - ")]
    for s in reversed(starts):
        e = s + 1
        while e < len(lines) and (lines[e].startswith("        ") or lines[e].strip() == ""):
            if lines[e].startswith("      - "):
                break
            e += 1
        step = lines[s:e]
        body = "\n".join(step)
        if "actions/upload-artifact" not in body:
            continue
        path = [l.split("path:", 1)[1].strip() for l in step if re.match(r"^\s+path:\s*\S", l)]
        if not path or not path[0].endswith(".apk"):
            continue
        if not m:
            bad.append("%s: uploads an APK but declares no WORK_DIR for the page-size guard" % name)
            continue
        conds = [l.strip() for l in step if re.match(r"^        if:", l) or re.match(r"^      - if:", l)]
        cond = conds[0].split("if:", 1)[1].strip() if conds else ""
        glob_ = path[0]
        if " " in glob_.replace("${{ ", "").replace(" }}", ""):
            bad.append("%s: APK upload path %r has spaces; the guard cannot pass it as one glob" % (name, glob_))
            continue
        block = [BEG] + DOC + [
            '      - name: "16 KB page size: native libs LOAD-aligned, stored, zipaligned"',
        ] + (["        if: %s" % cond] if cond else []) + [
            "        run: |",
            "          bash 1_cicd/src/scripts/test/page-size-guard.test.sh",
            "          python3 1_cicd/dist/scripts/cloud-android-page-size-guard.py \"%s\" %s" % (m.group(1), glob_),
            END,
            "",
        ]
        lines[s:s] = block
    return "\n".join(lines)


def main(argv):
    if len(argv) != 3 or argv[1] not in ("inject", "check"):
        sys.exit("usage: cloud-android-page-size-inject.py inject|check <repo-root>")
    mode, root = argv[1], argv[2]
    bad, stale = [], []
    for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/ship-*.yml"))):
        name = os.path.basename(wf)
        text = open(wf).read()
        new = render(text, name, bad)
        if new != text:
            stale.append(name)
            if mode == "inject":
                open(wf, "w").write(new)
                print("  page-size guarded %s" % name)
    for b in bad:
        print("  " + b, file=sys.stderr)
    if mode == "check" and stale:
        print("  page-size guard out of date in: %s (run ./build.sh workflow)" % ", ".join(stale), file=sys.stderr)
        return 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
