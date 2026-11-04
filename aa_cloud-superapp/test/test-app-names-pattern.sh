#!/usr/bin/env bash
# Tester (#351): every application in the constellation has ONE name, it is
# written cloud-<name> (or c3-<name> for the c3 family), and no surface spells it
# any other way.
#
# THE FAILURE THIS EXISTS FOR. Configs ▸ Constellation listed
#   "Cloud Chat (Mattermost)", "Cloud Dialer (Fossify)", "Cloud Mail",
#   "Cloud Office", "Cloud Terminal (Nix)", "Cloud Terminal (Termux)", "Messenger"
# beside cloud-tasks, cloud-browser and the rest. The owner had asked for the
# pattern twice before. The root cause was not the list: fork-apps declared their
# name in .forks.<key>.label while top-level apps used .name, and the superapp
# restated every name again in ui.external_apps, onehand.apps,
# ui.ai_routing_peers, tiles and floating_nav — so a rename fixed one copy and
# the other copies kept the old spelling.
#
# The declaration is each application's own build.json::.name. regen.sh reads
# it into constellation-fleet.json for BOTH branches, and the fleet file is what
# this tester trusts as the list of names.
#
#   T1  every fleet entry of kind "app" is named cloud-<x> or c3-<x>, unique
#   T2  that name IS the app's build.json::.name, and no fork entry of the same
#       package declares a second name in .forks.<key>.label
#   T3  ui.external_apps restates no name: label absent (the id is shown) and the
#       id is a fleet name
#   T4  any object that names a fleet package (package/pkg) with a label uses the
#       fleet name for it
#   T5  sweep: no label/title/name anywhere in those build.json files spells a
#       fleet name the old way ("Cloud Chat (Mattermost)", "Cloud-IDE",
#       "C3-Watchdog") — the regression a new hand-written roster would bring back
#
# #1335 THE PATTERN IS DECLARED, NOT SPELLED HERE. Every application is named by the pattern of its FAMILY, declared once in
# 1_cicd/src/data/app-families.json (the Cloud family: cloud-<x> / c3-<x>; the Lnktree family, folders ad_*: cg-<x>,
# capital-<x>, hardtech-<x>). T1 reads that file, so a new family is one declared entry and not a bypass: a name that matches
# NO declared family still fails, and so does a name in a family other than the one its folder belongs to.
#
# Scope: kind "app". Library APKs ("Lib: <module>") are a separate naming family
# and are not applications. Captions that name a FUNCTION rather than an app
# (tiles "Notes", "Mail", the "Messenger" inbox class) do not spell an app name
# and are left alone by T5 on purpose.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"                    # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → repository root
FLEET="$APP/data/constellation-fleet.json"
for f in "$FLEET" "$APP/build.json"; do
  [ -f "$f" ] || { echo "ERROR: $f missing — this tester resolved a path to nothing" >&2; exit 2; }
done
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }

python3 - "$ROOT" "$FLEET" "$APP/build.json" "${FAMILIES:-$ROOT/1_cicd/src/data/app-families.json}" <<'PY'
import glob, json, os, re, sys

root, fleet_path, superapp_path, families_path = sys.argv[1:5]
FAMILIES = json.load(open(families_path))["families"]
FAMILY_PATTERNS = [re.compile(f["name_pattern"]) for f in FAMILIES]
class _AnyFamily:
    """The name pattern of every declared family: a name is well formed when ONE family's pattern accepts it."""
    def match(self, text):
        return next((m for m in (p.match(text) for p in FAMILY_PATTERNS) if m), None)
PATTERN = _AnyFamily()
passed, failed = 0, 0
def check(condition, message):
    global passed, failed
    if condition:
        passed += 1
    else:
        failed += 1
        print("  FAIL: " + message)

fleet = [entry for entry in json.load(open(fleet_path))["apps"] if entry.get("kind") == "app"]
check(len(fleet) > 0, "constellation-fleet.json lists no application — nothing below would be checked")
names = [entry["label"] for entry in fleet]
by_package = {}
for entry in fleet:
    for package in (entry.get("package"), entry.get("alt_id")):
        if package:
            by_package[package] = entry["label"]

print("== T1: every application is named by its family's pattern (cloud-<x> / c3-<x>, or the Lnktree family's) ==")
for entry in fleet:
    check(PATTERN.match(entry["label"]) is not None,
          "fleet entry %s is labelled %r, which no declared app family accepts (1_cicd/src/data/app-families.json)" % (entry["id"], entry["label"]))
check(len(names) == len(set(names)), "two applications share a name: %s"
      % sorted({n for n in names if names.count(n) > 1}))

print("== T2: the name is declared once, in the app's own build.json::.name ==")
build_files = {}
tree = "/tree/main/"
for entry in fleet:
    directory = entry["repo_url"].split(tree, 1)[1] if tree in entry["repo_url"] else ""
    path = os.path.join(root, directory, "build.json")
    if not os.path.isfile(path):
        check(False, "fleet entry %s points at %s, which has no build.json" % (entry["id"], directory))
        continue
    data = json.load(open(path))
    build_files[directory] = data
    check(data.get("name") == entry["label"],
          "%s/build.json::name is %r but the fleet shows %r — regenerate data/regen.sh or fix the declaration"
          % (directory, data.get("name"), entry["label"]))
    for key, fork in (data.get("forks") or {}).items():
        if isinstance(fork, dict) and fork.get("app_id") == entry["package"] and "label" in fork:
            check(False, "%s/build.json::forks.%s.label = %r is a second name for %s — delete it, .name is the name"
                  % (directory, key, fork["label"], entry["package"]))

superapp = json.load(open(superapp_path))
build_files["aa_cloud-superapp"] = superapp

print("== T3: ui.external_apps shows the id and restates no name ==")
for app in superapp["ui"]["external_apps"]:
    check("label" not in app or app["label"] == app["id"],
          "ui.external_apps[%s].label = %r restates the name — delete it, the id is shown" % (app["id"], app.get("label")))
    check(app["id"] in names, "ui.external_apps id %r is not the name of any fleet application" % app["id"])

def walk(node, path=""):
    if isinstance(node, dict):
        yield path, node
        for key, value in node.items():
            yield from walk(value, path + "." + key)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk(value, "%s[%d]" % (path, index))

print("== T4: a label next to one of our packages is that application's name ==")
for directory, data in sorted(build_files.items()):
    for path, node in walk(data):
        package = node.get("package") if isinstance(node.get("package"), str) else node.get("pkg")
        label = node.get("label")
        if isinstance(package, str) and package in by_package and isinstance(label, str):
            check(label == by_package[package],
                  "%s/build.json%s.label = %r, but %s is named %r" % (directory, path, label, package, by_package[package]))

print("== T5: nothing spells an application name the old way ==")
def normalise(text):
    return re.sub(r"[^a-z0-9]", "", text.lower())
normalised_names = [normalise(n) for n in names]
# The display name ("Cloud Browser") is the one sanctioned human spelling; it is
# declared once per app in build.json::display_name and checked by
# test-launcher-labels.sh, so T5 must not flag it where a flag restates it.
display_names = {d.get("display_name") for d in build_files.values()}
# #733: a name only matches where it ENDS ON A WORD BOUNDARY of the caption.
# Stripping spaces made "Cloud Mesh" read as "cloudmesh", which starts with
# cloud-me's "cloudme" — a caption naming the mesh, not the app. The boundaries
# are where the caption's own words end, so "Cloud Chat (Mattermost)" and
# "CloudChat" still match cloud-chat and "Cloud Mesh" no longer matches cloud-me.
def word_ends(text):
    ends, n = set(), 0
    for word in re.findall(r"[a-z0-9]+", text.lower()):
        n += len(word); ends.add(n)
    return ends
# A project/area tab is not an application: a page whose id AND label are one project declared in the app's own
# data/container_projects.json (the owner's tabs All | Cloudy | CG | Capital | Hardtech | Cloud-me) names a project.
# The exemption is exact (same id, same label, same app); any other caption still has to clear the sweep.
project_tabs = {}
for directory in build_files:
    cp_path = os.path.join(root, directory, "data", "container_projects.json")
    if os.path.isfile(cp_path):
        project_tabs[directory] = {(p["id"], p["label"]) for p in json.load(open(cp_path)).get("projects", [])}
for directory, data in sorted(build_files.items()):
    for path, node in walk(data):
        for key in ("label", "title", "name", "--with-app-name"):
            value = node.get(key)
            if not isinstance(value, str) or PATTERN.match(value) or value in display_names:
                continue
            if key == "label" and (node.get("id"), value) in project_tabs.get(directory, ()):
                continue
            flat = normalise(value)
            if len(flat) <= len("cloud") + 1:
                continue
            ends = word_ends(value)
            if any((flat.startswith(n) and len(n) in ends) or n.startswith(flat) for n in normalised_names):
                check(False, "%s/build.json%s.%s = %r spells an application name outside the pattern"
                      % (directory, path, key, value))

print("== T6: an app whose gradle reads its build.json takes its launcher label from .display_name ==")
# The home-screen label is the one surface every other check above cannot see:
# it lived in res/values/strings.xml and said "Cloud Browser". Where
# app/build.gradle already parses the app's own build.json, the label can be
# inherited, so a strings.xml copy is a defect. Fork-apps whose label sits inside
# vendored upstream source (Mattermost, Element, Fossify, Termux) have no such
# reader and are not covered here.
#
# #522: THE GRADLE MODULE PATH IS DATA, NOT A CONVENTION. This block used to
# look only at <dir>/app/build.gradle. ac_cloud-notes has never had that path —
# its Android project is nested inside a vendored monorepo at
# packages/frontend/apps/android/App/app — so T6 did not fail on it, it SKIPPED
# it, silently, and a launcher tile with the wrong label shipped behind 18 green
# checks. An app that needs a different path DECLARES it in
# build.json::android.gradle_module; "app" is the default nobody has to write.
#
# #470: "reads its build.json" means the file() argument names build.json. The
# pattern used to stop at `file(`, so ANY JSON a gradle file parsed counted —
# ac_cloud-termux reading rootfs/rootfs.json for an asset directory name was
# pulled into T6 and failed for a launcher label that lives in vendored
# Termux strings, exactly the fork case the comment above excludes. Measured
# on the tree at the time: the narrower pattern covers the same 12 apps minus
# ac_cloud-termux, and nothing else.
#
# A module may be Groovy (build.gradle) or Kotlin DSL (build.gradle.kts): the
# Tauri template ac_cloud-c3-webserver builds with is the latter, and a T6 that
# opened build.gradle only would skip it in silence — the #522 failure again.
# In Kotlin DSL the parsed JSON is a Map, so the label reads buildJson["name"].
# Its OWN build.json only: a path that climbs into another application (file("../../ac_cloud-drive/build.json"),
# read by ac_cloud-gallery for Cloud Drive's shared storage root) is a cross-app read, not the label's source.
reads_build_json = re.compile(r"JsonSlurper\(\)\s*\.parse\(\s*file\((?![^)]*\bac_)[^)]*build\.json")
derives_label = re.compile(r"resValue\s*\(?\s*[\"']string[\"']\s*,\s*[\"']app_name[\"']\s*,\s*buildJson(\.display_name\b|\[\s*\"display_name\"\s*\])")
def module_gradle(directory, module):
    for name in ("build.gradle", "build.gradle.kts"):
        path = os.path.join(root, directory, module, name)
        if os.path.isfile(path):
            return path
    return None
covered = 0
for directory in sorted(build_files):
    data = build_files[directory]
    module = (data.get("android") or {}).get("gradle_module") or "app"
    gradle = module_gradle(directory, module)
    if gradle is None or not data.get("name", "").startswith("cloud-"):
        continue
    source = open(gradle).read()
    if not reads_build_json.search(source):
        # Fork-apps whose label sits inside vendored upstream source
        # (Mattermost, Element, Fossify, Termux, Nix-on-Droid) have no reader
        # and are out of scope here, exactly as before #522.
        continue
    covered += 1
    check(derives_label.search(source) is not None,
          "%s reads build.json but does not set app_name from buildJson.display_name"
          % os.path.relpath(gradle, root))
    resources = os.path.join(root, directory, module, "src")
    for base, _, files in os.walk(resources):
        if "strings.xml" in files and os.path.basename(base).startswith("values"):
            path = os.path.join(base, "strings.xml")
            if re.search(r'<string\s+name="app_name"', open(path).read()):
                check(False, "%s restates app_name — the launcher label comes from build.json::display_name"
                      % os.path.relpath(path, root))
check(covered > 0, "T6 found no application whose gradle reads its build.json — the detection matched nothing")
# A declared gradle_module that points at nothing is a check that quietly
# stopped guarding: the app would drop out of T6 exactly as it did before #522.
for directory, data in sorted(build_files.items()):
    module = (data.get("android") or {}).get("gradle_module")
    if module:
        check(module_gradle(directory, module) is not None,
              "%s/build.json::android.gradle_module = %r has no build.gradle[.kts] — T6 would skip this app in silence"
              % (directory, module))

print("== T7: a launcher tile shows a caption, never an application's identity ==")
# THE FAILURE THIS EXISTS FOR (#380, #381). Cloud ▸ Apps drew a Data Apps tile
# captioned "cloud-office" and an Inboxes tile captioned "cloud-matrix": the
# tiles printed the applications' IDENTITY because an identity string had been
# left sitting in the display field. On a tile, `label` is a CAPTION; identity
# travels in `target`, in ui.external_apps[].id and in the app's own
# build.json::name, and none of those changed when the captions were fixed.
#
# Nothing above caught it, and that is structural rather than bad luck: T5
# leaves function-captions ("Notes", "Mail") alone ON PURPOSE, and T4 only
# inspects objects carrying a `package`/`pkg` key, which a tile does not have.
# So the one check that would go red if a caption silently reverted to its
# identity string is this one.
#
# #390: the two AGI tiles #380/#381 left behind have captions again, so this
# set is empty. It is the regression's ledger, not a parked list: NEVER ADD TO
# IT — a tile captioned with an application name is the exact regression this
# test exists to catch, and a stale exemption re-opens the hole it closes.
pending_identity_captions = set()

tiles = [node for _, node in walk(superapp["ui"]["sections"])
         if isinstance(node.get("label"), str) and isinstance(node.get("target"), str)]
check(len(tiles) > 0, "T7 found no tiles under ui.sections — the detection matched nothing")
seen_pending = set()
for path, node in walk(superapp["ui"]["sections"], ".ui.sections"):
    label, target = node.get("label"), node.get("target")
    if not isinstance(label, str) or not isinstance(target, str) or label not in names:
        continue
    if node.get("id") in pending_identity_captions:
        seen_pending.add(node["id"])
        continue
    check(False, "aa_cloud-superapp/build.json%s.label = %r captions a tile with an "
                 "application's identity — labels are captions, identity belongs in target/id"
          % (path, label))
# A stale exemption is a check that quietly stopped guarding something.
check(seen_pending == pending_identity_captions,
      "T7 exempts tile ids %s that no longer caption themselves with an identity — delete them "
      "from pending_identity_captions" % sorted(pending_identity_captions - seen_pending))

print("== T8: the AGI terminal tile is captioned by function, not by the upstream app's own name (#499) ==")
# THE FAILURE THIS EXISTS FOR. #390 gave this tile the caption "Nix" — the
# same word Phone > Apps' own quickmark uses for the same package — but that
# word IS the upstream app's own name (ac_cloud-nix-on-droid ships as
# "Nix-on-Droid"/"cloud-terminal-nix" in the fleet), so the caption still
# spelled an application's identity rather than what tapping it does. Every
# other AGI tile with a target spells the FUNCTION (Browser, Navigation,
# Camera, MyTerminal); this one has to as well.
#
# Neither T5 nor T7 can catch a reversion back to "Nix". T5 skips it on
# length: normalise("Nix") == "nix" is shorter than len("cloud")+1, and that
# filter exists so short incidental words do not false-positive T5's prefix
# check. T7 only matches a caption equal to a FULL fleet name
# ("cloud-terminal-nix"), never a three-letter fragment of one. So this is the
# one check that goes red if the caption reverts to the upstream app's name.
agi = next((node for _, node in walk(superapp["ui"]["sections"]) if node.get("title") == "AGI"), None)
check(agi is not None, "T8 found no ui.sections group titled 'AGI' — the detection matched nothing")
nix_tile = next((t for t in agi["tiles"] if t.get("id") == "ai-tmx"), None) if agi else None
check(nix_tile is not None, "T8 found no AGI tile with id 'ai-tmx' — the detection matched nothing")
if nix_tile is not None:
    check(nix_tile.get("label") == "Terminal",
          "aa_cloud-superapp/build.json AGI tile ai-tmx.label = %r, want 'Terminal' — "
          "it must not spell the upstream app's own name" % nix_tile.get("label"))

print("== T9: a retired brand stays retired, and no caption collides inside its group (#561) ==")
# THE CHANGE THIS EXISTS FOR. #561 renamed cloud-ide (launcher "Cloud-IDE",
# asset Cloud-IDE-Hub.apk, AGI tile "MyIDE") to cloud-myterminal. A rename is
# only real if every copy moved, and #351/#522 are the record of copies that
# did not: the one declaration changed and a restated string kept the old
# spelling. So the retired spellings are a LEDGER here, like T7's, and they
# are looked for on every surface a user or the updater reads — never only
# where this commit happened to edit.
#
# Deliberately NOT scanned: _doc/description/comment prose (history is allowed
# to say what a thing used to be called) and on-device state that must keep
# its old name on an installed phone — the WorkManager unique names, the
# notification channel id and the /storage/emulated/0/CloudIDE workspace
# (renaming those orphans a job, the user's channel settings or their files).
# The applicationId com.diegonmarcos.ide is identity, not brand: #405/#495
# measured that moving it forces a manual reinstall, so it stays.
# cloud-calendar -> cloud-agenda (#315) -> cloud-tasks: the calendar app's two
# earlier brands join the ledger. Its applicationId com.diegonmarcos.cloudagenda
# is identity, like com.diegonmarcos.ide, and stays: a match may not start
# right after a '.', so a package name never reads as a brand.
retired = re.compile(r"(?i)(?<![a-z0-9.])(cloud[-_ ]?ide|my[-_ ]?ide|cloud[-_ ]?agenda|cloud[-_ ]?calendar)(?![a-z0-9])")
PROSE = ("description", "comment")
def values(node, path=""):
    if isinstance(node, dict):
        for key, value in node.items():
            if key.startswith("_") or key in PROSE:
                continue
            yield from values(value, path + "." + key)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from values(value, "%s[%d]" % (path, index))
    elif isinstance(node, str):
        yield path, node
scanned = 0
for entry in json.load(open(fleet_path))["apps"]:
    for path, value in values(entry, "[%s]" % entry.get("id")):
        scanned += 1
        retired.search(value) and check(False, "constellation-fleet.json%s = %r spells a retired brand" % (path, value))
for directory, data in sorted(build_files.items()):
    for path, value in values(data):
        scanned += 1
        retired.search(value) and check(False, "%s/build.json%s = %r spells a retired brand" % (directory, path, value))
    module = (data.get("android") or {}).get("gradle_module") or "app"
    for base, _, files in os.walk(os.path.join(root, directory, module, "src")):
        if "strings.xml" in files and os.path.basename(base).startswith("values"):
            xml = os.path.join(base, "strings.xml")
            for name, text in re.findall(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', open(xml).read(), re.S):
                scanned += 1
                retired.search(text) and check(False, "%s <string name=%r> = %r spells a retired brand"
                      % (os.path.relpath(xml, root), name, text))
for ui_json in sorted(glob.glob(os.path.join(root, "*", "data", "ui", "**", "*.json"), recursive=True)):
    for path, value in values(json.load(open(ui_json))):
        scanned += 1
        retired.search(value) and check(False, "%s%s = %r spells a retired brand" % (os.path.relpath(ui_json, root), path, value))
for workflows in ("1_cicd/src/cicd", ".github/workflows"):
    for name in os.listdir(os.path.join(root, workflows)):
        scanned += 1
        retired.search(name) and check(False, "%s/%s ships under a retired brand" % (workflows, name))
# One pass per scan, however many values it read: a hit fails on its own line above.
check(scanned > 0, "T9 scanned no surface — the detection matched nothing")

# #499 shipped an AGI tile captioned "Terminal"; this app's tile sits in the
# same row, so its caption could not be "Terminal" too. The rule is general:
# two tiles in one group with one caption are two doors nobody can tell apart.
groups = 0
for path, node in walk(superapp["ui"]["sections"], ".ui.sections"):
    tiles = node.get("tiles")
    if not isinstance(tiles, list):
        continue
    groups += 1
    captions = [t["label"] for t in tiles if isinstance(t, dict) and isinstance(t.get("label"), str)
                and isinstance(t.get("target"), str)]
    for caption in sorted({c for c in captions if captions.count(c) > 1}):
        check(False, "aa_cloud-superapp/build.json%s has %d tiles captioned %r"
              % (path, captions.count(caption), caption))
check(groups > 0, "T9 found no tile group under ui.sections — the detection matched nothing")

# A companion's first-install URL is hand-written in ui.external_apps, and it
# is the copy a rename leaves behind: it must be the URL the fleet derives
# from that app's own build.json, or a tap on an uninstalled app downloads
# an asset that is no longer published.
fleet_by_name = {entry["label"]: entry for entry in fleet}
for app in superapp["ui"]["external_apps"]:
    entry = fleet_by_name.get(app["id"])
    if entry is None or not app.get("install_apk_url"):
        continue
    check(app["install_apk_url"] == entry["release_url"],
          "ui.external_apps[%s].install_apk_url = %r, but the fleet publishes %s at %r"
          % (app["id"], app["install_apk_url"], app["id"], entry["release_url"]))
    check(app.get("install_package") in (None, entry["package"]),
          "ui.external_apps[%s].install_package = %r, but the fleet installs %r"
          % (app["id"], app.get("install_package"), entry["package"]))

print()
print("── test-app-names-pattern: %d passed, %d failed ──" % (passed, failed))
sys.exit(1 if failed else 0)
PY
