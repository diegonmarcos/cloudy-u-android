#!/usr/bin/env bash
# Tester (#1335): the app FAMILIES are declared once (1_cicd/src/data/app-families.json) and every guard reads them.
#
#   F1  the declaration: folder prefixes aa_ ab_ ac_ ad_, a family per app prefix (ac_ Cloud, ad_ Lnktree) with name / folder / display
#       patterns that compile, and a fleet id rule
#   F2  every top-level app folder (a build.json with an application id) carries a declared prefix; an ad_ folder matches the Lnktree
#       family's folder pattern; each app's build.json::name matches ITS family's name pattern and display_name its display pattern;
#       the fleet id the fleet file carries is the one the rule derives from the folder
#   F3  the four Lnktree apps exist, in the owner's spelling, and their tester is the shared template byte for byte
#   F4  no guard spells the folder prefixes by hand: every `a[...]_` class in 1_cicd/src and aa_cloud-superapp/test is one of the two
#       derived from the declaration (a[abcd]_ all prefixes, a[acd]_ the app prefixes), and compose-migration's unit_dir_pattern accepts every
#       declared app folder
#   F5  the Lnktree apps are in the fleet manifest (constellation-fleet.json, fleet-config.json, ipc-mesh.json, debug-ports.json, the
#       workflows generated from 1_cicd, test-coverage) and each ship workflow's WORK_DIR is its folder
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="${FAMILIES_ROOT:-$(cd "$APP/.." && pwd)}"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$ROOT/1_cicd/src/data/app-families.json" ] || { echo "ERROR: app-families.json missing - zero checks ran"; exit 1; }

check() {   # check <root> -> FAIL lines, exit 1 on any
python3 - "$1" <<'PY'
import glob, json, os, re, sys
root = sys.argv[1]
bad = []
def read(p):
    with open(os.path.join(root, p), encoding="utf-8") as f: return f.read()
def load(p): return json.loads(read(p))

# F1
fam = load("1_cicd/src/data/app-families.json")
if fam.get("folder_prefixes") != ["aa_", "ab_", "ac_", "ad_"]: bad.append("F1 folder_prefixes is %s, want aa_ ab_ ac_ ad_" % fam.get("folder_prefixes"))
prefixes = {f.get("folder_prefix"): f for f in fam["families"]}
for want in ("ac_", "ad_"):
    if want not in prefixes: bad.append("F1 no family declares the folder prefix %s" % want)
for f in fam["families"]:
    for k in ("name_pattern", "folder_pattern", "display_pattern"):
        try: re.compile(f[k])
        except Exception as e: bad.append("F1 family %s: %s does not compile (%s)" % (f.get("id"), k, e))
    if "id_strip" not in f: bad.append("F1 family %s has no id_strip" % f.get("id"))
    if f.get("folder_prefix") not in fam.get("folder_prefixes", []): bad.append("F1 family %s uses a prefix that is not in folder_prefixes" % f.get("id"))

def rx(p):
    try: return re.compile(p)
    except Exception: return re.compile(r"(?!)")   # F1 already reported it

# F2
fleet = {a["id"]: a for a in load("aa_cloud-superapp/data/constellation-fleet.json")["apps"]}
def fid(d):
    f = prefixes.get(d[:3])
    if not f: return d
    out = d
    for s in f.get("id_strip", []):
        if out.startswith(s): out = out[len(s):]; break
    return out.lower() if f.get("lowercase_id") else out
seen = 0
for d in sorted(os.listdir(root)):
    if not d.startswith(tuple(fam["folder_prefixes"])) or not os.path.isfile(os.path.join(root, d, "build.json")): continue
    bj = load(d + "/build.json")
    f = prefixes.get(d[:3])
    if not (bj.get("android") or {}).get("application_id"): continue
    if f is None:
        if d.startswith(("aa_", "ab_")): continue
        bad.append("F2 %s carries no declared app-family prefix" % d); continue
    seen += 1
    if not rx(f["folder_pattern"]).match(d) and d[:3] == "ad_": bad.append("F2 folder %s does not match the %s family's folder pattern" % (d, f["id"]))
    if d.startswith("ad_") or d.startswith("ac_"):
        if not rx(f["name_pattern"]).match(bj.get("name", "")): bad.append("F2 %s name %r does not match the %s family's name pattern" % (d, bj.get("name"), f["id"]))
        if d.startswith("ad_") and not rx(f["display_pattern"]).match(bj.get("display_name", "")): bad.append("F2 %s display_name %r does not match the family's display pattern" % (d, bj.get("display_name")))
        row = fleet.get(fid(d))
        if row is not None and row.get("label") != bj.get("name"): bad.append("F2 fleet row %s is labelled %r, %s names itself %r" % (fid(d), row.get("label"), d, bj.get("name")))
if seen < 30: bad.append("F2 only %d app folders were examined - the detection matched too little" % seen)

# F3
LNK = {"ad_CG-Omniverse": "cg-omniverse", "ad_Capital-Stack-FinTerminal": "capital-stack-finterminal", "ad_Hardtech-CAD": "hardtech-cad", "ad_Hardtech-BioMed": "hardtech-biomed"}
tpl = read("1_cicd/src/templates/test-lnktree-app.sh")
for d, name in LNK.items():
    if not os.path.isfile(os.path.join(root, d, "build.json")): bad.append("F3 the Lnktree app folder %s does not exist" % d); continue
    if load(d + "/build.json").get("name") != name: bad.append("F3 %s is not named %s" % (d, name))
    if not os.path.isfile(os.path.join(root, d, "test/test-lnktree-app.sh")) or read(d + "/test/test-lnktree-app.sh") != tpl:
        bad.append("F3 %s/test/test-lnktree-app.sh is not the shared template" % d)

# F4
classes = set()
for base in ("1_cicd/src", "aa_cloud-superapp/test"):
    for dp, _, files in os.walk(os.path.join(root, base)):
        for f in files:
            if f == "test-app-families.sh" or not f.endswith((".py", ".sh", ".yml")) or f == "cloud_android_app_families.py": continue
            for m in re.finditer(r"a\[([a-z]+)\]_", open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()):
                classes.add((m.group(1), os.path.relpath(os.path.join(dp, f), root)))
letters = "".join(p[1] for p in fam["folder_prefixes"])
apps = "".join(p[1] for p in fam["folder_prefixes"] if p != "ab_")
for cl, where in sorted(classes):
    if cl not in (letters, apps): bad.append("F4 %s spells the folder class a[%s]_, which is neither a[%s]_ (all prefixes) nor a[%s]_ (app prefixes) of app-families.json" % (where, cl, letters, apps))
pat = load("1_cicd/src/data/compose-migration.json")["unit_dir_pattern"]
for d in sorted(LNK):
    if not re.match(pat, d): bad.append("F4 compose-migration unit_dir_pattern %s does not accept %s" % (pat, d))

# F5
for d, name in LNK.items():
    if not os.path.isfile(os.path.join(root, d, "build.json")): continue   # F3 reported it
    if name not in fleet: bad.append("F5 %s is not in constellation-fleet.json" % name)
    if name not in load("fleet-private-data/fleet-config.json")["apps"]: bad.append("F5 %s is not in fleet-config.json" % name)
    if name not in [m["id"] for m in load("aa_cloud-superapp/data/ipc-mesh.json")["members"]]: bad.append("F5 %s is not in ipc-mesh.json" % name)
    if "ship-%s.yml" % name not in os.listdir(os.path.join(root, "1_cicd/src/cicd")): bad.append("F5 1_cicd/src/cicd/ship-%s.yml is missing" % name)
    elif not re.search(r"^  WORK_DIR:\s*%s\s*$" % re.escape(d), read("1_cicd/src/cicd/ship-%s.yml" % name), re.M): bad.append("F5 ship-%s.yml WORK_DIR is not %s" % (name, d))
    if not os.path.isfile(os.path.join(root, ".github/workflows/ship-%s.yml" % name)): bad.append("F5 .github/workflows/ship-%s.yml is not generated" % name)
    pkg = load(d + "/build.json")["android"]["application_id"]
    if pkg not in load("1_cicd/src/data/debug-ports.json")["ports"]: bad.append("F5 %s has no debug port" % pkg)
    if d not in load("1_cicd/src/data/test-coverage.json").get("apps", load("1_cicd/src/data/test-coverage.json")) and d not in read("1_cicd/src/data/test-coverage.json"): bad.append("F5 %s has no test-coverage entry" % d)
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[app families]"
out="$(check "$ROOT")" && ok "F1-F5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
stage() {   # a scratch copy of what the checks read (working tree, tracked or not)
  rm -rf "${W:?}/t"; mkdir -p "$W/t"
  ( cd "$ROOT" && {
      find . -maxdepth 2 -name build.json -path './a[a-d]_*' -print0
      find ./1_cicd/src ./aa_cloud-superapp/data ./aa_cloud-superapp/test ./.github/workflows -type f -print0
      find ./ad_* -path '*/test/*' -type f -print0
      printf '%s\0' ./fleet-private-data/fleet-config.json
    } | xargs -0 cp --parents -t "$W/t" )
}
mutant() {   # mutant <name> <python edit run in the copy> <needle>
  stage
  ( cd "$W/t" && python3 -c "$2" )
  if out="$(check "$W/t" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -qF -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
J="import json,re"
mutant "the ad_ prefix is not declared"       "$J;p='1_cicd/src/data/app-families.json';d=json.load(open(p));d['folder_prefixes'].remove('ad_');json.dump(d,open(p,'w'))"            "F1 folder_prefixes is"
mutant "a family pattern stops compiling"     "$J;p='1_cicd/src/data/app-families.json';d=json.load(open(p));d['families'][1]['name_pattern']='(';json.dump(d,open(p,'w'))"       "F1 family lnktree: name_pattern does not compile"
mutant "an app is named outside its family"   "$J;p='ad_Hardtech-CAD/build.json';d=json.load(open(p));d['name']='cloud-cad';json.dump(d,open(p,'w'))"                           "F2 ad_Hardtech-CAD name 'cloud-cad'"
mutant "a display name leaves the pattern"    "$J;p='ad_Hardtech-CAD/build.json';d=json.load(open(p));d['display_name']='Hardtech CAD';json.dump(d,open(p,'w'))"                 "F2 ad_Hardtech-CAD display_name"
mutant "the fleet label drifts from the app"  "$J;p='aa_cloud-superapp/data/constellation-fleet.json';d=json.load(open(p));[a.update(label='hardtech-cadx') for a in d['apps'] if a['id']=='hardtech-cad'];json.dump(d,open(p,'w'))" "F2 fleet row hardtech-cad is labelled"
mutant "an app folder is missing"             "import shutil;shutil.rmtree('ad_Hardtech-BioMed')"                                                                                "F3 the Lnktree app folder ad_Hardtech-BioMed does not exist"
mutant "a tester copy drifts from the template" "open('ad_Hardtech-CAD/test/test-lnktree-app.sh','a').write('# drift\n')"                                                          "F3 ad_Hardtech-CAD/test/test-lnktree-app.sh is not the shared template"
mutant "a guard spells the prefixes by hand"  "open('1_cicd/src/scripts/cloud-android-crash-default-guard.py','a').write('# glob a[ab]_*/x\n')"                                    "F4 1_cicd/src/scripts/cloud-android-crash-default-guard.py spells the folder class a[ab]_"
mutant "the ratchet pattern rejects the ad_"  "$J;p='1_cicd/src/data/compose-migration.json';d=json.load(open(p));d['unit_dir_pattern']='^a[abc]_[a-z0-9-]+\$';json.dump(d,open(p,'w'))" "F4 compose-migration unit_dir_pattern"
mutant "a ship workflow loses its folder"     "p='1_cicd/src/cicd/ship-hardtech-cad.yml';s=open(p).read().replace('WORK_DIR: ad_Hardtech-CAD','WORK_DIR: nowhere');open(p,'w').write(s)" "F5 ship-hardtech-cad.yml WORK_DIR"
mutant "an app leaves the fleet manifest"     "$J;p='fleet-private-data/fleet-config.json';d=json.load(open(p));d['apps'].pop('cg-omniverse');json.dump(d,open(p,'w'))" "F5 cg-omniverse is not in fleet-config.json"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
