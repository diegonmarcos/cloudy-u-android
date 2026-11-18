# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-decisions-use-guard.py ───
#!/usr/bin/env python3
"""cloud-android-decisions-use-guard.py -- #881 the fleet asks the Jev decision model through ONE engine,
and every question it asks is declared.

Jev only reorders, pre-selects, re-ranks or suggests; the app's own path is the fallback and runs on any
failure; a static deny list runs before Jev; and no app holds the key. Wave 0 built the plumbing (the
decisions engine, its contract client libs:decisions-link, the per-use declaration decisions.json); this
holds the rules every later wave must keep, from the sources alone:

  D1  the wire carries no credential: no parameter or method of IDecisionsEngine.aidl or of a public
      DecisionsLink function is named like a token, key, secret or password, and the contract module
      does not depend on a module that can reveal one. (The same property ac_cloud-mail/test K1 holds
      for ITextTools: an app may choose a use, never hold, read, store or send the key that pays for it.)
  D2  every use in the declaration validates: a class from the vocabulary; threshold in (0, 1]; a
      background use declares ttl_s (it caches and never re-asks); max_calls_per_hour >= 1; at least one
      app; consent required or implicit, and required for any use whose content is mail or git; every
      app is a fleet package or the engine itself. #1799 every image use (image.uses) too: max_calls_per_hour,
      apps, consent and content hold the same rules (an image use has no class or threshold).
  D3  every DecisionsLink.decide / .outcome call site names its use as a STRING LITERAL (so this check can
      see it), the use is declared, and the application it is compiled into is one the use lists. #1799 the same
      for every DecisionsLink.image call site, against the declaration's image.uses.
  D4  a use with a call site names `fallback_test` (path::Name): a file that exists and mentions both Name
      and the use, the test that proves the app does today's behaviour when there is no verdict.
  D5  nothing outside data::key_readers calls revealAiKey(: no NEW app or lib reads the OpenRouter key.
      A listed reader that no longer does can be dropped from the list; the list only shrinks.
  D6  not vacuous: the declaration exists, declares at least one use, and the link and the engine exist.

Usage: cloud-android-decisions-use-guard.py <repo root>
"""
import glob
import json
import os
import re
import sys

DATA = "1_cicd/src/data/decisions-use-guard.json"


def code(path):
    """A source file with comment lines and trailing // comments dropped: prose may NAME what it forbids."""
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"'])//.*$", "", l) for l in text.split("\n"))


def sources(root, rel, skip, exts=(".kt", ".java")):
    base = os.path.join(root, rel)
    for dirpath, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if d not in skip]
        for f in files:
            if f.endswith(exts):
                yield os.path.join(dirpath, f)


def split_args(text, start):
    """The top-level comma-separated arguments of the call whose '(' is at text[start - 1]."""
    args, depth, cur, i, quote = [], 1, "", start, None
    while i < len(text) and depth:
        c = text[i]
        if quote:
            cur += c
            if c == "\\" and i + 1 < len(text):
                cur += text[i + 1]
                i += 1
            elif c == quote:
                quote = None
        elif c in "\"'":
            quote = c
            cur += c
        elif c in "([{":
            depth += 1
            cur += c
        elif c in ")]}":
            depth -= 1
            if depth:
                cur += c
        elif c == "," and depth == 1:
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    if cur.strip():
        args.append(cur.strip())
    return args


def check(root):
    bad = []
    cfg = json.load(open(os.path.join(root, DATA), encoding="utf-8"))
    skip = set(cfg["skip_dirs"])
    link = cfg["link"]
    decl_path = os.path.join(root, cfg["declaration"])

    # D6 the subjects exist
    if not os.path.isfile(decl_path):
        return ["D6 %s is missing: the engine has no declaration, so nothing below can be checked" % cfg["declaration"]]
    decl = json.load(open(decl_path, encoding="utf-8"))
    uses = {k: v for k, v in (decl.get("uses") or {}).items() if not k.startswith("_")}
    image_uses = {k: v for k, v in ((decl.get("image") or {}).get("uses") or {}).items() if not k.startswith("_")}
    if not uses:
        bad.append("D6 %s declares no use: this guard checked nothing" % cfg["declaration"])
    for needed in (link + "/src/main/aidl", link + "/build.gradle", link + "/engine-client.json"):
        if not os.path.exists(os.path.join(root, needed)):
            bad.append("D6 %s is missing" % needed)
    if bad:
        return bad

    # D1 the wire carries no credential
    ident = re.compile(cfg["credential_identifier"])
    for aidl in glob.glob(os.path.join(root, link, "src", "main", "aidl", "**", "*.aidl"), recursive=True):
        text = code(aidl)
        for m in re.finditer(r"\b(\w+)\s*\(([^)]*)\)\s*;", text):
            names = [m.group(1)] + [p.strip().split()[-1] for p in m.group(2).split(",") if p.strip()]
            for n in names:
                if ident.search(n):
                    bad.append("D1 %s: `%s` is named like a credential -- the wire carries none; the engine holds the token"
                               % (os.path.relpath(aidl, root), n))
    for src in sources(root, link + "/src/main", skip):
        for m in re.finditer(r"^(?!\s*private)\s*(?:internal\s+|inline\s+)*fun\s+(?:\w+\.)?(\w+)\s*\(([^)]*)\)", code(src), re.M):
            names = [m.group(1)] + [re.split(r"\s*:\s*", p.strip())[0].split()[-1] for p in m.group(2).split(",") if p.strip()]
            for n in names:
                if ident.search(n):
                    bad.append("D1 %s: public `%s` is named like a credential -- an app names a use, a state and questions, never a key"
                               % (os.path.relpath(src, root), n))
    gradle = code(os.path.join(root, link, "build.gradle"))
    for dep in cfg["link_forbidden_dependencies"]:
        if re.search(r"project\(\s*['\"]:%s['\"]\s*\)" % re.escape(dep), gradle):
            bad.append("D1 %s/build.gradle depends on %s, which can reveal the key" % (link, dep))

    # D2 every declared use validates
    fleet = {a.get("package") for a in json.load(open(os.path.join(root, cfg["fleet"]), encoding="utf-8"))["apps"] if a.get("package")}
    fleet.add(cfg["engine_package"])
    for name, u, image in sorted([(n, u, False) for n, u in uses.items()] + [(n, u, True) for n, u in image_uses.items()]):
        w = ("D2 image use %s" if image else "D2 use %s") % name
        if not isinstance(u, dict):
            bad.append("%s is not an object" % w)
            continue
        if image:
            pass
        elif u.get("class") not in cfg["classes"]:
            bad.append("%s: class %r is not one of %s" % (w, u.get("class"), "/".join(cfg["classes"])))
        t = u.get("threshold")
        if image:
            pass
        elif not isinstance(t, (int, float)) or isinstance(t, bool) or not 0 < t <= 1:
            bad.append("%s: threshold %r must be in (0, 1]" % (w, t))
        if u.get("class") == "background" and not (isinstance(u.get("ttl_s"), (int, float)) and u["ttl_s"] > 0):
            bad.append("%s: a background use declares ttl_s (it caches and never re-asks)" % w)
        if not isinstance(u.get("max_calls_per_hour"), int) or u["max_calls_per_hour"] < 1:
            bad.append("%s: max_calls_per_hour must be an integer >= 1" % w)
        apps = u.get("apps")
        if not isinstance(apps, list) or not apps:
            bad.append("%s: apps lists no app, so nothing may call it" % w)
        else:
            for a in apps:
                if a not in fleet:
                    bad.append("%s: app %r is not a fleet package or the engine" % (w, a))
        consent = u.get("consent", "required")
        if consent not in ("required", "implicit"):
            bad.append("%s: consent must be required or implicit" % w)
        if consent == "implicit" and u.get("content") in cfg["sensitive_content"]:
            bad.append("%s: content %s always needs consent: required (off until the user grants it)" % (w, u["content"]))
    if sorted(decl.get("sensitive_content") or []) != sorted(cfg["sensitive_content"]):
        bad.append("D2 the declaration's sensitive_content %r must be %r (the guard's own list): mail and git content are off by default"
                   % (decl.get("sensitive_content"), cfg["sensitive_content"]))

    # D3 call sites
    call = re.compile(r"\bDecisionsLink\s*\.\s*(decide|outcome|image)\s*\(")
    app_ids = {}
    for bj in glob.glob(os.path.join(root, cfg["app_roots"], "build.json")):
        app = os.path.basename(os.path.dirname(bj))
        try:
            app_ids[app] = (json.load(open(bj, encoding="utf-8")).get("android") or {}).get("application_id")
        except (OSError, ValueError):
            app_ids[app] = None
    sites = {}
    scan = [(r, "app") for r in sorted(glob.glob(os.path.join(root, cfg["app_roots"])))] + [(os.path.join(root, cfg["lib_root"]), "lib")]
    for top, kind in scan:
        if not os.path.isdir(top):
            continue
        for src in sources(root, os.path.relpath(top, root), skip):
            rel = os.path.relpath(src, root)
            if rel.startswith(link + "/"):
                continue
            text = code(src)
            for m in call.finditer(text):
                args = split_args(text, m.end())
                lit = re.fullmatch(r'"([A-Za-z0-9_.-]+)"', args[1]) if len(args) > 1 else None
                where = "%s:%d" % (rel, text.count("\n", 0, m.start()) + 1)
                if not lit:
                    bad.append("D3 %s: DecisionsLink.%s names its use with %r, not a string literal -- this guard cannot see which use it is"
                               % (where, m.group(1), args[1] if len(args) > 1 else "nothing"))
                    continue
                use = lit.group(1)
                table = image_uses if m.group(1) == "image" else uses
                sites.setdefault((use, m.group(1) == "image"), []).append(where)
                if use not in table:
                    bad.append("D3 %s: %suse %r is not declared in %s" % (where, "image " if table is image_uses else "", use, cfg["declaration"]))
                    continue
                if kind == "app":
                    app = rel.split("/")[0]
                    aid = app_ids.get(app)
                    if not aid:
                        bad.append("D3 %s: cannot resolve %s's application id (build.json android.application_id)" % (where, app))
                    elif aid not in (table[use].get("apps") or []):
                        bad.append("D3 %s: %s (%s) is not in use %r's apps %s -- the engine would answer app_not_allowed"
                                   % (where, app, aid, use, table[use].get("apps")))

    # D4 a called use proves its fallback
    for (use, image), where in sorted(sites.items()):
        table = image_uses if image else uses
        if use not in table:
            continue
        ft = table[use].get("fallback_test")
        if not isinstance(ft, str) or "::" not in ft:
            bad.append("D4 use %r is called (%s) but declares no fallback_test (path::Name): the test that proves today's behaviour runs when there is no verdict"
                       % (use, where[0]))
            continue
        path, name = ft.split("::", 1)
        full = os.path.join(root, path)
        if not os.path.isfile(full):
            bad.append("D4 use %r: fallback_test file %s does not exist" % (use, path))
        else:
            body = open(full, encoding="utf-8", errors="replace").read()
            if name not in body or use not in body:
                bad.append("D4 use %r: %s does not mention both %r and the use -- it does not test this fallback" % (use, path, name))

    # D5 nobody new holds the key
    holders = {"app": set(), "lib": set()}
    for top, kind in scan:
        if not os.path.isdir(top):
            continue
        for src in sources(root, os.path.relpath(top, root), skip):
            rel = os.path.relpath(src, root)
            if cfg["key_call"] in code(src):
                holders[kind].add(rel.split("/")[0] if kind == "app" else rel.split("/")[len(cfg["lib_root"].split("/"))])
    for kind, allowed in (("app", cfg["key_readers"]["apps"]), ("lib", cfg["key_readers"]["libs"])):
        for h in sorted(holders[kind] - set(allowed)):
            bad.append("D5 %s %s calls %s: no new module holds the OpenRouter key -- ask through DecisionsLink and let the engine hold it "
                       "(or, for a module that legitimately reveals it, add it to data::key_readers with the reason)" % (kind, h, cfg["key_call"]))
    return bad


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    bad = check(argv[1])
    for b in bad:
        print("FAIL  " + b)
    if bad:
        return 1
    print("PASS  decisions-use guard: the wire carries no credential, every use validates, every call site names a declared use, no new key reader")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
