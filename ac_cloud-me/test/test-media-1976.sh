#!/usr/bin/env bash
# Tester (#1976 correction 2): Pers > Profiles > Media holds EVERY mySocials page as ONE flat strip in the site's own nav order
# Personal | Shelf | Vinyl | Bar | Menu | Theater | Story | ig0 | ig1 | ig2 | face0 | Pinterest | YouTube | Tidal | LinkedIn | Orkut | ICQ | DMs, each tab embedding
# https://diegonmarcos.github.io/mySocials/<page>.html (Personal the site root). The strip is generated from the site's nav declaration (data/regen-media-1976.py); this guard fails when
# a site page has no Media tab. Health and DD stay Pers' own native pages. Each check is proven red by a mutation of a copy.
# Usage: test-media-1976.sh   |   test-media-1976.sh --check DIR  (exit 1 when DIR is red)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 -I - "$1" <<'PY'
import json, os, subprocess, sys
app = sys.argv[1]; J = lambda p: json.load(open(os.path.join(app, p), encoding="utf-8"))
def E(c, m): print("%s\t%s" % (c, m))
BASE = "https://diegonmarcos.github.io/mySocials/"
ORDER = [("personal", "Personal", ""), ("shelf", "Shelf", "shelf.html"), ("vinyl", "Vinyl", "vinyl.html"), ("bar", "Bar", "bar-cellar.html"), ("menu", "Menu", "menu.html"), ("theater", "Theater", "theater.html"),
         ("story", "Story", "social.html"), ("ig0", "ig0", "instagram-diegocnmarcos_.html"), ("ig1", "ig1", "instagram-diegocmarcos_.html"), ("ig2", "ig2", "instagram-diegonmarcos.html"),
         ("face0", "face0", "facebook-diegonmarcos.html"), ("pinterest", "Pinterest", "pinterest.html"), ("youtube", "YouTube", "youtube.html"), ("tidal", "Tidal", "tidal.html"),
         ("linkedin", "LinkedIn", "linkedin.html"), ("orkut", "Orkut", "orkut.html"), ("icq", "ICQ", "icq.html"), ("dms", "DMs", "dms.html")]
pers = [s for s in J("build.json")["ui"]["sections"] if s["id"] == "pers"][0]; media = pers["pages"][0]
if media["id"] != "pers-profiles" or media["label"] != "Media": E("M0", "the first Pers page is %r %r, want pers-profiles Media" % (media["id"], media["label"]))
tabs = media.get("pages", [])
if [(t["id"], t["label"]) for t in tabs] != [(i, l) for i, l, _ in ORDER]: E("M1", "Media strip is %r" % [(t["id"], t["label"]) for t in tabs])
if any(t.get("pages") for t in tabs) or media.get("groups"): E("M2", "Media is not one flat strip (a tab holds pages, or Media has groups)")
if [g["pages"][:1] + g["pages"][1:4] for g in pers.get("groups", [])][:1] != [["pers-profiles", "health", "astro", "dd"]]: E("M3", "Pers rows changed")
for i, l, page in ORDER:
    try: b = J("data/ui/pers/pers-profiles/%s.json" % i)
    except Exception: E("M4", "tab %s has no page file" % i); continue
    if len(b) != 1 or b[0].get("kind") != "fragment" or b[0].get("id") != "web" or b[0].get("url") != BASE + page: E("M4", "%s does not embed %s (%r)" % (i, BASE + page, b[0].get("url") if b else None))
# G: every page of the site has a Media tab (the snapshot lists the site's pages; --check against the clone when it is there)
nav = J("data/media/mysocials-nav.json"); have = {t["page"] for t in [nav["home"]] + nav["tabs"]}
for f in nav["site_pages"]:
    if f not in have: E("G1", "the mySocials page %s has no Media tab" % f)
urls = set()
for t in tabs:
    try: urls.add(J("data/ui/pers/pers-profiles/%s.json" % t["id"])[0].get("url"))
    except Exception: pass
for f in nav["site_pages"]:
    if BASE + f not in urls: E("G2", "no Media tab embeds %s%s" % (BASE, f))
for n in ("health", "dd"):
    if n in [t["id"] for t in tabs] or n not in [p["id"] for p in pers["pages"]]: E("G3", "%s must stay Pers' own page, outside Media" % n)
r = subprocess.run([sys.executable, os.path.join(app, "data/regen-media-1976.py"), "--check"], capture_output=True, text=True, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
if r.returncode != 0: E("G4", (r.stderr or r.stdout).strip()[:200])
links = {e["path"][1]: e["url"] for e in J("data/web/me-links.json")["entries"] if e.get("section") == "pers" and e.get("path", [""])[0] == "pers-profiles"}
if links != {i: BASE + p for i, _, p in ORDER}: E("G5", "me-links.json does not declare every Media tab url")
PY
}
if [ "${1:-}" = --check ]; then o=$(check "${2:-$APP}"); [ -z "$o" ] && exit 0; echo "$o"; exit 1; fi
out=$(check "$APP"); if [ -z "$out" ]; then ok "M/G: every mySocials page is a Media tab in the site's order, flat, generated, Health and DD stay native"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/data/web" "$T/app/data/media"; cp "$APP/build.json" "$T/app/"; cp -r "$APP/data/ui" "$T/app/data/"; cp "$APP/data/media/mysocials-nav.json" "$T/app/data/media/"; cp "$APP/data/web/me-links.json" "$T/app/data/web/"; cp "$APP/data/regen-media-1976.py" "$T/app/data/"
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
BJ='import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p,encoding="utf-8"));P=[s for s in d["ui"]["sections"] if s["id"]=="pers"][0];M=P["pages"][0];'
WB='json.dump(d,open(p,"w",encoding="utf-8"),ensure_ascii=False)'
NJ='import json,sys;p=sys.argv[1]+"/data/media/mysocials-nav.json";n=json.load(open(p,encoding="utf-8"));'
WN='json.dump(n,open(p,"w",encoding="utf-8"),ensure_ascii=False)'
mut "the DMs tab is dropped" "${BJ}M['pages'].pop();$WB"
mut "the ICQ page file is gone" 'import os,sys;os.remove(sys.argv[1]+"/data/ui/pers/pers-profiles/icq.json")'
mut "the Story tab is dropped" "${BJ}M['pages']=[t for t in M['pages'] if t['id']!='story'];$WB"
mut "the strip is reordered (Shelf first)" "${BJ}M['pages'].insert(0,M['pages'].pop(1));$WB"
mut "a tab is nested again (ig1 under ig0)" "${BJ}m=M['pages'];m[7]['pages']=[m.pop(8)];$WB"
mut "Media gets groups" "${BJ}M['groups']=[{'id':'x','label':'x','pages':[]}];$WB"
mut "a tab embeds the wrong page (Vinyl shows Shelf)" 'import json,sys;p=sys.argv[1]+"/data/ui/pers/pers-profiles/vinyl.json";d=json.load(open(p));d[0]["url"]=d[0]["url"].replace("vinyl","shelf");json.dump(d,open(p,"w"))'
mut "the site gets a new page that has no Media tab" "${NJ}n['site_pages'].append('podcast.html');$WN"
mut "Health moves into Media" "${BJ}M['pages'].append(P['pages'].pop(1));$WB"
mut "me-links.json forgets a Media tab" 'import json,sys;p=sys.argv[1]+"/data/web/me-links.json";d=json.load(open(p));d["entries"]=[e for e in d["entries"] if e.get("path",[""])[-1]!="dms"];json.dump(d,open(p,"w"))'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
