#!/usr/bin/env bash
# Tester (#1906): every social account and company shown on Pers and Prof has its real link, declared in data/links/me-links.json and
# derived from the SoT by data/regen-me-links.py. L1 each entry has a url equal to its SoT snapshot; L2 each tab entry's page file carries the same
# `link`; L3 the SoT repos (when present) agree (regen --check). Each is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" <<'PY'
import json, os, sys
app = sys.argv[1]; t = json.load(open(os.path.join(app, "data/links/me-links.json")))["entries"]
for e in t:
    if not e.get("url", "").startswith(("https://", "mailto:")): print("L1\t%s has no link" % e["id"])
    elif e["url"] != e.get("sot_url"): print("L1\t%s url %s differs from the SoT %s" % (e["id"], e["url"], e.get("sot_url")))
    if "path" in e:
        f = os.path.join(app, "data/ui", e["section"], *e["path"]) + ".json"
        if json.load(open(f))[0].get("link") != e.get("url"): print("L2\t%s page file link differs from the table" % e["id"])
K = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt")).read() if os.path.exists(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt")) else ""
S = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt")).read()
G = open(os.path.join(app, "app/build.gradle")).read()
for need_, where_, txt_ in (("MeLinks.open(ctx, e.url)", "menu row click", K), ("MeLinks.open(ctx, link)", "Open action click", K), ("MeLinkedWeb(single, section.id, page.id, modifier)", "page wrapper", S), ("data/links", "me-links.json asset", G)):
    if need_ not in txt_: print("L4\t%s: %s is not bound" % (where_, need_))
need = {"pers-personal", "pers-instagram", "pers-social", "pers-astro", "prof-professional", "prof-nexus", "prof-leafy", "prof-stark"}
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
  rm -rf "$T/app"; mkdir -p "$T/app/data" "$T/app/app/src/main/java/com/diegonmarcos/cloudme"; cp -r "$APP/data/links" "$T/app/data/links"; cp -r "$APP/data/ui" "$T/app/data/ui"; cp "$APP/app/build.gradle" "$T/app/app/"; cp "$APP/app/src/main/java/com/diegonmarcos/cloudme/"{MeLinks,SectionScreen}.kt "$T/app/app/src/main/java/com/diegonmarcos/cloudme/"
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
mut "blank url" 'import json,sys;p=sys.argv[1]+"/data/links/me-links.json";d=json.load(open(p));d["entries"][0]["url"]="";json.dump(d,open(p,"w"))'
mut "url differs from SoT" 'import json,sys;p=sys.argv[1]+"/data/links/me-links.json";d=json.load(open(p));d["entries"][7]["url"]="https://example.com/";json.dump(d,open(p,"w"))'
mut "entry dropped" 'import json,sys;p=sys.argv[1]+"/data/links/me-links.json";d=json.load(open(p));d["entries"]=[e for e in d["entries"] if e["id"]!="prof-stark"];json.dump(d,open(p,"w"))'
mut "page link differs" 'import json,sys;p=sys.argv[1]+"/data/ui/prof/leafy.json";d=json.load(open(p));d[0]["link"]="https://x.test/";json.dump(d,open(p,"w"))'
K=app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt
mut "a menu row no longer opens its URL" "import sys;p=sys.argv[1]+'/$K';s=open(p).read().replace('MeLinks.open(ctx, e.url)','Unit');open(p,'w').write(s)"
mut "the Open action no longer opens" "import sys;p=sys.argv[1]+'/$K';s=open(p).read().replace('MeLinks.open(ctx, link)','Unit');open(p,'w').write(s)"
mut "the page is no longer wrapped" "import sys;p=sys.argv[1]+'/app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt';s=open(p).read().replace('MeLinkedWeb(','WebPage(');open(p,'w').write(s)"
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
