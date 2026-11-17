#!/usr/bin/env bash
# #1777 Hardtech-Sim2Real was rebranded Hardtech-BioMed (new folder ad_Hardtech-BioMed, new application id com.diegonmarcos.hardtechbiomed, the old
# id retired). This holds that nothing of the OLD identity is left in the repository outside its retirement records:
#   R1 no tracked file spells the old identity (hardtech-sim2real / Hardtech-Sim2Real / hardtechsim2real / ad_Hardtech-Sim2Real / proj_sim2real / #Sim2Real)
#      except the retirement records: ad_Hardtech-BioMed/RETIRED.json, the Store's appstore-retired-apps.json and 1_cicd/src/data/release-orphan-guard.json
#   R2 the word sim2real at all appears only where it is a real name today: Hardtech-CAD's Sim2Real section (its folder, the shared lnktree tester template
#      with its four copies, the emulator walk of that section, the coverage notes, the SuperApp's retirement note and the BioMed README that points to it)
#   R3 the old folder and workflow are gone (ad_Hardtech-Sim2Real, ship-hardtech-sim2real.yml in .github, 1_cicd/src and 1_cicd/dist)
#   R4 the retirement record exists and is complete: old application id, old name, the replacement package, a date and a reason
# then plants a stray in a scratch tree and requires RED for each rule.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <root> -> FAIL lines
python3 - "$1" <<'PY'
import json, os, re, subprocess, sys
root = sys.argv[1]
bad = []
IDENT = re.compile(r"hardtech[-_ ]?sim2real|ad_hardtech-sim2real|proj_sim2real|#sim2real", re.I)
ANY = re.compile(r"sim2real", re.I)
RECORDS = {"ad_Hardtech-BioMed/RETIRED.json", "ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-retired-apps.json", "1_cicd/src/data/release-orphan-guard.json"}
TESTS = {"ad_Hardtech-BioMed/test/test-no-sim2real.sh"}
CAD = re.compile(r"^(ad_Hardtech-CAD/(build\.json|test/|app/src/main/java/.*/Pages\.kt)|1_cicd/src/templates/test-lnktree-app\.sh|ad_[A-Za-z-]+/test/test-lnktree-app\.sh"
                 r"|(1_cicd/(src|dist)/cicd|\.github/workflows)/hardtech-nav-screens\.yml|1_cicd/(src|dist)/scripts/hardtech-nav-walk\.py|1_cicd/(src|dist)/data/test-coverage\.json"
                 r"|aa_cloud-superapp/build\.json|ad_Hardtech-BioMed/README\.md|licenses/curated\.json)$|^ad_Hardtech-CAD/")
SKIP = {".git", "z_archive", "build", "node_modules", ".gradle", "intermediates"}
def files():
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"], capture_output=True, timeout=120)
        if out.returncode == 0 and out.stdout:
            for f in out.stdout.decode().split("\0"):
                if f and os.path.isfile(os.path.join(root, f)) and not f.startswith("z_archive/"): yield f
            return
    except Exception: pass
    for d, ds, fs in os.walk(root):
        ds[:] = [x for x in ds if x not in SKIP]
        for f in fs: yield os.path.relpath(os.path.join(d, f), root)
for f in files():
    try: text = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
    except OSError: continue
    if f.endswith((".glb", ".png", ".jpg", ".apk", ".so", ".jar")): continue
    if IDENT.search(text) and f not in RECORDS and f not in TESTS: bad.append("R1 %s spells the old identity (outside a retirement record)" % f)
    elif ANY.search(text) and f not in RECORDS and f not in TESTS and not CAD.match(f): bad.append("R2 %s mentions sim2real outside Hardtech-CAD's Sim2Real section" % f)
    if IDENT.search(f) or "Sim2Real" in f:
        if f not in RECORDS: bad.append("R3 %s still carries the old name in its path" % f)
if os.path.isdir(os.path.join(root, "ad_Hardtech-Sim2Real")): bad.append("R3 the folder ad_Hardtech-Sim2Real still exists")
rp = os.path.join(root, "ad_Hardtech-BioMed/RETIRED.json")
try:
    r = json.load(open(rp))
    for k in ("package", "name", "replacement_package", "replacement_name", "retired", "reason"):
        if not str(r.get(k, "")).strip(): bad.append("R4 RETIRED.json has no %s" % k)
    if r.get("package") != "com.diegonmarcos.hardtechsim2real": bad.append("R4 RETIRED.json does not retire com.diegonmarcos.hardtechsim2real")
    if r.get("replacement_package") != "com.diegonmarcos.hardtechbiomed": bad.append("R4 RETIRED.json does not name com.diegonmarcos.hardtechbiomed as the replacement")
    if not re.match(r"^\d{4}-\d{2}-\d{2}$", str(r.get("retired", ""))): bad.append("R4 RETIRED.json date is not YYYY-MM-DD")
except Exception as e:
    bad.append("R4 RETIRED.json is missing or not JSON: %s" % e)
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] no old identity outside the retirement records (#1777)"
out="$(check "$ROOT")" && ok "R1-R4 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
scratch() { rm -rf "${W:?}/t"; mkdir -p "$W/t/ad_Hardtech-BioMed" "$W/t/ad_Hardtech-CAD"; cp "$APP/RETIRED.json" "$W/t/ad_Hardtech-BioMed/"; echo '{"x":"Sim2Real"}' > "$W/t/ad_Hardtech-CAD/build.json"; }
mutant() {   # mutant <name> <shell edit in the scratch tree> <needle>
  scratch; ( cd "$W/t" && eval "$2" )
  if o="$(check "$W/t" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$o"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $o"; fi
}
scratch; if o="$(check "$W/t" 2>&1)"; then ok "the scratch baseline is green"; else bad "the scratch baseline is red: $o"; fi
mutant "the old id comes back in a manifest"  "echo 'com.diegonmarcos.hardtechsim2real' > a.xml"                                  "R1 a.xml"
mutant "the old name comes back in docs"      "echo 'see Hardtech-Sim2Real' > README.md"                                           "R1 README.md"
mutant "sim2real appears outside CAD"         "echo 'sim2real' > other.json"                                                       "R2 other.json"
mutant "the old folder survives"              "mkdir ad_Hardtech-Sim2Real"                                                         "R3 the folder"
mutant "an old-named file survives"           "echo x > ship-hardtech-sim2real.yml"                                                "R3 ship-hardtech-sim2real.yml"
mutant "the retirement record loses its date" "python3 -c \"import json;p='ad_Hardtech-BioMed/RETIRED.json';d=json.load(open(p));d['retired']='soon';json.dump(d,open(p,'w'))\""  "R4 RETIRED.json date"
mutant "the retirement record is deleted"     "rm ad_Hardtech-BioMed/RETIRED.json"                                                 "R4 RETIRED.json is missing"
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
