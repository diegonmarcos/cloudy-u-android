#!/usr/bin/env python3
"""Store > Cloud > Apps categories, DERIVED from the SuperApp's Cloud > Apps page.

Reads aa_cloud-superapp/build.json::ui.sections[id=cloud].tile_groups (the one
declaration) and the fleet apps JSON, prints the app_categories array that
regen.sh writes into constellation-fleet.json: one category per tile group
(same title, same order), the fleet apps its tiles open as members (first tile
wins), and a group's `store_apps` (tile-less members). Every app of the `apps` group must be filed: an unfiled app fails the
build (#1704), there is no "Other".

A tile opens a fleet app by extapp:<id> (ui.external_apps[].install_package),
app://<package>, or an explicit `fleet_app` on the tile. Nothing is typed here.

usage: app-categories.py <superapp build.json> <apps.json>
"""
import json, re, sys

OTHER = {"id": "other", "label": "Other"}


def derive(bj, apps):
    by_pkg = {a["package"]: a["id"] for a in apps if a.get("package")}
    ext = {e["id"]: e.get("install_package") for e in bj["ui"].get("external_apps", [])}
    tab = [a["id"] for a in apps if a.get("group") == "apps"]

    def fleet_of(tile):
        if tile.get("fleet_app"):
            return tile["fleet_app"]
        t = tile.get("target") or ""
        if t.startswith("extapp:"):
            return by_pkg.get(ext.get(re.sub(r"#.*$", "", t[len("extapp:"):])) or "")
        if t.startswith("app://"):
            return by_pkg.get(t[len("app://"):])
        return None

    section = next(s for s in bj["ui"]["sections"] if s["id"] == "cloud")
    seen, out = set(), []
    for g in section["tile_groups"]:
        members = []
        for tile in g["tiles"]:
            # #1627 a tile flagged category_skip is a DUPLICATE shortcut (the same app also lives in another
            # row that is its real home, e.g. the 'me' tile added to Data beside the Me row). First-group-wins
            # would otherwise re-home the app's Store category onto this duplicate and empty the real category.
            if tile.get("category_skip"):
                continue
            f = fleet_of(tile)
            if f and f in tab and f not in seen:
                seen.add(f)
                members.append(f)
        # #1704 `store_apps`: fleet apps with no tile on the page that the group still files (no tile drawn).
        for f in g.get("store_apps", []):
            if f in tab and f not in seen:
                seen.add(f)
                members.append(f)
        cid = re.sub(r"[^a-z0-9]+", "-", g["title"].lower()).strip("-")
        out.append({"id": cid, "label": g["title"], "members": members})
    out = [c for c in out if c["members"]]
    rest = [i for i in tab if i not in seen]
    # #1704 no "Other": an app of the Cloud > Apps tab that no group files is a build error, not a shelf.
    if rest:
        raise SystemExit("ERROR: apps with no Store category (declare them in a tile group or its store_apps): " + ", ".join(rest))
    return out


if __name__ == "__main__":
    bj = json.load(open(sys.argv[1]))
    apps = json.loads(open(sys.argv[2]).read())
    print(json.dumps(derive(bj, apps), separators=(",", ":"), ensure_ascii=False))
