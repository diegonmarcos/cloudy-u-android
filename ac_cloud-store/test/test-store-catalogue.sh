#!/usr/bin/env bash
# #1791 Store > Search > Phone: ONE central app catalogue (libs:appstore assets/store-catalogue.json), declared in build.json::store_catalogue.
#  1. Schema: one row per package, category / licence / play_integrity / game_dimension / source / icon, each inside the declared values.
#  2. Parity: every package and every field value of the three old per-page lists (snapshot in libs:appstore src/test/resources) is in it.
#  3. Icons: every row has a well-formed https icon URL (the live HEAD check is .github/workflows/store-catalogue-icons.yml, dispatchable).
#  4. Declared: build.json::store_catalogue names the asset, the baseline lists it as a tier-4 seed, the page draws the five chip rows with stable tags.
#  5. Cloud Store's Search > Games page reads games-catalogue.json (kept; it moved from Media Center, #1895).
# Then a planted defect (a dropped package, a blank icon, a bad enum) must turn it red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
exec python3 - "$ROOT" <<'PY'
import json, os, re, sys, copy
root = sys.argv[1]
def rd(p): return open(os.path.join(root, p), encoding="utf-8").read()
ASSET = "ab_cloud-libs-shared/libs/appstore/src/main/assets/store-catalogue.json"
SNAP = "ab_cloud-libs-shared/libs/appstore/src/test/resources/store-catalogue-old-lists.json"
P = F = 0
def ok(m):
    global P; P += 1; print("  PASS: " + m)
def bad(m):
    global F; F += 1; print("  FAIL: " + m)
def check(cat, old):
    errs = []
    rows = {}
    for a in cat["apps"]:
        if a["pkg"] in rows: errs.append("duplicate " + a["pkg"])
        rows[a["pkg"]] = a
    enums = {"category": {"tools", "services", "others", "games"}, "licence": {"open", "closed"}, "play_integrity": {"none", "basic", "mid", "full"},
             "game_dimension": {"2d", "3d"}, "source": {"fdroid", "aurora", "playstore", "other"}}
    for p, a in rows.items():
        for k, vs in enums.items():
            if k == "game_dimension":
                if (a["category"] == "games") != (k in a): errs.append("%s: game_dimension only on games" % p)
                if k in a and a[k] not in vs: errs.append("%s: bad %s" % (p, k))
            elif a.get(k) not in vs: errs.append("%s: bad %s=%r" % (p, k, a.get(k)))
        if not re.match(r"^https://[^/ ]+\.[^/ ]+/\S+$", a.get("icon", "")): errs.append("%s: icon not a well-formed https URL" % p)
        if not a.get("name", "").strip(): errs.append("%s: blank name" % p)
    ren = {"recommendations": {"category": "subcategory", "source": "source_listed", "licence": "licence_spdx"},
           "games": {"category": "subcategory", "source": "source_listed", "dimension": "game_dimension"},
           "integrity": {"tier": "integrity_tier"}}
    for lst in ("recommendations", "games", "integrity"):
        for o in old[lst]:
            x = rows.get(o["pkg"])
            if x is None: errs.append("%s: %s dropped from the catalogue" % (lst, o["pkg"])); continue
            for k, v in o.items():
                if k == "open_source":
                    if x.get("licence") != ("open" if v else "closed"): errs.append("%s: open_source lost" % o["pkg"])
                    continue
                n = ren[lst].get(k, k)
                if k == "name" and lst == "integrity": continue
                if x.get(n) != v: errs.append("%s: %s=%r lost (catalogue %s=%r)" % (o["pkg"], k, v, n, x.get(n)))
    return errs, rows
print("== T1-T3: schema, parity with the old lists, icons ==")
cat = json.loads(rd(ASSET)); old = json.loads(rd(SNAP))
errs, rows = check(cat, old)
if not errs: ok("%d rows: schema, every package and field value of the old lists (47 + 103 + 22), https icons" % len(rows))
else: bad("; ".join(errs[:5]))
want = {x["pkg"] for l in old.values() if isinstance(l, list) for x in l if isinstance(x, dict) and "pkg" in x}
if want == set(rows): ok("exactly the union of the old lists (%d packages), nothing invented" % len(want))
else: bad("catalogue differs from the union: %s" % sorted(want ^ set(rows))[:5])
owners = ["io.anuke.mindustry", "com.unciv.app", "org.openttd.fdroid", "com.shatteredpixel.shatteredpixeldungeon", "com.cleverraven.cataclysmdda", "sh.ppy.osulazer",
          "org.lichess.mobileV2", "net.minetest.minetest", "org.supertuxkart.stk", "org.stjr.srb2"]
if all(rows.get(p, {}).get("licence") == "open" and rows[p]["category"] == "games" for p in owners): ok("the ten open-source games of #1776 are open games")
else: bad("an open-source game of #1776 is missing or not open")
print("== T4: declared, seeded, drawn ==")
b = json.loads(rd("ac_cloud-store/build.json")).get("store_catalogue", {})
if b.get("asset") == ASSET and b.get("_doc"): ok("build.json::store_catalogue declares the asset with a _doc")
else: bad("build.json::store_catalogue missing")
if ASSET in json.loads(rd("1_cicd/src/data/runtime-data-baseline.json"))["seed_allowlist"]: ok("listed as a tier-4 seed in the runtime-data baseline")
else: bad("not in seed_allowlist")
rowsids = [f["id"] for f in cat["filters"]]
if rowsids == ["category", "licence", "play_integrity", "game_dimension", "source"] and [len(f["values"]) for f in cat["filters"]] == [4, 2, 4, 2, 4]: ok("five filter rows declared in the catalogue (4, 2, 4, 2, 4 chips after All)")
else: bad("filter rows are not the five")
page = rd("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreSearchPage.kt")
flt = rd("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StorePhoneFilter.kt")
if "StorePhoneFilter.tag(row.id, v.id)" in page and 'fun tag(row: String, value: String) = "store-search:phone-filter:$row:$value"' in flt and "ALL, cat.allLabel" in page: ok("every chip (All first) has the tag store-search:phone-filter:<row>:<value>")
else: bad("chip tags missing")
if "games-catalogue" in rd("ac_cloud-store/app/src/main/assets/games/games.js") and os.path.exists(os.path.join(root, "data/fleet/games-catalogue.json")): ok("games-catalogue.json is kept for Cloud Store's Search > Games page (moved from Cloud Media Center, #1895)")
else: bad("games-catalogue.json is gone but Cloud Store's Games page reads it")
print("== mutations ==")
def mut(name, fn):
    c = copy.deepcopy(cat); fn(c)
    e, _ = check(c, old)
    (ok if e else bad)("planted defect turns it red: " + name)
mut("a dropped package", lambda c: c["apps"].pop(5))
mut("a blank icon", lambda c: c["apps"][3].__setitem__("icon", ""))
mut("an http icon", lambda c: c["apps"][3].__setitem__("icon", "http://x.y/z.png"))
mut("a bad licence", lambda c: c["apps"][2].__setitem__("licence", "free"))
mut("a lost description", lambda c: [a for a in c["apps"] if a["pkg"] == "com.unciv.app"][0].__setitem__("desc", "x"))
mut("a dimension on a tool", lambda c: [a for a in c["apps"] if a["category"] == "tools"][0].__setitem__("game_dimension", "2d"))
print("\n%d passed, %d failed" % (P, F))
sys.exit(1 if F else 0)
PY
