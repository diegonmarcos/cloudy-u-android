#!/usr/bin/env bash
# Tester (#1906): every social account and company shown on Pers and Prof has its real link, declared in data/web/me-links.json and
# derived from the SoT by data/regen-me-links.py. L1 each entry has a url equal to its SoT snapshot; L2 each tab entry's page file carries the same
# `link`; L3 the SoT repos (when present) agree (regen --check). Each is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" <<'PY'
import json, os, sys
app = sys.argv[1]; t = json.load(open(os.path.join(app, "data/web/me-links.json")))["entries"]
for e in t:
    if not e.get("url", "").startswith(("https://", "mailto:")): print("L1\t%s has no link" % e["id"])
    elif e["url"] != e.get("sot_url"): print("L1\t%s url %s differs from the SoT %s" % (e["id"], e["url"], e.get("sot_url")))
    if "path" in e:
        f = os.path.join(app, "data/ui", e["section"], *e["path"]) + ".json"
        if json.load(open(f))[0].get("link") != e.get("url"): print("L2\t%s page file link differs from the table" % e["id"])
K = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt")).read() if os.path.exists(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt")) else ""
S = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt")).read()
G = open(os.path.join(app, "app/build.gradle")).read()
W = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt")).read() if os.path.exists(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt")) else ""
for need_, where_, txt_ in (("WebPage(single, modifier, pageId = page.id)", "page is the bare WebPage", S), ("data/web", "me-links.json asset", G), ("?: super.shouldInterceptRequest(view, request)", "asset loader fallback", W), ("key(generation, url, pageId)", "WebView keyed by its page", W), ("MAINFRAME_ERROR", "main-frame error log", W), ("naturalWidth==0", "broken-image check", W)):
    if need_ not in txt_: print("L4\t%s: %s is not bound" % (where_, need_))
for gone_, txt_ in (("MeLinkedWeb", S + K), ("FilledTonalButton", K), ("ACTION_VIEW", K)):
    if gone_ in txt_: print("L5\t%s is back (the Open button and Links panel are removed)" % gone_)
seen = {}
for e in t:
    if "path" not in e: continue
    f = os.path.join(app, "data/ui", e["section"], *e["path"]) + ".json"; b = json.load(open(f))[0]
    if b.get("url") != e.get("url"): print("L6\t%s embeds %s, the table says %s" % (e["id"], b.get("url"), e.get("url")))
    if not b.get("url", "").startswith("https://"): print("L6\t%s does not embed an https site" % e["id"])
    if b.get("url") in seen and not e.get("mirror_of"): print("L7\t%s and %s embed the same page" % (seen[b.get("url")], e["id"]))
    if not e.get("mirror_of"): seen[b.get("url")] = e["id"]
need = {"pers-personal", "pers-ig0", "pers-ig1", "pers-ig2", "pers-face0", "pers-pinterest", "pers-youtube", "pers-tidal", "pers-linkedin", "pers-orkut", "pers-astro", "prof-nexus", "prof-leafy", "prof-stark"}
for m in sorted(need - {e["id"] for e in t}): print("L1\t%s is not declared" % m)
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "L1+L2 table complete, equal to the SoT snapshot and to the page files"; else bad "$out"; fi
if [ -d "$APP/../../cloud-me-pub-profiles-___" ] || [ -d "$APP/../../cloud-me-pub-profiles-_lnktree" ] || [ -n "${ME_SOT_DIR:-}" ]; then
  python3 "$APP/data/regen-me-links.py" --check >/dev/null && ok "L3 SoT agrees" || bad "L3 table differs from the SoT"
fi
# mutations
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { # name, python edit on copy
  rm -rf "$T/app"; mkdir -p "$T/app/data" "$T/app/app/src/main/java/com/diegonmarcos/cloudme"; cp -r "$APP/data/web" "$T/app/data/web"; cp -r "$APP/data/ui" "$T/app/data/ui"; cp "$APP/app/build.gradle" "$T/app/app/"; cp "$APP/app/src/main/java/com/diegonmarcos/cloudme/"{MeLinks,SectionScreen,WebPage}.kt "$T/app/app/src/main/java/com/diegonmarcos/cloudme/"
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
mut "blank url" 'import json,sys;p=sys.argv[1]+"/data/web/me-links.json";d=json.load(open(p));d["entries"][0]["url"]="";json.dump(d,open(p,"w"))'
mut "url differs from SoT" 'import json,sys;p=sys.argv[1]+"/data/web/me-links.json";d=json.load(open(p));d["entries"][7]["url"]="https://example.com/";json.dump(d,open(p,"w"))'
mut "entry dropped" 'import json,sys;p=sys.argv[1]+"/data/web/me-links.json";d=json.load(open(p));d["entries"]=[e for e in d["entries"] if e["id"]!="prof-stark"];json.dump(d,open(p,"w"))'
mut "page link differs" 'import json,sys;p=sys.argv[1]+"/data/ui/prof/leafy.json";d=json.load(open(p));d[0]["link"]="https://x.test/";json.dump(d,open(p,"w"))'
K=app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt
W=app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt
mut "the Open button is back" "import sys;p=sys.argv[1]+'/$K';s=open(p).read()+'\nFilledTonalButton\n';open(p,'w').write(s)"
mut "the Links panel wrapper is back" "import sys;p=sys.argv[1]+'/app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt';s=open(p).read().replace('WebPage(single, modifier, pageId = page.id)','MeLinkedWeb(single, section.id, page.id, modifier)');open(p,'w').write(s)"
mut "a cache miss no longer falls to the asset loader" "import sys;p=sys.argv[1]+'/$W';s=open(p).read().replace('?: super.shouldInterceptRequest(view, request)','');open(p,'w').write(s)"
mut "the WebView is no longer keyed by its page" "import sys;p=sys.argv[1]+'/$W';s=open(p).read().replace('key(generation, url, pageId)','key(generation)');open(p,'w').write(s)"
mut "the broken-image check is gone" "import sys;p=sys.argv[1]+'/$W';s=open(p).read().replace('naturalWidth==0','x');open(p,'w').write(s)"
mut "a tab embeds a bundled mirror" 'import json,sys;p=sys.argv[1]+"/data/ui/prof/leafy.json";d=json.load(open(p));d[0]["url"]="mysocials/linkedin.html";json.dump(d,open(p,"w"))'
mut "Leaf.y embeds the LinkedIn page" 'import json,sys;p=sys.argv[1]+"/data/ui/prof/leafy.json";d=json.load(open(p));d[0]["url"]="https://linkedin.com/in/diegonmarcos";json.dump(d,open(p,"w"))'
mut "two tabs embed the same page" 'import json,sys;[ (lambda p:(lambda d:(d[0].update(url="https://diegonmarcos.github.io/nexus/"),json.dump(d,open(p,"w"))))(json.load(open(p))))(sys.argv[1]+"/data/ui/prof/"+f) for f in ("stark.json",)]; q=sys.argv[1]+"/data/web/me-links.json";d=json.load(open(q));[x.update(url="https://diegonmarcos.github.io/nexus/") for x in d["entries"] if x["id"]=="prof-stark"];json.dump(d,open(q,"w"))'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
