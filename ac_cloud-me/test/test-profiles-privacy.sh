#!/usr/bin/env bash
# Guard: Profile > Health and Profile > DD hold special-category data. This repo is PUBLIC, so it may carry their
# SCHEMAS (labels only) and nothing else. Fails if a values file (`*.data.json`, or anything named health/dd with
# data/values/record in a json name) appears anywhere in the repo, if a schema carries a value, status or expiry,
# or if a Health/DD page file carries anything but a profile_record block, a web fragment (url + inject only) and links,
# or if the bundled web pages (data/web/mysocials/data-profile-*.json.js, the schema as the page reads it) carry a value. The owner's values live in the
# private repo cloud-me-pvt-data (profiles/<id>.data.json) and on the device. It plants each violation in a scratch
# copy first and requires RED, then checks the real tree.
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$PWD"
ROOT="$(git -C "$APP" rev-parse --show-toplevel 2>/dev/null || (cd "$APP/.." && pwd))"

check() { python3 - "$1" "$2" <<'PY'
import json, os, re, sys
root, app = sys.argv[1], sys.argv[2]
bad = []
NAME = re.compile(r"(^|[-_.])(health|dd|medical|kyc)([-_.].*)?(data|values|record|dossier)[^/]*\.json$", re.I)
for d, dirs, names in os.walk(root):
    dirs[:] = [x for x in dirs if x not in (".git", "build", ".gradle", "node_modules")]
    for n in names:
        p = os.path.join(d, n); rel = os.path.relpath(p, root)
        if n.endswith(".data.json") or NAME.search(n):
            bad.append(f"values file in a public repo path: {rel}")
        if os.sep + "profiles-data" + os.sep in os.sep + rel:
            bad.append(f"private profiles-data folder in a public repo path: {rel}")
sd = os.path.join(app, "data/profiles/profiles")
for n in sorted(os.listdir(sd)) if os.path.isdir(sd) else []:
    if not n.endswith(".schema.json"):
        bad.append(f"{n}: only *.schema.json may live in data/profiles/profiles")
        continue
    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k in ("value", "values", "status", "expires"):
                    bad.append(f"{n}: schema carries a '{k}' key")
                walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(json.load(open(os.path.join(sd, n))))
for pid, rel in (("health", "health/health-profile"), ("dd", "dd")):   # #1901 Pers > Health > Record and Pers > DD (were Lnktree > Health and DD)
    f = os.path.join(app, f"data/ui/pers/{rel}.json")
    if os.path.exists(f):
        for b in json.load(open(f)):
            if b.get("kind") not in ("profile_record", "fragment", "link", "note", "section_title"):
                bad.append(f"pers/{rel}.json: block kind {b.get('kind')} may carry data")
            if b.get("kind") == "profile_record" and set(b) - {"kind", "schema", "store", "_doc"}:
                bad.append(f"pers/{rel}.json: profile_record carries extra keys {sorted(set(b) - {'kind','schema','store','_doc'})}")
            if b.get("kind") == "fragment" and set(b) - {"kind", "id", "url", "inject", "_doc"}:
                bad.append(f"pers/{rel}.json: fragment carries extra keys {sorted(set(b) - {'kind','id','url','inject','_doc'})}")
wd = os.path.join(app, "data/web/mysocials")
for n in sorted(os.listdir(wd)) if os.path.isdir(wd) else []:
    if re.match(r"data-profile-[a-z]+\.json\.js$", n):
        txt = open(os.path.join(wd, n), encoding="utf-8").read()
        try: o = json.loads(txt[txt.index("] = ") + 4: txt.rindex(";\n})();")])
        except Exception: bad.append(f"{n}: unreadable"); continue
        def wk(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k in ("value", "values", "status", "expires"): bad.append(f"web/{n}: bundled schema carries a '{k}' key")
                    wk(v)
            elif isinstance(o, list):
                for v in o: wk(v)
        wk(o)
for b in sorted(bad): print("FAIL:", b)
sys.exit(1 if bad else 0)
PY
}

fails=0
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
plant() { # name, shell snippet run inside a scratch copy of the app dir
  rm -rf "$T/c"; mkdir -p "$T/c/ac_cloud-me"; cp -r data "$T/c/ac_cloud-me/data"
  (cd "$T/c/ac_cloud-me" && eval "$2")
  if check "$T/c" "$T/c/ac_cloud-me" >/dev/null; then echo "  FAIL: guard stayed green for: $1"; fails=$((fails+1)); else echo "  PASS: red for: $1"; fi
}
plant "a health values file" 'echo "{}" > data/health.data.json'
plant "a dd values file" 'mkdir -p data/profiles && echo "{}" > data/profiles/dd.data.json'
plant "a value inside a schema" 'sed -i "0,/\"label\"/s//\"value\": \"x\", \"label\"/" data/profiles/profiles/health.schema.json'
plant "a non-schema file next to the schemas" 'echo "{}" > data/profiles/profiles/health.json'
plant "a value inside the bundled web schema" 'sed -i "0,/\"label\"/s//\"value\": \"x\", \"label\"/" data/web/mysocials/data-profile-dd.json.js'
plant "a fragment carrying data" 'echo "[{\"kind\":\"fragment\",\"id\":\"web\",\"url\":\"x\",\"values\":{}}]" > data/ui/pers/health/health-profile.json'
plant "a stack block carrying data" 'echo "[{\"kind\":\"stats\",\"rows\":[]}]" > data/ui/pers/dd.json'

if check "$ROOT" "$APP"; then echo "  PASS: no Health/DD values in this public repo"; else echo "  FAIL: see above"; fails=$((fails+1)); fi
echo "profiles privacy: $fails failure(s)"
exit $((fails>0))
