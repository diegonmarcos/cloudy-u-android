#!/usr/bin/env python3
"""
cloud-android-licence-inventory — #805: one machine-readable licence inventory
of everything this repository builds from, and a CI gate that keeps it whole.

  refresh ROOT [--offline]   rebuild ROOT/licenses/inventory.json
  check   ROOT               exit 1 naming every subject with no inventory entry

SUBJECTS (what must have an entry; discovered from `git ls-files`, z_archive/
and anything under licenses/curated.json::scan_skip_prefixes excluded):

  dir:<top>        every top-level directory            -> curated.directories
  vendored:<dir>   every directory holding a LICENSE / COPYING / NOTICE file
                   that is not inside a module of licenses/upstreams.json
                                                        -> curated.directories
  maven:<g>:<a>    every Maven coordinate a build.gradle(.kts) string literal or
                   a gradle/libs.versions.toml [libraries] entry declares
  npm:<name>       every dependency a package.json declares (workspace-internal
                   packages, i.e. names some in-tree package.json defines, are
                   own code and not subjects)
  asset:<path>     every tracked binary of a licence-bearing kind
                   (curated.asset_extensions: native libs, jars, models, fonts…)
  cargo:<crate>    every crate a Cargo.lock listed in licenses/curated.json::cargo_lockfiles resolves (a lock lists the
                   whole closure, so the transitive crates are subjects too: unlike maven/npm they ARE resolved) -> curated.cargo_licences
  fetched:<repo>:<path>
                   every file the build FETCHES from another repository at a pinned commit and
                   ships (ab_cloud-terminal-store/store.json::linux_tools: the linux-store /
                   linux-account CLIs and the fish greeting, both terminals). They are not in this
                   tree, so nothing else would ever record them. The licence is the one declared
                   beside the pin (linux_tools.licence), checked against the fetched repository's
                   LICENSE at that commit when the pin moves.

Keys carry no version, so a version bump never fails the gate; a NEW
coordinate, package, vendored directory, binary or top-level directory does.

LICENCE RESOLUTION (refresh only; check never touches the network):
  maven  curated.maven_group_licences (longest group prefix) first, else the
         POM's <licenses> (then its parent POM's) from the repos in
         curated.maven_repos at the declared version, or the latest in
         maven-metadata.xml when the version comes from a BOM / catalog
         reference that cannot be resolved without Gradle.
  npm    registry.npmjs.org/<name>/latest `license`.
  asset  the licence of the deepest curated directory or upstream module
         containing it.
Anything unresolved is recorded as NOASSERTION with the reason - never guessed.

TRANSITIVE DEPENDENCIES are NOT resolved: that needs a Gradle / npm resolution
this repository's shared runners do not run for an audit. Each maven/npm entry
says so ("transitive": "not resolved"). Not legal advice.

EXIT  0 ok - 1 missing entries - 2 usage
"""
import concurrent.futures as cf, json, os, re, subprocess, sys, tomllib, urllib.request
import xml.etree.ElementTree as ET

CONFIG_RE = re.compile(r"^\s*(implementation|api|compileOnly|runtimeOnly|kapt|ksp|annotationProcessor|"
                       r"coreLibraryDesugaring|classpath|lintChecks|detektPlugins|"
                       r"(?:test|androidTest|debug|release|testFixtures|[a-z]+)(?:Implementation|Api|CompileOnly|RuntimeOnly))\b")
COORD_RE = re.compile(r"[\"']([A-Za-z0-9_.\-]+\.[A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([^\"'\s@]+))?(?:@\w+)?[\"']")
LIC_NAMES = re.compile(r"(^|/)(LICEN[CS]E|COPYING|NOTICE)(\.(md|txt|rst|html)|-(MIT|APACHE|COMMERCIAL|GPL|BSD|LGPL|MPL)[^/]*)?$", re.I)
SPDX = {  # licence names as POMs / registries write them -> SPDX
    "the apache software license, version 2.0": "Apache-2.0", "apache license, version 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0", "apache-2.0": "Apache-2.0", "the apache license, version 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0", "apache 2": "Apache-2.0", "apache license version 2.0": "Apache-2.0",
    "mit license": "MIT", "the mit license": "MIT", "mit": "MIT",
    "bsd-3-clause": "BSD-3-Clause", "new bsd license": "BSD-3-Clause", "the bsd license": "BSD-3-Clause",
    "bsd 3-clause license": "BSD-3-Clause", "bsd-2-clause": "BSD-2-Clause",
    "eclipse public license - v 1.0": "EPL-1.0", "eclipse public license 1.0": "EPL-1.0",
    "eclipse public license - v 2.0": "EPL-2.0", "eclipse public license v2.0": "EPL-2.0",
    "gnu lesser general public license": "LGPL-2.1-or-later", "mozilla public license 2.0": "MPL-2.0",
    "mpl 2.0": "MPL-2.0", "isc": "ISC", "0bsd": "0BSD", "unlicense": "Unlicense", "cc0-1.0": "CC0-1.0",
    "android software development kit license": "LicenseRef-Android-SDK",
    "simplified bsd license": "BSD-2-Clause", 'bsd 2-clause "simplified" license': "BSD-2-Clause",
    "the 3-clause bsd license": "BSD-3-Clause", "the bsd 3-clause license": "BSD-3-Clause",
    "revised bsd": "BSD-3-Clause", "the mit license (mit)": "MIT", "public domain": "LicenseRef-PublicDomain",
    "play core software development kit terms of service": "LicenseRef-Google-Play-Core-SDK-Terms",
}


def git_files(root):
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return [f for f in out.decode().split("\0") if f]


def load(root, p):
    with open(os.path.join(root, p)) as h:
        return json.load(h)


def deepest(prefixes, path):
    best = None
    for p in prefixes:
        if (path == p or path.startswith(p.rstrip("/") + "/")) and (best is None or len(p) > len(best)):
            best = p
    return best


# ── discovery ────────────────────────────────────────────────────────────────

def discover(root):
    cur = load(root, "licenses/curated.json")
    ups = load(root, "licenses/upstreams.json")["modules"]
    skip = tuple(cur.get("scan_skip_prefixes", []))
    files = [f for f in git_files(root) if not f.startswith(skip)]
    up_paths = [m["path"] for m in ups]
    subj = {}

    def add(key, **kw):
        e = subj.setdefault(key, {"declared_in": []})
        for k, v in kw.items():
            if k == "declared_in":
                if v not in e["declared_in"]:
                    e["declared_in"].append(v)
            elif v and not e.get(k):
                e[k] = v

    for f in files:
        if "/" in f:
            add("dir:" + f.split("/", 1)[0], declared_in="(tree)")
        if LIC_NAMES.search(f) and "/" in f:
            d = os.path.dirname(f)
            if not deepest(up_paths, d) and "/node_modules/" not in f:
                add("vendored:" + d, declared_in=f)
        if os.path.splitext(f)[1].lower() in cur["asset_extensions"]:
            add("asset:" + f, declared_in=f)

    for f in files:
        base = os.path.basename(f)
        if base in ("build.gradle", "build.gradle.kts"):
            test = False
            for line in open(os.path.join(root, f), errors="replace"):
                m = CONFIG_RE.match(line)
                if not m:
                    continue
                cfg = m.group(1)
                scope = "test" if cfg.lower().startswith(("test", "androidtest")) else (
                    "build" if cfg in ("classpath", "lintChecks", "detektPlugins") else "shipped")
                for g, a, v in COORD_RE.findall(line):
                    add(f"maven:{g}:{a}", declared_in=f, version=v or "", scope=scope)
        elif base == "libs.versions.toml":
            try:
                t = tomllib.load(open(os.path.join(root, f), "rb"))
            except Exception:
                continue
            vers = t.get("versions", {})
            for _, spec in (t.get("libraries") or {}).items():
                if isinstance(spec, str):
                    parts = spec.split(":")
                    g, a, v = parts[0], parts[1], (parts[2] if len(parts) > 2 else "")
                else:
                    if "module" in spec:
                        g, a = spec["module"].split(":")[:2]
                    else:
                        g, a = spec.get("group", ""), spec.get("name", "")
                    v = spec.get("version", "")
                    if isinstance(v, dict):
                        v = vers.get(v.get("ref", ""), "") if "ref" in v else v.get("strictly", v.get("require", ""))
                    if isinstance(v, dict):
                        v = v.get("strictly", v.get("require", ""))
                if g and a:
                    add(f"maven:{g}:{a}", declared_in=f, version=str(v or ""), scope="catalog")

    for lock in cur.get("cargo_lockfiles", []):
        if lock not in files:
            continue
        try:
            pk = tomllib.load(open(os.path.join(root, lock), "rb")).get("package", [])
        except Exception:
            continue
        for c in pk:
            if c.get("source"):   # registry crates; the workspace's own crate has no source and is own code
                add("cargo:" + c["name"], declared_in=lock, version=str(c.get("version", "")), scope="shipped")

    store = "ab_cloud-terminal-store/store.json"
    if store in files:
        try:
            lt = load(root, store).get("linux_tools") or {}
        except Exception:
            lt = {}
        for name, spec in sorted((lt.get("files") or {}).items()):
            add(f"fetched:{lt.get('repo', '?')}:{spec.get('path', name)}", declared_in=store,
                version=str(lt.get("ref", ""))[:12], scope="shipped")

    pkgs, internal = [], set()
    for f in files:
        if os.path.basename(f) == "package.json" and "/node_modules/" not in f:
            try:
                d = json.load(open(os.path.join(root, f)))
            except Exception:
                continue
            pkgs.append((f, d))
            if d.get("name"):
                internal.add(d["name"])
    for f, d in pkgs:
        for k in ("dependencies", "devDependencies", "peerDependencies", "optionalDependencies"):
            for n, spec in (d.get(k) or {}).items():
                if n in internal or str(spec).startswith(("workspace:", "file:", "link:", "portal:")):
                    continue
                add("npm:" + n, declared_in=f, version=str(spec),
                    scope="dev" if k == "devDependencies" else "shipped")
    return cur, ups, subj


# ── resolution (refresh only) ────────────────────────────────────────────────

def fetch(url, timeout=20):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read()
    except Exception:
        return None


def spdx(name):
    n = (name or "").strip()
    return SPDX.get(n.lower(), n) if n else ""


def pom_licences(repos, g, a, v, depth=0):
    for repo in repos:
        b = fetch(f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
        if not b:
            continue
        try:
            x = ET.fromstring(b)
        except ET.ParseError:
            return []
        ns = {"m": x.tag[1:].split("}")[0]} if x.tag.startswith("{") else {"m": ""}
        q = (lambda p: x.findall(p.replace("m:", "m:" if ns["m"] else ""), ns)) if ns["m"] else (
            lambda p: x.findall(p.replace("m:", "")))
        names = [e.text for e in q("./m:licenses/m:license/m:name") if e.text]
        if names:
            return [spdx(n) for n in names]
        par = q("./m:parent")
        if par and depth < 2:
            pe = par[0]
            pg = pe.find("m:groupId", ns) if ns["m"] else pe.find("groupId")
            pa = pe.find("m:artifactId", ns) if ns["m"] else pe.find("artifactId")
            pv = pe.find("m:version", ns) if ns["m"] else pe.find("version")
            if pg is not None and pa is not None and pv is not None:
                return pom_licences(repos, pg.text, pa.text, pv.text, depth + 1)
        return []
    return None  # not found in any repo


def latest(repos, g, a):
    for repo in repos:
        b = fetch(f"{repo}/{g.replace('.', '/')}/{a}/maven-metadata.xml")
        if b:
            m = re.search(rb"<release>([^<]+)</release>", b) or re.search(rb"<latest>([^<]+)</latest>", b)
            if m:
                return m.group(1).decode()
    return ""


def resolve_maven(cur, key, e):
    _, g, a = key.split(":", 2)
    rules = cur.get("maven_group_licences", {})
    pref = max((p for p in rules if g == p or g.startswith(p + ".") or f"{g}:{a}" == p), key=len, default=None)
    if pref:
        r = rules[pref]
        return {"licence": r["licence"], "licence_source": f"curated.maven_group_licences[{pref}]",
                **({"note": r["note"]} if r.get("note") else {})}
    v = e.get("version", "")
    if not re.match(r"^[0-9][A-Za-z0-9.\-+]*$", v):
        lv = latest(cur["maven_repos"], g, a)
        if not lv:
            return {"licence": "NOASSERTION", "licence_source": f"version '{v}' not resolvable without Gradle and no maven-metadata.xml found"}
        why = f"POM of latest release {lv} (declared version '{v or 'from BOM'}' needs Gradle to resolve)"
        v = lv
    else:
        why = f"POM {v}"
    lic = pom_licences(cur["maven_repos"], g, a, v)
    if lic is None:
        return {"licence": "NOASSERTION", "licence_source": f"no POM found for {g}:{a}:{v} in curated.maven_repos"}
    if not lic:
        return {"licence": "NOASSERTION", "licence_source": why + " declares no <licenses>"}
    return {"licence": " AND ".join(dict.fromkeys(lic)), "licence_source": why}


def resolve_npm(key, e):
    name = key[4:]
    b = fetch("https://registry.npmjs.org/" + name.replace("/", "%2F") + "/latest")
    if not b:
        return {"licence": "NOASSERTION", "licence_source": "registry.npmjs.org: not found (private or unpublished)"}
    try:
        d = json.loads(b)
    except ValueError:
        return {"licence": "NOASSERTION", "licence_source": "registry.npmjs.org: unparseable"}
    lic = d.get("license")
    if isinstance(lic, dict):
        lic = lic.get("type")
    return {"licence": lic or "NOASSERTION",
            "licence_source": f"registry.npmjs.org latest ({d.get('version', '?')})"}


def refresh(root, offline):
    cur, ups, subj = discover(root)
    dirs = cur.get("directories", {})
    up_by_path = {m["path"]: m for m in ups}
    old = {}
    p = os.path.join(root, "licenses/inventory.json")
    if os.path.exists(p):
        old = json.load(open(p)).get("entries", {})

    def one(item):
        k, e = item
        kind = k.split(":", 1)[0]
        out = {"kind": kind, **e}
        if kind in ("dir", "vendored"):
            d = dirs.get(k.split(":", 1)[1])
            out.update({"licence": d["licence"], "licence_source": "licenses/curated.json"} if d else
                       {"licence": "NOASSERTION", "licence_source": "MISSING from licenses/curated.json::directories"})
        elif kind == "asset":
            path = k.split(":", 1)[1]
            a = cur.get("assets", {}).get(path)
            if a:
                out.update(a)
                out.setdefault("licence_source", "licenses/curated.json::assets")
            else:
                dp = deepest(list(dirs) + list(up_by_path), path)
                lic = (up_by_path[dp]["licence"] if dp in up_by_path else dirs[dp]["licence"]) if dp else "NOASSERTION"
                out.update({"licence": lic, "licence_source": f"inherits {dp}" if dp else "no containing entry"})
        elif kind == "fetched":
            lic = (load(root, "ab_cloud-terminal-store/store.json").get("linux_tools") or {}).get("licence", {})
            out.update({"licence": lic.get("spdx") or "NOASSERTION",
                        "licence_source": "ab_cloud-terminal-store/store.json::linux_tools.licence"
                                          + (f" (holder {lic['holder']})" if lic.get("holder") else "")})
        elif kind == "cargo":
            c = cur.get("cargo_licences", {}).get(k.split(":", 1)[1])
            out.update({"licence": c["licence"], "licence_source": "licenses/curated.json::cargo_licences (the SPDX expression the crate declares, read with cargo metadata)"}
                       if c else {"licence": "NOASSERTION", "licence_source": "MISSING from licenses/curated.json::cargo_licences"})
        elif offline:
            prev = old.get(k, {})
            out.update({"licence": prev.get("licence", "NOASSERTION"),
                        "licence_source": prev.get("licence_source", "offline refresh: not resolved")})
            if kind == "maven" and not prev:
                rules = cur.get("maven_group_licences", {})
                g = k.split(":")[1]
                pref = max((x for x in rules if g == x or g.startswith(x + ".")), key=len, default=None)
                if pref:
                    out.update({"licence": rules[pref]["licence"],
                                "licence_source": f"curated.maven_group_licences[{pref}]"})
        elif kind == "maven":
            out.update(resolve_maven(cur, k, e))
        elif kind == "npm":
            out.update(resolve_npm(k, e))
        if kind in ("maven", "npm"):
            out["transitive"] = "not resolved (needs a Gradle/npm resolution; see script docstring)"
        elif kind == "cargo":
            out["transitive"] = "resolved: Cargo.lock lists the whole closure and every crate in it is a subject"
        dp = deepest(list(up_by_path), out["declared_in"][0]) if out.get("declared_in") else None
        if dp:
            out["within_upstream_module"] = up_by_path[dp]["id"]
        return k, out

    with cf.ThreadPoolExecutor(16) as ex:
        entries = dict(sorted(ex.map(one, subj.items())))
    summary = {}
    for k, e in entries.items():
        summary.setdefault(e["kind"], {}).setdefault(e["licence"], 0)
        summary[e["kind"]][e["licence"]] += 1
    inv = {
        "_doc": "Generated by 1_cicd/src/scripts/cloud-android-licence-inventory.py refresh from the tree, "
                "licenses/curated.json and licenses/upstreams.json. `check` (CI: licence-guard.yml) fails when "
                "the tree has a subject with no entry here. Transitive dependencies are not resolved. Not legal advice.",
        "summary": {k: dict(sorted(v.items(), key=lambda x: -x[1])) for k, v in sorted(summary.items())},
        "upstream_modules": {m["id"]: {"path": m["path"], "upstream": m["upstream"], "licence": m["licence"]}
                             for m in ups},
        "linkage": linkage(root, dirs, up_by_path),
        "entries": entries,
    }
    with open(p, "w") as h:
        json.dump(inv, h, indent=1, sort_keys=False, ensure_ascii=False)
        h.write("\n")
    print(f"inventory: {len(entries)} entries", file=sys.stderr)
    return 0


def linkage(root, dirs, up_by_path):
    """Which shared modules (ab_cloud-libs-shared/...) each app's build.json::modules
    compiles in: an own library linked into a GPL app ships under the GPL in that APK."""
    out = {}
    for top in sorted(os.listdir(root)):
        bj = os.path.join(root, top, "build.json")
        if not os.path.isfile(bj):
            continue
        try:
            mods = json.load(open(bj)).get("modules", {})
        except Exception:
            continue
        if not isinstance(mods, dict):
            continue
        shared = sorted({os.path.normpath(os.path.join(top, s["dir"])) for n, s in mods.items()
                         if isinstance(s, dict) and s.get("dir") and not n.startswith("_")})
        if not shared:
            continue
        dp = deepest(list(up_by_path), top)
        app_lic = up_by_path[dp]["licence"] if dp else dirs.get(top, {}).get("licence", "NOASSERTION")
        out[top] = {"app_licence": app_lic, "links_shared_modules": shared}
    return out


def check(root):
    _, _, subj = discover(root)
    p = os.path.join(root, "licenses/inventory.json")
    inv = json.load(open(p)).get("entries", {}) if os.path.exists(p) else {}
    missing = sorted(k for k in subj if k not in inv)
    bad = sorted(k for k, e in inv.items() if e.get("kind") in ("dir", "vendored", "cargo") and
                 e.get("licence") == "NOASSERTION" and k in subj)
    for k in missing:
        print(f"MISSING  {k}  (declared in {subj[k]['declared_in'][0]})")
    for k in bad:
        print(f"NO-LICENCE  {k}  (licenses/curated.json has no licence for it)")
    if missing or bad:
        print(f"\n{len(missing)} subject(s) without an inventory entry, {len(bad)} directory(ies) without a licence.\n"
              "Add the directory to licenses/curated.json (or the upstream to licenses/upstreams.json), then run\n"
              "  python3 1_cicd/src/scripts/cloud-android-licence-inventory.py refresh .\n"
              "and commit licenses/inventory.json.", file=sys.stderr)
        return 1
    print(f"licence inventory: {len(subj)} subjects, all covered")
    return 0


def main(argv):
    if len(argv) < 3 or argv[1] not in ("refresh", "check"):
        print(__doc__, file=sys.stderr)
        return 2
    root = os.path.abspath(argv[2])
    return refresh(root, "--offline" in argv) if argv[1] == "refresh" else check(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
