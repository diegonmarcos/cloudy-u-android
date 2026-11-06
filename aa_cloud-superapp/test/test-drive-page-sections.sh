#!/usr/bin/env bash
# Drive is no longer a section of this app. Task 302/304 lifted it out into its
# own APK (ac_cloud-drive, com.diegonmarcos.clouddrive), whose Files tab is a
# real local file manager rather than a list of remote back ends. Everything the
# SuperApp still owns about Drive is a LAUNCH: the Data Apps tile and the left
# handle's `down` sector both fire the package.
#
# The failures this exists to catch are the three ways an extraction goes wrong:
# a surface that was deleted here but still referenced (a tile or a route that
# resolves to nothing — a dead tap), data that was deleted here and not carried
# to the app that now owns it (silent loss of the twelve storage back ends), and
# the one-hand picker pair drifting apart, which is the documented root cause of
# tasks 111, 127 and 284.
#
# What this asserts:
#   T1  the Data Apps group still leads with Drive, and that tile now targets
#       `extapp:cloud-drive` — NOT the `drive` tile in Cloud ▸ AGI, which is
#       MyTerminal files and a different thing entirely
#   T2  the extraction is COMPLETE on this side: no `drive` section, no
#       `drive_connections` panel kind, no DriveConnectionsFragment, no
#       DRIVE_CONNECTIONS_B64 — a leftover route with no fragment is a crash and
#       a leftover fragment with no route is dead weight that still compiles
#   T3  `extapp:cloud-drive` resolves — ui.external_apps carries the entry, with
#       the real package on both hub_package and install_package, and an APK url
#       that names its tag explicitly (`/download/<tag>/`, never
#       `/releases/latest/download/`, which any per-app release hijacks into a
#       flapping 404)
#   T4  every app in the Data Apps row carries a target the tile dispatcher has
#       a branch for, so no icon in that row can be inert
#   T5  PM Boards stays removed — #311 deleted the tile from Projects W, which
#       held its only definition
#   T6  the twelve back ends were MOVED, not dropped: gone from this build.json,
#       present in ac_cloud-drive/data/drive-connections.json, and actually
#       carried into that APK (gradle bakes it, Declarations decodes it, the
#       Home page reads it)
#   T7  the one-hand pair is byte-identical — the left handle's `down` sector and
#       the matching circular_menu.actions option. When they diverge the picker
#       finds no match, falls back to index 0 and shows a working slot as 'None',
#       one tap from persisting None over it
#   T8  the verb list T4 checks against is READ OUT OF the dispatcher, not
#       retyped here — a `when` that grows or loses a branch moves the assertion
#       with it instead of leaving T4 green against a shell that no longer
#       agrees with it
#   T9  a tap that reaches launchUri cannot throw out of the click handler. An
#       uncaught throw there is a crash on a tap — strictly worse than the icon
#       doing nothing — so every parse and every launch in that function has to
#       sit inside a runCatching, and the function has to end by SAYING the tap
#       failed rather than returning silently.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
SHELL_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
DRIVE_APP="$REPO/ac_cloud-drive"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== Drive lives in its own APK, and nothing here still points at the old one =="

# The verbs the router actually branches on, read off the `when` itself.
# It lives in routeTarget, which is onTileClicked minus the click bookkeeping
# — the split exists so the shell can re-enter the router mid-tap without
# the re-entry counting as a second tap.
# Retyping them here is how T4 would go on passing against a dispatcher that
# had since dropped a branch — the row would still "match" a prefix this file
# remembers and the shell no longer honours. One list, and it lives in the
# Kotlin.
DISPATCH_PREFIXES="$(
  awk '/^    override fun routeTarget\(tileId: String\) \{/{f=1} f{print} f&&/^    \}$/{exit}' \
      "$SHELL_KT" |
  grep -o 'tileId\.startsWith("[^"]*")' | sed 's/.*("//;s/")//' | sort -u
)"
if [ -z "$DISPATCH_PREFIXES" ]; then
  bad "T8: read no startsWith prefixes out of routeTarget — T4 would assert nothing"
else
  ok "T8: T4 checks against $(printf '%s\n' "$DISPATCH_PREFIXES" | wc -l | tr -d ' ') prefixes read out of routeTarget"
fi

export DISPATCH_PREFIXES
RESULT="$(python3 - "$APP" "$DRIVE_APP" <<'PY'
import json, os, sys

app, drive_app = sys.argv[1], sys.argv[2]
build = json.load(open(os.path.join(app, "build.json")))
ui = build["ui"]
raw = open(os.path.join(app, "build.json")).read()

def emit(test, message):
    print("%s\t%s" % (test, message))

# ── T1 ────────────────────────────────────────────────────────────────────────
# The tile row is named by its group, so the assertion is about the group, not
# about a position in some flattened list. Groups hang off their section.
all_groups = [(section, group)
              for section in ui.get("sections", [])
              for group in section.get("tile_groups", [])]
data_apps = next((g for s, g in all_groups
                  if s.get("id") == "cloud" and g.get("title") == "Data Apps"), None)
if data_apps is None:
    emit("T1", "Cloud ▸ Apps ▸ Data Apps no longer exists — every check below it is vacuous")
    tiles = []
else:
    tiles = data_apps.get("tiles", [])
    if not tiles:
        emit("T1", "Data Apps declares no tiles")
    else:
        first = tiles[0]
        if first.get("id") != "drive-conn":
            emit("T1", "Data Apps leads with %r, not the drive-conn tile" % first.get("id"))
        if first.get("target") != "extapp:cloud-drive":
            emit("T1", "the leading Drive tile targets %r — it must launch the extracted "
                       "APK as extapp:cloud-drive" % first.get("target"))

# The AGI `drive` tile is a different thing (MyTerminal files) and must not have been
# swept up by the rename.
for _section, group in all_groups:
    for tile in group.get("tiles", []):
        if tile.get("id") == "drive" and tile.get("target") == "extapp:cloud-drive":
            emit("T1", "the Cloud ▸ AGI `drive` tile (MyTerminal files) was retargeted at the "
                       "Drive APK — it is a different page")

# ── T2 ────────────────────────────────────────────────────────────────────────
if any(s.get("id") == "drive" for s in ui.get("sections", [])):
    emit("T2", "ui.sections still declares a `drive` section — it was lifted into its own APK")
if "drive_connections" in ui:
    emit("T2", "ui.drive_connections is still here — the data moved to the Drive app")

src = os.path.join(app, "app/src/main/java/com/diegonmarcos/superapp")
leftovers = []
for root, _dirs, files in os.walk(src):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(root, name)
        text = open(path).read()
        for token in ("DriveConnectionsFragment", "DRIVE_CONNECTIONS_B64",
                      '"drive_connections"', 'pageId == "connections"'):
            if token in text:
                leftovers.append("%s in %s" % (token, os.path.relpath(path, app)))
for leftover in leftovers:
    emit("T2", "the extraction left %s behind" % leftover)

gradle = open(os.path.join(app, "app/build.gradle")).read()
if "DRIVE_CONNECTIONS_B64" in gradle:
    emit("T2", "app/build.gradle still bakes DRIVE_CONNECTIONS_B64 from data this app no longer has")

# ── T3 ────────────────────────────────────────────────────────────────────────
externals = {entry.get("id"): entry for entry in ui.get("external_apps", [])}
drive = externals.get("cloud-drive")
if drive is None:
    emit("T3", "ui.external_apps has no `cloud-drive` entry — every extapp:cloud-drive "
               "target resolves to nothing")
else:
    package = "com.diegonmarcos.clouddrive"
    for field in ("hub_package", "install_package"):
        if drive.get(field) != package:
            emit("T3", "cloud-drive.%s is %r, not %r" % (field, drive.get(field), package))
    url = drive.get("install_apk_url", "")
    if "/releases/latest/download/" in url:
        emit("T3", "cloud-drive's APK url uses /releases/latest/download/ — any per-app "
                   "tagged release hijacks that route into a flapping 404")
    if "/download/" not in url:
        emit("T3", "cloud-drive's APK url %r does not name a release asset" % url)
    folders = {e.get("folder") for e in ui.get("external_apps", []) if e.get("id") != "cloud-drive"}
    if drive.get("folder") not in folders:
        emit("T3", "cloud-drive sits in folder %r, which no other external app uses — "
                   "the launcher has nowhere to file it" % drive.get("folder"))

# ── T4 / T5 ───────────────────────────────────────────────────────────────────
prefixes = [p for p in os.environ.get("DISPATCH_PREFIXES", "").split("\n") if p]
for tile in tiles:
    target = tile.get("target", "")
    if tile.get("label", "").strip() in ("|", ""):
        continue                       # the separator character, not an app
    if not target:
        emit("T4", "the %r tile in Data Apps carries no target — it renders and does nothing"
                   % tile.get("id"))
    elif prefixes and not any(target.startswith(p) for p in prefixes) \
            and "://" not in target:
        # routeTarget's last branch is a scheme catch-all: anything carrying
        # "://" that is not one of the internal verbs goes to launchUri, which
        # parses it as a URI. That is how the `app://…?fallback=…` tiles
        # dispatch, so a bare startsWith sweep would call them inert.
        emit("T4", "the %r tile targets %r, which matches no branch in routeTarget — "
                   "it renders, it is tappable, and the tap is inert"
                   % (tile.get("id"), target))
    if "pmboards" in (tile.get("id") or "").lower():
        emit("T5", "PM Boards is back in Data Apps — #311 deleted its only definition")

# ── T6 ────────────────────────────────────────────────────────────────────────
moved = os.path.join(drive_app, "data/drive-connections.json")
if not os.path.exists(moved):
    emit("T6", "the twelve storage back ends are gone from here and absent from "
               "ac_cloud-drive/data/drive-connections.json — the move dropped them")
else:
    connections = json.load(open(moved))
    if not isinstance(connections, list) or not connections:
        emit("T6", "ac_cloud-drive/data/drive-connections.json holds no back ends")
    else:
        required = {"name", "kind", "endpoint", "status"}
        for entry in connections:
            missing = required - set(entry)
            if missing:
                emit("T6", "the %r back end lost %s in the move"
                           % (entry.get("name"), ", ".join(sorted(missing))))
    # Present in the repo is not the same as present in the APK.
    carriers = [
        ("app/build.gradle", 'CONNECTIONS_B64'),
        # Drive is native now (no WebView bridge / drive.html): Declarations
        # decodes the baked list and the Home page reads it from there.
        ("app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt", "parseConnections(decode(BuildConfig.CONNECTIONS_B64))"),
        ("app/src/main/java/com/diegonmarcos/clouddrive/volumes/VolumeClasses.kt", "Declarations.connections"),  # #1534 Home deleted: the Volumes Overview groups every back end
    ]
    for relative, token in carriers:
        path = os.path.join(drive_app, relative)
        if not os.path.exists(path) or token not in open(path).read():
            emit("T6", "the Drive app's %s does not carry %s — the data sits in the repo "
                       "and never reaches the phone" % (relative, token))

# ── T7 ────────────────────────────────────────────────────────────────────────
onehand = build.get("onehand", {})
handles = {h.get("id"): h for h in onehand.get("handles", [])}
left = handles.get("left", {})
sector = left.get("gestures", {}).get("down")
options = [a.get("target") for a in onehand.get("circular_menu", {}).get("actions", [])]
if sector is None:
    emit("T7", "the left handle declares no `down` sector")
elif sector not in options:
    emit("T7", "the left handle's `down` sector is %r and circular_menu.actions offers "
               "%r — the picker finds no match, falls back to index 0 and shows a working "
               "slot as 'None'" % (sector, options))
elif "clouddrive" not in sector:
    emit("T7", "the left handle's `down` sector is %r — it used to open Drive and must "
               "now launch the extracted package" % sector)
PY
)" || { echo "  FAIL: the build.json checker crashed"; exit 1; }

for label in T1 T2 T3 T4 T5 T6 T7; do
  lines="$(printf '%s\n' "$RESULT" | awk -F'\t' -v t="$label" '$1==t {print $2}')"
  if [ -z "$lines" ]; then
    case "$label" in
      T1) ok "T1: Data Apps leads with the Drive tile, and it launches extapp:cloud-drive" ;;
      T2) ok "T2: the Drive section, its data, its fragment and its BuildConfig field are all gone" ;;
      T3) ok "T3: extapp:cloud-drive resolves to a real external app with a tagged APK url" ;;
      T4) ok "T4: every Data Apps tile carries a target routeTarget has a branch for" ;;
      T5) ok "T5: PM Boards stays removed" ;;
      T6) ok "T6: the twelve back ends moved to the Drive app and reach its APK" ;;
      T7) ok "T7: the left handle's down sector and its picker option are byte-identical" ;;
    esac
  else
    printf '%s\n' "$lines" | sed 's/^/    /'
    bad "$label"
  fi
done

# ── T9 ────────────────────────────────────────────────────────────────────────
# Notes targets `obsidian://open`, which Intent.parseUri turns into a plain
# ACTION_VIEW, and startActivity on that raises ActivityNotFoundException
# on every phone that does not have Obsidian. The same is true of the app://
# branch's store fallback and of any intent:// whose browser_fallback_url has
# no handler either. Guarding them is not optional: an uncaught throw on the
# click handler kills the launcher, which is a worse answer than the icon
# doing nothing.
T9="$(python3 - "$SHELL_KT" <<'PY'
import re, sys

src = open(sys.argv[1]).read().splitlines()
try:
    start = next(i for i, l in enumerate(src)
                 if l.startswith(("    private fun launchUri(uri: String) {", "    internal fun launchUri(uri: String) {")))
except StopIteration:
    print("launchUri is gone or was renamed — this whole check is asserting nothing")
    raise SystemExit

end = next(i for i in range(start + 1, len(src)) if src[i] == "    }")
body = src[start:end + 1]

bad = []
# Brace-depth of the innermost open runCatching, or None when outside one.
guard = None
depth = 0
for line in body:
    for tok in re.findall(r'runCatching|startActivity\(|Intent\.parseUri\(|[{}]', line):
        if tok == "runCatching":
            if guard is None:
                guard = depth
        elif tok == "{":
            depth += 1
        elif tok == "}":
            depth -= 1
            if guard is not None and depth <= guard:
                guard = None
        elif guard is None:
            bad.append(tok.rstrip("("))

for name in dict.fromkeys(bad):
    print("%s is called outside any runCatching — a target with no installed "
          "handler throws straight out of the tap" % name)

if not any("snack(" in l for l in body[-6:]):
    print("launchUri no longer ends by saying the tap failed — a target that "
          "nothing handles would go silently dead")
PY
)" || { echo "  FAIL: launchUri checker crashed"; exit 1; }
if [ -z "$T9" ]; then
  ok "T9: every parse and launch in launchUri is caught, and a dead target says so"
else
  printf '%s\n' "$T9" | sed 's/^/    /'
  bad "T9: launchUri can throw out of a tap"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
