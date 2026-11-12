#!/usr/bin/env bash
# test-store-data: data/store.json (the Store's catalogue, what Galaxy loads) is GENERATED from data/fwa.md by data/gen-store.py.
#   S1  no drift: store.json is byte-for-byte what the generator makes of fwa.md today
#   S2  completeness: one entry per row of fwa.md, every row's link, Type, 2D/3D and VR carried over
#   S4  cleartext is narrow: the network security config forbids it by default, allows it for EXACTLY the hosts of the http-only
#       Store entries (no more, no fewer, never a global allowance), and the manifest uses that config
#   S5  every entry carries its link's status/checked from data/link-status.json (an unprobed link is unverified, never online)
#   S7  every entry's wayback link is the Wayback Machine 2020 copy of its own url
#   S6  the probe's verdicts: HTTP 404/410 and a timeout are offline, a proxy block is unverified (never offline), 200 online
#   S3  the generator refuses a malformed row (an unknown Type / 2D/3D / VR, a name without a link) instead of guessing
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

check() {   # check <data dir>: S1 + S2, prints FAIL lines
python3 - "$1" <<'PY'
import json, os, re, subprocess, sys
d = sys.argv[1]
bad = []
r = subprocess.run([sys.executable, "-I", os.path.join(d, "gen-store.py"), "--check"], capture_output=True, text=True)
if r.returncode != 0: bad.append("S1 " + (r.stdout + r.stderr).strip().splitlines()[-1] if (r.stdout + r.stderr).strip() else "S1 the generator failed")
rows = [l for l in open(os.path.join(d, "fwa.md"), encoding="utf-8").read().splitlines() if l.strip().startswith("|")][2:]
try: all_entries = json.load(open(os.path.join(d, "store.json"), encoding="utf-8")).get("entries", [])
except Exception as e: all_entries = []; bad.append("S2 store.json does not parse: %s" % e)
entries = [e for e in all_entries if "model" not in e]      # the web worlds: one per row of fwa.md (the Type Model entries are S8)
models = [e for e in all_entries if "model" in e]
if len(entries) != len(rows): bad.append("S2 store.json has %d entries, fwa.md %d rows" % (len(entries), len(rows)))
for row, e in zip(rows, entries):
    c = [x.strip() for x in row.strip().strip("|").split("|")]
    m = re.fullmatch(r"\[([^\]]+)\]\(([^)]+)\)", c[0])
    want = (m.group(1), m.group(2), c[1], c[2], c[5] == "Yes", c[6]) if m else None
    got = (e.get("name"), e.get("url"), e.get("type"), e.get("dimension"), e.get("vr"), e.get("description"))
    if want != got: bad.append("S2 entry %r does not carry its row %r" % (e.get("name"), c[0]))
# S4
import xml.etree.ElementTree as ET
app = os.path.dirname(os.path.abspath(d))
nsc_path = os.path.join(app, "app/src/main/res/xml/network_security_config.xml")
try:
    root = ET.parse(nsc_path).getroot()
    base = root.find("base-config")
    if base is None or base.get("cleartextTrafficPermitted") != "false": bad.append("S4 the base config does not forbid cleartext (a global allowance)")
    allowed = set()
    for dc in root.findall("domain-config"):
        if dc.get("cleartextTrafficPermitted") == "true":
            allowed |= {x.text.strip().lower() for x in dc.findall("domain")}
    http = {re.match(r"http://([^/:]+)", e["url"]).group(1).lower() for e in entries if e.get("url", "").startswith("http://")}
    if allowed != http: bad.append("S4 cleartext hosts %s are not the http-only Store hosts %s" % (sorted(allowed), sorted(http)))
except Exception as e:
    bad.append("S4 the network security config does not parse: %s" % e)
man = open(os.path.join(app, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
if 'android:networkSecurityConfig="@xml/network_security_config"' not in man: bad.append("S4 the manifest does not use the generated network security config")
if 'usesCleartextTraffic="true"' in man: bad.append("S4 the manifest allows cleartext globally")
# S5
lp = os.path.join(d, "link-status.json")
links = json.load(open(lp, encoding="utf-8")).get("links", {}) if os.path.exists(lp) else {}
for e in entries:
    want = links.get(e.get("url"), {"status": "unverified", "checked": ""})
    if (e.get("status"), e.get("checked")) != (want.get("status"), want.get("checked")):
        bad.append("S5 %r carries %r/%r, link-status.json says %r/%r" % (e.get("id"), e.get("status"), e.get("checked"), want.get("status"), want.get("checked")))
# S7
for e in entries:
    if e.get("wayback") != "https://web.archive.org/web/2020/" + str(e.get("url")): bad.append("S7 %r wayback %r is not the archive copy of its url" % (e.get("id"), e.get("wayback")))
# S8 (#1701) the 3D models: one Type Model entry per models.json row, after the web worlds, each carrying its GLB address (https), SHA-256, size and photo;
# no model file is baked into the app (assets hold the viewer page only)
mp = os.path.join(d, "models.json")
decl = json.load(open(mp, encoding="utf-8")).get("models", []) if os.path.exists(mp) else []
if [e["id"] for e in models] != [m["id"] for m in decl]: bad.append("S8 store.json models %s are not models.json %s" % ([e["id"] for e in models], [m["id"] for m in decl]))
if all_entries[len(entries):] != models: bad.append("S8 the model entries are not last in store.json")
for e, m in zip(models, decl):
    mo = e["model"]
    if (mo.get("glb"), mo.get("sha256"), mo.get("size"), mo.get("photo")) != (m["glb"]["url"], m["glb"]["sha256"], m["glb"]["size"], m["photo"]["url"]): bad.append("S8 %s does not carry its models.json GLB / SHA-256 / size / photo" % e["id"])
    if e.get("type") != "Model" or e.get("dimension") != "3D": bad.append("S8 %s is not a Type Model 3D entry" % e["id"])
    if not (mo.get("glb", "").startswith("https://") and mo.get("photo", "").startswith("https://")): bad.append("S8 %s has a non-https address" % e["id"])
assets = os.path.join(app, "app/src/main/assets")
for root_, _, files in os.walk(assets):
    for f in files:
        if f.lower().endswith((".glb", ".gltf", ".obj", ".step", ".stp")) and "avatar" not in os.path.relpath(root_, assets).split(os.sep)[:1]:
            bad.append("S8 a model file is baked into the app: %s" % os.path.join(root_, f))
# S6: the probe against a stubbed network
import importlib.util, socket, urllib.error, urllib.request, io
spec = importlib.util.spec_from_file_location("probe", os.path.join(d, "probe-links.py")); pr = importlib.util.module_from_spec(spec); spec.loader.exec_module(pr)
def http(code, body=b""):
    def f(*a, **k): raise urllib.error.HTTPError("u", code, "x", {}, io.BytesIO(body))
    return f
class Ok:
    status = 200
    def __enter__(self): return self
    def __exit__(self, *a): return False
def raise_(e):
    def f(*a, **k): raise e
    return f
cases = [("HTTP 404", http(404), "offline"), ("HTTP 410", http(410), "offline"), ("HTTP 500", http(500), "online"),
         ("HTTP 200", lambda *a, **k: Ok(), "online"), ("timeout", raise_(urllib.error.URLError(socket.timeout("timed out"))), "offline"),
         ("bare timeout", raise_(socket.timeout("timed out")), "offline"), ("proxy 407", http(407), "unverified"),
         ("proxy 403", http(403, b"blocked by proxy policy"), "unverified"),
         ("tunnel", raise_(urllib.error.URLError("Tunnel connection failed: 403 Forbidden")), "unverified")]
real = urllib.request.urlopen
for name, fn, want in cases:
    urllib.request.urlopen = fn
    try: got = pr.probe("https://x.example/", 1)[0]
    finally: urllib.request.urlopen = real
    if got != want: bad.append("S6 probe says %s for %s, want %s" % (got, name, want))
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

echo "[$(basename "$APP")] store data"
out="$(check "$APP/data")" && ok "S1-S2, S4 hold on the real tree (store.json is generated from fwa.md)" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <edit run in the copy> <needle>
  rm -rf "${W:?}/app"; mkdir -p "$W/app/app/src/main/res"; cp -r "$APP/data" "$W/app/data"
  cp -r "$APP/app/src/main/res/xml" "$W/app/app/src/main/res/xml"; cp "$APP/app/src/main/AndroidManifest.xml" "$W/app/app/src/main/"
  ( cd "$W/app/data" && eval "$2" )
  if out="$(check "$W/app/data" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
mutant "store.json is hand-edited"     "sed -i '0,/\"vr\": false/s//\"vr\": true/' store.json"            "S1"
mutant "a row is added to fwa.md only" "tail -1 fwa.md >> fwa.md && sed -i '\$s/Inside The Head/Inside The Head 2/' fwa.md" "S1"
NSC=../app/src/main/res/xml/network_security_config.xml
MAN=../app/src/main/AndroidManifest.xml
mutant "cleartext is allowed globally" "sed -i 's/base-config cleartextTrafficPermitted=.false./base-config cleartextTrafficPermitted=\"true\"/' \$NSC" "S4 the base config"
mutant "an extra cleartext host"       "sed -i 's#a-way-to-go.com</domain>#a-way-to-go.com</domain><domain>evil.example</domain>#' \$NSC" "S4 cleartext hosts"
mutant "an http host loses cleartext"  "sed -i '/a-way-to-go.com/d' \$NSC" "S4 cleartext hosts"
mutant "the manifest drops the config" "sed -i 's#android:networkSecurityConfig=.@xml/network_security_config.##' \$MAN" "S4 the manifest does not use"
mutant "the manifest goes cleartext"   "sed -i 's#android:hardwareAccelerated=#android:usesCleartextTraffic=\"true\" android:hardwareAccelerated=#' \$MAN" "S4 the manifest allows cleartext"
mutant "a probe result is not merged" "python3 -c \"import json;d=json.load(open('link-status.json'));d['links']['http://a-way-to-go.com/']['status']='online';json.dump(d,open('link-status.json','w'),indent=2)\"" "S1"
mutant "store.json hides an offline world" "sed -i '0,/\"status\": \"offline\"/s//\"status\": \"online\"/' store.json" "S5"
mutant "an unprobed link reads online" "sed -i 's/get(\"status\", \"unverified\")/get(\"status\", \"online\")/' gen-store.py && python3 -c \"import json;d=json.load(open('link-status.json'));d['links'].pop('https://lusion.co/');json.dump(d,open('link-status.json','w'),indent=2)\" && python3 gen-store.py >/dev/null" "S5"
mutant "404 reads online"              "sed -i 's/GONE = (404, 410)/GONE = (410,)/' probe-links.py" "S6 probe says online for HTTP 404"
mutant "410 reads online"              "sed -i 's/GONE = (404, 410)/GONE = (404,)/' probe-links.py" "S6 probe says online for HTTP 410"
mutant "a timeout reads unverified"    "sed -i 's/return \"offline\", \"timeout\"/return \"unverified\", \"timeout\"/g' probe-links.py" "S6 probe says unverified for timeout"
mutant "a proxy block reads offline"   "sed -i 's/return \"unverified\", \"proxy %d\"/return \"offline\", \"proxy %d\"/' probe-links.py" "S6 probe says offline for proxy"
mutant "a tunnel block reads offline"  "sed -i 's/return \"unverified\", \"proxy: %s\"/return \"offline\", \"proxy: %s\"/' probe-links.py" "S6 probe says offline for tunnel"
mutant "a wayback link points elsewhere" "sed -i 's#^WAYBACK = .*#WAYBACK = \"https://web.archive.org/web/\"#' gen-store.py && python3 gen-store.py >/dev/null" "S7"
mutant "a web entry is dropped"        "python3 -c \"import json;d=json.load(open('store.json'));d['entries'].pop(0);json.dump(d,open('store.json','w'),indent=2)\"" "S2"
mutant "a model entry is dropped"      "python3 -c \"import json;d=json.load(open('store.json'));d['entries'].pop();json.dump(d,open('store.json','w'),indent=2)\"" "S8"
mutant "a model's SHA-256 is hand-edited" "sed -i '0,/\"sha256\": \"[0-9a-f]*\"/s//\"sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\"/' models.json" "S1"
mutant "a model's GLB is not https"    "sed -i 's#\"url\": \"https://media#\"url\": \"http://media#' models.json" "S1"

# S3: the generator itself refuses what it cannot map
refuse() {   # refuse <name> <sed on fwa.md> <needle in the generator's message>
  rm -rf "${W:?}/app"; mkdir -p "$W/app/app/src/main/res"; cp -r "$APP/data" "$W/app/data"; cp -r "$APP/app/src/main/res/xml" "$W/app/app/src/main/res/xml"
  ( cd "$W/app/data" && sed -i "$2" fwa.md )
  if out="$(python3 -I "$W/app/data/gen-store.py" --check 2>&1)"; then bad "S3 the generator took a $1"; elif grep -q -- "$3" <<<"$out"; then ok "S3 the generator refuses a $1"; else bad "S3 the generator refused a $1 for the wrong reason: $out"; fi
}
refuse "unknown Type"        '0,/| Website |/s//| Site |/'  "Type 'Site'"
refuse "unknown 2D\/3D"      '0,/| 3D | Three/s//| 4D | Three/' "2D/3D '4D'"
refuse "unknown VR value"    '0,/| No |/s//| Maybe |/'      "VR 'Maybe'"
refuse "name without a link" '0,/\[Lusion v3\](https:\/\/lusion.co\/)/s//Lusion v3/' "is not \[name\](url)"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
