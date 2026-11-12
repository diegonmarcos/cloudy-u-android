# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-sign-in-owner-guard.py ───
#!/usr/bin/env python3
"""#1693 sign-in owner guard: Cloud Account is the only fleet app that STARTS a sign-in.

Every other app's sign-in row is a link that opens Cloud Account at that provider's page and reads
the resulting state back over the fleet IPC. This guard scans every app and lib tree for code that
starts a sign-in (gh auth login, a call into the gh engine's login, an engine login declaration, an
OAuth device grant, an OAuth browser flow, libs:auth's sign-in surface, a credential prompt on a
sign-in page) and fails for each hit outside the owner, unless the policy exempts that path for that
rule with a reason. An exemption that matches nothing any more fails too, so the list only shrinks
as apps migrate. Everything the guard knows is in 1_cicd/src/data/sign-in-owner-guard.json.

Usage: cloud-android-sign-in-owner-guard.py <repo-root> [--policy FILE] [--git REV]
  --git REV scans the tree of a commit (git objects) instead of the working tree.
Exit 0 green, 1 red, 2 cannot answer (no policy, no app tree).
"""
import json, os, re, subprocess, sys


def load_args(argv):
    root, policy, rev = None, None, None
    it = iter(argv[1:])
    for a in it:
        if a == "--policy": policy = next(it)
        elif a == "--git": rev = next(it)
        else: root = a
    if not root:
        print(__doc__); sys.exit(2)
    root = os.path.abspath(root)
    policy = policy or os.path.join(root, "1_cicd/src/data/sign-in-owner-guard.json")
    return root, policy, rev


def wanted(path, cfg):
    parts = path.split("/")
    if not any(parts[0].startswith(p) for p in cfg["scan_prefixes"]): return False
    if any(p in cfg["skip_dirs"] for p in parts): return False
    if any(s in "/" + path for s in cfg["skip_path_parts"]): return False
    return os.path.splitext(path)[1] in cfg["extensions"]


def files_worktree(root, cfg):
    for top in sorted(os.listdir(root)):
        if not any(top.startswith(p) for p in cfg["scan_prefixes"]): continue
        base = os.path.join(root, top)
        if not os.path.isdir(base): continue
        for d, dirs, names in os.walk(base):
            dirs[:] = sorted(x for x in dirs if x not in cfg["skip_dirs"])
            for n in sorted(names):
                rel = os.path.relpath(os.path.join(d, n), root).replace(os.sep, "/")
                if wanted(rel, cfg):
                    try: yield rel, open(os.path.join(d, n), encoding="utf-8", errors="replace").read()
                    except OSError: pass


def files_git(root, rev, cfg):
    out = subprocess.run(["git", "-C", root, "ls-tree", "-r", "--name-only", rev], capture_output=True, text=True, check=True).stdout
    paths = [p for p in out.splitlines() if wanted(p, cfg)]
    p = subprocess.Popen(["git", "-C", root, "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    for path in paths:
        p.stdin.write(f"{rev}:{path}\n".encode()); p.stdin.flush()
        head = p.stdout.readline().split()
        if len(head) < 3 or head[1] != b"blob": continue
        data = p.stdout.read(int(head[2])); p.stdout.read(1)
        yield path, data.decode("utf-8", "replace")
    p.stdin.close(); p.wait()


def read_file(root, rev, rel):
    if rev:
        r = subprocess.run(["git", "-C", root, "show", f"{rev}:{rel}"], capture_output=True, text=True)
        return r.stdout if r.returncode == 0 else None
    try: return open(os.path.join(root, rel), encoding="utf-8").read()
    except OSError: return None


def main(argv):
    root, policy, rev = load_args(argv)
    try: cfg = json.load(open(policy))
    except (OSError, ValueError) as e:
        print(f"sign-in owner guard: cannot read the policy {policy}: {e}"); return 2
    rules = {k: (re.compile(v["pattern"], re.M), re.compile(v["file_context"], re.M | re.I) if v.get("file_context") else None)
             for k, v in cfg["rules"].items()}
    owner = cfg["owner"].rstrip("/") + "/"
    owner_impl = [p.rstrip("/") + "/" for p in cfg.get("owner_impl", {})]
    exempt = cfg.get("exempt", [])
    used = [0] * len(exempt)
    bad, scanned, apps = [], 0, set()

    src = files_git(root, rev, cfg) if rev else files_worktree(root, cfg)
    for rel, text in src:
        scanned += 1; apps.add(rel.split("/")[0])
        if rel.startswith(owner) or any(rel.startswith(p) for p in owner_impl): continue
        for rid, (pat, ctx) in rules.items():
            m = pat.search(text)
            if not m or (ctx and not ctx.search(text)): continue
            hit = False
            for i, e in enumerate(exempt):
                if rel.startswith(e["path"]) and (e["rules"] == "*" or rid in e["rules"]):
                    used[i] += 1; hit = True
            if not hit:
                line = text.count("\n", 0, m.start()) + 1
                bad.append(f"{rel}:{line}: [{rid}] {cfg['rules'][rid]['why']}")

    if not scanned or owner.split("/")[0] not in apps:
        print(f"sign-in owner guard: nothing to scan or the owner {owner} is missing; refusing to answer"); return 2

    for need in cfg.get("owner_must_hold", []):
        text = read_file(root, rev, need["file"])
        for n in need["needles"]:
            if text is None or n not in text:
                bad.append(f"{need['file']}: the owner no longer holds '{n}' ({need['why']})")

    for i, e in enumerate(exempt):
        if not used[i]:
            bad.append(f"exemption {e['path']} {e['rules']} matches nothing any more: remove it from the policy (the list only shrinks)")

    if bad:
        print(f"sign-in owner guard: RED, {len(bad)} finding(s). Only {owner} starts a sign-in; other apps link to it (#1693).")
        for b in bad: print("  " + b)
        return 1
    print(f"sign-in owner guard: green ({scanned} files, {len(exempt)} declared exemptions, owner {owner}).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
