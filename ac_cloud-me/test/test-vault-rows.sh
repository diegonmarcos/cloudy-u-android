#!/usr/bin/env bash
# test-vault-rows.sh — #1398 Tasks rows (Inboxes | Backlogs | RoadMaps | Library | Projects) from the notes vault.
#   T1 data/repos/vault-rows.json is exactly the render of its snapshot + build.json::ui.vault_rows (--check).
#   T2 folder-to-row mapping on a fixture vault: 0.x -> Inboxes, 00.x and 1.N -> Backlogs (row roots excluded),
#      1.1.x -> RoadMaps (an orphan 1.1.10.1 anchors on 1.1), 2.x -> Library with 2.x.y as its sub-folders, natural order.
#   T3 label cleanup: number prefix and surrounding underscores stripped, "_" -> space.
#   T4 Projects is Lnktree's Proj group: build.json names that group (no page typed for Tasks), app/build.gradle takes the
#      pages and page files from the named section/group, and ui.sections[tasks] declares only its two lead pages.
#   Mutations: each check must go red on its own break, on a scratch copy.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok() { echo "  PASS: $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

mapping() { # <app dir>: one line per violation
  python3 - "$1" <<'PY'
import importlib.util, json, os, sys
app = sys.argv[1]
spec = importlib.util.spec_from_file_location("g", os.path.join(app, "data/regen-vault-rows.py")); g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
decl = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))["ui"]["vault_rows"]
snap = ["0___Inbox___/", "0.1_Mental-Models___/", "0.1.1_attachments/", "0.10_Z/", "0.2_Raw/", "0.2_Raw/a.md", "0.2_Raw/b.md",
        "1___Workflows___/", "1.0_Backlogs___/", "00.1_Ideas/", "1.0_U_Cloudy/", "1.1_RoadMaps___/", "1.1_U_Cloud-me/", "1.10___42___/",
        "1.10.1_PROJ_42/", "1.1.00_General/", "1.1.0_U_Cloudy/", "1.1.10.1_PROJ_42-Career/", "1.1.2_CG/",
        "2___Library___/", "2.1_Wisdom___/", "2.1.2_Stoicism/", "2.1.10_Astrology/", "2.2_Tech___/", "2.2.1_UI/", "_dotfiles/", "a_docs/"]
t = g.render(snap, decl)
rows = {r["id"]: r for r in t["rows"]}
want = {"inboxes": ["0.1", "0.2", "0.10"], "backlogs": ["00.1", "1.0", "1.1", "1.10"],
        "roadmaps": ["1.1.00", "1.1.0", "1.1.2", "1.1.10.1"], "library": ["2.1", "2.2"]}
if [r["id"] for r in t["rows"]] != ["inboxes", "backlogs", "roadmaps", "library"]: print("row order is not Inboxes, Backlogs, RoadMaps, Library")
for k, v in want.items():
    got = [c["num"] for c in rows.get(k, {}).get("chips", [])]
    if got != v: print(f"{k}: chips {got}, want {v}")
lib = {c["num"]: [x["num"] for x in c.get("children", [])] for c in rows["library"]["chips"]}
if lib != {"2.1": ["2.1.2", "2.1.10"], "2.2": ["2.2.1"]}: print(f"library sub-folders {lib}")
if any("children" in c for k in ("inboxes", "backlogs", "roadmaps") for c in rows[k]["chips"]): print("a non-sub row lists sub-folders")
if rows["inboxes"]["chips"][1]["notes"] != ["a", "b"]: print("notes of 0.2_Raw are not [a, b]")
if any(c["page"] != "v" + c["num"].replace(".", "-") for r in t["rows"] for c in r["chips"]): print("a page id is not v<number>")
labels = {"0.1_Mental-Models___": "Mental-Models", "1.10___42___": "42", "0.6_Instagram_Collections": "Instagram Collections",
          "2___Library___": "Library", "1.1.10.1_PROJ_42-Career": "PROJ 42-Career", "1.1_U_Cloud-me": "U Cloud-me"}
for n, w in labels.items():
    if g.label(n) != w: print(f"label({n}) = {g.label(n)!r}, want {w!r}")
shipped = json.load(open(os.path.join(app, "data/repos/vault-rows.json"), encoding="utf-8"))
import re
if any("___" in c["label"] or re.match(r"^\d+(\.\d+)*_", c["label"]) for r in shipped["rows"] for c in r["chips"]): print("a shipped label keeps a number or ___")
PY
}

projects() { # <app dir>
  python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
ui = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))["ui"]
secs = {s["id"]: s for s in ui["sections"]}
pj = ui.get("vault_rows", {}).get("projects", {})
src = secs.get(pj.get("section"), {})
g = next((x for x in src.get("groups", []) if x["id"] == pj.get("group")), None)
if pj.get("section") != "lnktree" or g is None or pj.get("group") != ui.get("repo_tree", {}).get("group"): print("Projects does not name the Lnktree Proj group")
if [p["id"] for p in secs["tasks"]["pages"]] != ["roadmaps", "backlogs"] or "groups" in secs["tasks"]: print("Tasks types its own pages or groups")
gr = open(os.path.join(app, "app/build.gradle"), encoding="utf-8").read()
if "area-" in gr: print("app/build.gradle types an area page")
if not re.search(r"src\?\.groups\?\.find \{ it\.id == pj\.group \}", gr) or "g.pages.each" not in gr or 'file("../data/ui/${pj.section}/${pid}.json")' not in gr:
    print("app/build.gradle does not take the Projects pages and files from the named group")
if "vaultSection(s0)" not in gr: print("the Tasks rows are not baked into UI_SECTIONS_B64")
PY
}

echo "== T1 vault-rows.json is the render of its snapshot =="
if (cd "$APP" && python3 data/regen-vault-rows.py --check >/dev/null); then ok "vault-rows.json current"; else bad "vault-rows.json drifted — run ./data/regen-vault-rows.py"; fi
echo "== T2/T3 mapping and labels =="
r="$(mapping "$APP")"; if [ -z "$r" ]; then ok "folders map to rows, labels are clean"; else bad "mapping"; echo "$r" | sed 's/^/        /'; fi
echo "== T4 Projects is the Lnktree Proj group =="
r="$(projects "$APP")"; if [ -z "$r" ]; then ok "Projects reuses Lnktree's declaration"; else bad "projects"; echo "$r" | sed 's/^/        /'; fi

echo "== mutations =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mk() { rm -rf "$W/a"; mkdir -p "$W/a/data/repos" "$W/a/app"; cp "$APP/data/regen-vault-rows.py" "$W/a/data/"; cp "$APP"/data/repos/vault-rows.* "$W/a/data/repos/"; cp "$APP/build.json" "$W/a/"; cp "$APP/app/build.gradle" "$W/a/app/"; }
red() { mk; (cd "$W/a" && eval "$3") >/dev/null 2>&1; if [ -z "$($1 "$W/a")" ]; then bad "NOT red: $2"; else ok "red: $2"; fi; }
red_check() { mk; (cd "$W/a" && eval "$2") >/dev/null 2>&1; if (cd "$W/a" && python3 data/regen-vault-rows.py --check >/dev/null 2>&1); then bad "NOT red: $1"; else ok "red: $1"; fi; }
red_check "a vault folder added without a re-render" "echo '0.12_New/' >> data/repos/vault-rows.snapshot.txt"
red_check "the generated rows edited by hand" "sed -i 's/\"Inboxes\"/\"Inbox\"/' data/repos/vault-rows.json"
red mapping "anchor is the immediate parent (orphans dropped)" "sed -i 's/    p = parent(num)\$/    return parent(num)/' data/regen-vault-rows.py"
red mapping "row roots no longer excluded" "sed -i 's/if f not in roots and anchor/if anchor/' data/regen-vault-rows.py"
red mapping "plain string order (1.10 before 1.2)" "sed -i 's/key=lambda f: nat(number(f))/key=lambda f: f/' data/regen-vault-rows.py"
red mapping "Library loses its sub-folders" "sed -i 's/\"sub\": true/\"sub\": false/' build.json"
red mapping "Backlogs stops reading 00.x" "sed -i 's/\"under\": \[\"00\", \"1\"\]/\"under\": [\"1\"]/' build.json"
red mapping "labels keep the ___ (no trimming)" "sed -i 's/rest.strip(\"_\")/rest/; s/)).strip()\$/))/' data/regen-vault-rows.py"
red mapping "labels keep the number" "sed -i 's/rest = m.group(2) if m else name/rest = name/' data/regen-vault-rows.py"
red mapping "underscores stay in labels" "sed -i 's/.replace(\"_\", \" \")//' data/regen-vault-rows.py"
red projects "Projects points at another group" "sed -i 's/\"group\": \"proj\"}/\"group\": \"pers\"}/' build.json"
red projects "Tasks copies the area pages into build.json" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'].append({'id':'area-unix'}) for s in d['ui']['sections'] if s['id']=='tasks'];json.dump(d,open('build.json','w'))\""
red projects "gradle types the Projects pages" "sed -i 's/g.pages.each { pid ->/[\"area-unix\", \"area-cg\"].each { pid ->/' app/build.gradle"
red projects "the rows are not baked" "sed -i 's/vaultSection(s0)/s0/' app/build.gradle"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
