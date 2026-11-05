#!/usr/bin/env bash
# test-profile-sot: #1445 Cloud Me's profile declaration is THE source of truth of Cloud Account's Personal Info and Configs.
#
# Holds that:
#  1. SHAPE: ac_cloud-me/data/profile/account-ui.json declares Personal Info = IDs, Pay Cards, Others (Entries, Me),
#     Autocomplete (Personal, Sites, Snippets) and Configs = Android (Cloud Apps, Phone Apps, Runbook), Desktop,
#     C3 (Infra, Containers), Repos, and every leaf carries a `render`;
#  2. MIRROR: libs:account-ui's account-ui.json (what both apps bake) is byte-equal to it;
#  3. NO DRIFT: Cloud Account's build.json::ui personal and configs match it page for page (id, label, icon);
#  4. DATA DRIVEN: the router (AccountUi.kt) mounts exactly the renderers the declaration names, no more, no fewer,
#     and the Personal Info field kinds (fields, masks) are declared (`kinds`), a number is masked;
#  5. PUBLIC REPO, PRIVATE DATA: the declaration holds no value (no long digit run, e-mail, token or key) and its
#     connector paths stay inside the repo.
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOT="ac_cloud-me/data/profile/account-ui.json"
MIR="ab_cloud-libs-shared/libs/account-ui/account-ui.json"
ACC="ac_cloud-account/build.json"
RT="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/superapp/accountui/AccountUi.kt"
FILES="$SOT $MIR $ACC $RT"

check() {
python3 - "$1" "$SOT" "$MIR" "$ACC" "$RT" <<'PY'
import json, os, re, sys
R, SOT, MIR, ACC, RT = sys.argv[1:6]
fails = 0
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
sot = json.loads(rd(SOT))
sec = {s["id"]: s for s in sot["sections"]}
def ids(ps): return [p["id"] for p in ps]
def sub(s, p): return ids(next(x for x in sec[s]["pages"] if x["id"] == p).get("pages", []))
ok(list(sec) == ["personal", "configs"], "two top-level sections: Personal Info, Configs", f"sections are {list(sec)}")
ok(ids(sec["personal"]["pages"]) == ["ids", "cards", "others", "autocomplete"], "Personal Info = IDs, Pay Cards, Others, Autocomplete", "Personal Info pages drifted")
ok(sub("personal", "others") == ["entries", "me"] and sub("personal", "autocomplete") == ["personal", "sites", "snippets"], "Others and Autocomplete sub-pages", "Others / Autocomplete sub-pages drifted")
ok(ids(sec["configs"]["pages"]) == ["android", "desktop", "c3", "repos"], "Configs = Android, Desktop, C3, Repos", "Configs pages drifted")
ok(sub("configs", "android") == ["cloud-apps", "phone-apps", "runbook"] and sub("configs", "c3") == ["infra", "containers"], "Android and C3 sub-pages", "Android / C3 sub-pages drifted")
def leaves(ps):
    for p in ps:
        if p.get("pages"): yield from leaves(p["pages"])
        else: yield p
lv = [p for s in sot["sections"] for p in leaves(s["pages"])]
ok(all(p.get("render") for p in lv), "every leaf declares its renderer", "a leaf has no render")
ok(rd(SOT) == rd(MIR), "libs:account-ui's account-ui.json is byte-equal to Cloud Me's SoT", "the mirror differs from Cloud Me's SoT (copy the SoT over it)")
def shape(ps): return [(p["id"], p["label"], p.get("icon"), shape(p.get("pages") or [])) for p in ps]
acc = {s["id"]: s for s in json.loads(rd(ACC))["ui"]["sections"]}
# #1482 Cloud Account re-files the SoT: Configs' pages are its bottom-nav Config (page for page), Personal Info's leaves are in its Vault
# (IDs, PayCards, Entries, Autocomplete) and Devices (Me), same ids and icons; its own `configs` is General | Perms | About.
def lf(ps): return {p["id"]: p.get("icon") for p in leaves(ps)}
acc_leaves = {**lf(acc["vault"]["pages"]), **lf(acc["devices"]["pages"])}
ok("config" in acc and shape(acc["config"]["pages"]) == shape(sec["configs"]["pages"]) and all(acc_leaves.get(i) == ic for i, ic in lf(sec["personal"]["pages"]).items()),
   "Cloud Account's Config equals Cloud Me's Configs page for page and its Vault / Devices hold every Personal Info leaf", "Cloud Account's build.json::ui drifted from Cloud Me's SoT")
rt = re.sub(r"(?m)^\s*//[^\n]*", "", rd(RT))
routed = set(re.findall(r'^\s*"([a-z]+:[a-z-]+)" ->', rt[rt.index("fun AccountUiPage"):rt.index("object AccountUiTags")], re.M))
declared = {p["render"] for p in lv}
ok(routed - {"configs:cloud-apps"} == declared - {"configs:cloud-apps"} and "else -> CloudAppsPage()" in rt, "the router mounts exactly the declared renderers", f"router/declaration differ: {sorted(routed ^ declared)}")
k = sot.get("kinds", {})
ok(set(k) == {"ids", "cards", "others"} and all(set(v.get("masked", [])) <= set(v.get("fields", [])) and v.get("fields") for v in k.values()), "the field kinds are declared", "kinds missing or inconsistent")
ok("number" in k.get("ids", {}).get("masked", []) and "number" in k.get("cards", {}).get("masked", []) and "value" in k.get("others", {}).get("masked", []), "ID number, card number and Others value are masked", "a sensitive field lost its mask")
strings = []
def walk(o):
    if isinstance(o, dict):
        for v in o.values(): walk(v)
    elif isinstance(o, list):
        for v in o: walk(v)
    elif isinstance(o, str): strings.append(o)
walk({k2: v for k2, v in sot.items() if not k2.startswith("_")})
bad = [s[:30] for s in strings if re.search(r"\d{9,}|[\w.+-]+@[\w-]+\.[a-z]{2,}|ghp_|github_pat_|-----BEGIN|sk-[A-Za-z0-9]{16,}", s)]
ok(not bad, "the declaration holds no value (no long number, e-mail, token or key)", f"the declaration holds value-like strings: {bad}")
cons = sot["configs_sources"].get("connectors", [])
ok(cons and all(re.fullmatch(r"[\w.-]+", c["repo"]) and c["paths"] and all(p and not p.startswith("/") and ".." not in p.split("/") for p in c["paths"]) for c in cons), "connectors name a repo and safe relative paths", "a connector is malformed")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
M=0
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"; mkdir -p "$TMP/r"
  for f in $FILES; do mkdir -p "$TMP/r/$(dirname "$f")"; cp "$ROOT/$f" "$TMP/r/$f"; done
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; M=1; return; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if old not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "mirror drifts from the SoT" "$MIR" '"label": "Pay Cards"' '"label": "Cards"'
mutate "SoT drifts from the mirror" "$SOT" '"label": "Pay Cards"' '"label": "Cards"'
mutate "Cloud Account drifts" "$ACC" '"label": "Phone Apps"' '"label": "Phones"'
mutate "a page leaves Configs" "$SOT" '"id": "repos"' '"id": "repos2"'
mutate "a renderer is dropped" "$SOT" '"render": "configs:infra"' '"render": ""'
mutate "the router loses a renderer" "$RT" '"configs:infra" -> InfraPage()' '"configs:infra2" -> InfraPage()'
mutate "a card number loses its mask" "$SOT" '"masked": [
        "number"
      ],
      "numeric"' '"masked": [],
      "numeric"'
mutate "an e-mail enters the declaration" "$SOT" '"title": "ID documents"' '"title": "someone@example.com"'
mutate "a connector escapes the repo" "$SOT" '"profile/configs.json"' '"../configs.json"'
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ] || { echo "FAIL"; exit 1; }
echo "ok: Cloud Me's profile is the SoT; each guard proven red"
