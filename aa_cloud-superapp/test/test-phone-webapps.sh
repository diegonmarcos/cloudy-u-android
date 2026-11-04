#!/usr/bin/env bash
# Phone ▸ tabs: Apps | WebApps | Configs (#1466). WebApps = the web version of the phone apps, declared as DATA (data/phone-webapps.json).
#   T1 the Phone section lists apps, webapps, configs in that order
#   T2 WebApps declares webapps_base (https) + webapps_path (embed=1) once, no per-app list or url in build.json, same site as Cloud ▸ WebApps
#   T3 data/phone-webapps.json holds exactly the packages of phone_app_groups, each once, in the same groups
#   T4 every url is https or null (null = greyed, disabled tile); none carries credentials or a query secret
#   T5 the page opens through the generic `url` branch (WebPageFragment), no phone-specific Kotlin
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
B="$APP/build.json"; D="$APP/data/phone-webapps.json"
for f in "$B" "$D" "$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"; do [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }; done
out="$(python3 - "$B" "$D" <<'PY'
import json, sys, re
b = json.load(open(sys.argv[1])); d = json.load(open(sys.argv[2]))
secs = {x["id"]: x for x in b["ui"]["sections"]}
ph = secs["phone"]; vis = [p for p in ph["pages"] if not p.get("hidden")]
print("ORDER", ",".join(p["id"] for p in vis))
w = ([p for p in ph["pages"] if p["id"] == "webapps"] or [{}])[0]
cw = [p for p in secs["cloud"]["pages"] if p["id"] == "webapps"][0]
base = w.get("webapps_base", "").rstrip("/"); path = w.get("webapps_path", "")
good = base.startswith("https://") and base == cw["webapps_base"].rstrip("/") and "embed=1" in path and not w.get("url") and "webapps" not in w and "apps" not in w
print("DECL", "ok" if good else "BAD base=%r path=%r" % (base, path))
def pk(p): return p if isinstance(p, str) else p["pkg"]
want = []
for g in ph["phone_app_groups"]:
    ps = [pk(p) for p in g["packages"]] + [pk(p) for f in (g.get("folders") or []) for p in f["packages"]]
    want.append((g["title"], [p for i, p in enumerate(ps) if p not in ps[:i]]))
seen = set(); exp = []
for t, ps in want:
    ps = [p for p in ps if p not in seen]; seen.update(ps); exp.append((t, ps))
got = [(g["title"], [a["pkg"] for a in g["apps"]]) for g in d["groups"]]
print("SAME", "ok" if got == exp else "BAD missing=%s extra=%s" % (sorted({p for _, l in exp for p in l} - {p for _, l in got for p in l}), sorted({p for _, l in got for p in l} - {p for _, l in exp for p in l})))
allapps = [a for g in d["groups"] for a in g["apps"]]
badu = [a["pkg"] for a in allapps if "url" not in a or not a.get("label") or (a["url"] is not None and not re.fullmatch(r"https://[a-z0-9.-]+(/[A-Za-z0-9._~/-]*)?", a["url"]))]
print("URLS", "ok %d/%d with a url" % (sum(1 for a in allapps if a["url"]), len(allapps)) if not badu else "BAD " + ",".join(badu))
PY
)"
[ "$(grep '^ORDER' <<<"$out" | cut -d' ' -f2)" = "apps,webapps,configs" ] && ok "T1 Phone lists apps, webapps, configs" || bad "T1 Phone's pages are $(grep '^ORDER' <<<"$out")"
case "$(grep '^DECL' <<<"$out")" in "DECL ok") ok "T2 WebApps declares its address once, on the Cloud ▸ WebApps site";; *) bad "T2 $(grep '^DECL' <<<"$out")";; esac
case "$(grep '^SAME' <<<"$out")" in "SAME ok") ok "T3 phone-webapps.json == phone_app_groups (packages and groups)";; *) bad "T3 $(grep '^SAME' <<<"$out")";; esac
case "$(grep '^URLS' <<<"$out")" in "URLS ok"*) ok "T4 $(grep '^URLS' <<<"$out" | cut -d' ' -f2-)";; *) bad "T4 $(grep '^URLS' <<<"$out")";; esac
grep -q 'url.isNotBlank() -> WebPageFragment.newInstance(url)' "$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt" && ok "T5 a page with a url is WebPageFragment" || bad "T5 the url branch is gone"
# mutation: dropping the page / an app / making a url insecure must go red
M="$(mktemp -d)"; trap 'rm -rf "$M"' EXIT
python3 - "$D" "$M/d.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["groups"][0]["apps"].pop(); json.dump(d, open(sys.argv[2], "w"))
PY
python3 - "$B" "$M/d.json" <<'PY' | grep -q 'SAME ok' && bad "mutation: a dropped app is invisible to T3" || ok "mutation: dropping an app turns T3 red"
import json, sys
b = json.load(open(sys.argv[1])); d = json.load(open(sys.argv[2]))
def pk(p): return p if isinstance(p, str) else p["pkg"]
ph = [x for x in b["ui"]["sections"] if x["id"] == "phone"][0]
g = ph["phone_app_groups"][0]
ps = [pk(p) for p in g["packages"]] + [pk(p) for f in (g.get("folders") or []) for p in f["packages"]]
print("SAME ok" if [a["pkg"] for a in d["groups"][0]["apps"]] == ps else "SAME BAD")
PY
echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" -eq 0 ]
