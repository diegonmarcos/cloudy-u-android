#!/usr/bin/env python3
"""#1422 Derive ac_cloud-browser/data/favourites_catalog.json: the Favourites filters and every seeded link.

Nothing is typed here. build.json::ui.browser.favourites.filters names the chips (label + `from`, in order); each `from`
reads one declaration:
  github_pages     data/github_pages.json (itself derived by gen-github-pages.py); sub-folder = its `repo`, the front
                   project's source folder the site is built from
  services_public  aa_cloud-superapp/data/services_public.json (public_url); sub-folder = the category the SuperApp's
  services_private aa_cloud-superapp/data/services_private.json (private_dns, http/https + port only)   Cloud dashboard
                   (aa_cloud-superapp/data/cloud_services.json, Infra Apps + User Apps subgroups) files the container
                   under, else "Other"
  webserver_pages  no links: the chip opens the cloud-webserver app's Pages tab, http://<serving endpoint>/__app__/tab/pages
                   (ac_cloud-c3-webserver/build.json::serving, the endpoint the app itself owns at runtime.port; the route
                   must exist in server/src/app.rs)
  phone_section    aa_cloud-superapp/build.json::ui.phone_sections whose title starts with the filter's `section` (the
                   Store's Phone > Apps taxonomy); sub-folder = each folder of those sections (ui.phone_folders), links =
                   the site of every app the folder declares (pkg:<id> -> the package's reverse-domain host)

Rows are {name, url, group} with group "<filter label>/<sub-folder>"; app/build.gradle bakes them as the Fav seed.

  gen-favourites-catalog.py           regenerate
  gen-favourites-catalog.py --check   write nothing; exit 1 when the committed file differs (test-browser-favourites-catalog.sh)
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.normpath(os.path.join(HERE, ".."))
ROOT = os.path.normpath(os.path.join(APP, ".."))
OUT = os.path.join(APP, "data", "favourites_catalog.json")
WEBSERVER = "ac_cloud-c3-webserver"


def load(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return json.load(f)


def cloud_categories(cloud_services):
    """container name and mesh host -> the SuperApp Cloud dashboard subgroup (Infra Apps + User Apps hold each once)."""
    by = {}
    for g in cloud_services["groups"]:
        if g.get("id") not in ("infra", "user"):
            continue
        for sg in g["subgroups"]:
            for c in sg["containers"]:
                by.setdefault(c["name"], sg["label"])
                by.setdefault(c["url"].split(":")[0], sg["label"])
    return by


def service_rows(rows, cats, url_field, scheme, protocols=None, require_port=False):
    out = []
    for r in rows:
        raw = (r.get(url_field) or "").strip()
        if not raw or (protocols and r.get("protocol") not in protocols) or (require_port and r.get("port") is None):
            continue
        cat = cats.get(r["name"]) or cats.get(r.get("service") or "") or cats.get((r.get("private_dns") or "").split(":")[0]) or "Other"
        out.append((r["name"], raw if "://" in raw else scheme + "://" + raw, cat))
    return out


def pages_rows(pages):
    out = []
    for p in pages:
        # a row from before `repo` existed: the site's own path segment names its project
        repo = p.get("repo") or p["url"].split("://", 1)[1].rstrip("/").split("/")[-1]
        out.append((p["name"], p["url"], repo))
    return out


def site_of(pkg):
    """com.ubercab -> https://ubercab.com (the reverse-domain host the package is named after)."""
    parts = [x for x in pkg.split(".") if x]
    if len(parts) < 2:
        return None
    return "https://%s.%s/" % (parts[1].lower(), parts[0].lower())


def phone_rows(superapp_ui, section):
    prefixes = [s["prefix"] for s in superapp_ui["phone_sections"] if s["title"].startswith(section)]
    if not prefixes:
        raise ValueError("no phone_sections title starts with %r" % section)
    out = []
    for f in superapp_ui["phone_folders"]:
        label = f["label"]
        if not label or label[0] not in prefixes:
            continue
        sub, seen = label[1:].strip(), set()
        for kw in f.get("match_keywords", []):
            if not kw.startswith("pkg:"):
                continue
            url = site_of(kw[4:])
            if url and url not in seen:
                seen.add(url)
                out.append((url.split("://")[1].rstrip("/"), url, sub))
    return out


def webserver_pages(ws_build, app_rs):
    eps = [e for e in ws_build["serving"]["endpoints"] if e["owner"] == WEBSERVER and e["port_from"] == "runtime.port"]
    if len(eps) != 1:
        raise ValueError("serving must name exactly one endpoint of %s at runtime.port" % WEBSERVER)
    route = "/__app__/tab/pages"
    if ('path: "%s"' % route) not in app_rs:
        raise ValueError("the webserver no longer serves %s" % route)
    return "http://%s%s" % (eps[0]["name"], route)


def derive(bj, read):
    fav = bj["ui"]["browser"]["favourites"]
    sa = read("aa_cloud-superapp/build.json")["ui"]
    cats = cloud_categories(read("aa_cloud-superapp/data/cloud_services.json"))
    filters, items, urls = [], [], set()
    for flt in fav["filters"]:
        kind, label = flt["from"], flt["label"]
        entry = {"id": flt["id"], "label": label}
        if kind == "github_pages":
            rows = pages_rows(read("ac_cloud-browser/data/github_pages.json"))
        elif kind == "services_public":
            rows = service_rows(read("aa_cloud-superapp/data/services_public.json"), cats, "public_url", "https")
        elif kind == "services_private":
            rows = service_rows(read("aa_cloud-superapp/data/services_private.json"), cats, "private_dns", "http", ["http", "https"], True)
        elif kind == "webserver_pages":
            entry["open"] = webserver_pages(read(WEBSERVER + "/build.json"), read(WEBSERVER + "/server/src/app.rs"))
            rows = []
        elif kind == "phone_section":
            rows = phone_rows(sa, flt["section"])
        else:
            raise ValueError("unknown favourites filter source %r" % kind)
        filters.append(entry)
        for name, url, sub in sorted(rows, key=lambda r: (r[2].lower(), r[0].lower())):
            if url in urls:
                continue
            urls.add(url)
            items.append({"name": name, "url": url, "group": "%s/%s" % (label, sub)})
    return {"_generated": "DO NOT EDIT: tools/gen-favourites-catalog.py derives this file from the registries (#1422)",
            "filters": filters, "items": items}


def reader(rel):
    if rel.endswith(".rs"):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            return f.read()
    return load(rel)


def render():
    return json.dumps(derive(load("ac_cloud-browser/build.json"), reader), indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv[1:]:
        cur = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if cur != text:
            sys.stderr.write("favourites_catalog.json is stale: run tools/gen-favourites-catalog.py\n")
            sys.exit(1)
        sys.exit(0)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote %s" % OUT)
