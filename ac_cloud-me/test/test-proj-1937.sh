#!/usr/bin/env bash
# Tester (#1937 points 1A, 1B, 2, 3, 4, 6): declarations and sources, each rule proven red by a mutation of a copy.
#   B1 banners   every Proj Home banner is a BUNDLED asset (data/web/banners/<file>, a real GIF/WebP) declared in me-proj-emoji.json; ProjHome reads the asset first and
#                draws a titled panel when nothing loads (never an empty block)
#   I1 icons     every Proj leaf declares a tile_icon: a bundled drawable, none shared, none the globe (ic_world) or link tile, no two with the same artwork; the Home tile
#                label drops the emoji and draws the declared icon
#   V1 nav       every Proj page (top chips and the sub chips of each area) is `emoji_only` (no glyph: no grey link icon, no default globe), and a declared
#                `separator_before` follows Home (the fleet's convention, aa_cloud-superapp/build.json); the chip renderer honours emoji_only
#   L1 lnktree   a remote page that cannot load shows a visible error with Retry (main-frame error, HTTP failure, blank page), brings the chrome back, and is cached first
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" "$APP/.." <<'PY'
import json, os, re, sys, hashlib
app, mono = sys.argv[1], sys.argv[2]
J = lambda p: json.load(open(os.path.join(app, p), encoding="utf-8"))
T = lambda p: open(os.path.join(app, p), encoding="utf-8").read() if os.path.exists(os.path.join(app, p)) else ""
ui = J("build.json")["ui"]; proj = next(s for s in ui["sections"] if s["id"] == "proj")
tab = J("data/web/me-proj-emoji.json")
def flat(ps):
    for p in ps or []:
        yield p; yield from flat(p.get("pages"))
# B1
for g in ("pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42", "pj-repos"):
    b = tab.get("banners", {}).get(g, {}); a = b.get("asset", "")
    f = os.path.join(app, "data/web", a)
    if not a.startswith("banners/") or not os.path.isfile(f): print("B1\t%s banner has no bundled asset (%r)" % (g, a)); continue
    h = open(f, "rb").read(12)
    if os.path.getsize(f) < 10000 or not (h[:4] == b"GIF8" or (h[:4] == b"RIFF" and h[8:12] == b"WEBP")): print("B1\t%s banner asset %s is not a real image" % (g, a))
home = T("app/src/main/java/com/diegonmarcos/cloudme/ProjHome.kt")
for need in ("fun bytes(entry", "entry.asset", "ctx.assets.open(it)", "ProjTileIcons.plainLabel", "if (drawable == null)", "Text(title"):
    if need not in home: print("B1\tProjHome.kt lacks %s (asset first, panel when nothing loads)" % need)
if home.index("entry.asset") > home.index("fetch(entry.url)") if ("entry.asset" in home and "fetch(entry.url)" in home) else True: print("B1\tthe network is not the fallback of the bundled asset")
# I1
seen_name, seen_art = {}, {}
leaves = [p for g in proj["pages"] for p in g.get("pages", [])]
if len(leaves) != 26: print("I1\tProj has %d leaves, wanted 26" % len(leaves))
for l in leaves:
    n = l.get("tile_icon", "")
    if not n or n in ("ic_world", "ic_link_tile") or not n.startswith("ic_pj_"): print("I1\t%s tile_icon %r is missing or shared" % (l["id"], n)); continue
    f = os.path.join(app, "app/src/main/res/drawable", n + ".xml")
    if not os.path.isfile(f): print("I1\t%s drawable %s is not bundled" % (l["id"], n)); continue
    if n in seen_name: print("I1\t%s and %s share %s" % (l["id"], seen_name[n], n))
    seen_name[n] = l["id"]
    art = hashlib.sha1(re.sub(r"\s+", " ", re.sub(r"<!--.*?-->", "", open(f, encoding="utf-8").read(), flags=re.S)).encode()).hexdigest()
    if art in seen_art: print("I1\t%s and %s have the same artwork" % (l["id"], seen_art[art]))
    seen_art[art] = l["id"]
for need in ("ProjTileIcons.declared[leaf.id]", "painterResource(res)"):
    if need not in home: print("I1\tProjHome.kt does not draw the declared tile icon (%s)" % need)
if "getIdentifier(leaf.icon" in home or "ic_world" in home: print("I1\tProjHome.kt falls back to the shared icon")
for p in flat(proj["pages"]):
    if re.match(r"^[^\w]", p["label"]) is None: print("I1\t%s label has no emoji for the chips" % p["id"])
# V1
for p in flat(proj["pages"]):
    if p.get("emoji_only") is not True: print("V1\t%s is not emoji_only (it would draw a glyph)" % p["id"])
ids = [p["id"] for p in proj["pages"]]
if ids[0] != "pj-home" or not proj["pages"][1].get("separator_before") or any(p.get("separator_before") for p in proj["pages"][2:]) or proj["pages"][0].get("separator_before"):
    print("V1\tthe separator is not declared after Home (separator_before on the page that follows Home)")
nd = T("../ab_cloud-libs-shared/libs/bottomnav/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/NavDecl.kt")
row = T("../ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt")
if not os.path.exists(os.path.join(app, "../ab_cloud-libs-shared")): nd = open(os.path.join(mono, "ab_cloud-libs-shared/libs/bottomnav/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/NavDecl.kt"), encoding="utf-8").read(); row = open(os.path.join(mono, "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt"), encoding="utf-8").read()
if 'o["emoji_only"] == true' not in nd or "val emojiOnly" not in nd: print("V1\tNavDecl does not read emoji_only")
if "if (!page.emojiOnly) Icon(" not in row or "named || page.emojiOnly" not in row: print("V1\tthe second row still draws a glyph for an emoji_only page, or hides its name when compact")
# L1
w = T("app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt")
for need in ("override fun onReceivedHttpError", "WebLoadErrorPanel(it, retry", "WebLoadState.PROBE_JS", "loadError = WebLoadState.describe(error?.description", "collapse?.restore() }   // the top bar", "me:web:retry", "LnktreeCache.intercept"):
    if need not in w: print("L1\tWebPage.kt lacks %s" % need)
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "B1 I1 V1 L1 banners, icons, strips and the failed-page error hold"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/m"; mkdir -p "$T/m/app/src/main/java/com/diegonmarcos/cloudme" "$T/m/app/src/main/res" "$T/m/data"; cp "$APP/build.json" "$T/m/"; cp -r "$APP/data/web" "$T/m/data/web"
  cp "$APP/app/src/main/java/com/diegonmarcos/cloudme/"{ProjHome,WebPage}.kt "$T/m/app/src/main/java/com/diegonmarcos/cloudme/"; cp -r "$APP/app/src/main/res/drawable" "$T/m/app/src/main/res/drawable"
  ln -sfn "$APP/../ab_cloud-libs-shared" "$T/ab_cloud-libs-shared"
  python3 -c "$2" "$T/m"; r=$(check "$T/m"); if echo "$r" | grep -q "^$3"; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
BJ='import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p));S={s["id"]:s for s in d["ui"]["sections"]};'
WR='json.dump(d,open(p,"w"))'
mut "a banner asset is deleted" 'import os,sys;os.remove(sys.argv[1]+"/data/web/banners/cloud.gif")' B1
mut "a banner asset is an HTML error page" 'import sys;open(sys.argv[1]+"/data/web/banners/circus.gif","w").write("<html>"+"x"*20000)' B1
mut "the table drops a bundled asset" 'import json,sys;p=sys.argv[1]+"/data/web/me-proj-emoji.json";d=json.load(open(p));d["banners"]["pj-cg"].pop("asset");json.dump(d,open(p,"w"))' B1
mut "ProjHome draws nothing when the banner fails" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/ProjHome.kt";s=open(p).read().replace("if (drawable == null)","if (false)");open(p,"w").write(s)' B1
mut "a project falls back to the globe" "${BJ}S['proj']['pages'][1]['pages'][0]['tile_icon']='ic_world';$WR" I1
mut "two projects share an icon" "${BJ}S['proj']['pages'][1]['pages'][1]['tile_icon']=S['proj']['pages'][1]['pages'][0]['tile_icon'];$WR" I1
mut "two projects have the same artwork" 'import shutil,sys;d=sys.argv[1]+"/app/src/main/res/drawable/";shutil.copy(d+"ic_pj_cg_omniverse.xml",d+"ic_pj_cg_zirkusverse.xml")' I1
mut "a project icon is not bundled" 'import os,sys;os.remove(sys.argv[1]+"/app/src/main/res/drawable/ic_pj_capital_cb.xml")' I1
mut "a project declares no icon" "${BJ}S['proj']['pages'][2]['pages'][0].pop('tile_icon');$WR" I1
mut "ProjHome falls back to the declared page icon" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/ProjHome.kt";s=open(p).read().replace("ProjTileIcons.declared[leaf.id]","leaf.icon");open(p,"w").write(s)' I1
mut "the tile label keeps its emoji" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/ProjHome.kt";s=open(p).read().replace("ProjTileIcons.plainLabel","String");open(p,"w").write(s)' B1
mut "a top chip draws its glyph again" "${BJ}S['proj']['pages'][3].pop('emoji_only');$WR" V1
mut "a sub chip draws its glyph again" "${BJ}S['proj']['pages'][1]['pages'][0].pop('emoji_only');$WR" V1
mut "the separator after Home is gone" "${BJ}S['proj']['pages'][1].pop('separator_before');$WR" V1
mut "the separator sits in the wrong place" "${BJ}S['proj']['pages'][1].pop('separator_before');S['proj']['pages'][2]['separator_before']=True;$WR" V1
mut "the failed page panel is not drawn" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt";s=open(p).read().replace("WebLoadErrorPanel(it, retry","Unit.let { retry");open(p,"w").write(s)' L1
mut "an HTTP failure is not handled" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt";s=open(p).read().replace("override fun onReceivedHttpError","fun onReceivedHttpErrorZ");open(p,"w").write(s)' L1
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
