#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-ai-chat-guard — the fleet draws its AI chat ONCE,   ║
# ║ in libs:ai; an app hosts it, it does not draw its own            ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The AI chat used to be drawn four times in three dialects: Cloud Search's
# page (shared as libs:search-page only for Cloud Browser), the browser's own assistant side
# panel, Cloud Tools Small LLMs' ChatPage and, soon, one per app that wanted a tab. libs:ai
# is the one chat now (page, streaming, sessions, Agent and Model pickers, the consent card,
# the backends, the shared ai.json contract). This guard makes a second chat UI impossible
# to arrive silently: it fails when Kotlin outside the owner tree
#   - branches on the OpenAI message roles inside a composable (the bubbles),
#   - declares a composable named as a chat page / screen / panel, or
#   - names the model endpoint or the gateway's mode header inside a composable file
#     (a UI that talks to the model itself instead of through the host).
# It also fails when the owner stops HOLDING the chat (an emptied lib would stay green for
# every app that copied the page back out), and when there is nothing to scan (a guard that
# found nothing because it saw no apps is a false green).
#
# The failure message teaches the rule: a guard that only says "unexpected file" gets deleted
# by the next person who hits it.
#
# Everything it knows is in 1_cicd/src/data/ai-chat-guard.json: no app, no path and no marker
# is named below. Python 3 only; no ripgrep (it is not on the CI runner).
#
# usage: cloud-android-ai-chat-guard.py [repo-root] [--policy FILE]
# exit:  0 clean, 1 a violation, 2 the guard could not run (bad policy, empty scan)

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "ai-chat-guard.json")


def strip_comments(src):
    """Kotlin source without comments; string literals are kept (the rules look at them)."""
    out, i, n, depth = [], 0, len(src), 0
    while i < n:
        two = src[i:i + 2]
        if depth:
            if two == "/*":
                depth += 1; i += 2; continue
            if two == "*/":
                depth -= 1; i += 2; continue
            i += 1; continue
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j; continue
        if src[i] in ('"', "'"):
            q = src[i]; j = i + 1
            while j < n and src[j] != q and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1]); i = j + 1; continue
        if two == "/*":
            depth += 1; i += 2; continue
        if two == "//":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        out.append(src[i]); i += 1
    return "".join(out)


def refuse(msg):
    """The guard could not run: exit 2, never a verdict."""
    print("ai-chat-guard: " + msg, file=sys.stderr)
    sys.exit(2)


def norm(p):
    return os.path.normpath(p).replace(os.sep, "/")


def load_policy(path):
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        refuse("cannot read the policy %s (%s)" % (path, e))
    for k in ("owner", "owner_must_hold", "scan_prefixes", "rules"):
        if not cfg.get(k):
            refuse("the policy has no %s" % k)
    for name, r in cfg["rules"].items():
        try:
            re.compile(r["pattern"])
        except (re.error, KeyError) as e:
            refuse("rule %s is not a pattern (%s)" % (name, e))
    return cfg


def owner_findings(repo, cfg):
    bad = []
    owner = os.path.join(repo, cfg["owner"])
    if not os.path.isdir(owner):
        return ["the owner tree %s does not exist: the fleet's AI chat is gone" % cfg["owner"]]
    for need in cfg["owner_must_hold"]:
        # #1566 a need may name a repo-root file (`repo_file`): the fleet-private contract, materialised by CI, no longer sits in the owner tree.
        name = need.get("repo_file") or need["file"]
        p = os.path.join(repo, need["repo_file"]) if need.get("repo_file") else os.path.join(owner, need["file"])
        try:
            text = open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            bad.append("%s is missing: libs:ai no longer holds the chat" % (name if need.get("repo_file") else cfg["owner"] + "/" + name))
            continue
        code = strip_comments(text) if p.endswith(".kt") else text
        for n in need["needles"]:
            if n not in code:
                bad.append("%s no longer contains %s: libs:ai no longer holds the chat" % (name if need.get("repo_file") else cfg["owner"] + "/" + name, n))
    return bad


def kotlin_files(repo, cfg):
    """Every Kotlin file of the scanned trees outside the owner and the exemptions. Returns (files, scanned_roots)."""
    owner = norm(cfg["owner"]).rstrip("/") + "/"
    skip_dirs = set(cfg.get("skip_dirs", []))
    parts = cfg.get("skip_path_parts", [])
    exempt = [norm(e["path"]).rstrip("/") + "/" for e in cfg.get("exempt", [])]
    files, roots = [], 0
    for top in sorted(os.listdir(repo)):
        if not any(top.startswith(p) for p in cfg["scan_prefixes"]) or not os.path.isdir(os.path.join(repo, top)):
            continue
        roots += 1
        for dp, dn, fn in os.walk(os.path.join(repo, top)):
            dn[:] = sorted(d for d in dn if d not in skip_dirs)
            rel_dir = norm(os.path.relpath(dp, repo)) + "/"
            if rel_dir.startswith(owner):
                dn[:] = []
                continue
            if any(rel_dir.startswith(e) for e in exempt):
                dn[:] = []
                continue
            for f in sorted(fn):
                if not f.endswith(".kt"):
                    continue
                rel = rel_dir + f
                if any(part in "/" + rel for part in parts):
                    continue
                files.append(rel)
    return files, roots


def violations(repo, cfg):
    files, roots = kotlin_files(repo, cfg)
    if roots == 0 or not files:
        refuse("nothing to scan (%d trees, %d Kotlin files): a green verdict here would mean nothing" % (roots, len(files)))
    found = []
    for rel in files:
        try:
            raw = open(os.path.join(repo, rel), encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        code = strip_comments(raw)
        composable = "@Composable" in code
        for name, r in cfg["rules"].items():
            if r.get("composable_only") and not composable:
                continue
            if any(w in code for w in r.get("unless_file_contains", [])):
                continue
            m = re.search(r["pattern"], code)
            if m:
                line = code.count("\n", 0, m.start()) + 1
                found.append((rel, line, name, r.get("why", ""), m.group(0).strip()[:70]))
    return found, len(files)


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    policy = DEFAULT_POLICY
    if "--policy" in argv:
        policy = argv[argv.index("--policy") + 1]
        args = [a for a in args if a != policy]
    repo = os.path.abspath(args[0] if args else os.path.join(HERE, "..", "..", ".."))
    cfg = load_policy(policy)
    bad = owner_findings(repo, cfg)
    found, scanned = violations(repo, cfg)
    for rel, line, name, why, hit in found:
        bad.append("%s:%d [%s] %s: `%s`" % (rel, line, name, why, hit))
    if bad:
        print("ai-chat-guard: FAIL (%d)" % len(bad))
        for b in bad:
            print("  - " + b)
        print()
        print("RULE: %s" % cfg.get("_rule", "one AI chat UI in the fleet, libs:ai's."))
        print("The chat page, the consent card, the assistant panel, the backends and the shared contract live ONCE in %s." % cfg["owner"])
        print("To give an app a chat: add libs:ai to its build.json::modules and host it (AiChatPage + AiStandardHost, your own theme,")
        print("store and token). Do not copy the page. If a file is a different kind of chat (people talking to people), say so in")
        print("the policy's `exempt` with the reason; if a rule is too wide, narrow it THERE.")
        return 1
    print("ai-chat-guard: ok (%d Kotlin files scanned outside %s; the owner still holds the chat)" % (scanned, cfg["owner"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
