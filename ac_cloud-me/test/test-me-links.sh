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
  rm -rf "$T/app"; mkdir -p "$T/app/data"; cp -r "$APP/data/links" "$T/app/data/links"; cp -r "$APP/data/ui" "$T/app/data/ui"
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
mut "blank url" 'import json,sys;p=sys.argv[1]+"/data/links/me-links.json";d=json.load(open(p));d["entries"][0]["url"]="";json.dump(d,open(p,"w"))'
mut "url differs from SoT" 'import json,sys;p=sys.argv[1]+"/data/links/me-links.json";d=json.load(open(p));d["entries"][7]["url"]="https://example.com/";json.dump(d,open(p,"w"))'
mut "entry dropped" 'import json,sys;p=sys.argv[1]+"/data/links/me-links.json";d=json.load(open(p));d["entries"]=[e for e in d["entries"] if e["id"]!="prof-stark"];json.dump(d,open(p,"w"))'
mut "page link differs" 'import json,sys;p=sys.argv[1]+"/data/ui/prof/leafy.json";d=json.load(open(p));d[0]["link"]="https://x.test/";json.dump(d,open(p,"w"))'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
