#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1839 Rule 16 field parity: the Filters tab moved, nothing was removed ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The Filters page (the VR mode) left the bottom nav for an icon on the Camera page, and its slot became Scanner.
# BASELINE below is what the bottom nav and the Filters screen carried BEFORE #1839 (taken from build.json,
# activity_main.xml, strings.xml and vr-filters.json at that commit). This tester proves that every one of those fields is
# still there, and that the move itself is wired:
#   P1 every section the nav declared is still declared, with the same label, icon and pages; the four other bar items are
#      still on the bar in the same order; the only change of the bar is Scanner in the Filters slot; the default is unchanged.
#   P2 the Filters screen keeps every view it had (vr_view, vr_panel, vr_status, vr_filters, vr_record), every string, the
#      CameraMode.VR entry, the controller / recorder / snapshot files and the declared filters (all 15 ids, same kinds).
#   P3 the Filters button on the Camera page opens THAT screen (finalizeMode(CameraMode.VR)); VR stays on the swipe order.
#   P4 Scanner is the fifth bar item, opens ScannerActivity (declared in the manifest, with its FileProvider), has its icon,
#      and its permission goes through libs:perms (no raw ACTION_*_SETTINGS); page ids stay unique (N12).
# Each check is then run against planted mutations; a mutation that leaves every check green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys
import xml.etree.ElementTree as ET

APP = os.environ["APP_DIR"]
A = "{http://schemas.android.com/apk/res/android}"

BASELINE = {
    "bar": ["qr_scan", "objects", "photo", "video", "vr"],
    "default": "photo",
    "sections": {
        "qr_scan": ("QR scan", "qr_scan", []),
        "objects": ("Objects", "objects", []),
        "photo": ("Camera", "photo", ["auto", "face_retouch", "portrait", "night", "hdr", "camera"]),
        "video": ("Video", "video", []),
        "vr": ("VR", "vr", []),
    },
    "vr_views": ["vr_view", "vr_panel", "vr_status", "vr_filters", "vr_record"],
    "vr_strings": ["vr_mode", "vr_pick", "vr_no_face", "vr_record", "vr_stop", "vr_saved_photo", "vr_saved_video", "vr_save_failed", "vr_catalog_failed"],
    "vr_files": ["vr/VrController.kt", "vr/VrRecorder.kt", "vr/VrSnapshot.kt"],
    "filters": ["hat_party", "hat_crown", "glasses_round", "glasses_shades", "mask_bandit", "mask_cat", "bg_blur", "bg_sunset", "bg_green",
                "lut_warm", "lut_cool", "lut_vivid", "lut_mono", "sticker_star", "sticker_heart_food"],
}
SRC = "app/src/main/java/cld/camera/"
FILES = {
    "build": "build.json",
    "layout": "app/src/main/res/layout/activity_main.xml",
    "strings": "app/src/main/res/values/strings.xml",
    "main": SRC + "ui/activities/MainActivity.kt",
    "nav": SRC + "ui/ModeNav.kt",
    "cam": SRC + "CamConfig.kt",
    "manifest": "app/src/main/AndroidManifest.xml",
    "scanner": SRC + "scanner/ScannerActivity.kt",
    "filters": "../data/fleet/app/cld.camera/vr-filters.json",
}


def code(text):
    """Kotlin with comments blanked (string literals kept, so "image/*" is not a comment), so prose cannot satisfy a check."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(text[i:j]); i = j; continue
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)


def load():
    f = {k: open(os.path.join(APP, p), encoding="utf-8").read() for k, p in FILES.items()}
    f["files"] = "\n".join(p for p in BASELINE["vr_files"] if os.path.exists(os.path.join(APP, SRC, p)))
    f["icon"] = "yes" if os.path.exists(os.path.join(APP, "app/src/main/res/drawable/ic_mode_scanner.xml")) else ""
    scan_dir = os.path.join(APP, SRC, "scanner")
    f["scan_code"] = "\n".join(open(os.path.join(scan_dir, n), encoding="utf-8").read() for n in sorted(os.listdir(scan_dir)) if n.endswith(".kt"))
    return f


def page_ids(pages):
    for p in pages or []:
        yield p["id"]
        yield from page_ids(p.get("pages"))


def checks(f):
    bad = []
    def need(label, ok):
        if not ok:
            bad.append(label)
    try:
        ui = json.loads(f["build"])["ui"]
        secs = {s["id"]: s for s in ui["sections"]}
        flt = json.loads(f["filters"])["filters"]
        views = {e.get(A + "id").split("/")[-1] for e in ET.fromstring(f["layout"]).iter() if e.get(A + "id")}
    except (ValueError, KeyError, ET.ParseError):
        return ["build.json / vr-filters.json / activity_main.xml is unreadable"]
    # P1
    for sid, (label, icon, pages) in BASELINE["sections"].items():
        s = secs.get(sid)
        need("P1 section %s is still declared" % sid, s is not None)
        if s is not None:
            need("P1 section %s keeps its label %r" % (sid, label), s.get("label") == label)
            need("P1 section %s keeps its icon %r" % (sid, icon), s.get("icon") == icon)
            need("P1 section %s keeps its pages %s" % (sid, pages), [p["id"] for p in s.get("pages", [])] == pages)
    bar = ui["bottom_nav"]
    need("P1 the four other bar items keep their order", bar[:4] == BASELINE["bar"][:4])
    need("P1 the bar is those four and Scanner in the Filters slot", bar == BASELINE["bar"][:4] + ["scanner"])
    need("P1 the default section is unchanged", ui.get("default_section") == BASELINE["default"])
    ids = list(page_ids([p for s in ui["sections"] for p in s.get("pages", [])]))
    need("P4 page ids are unique (N12)", len(ids) == len(set(ids)))
    # P2
    for v in BASELINE["vr_views"]:
        need("P2 the Filters screen keeps view %s" % v, v in views)
    for s in BASELINE["vr_strings"]:
        need("P2 the Filters screen keeps string %s" % s, 'name="%s"' % s in f["strings"])
    need("P2 CameraMode.VR is still an entry", re.search(r"\bVR\(ExtensionMode\.NONE, R\.string\.vr_mode\)", code(f["cam"])) is not None)
    for p in BASELINE["vr_files"]:
        need("P2 %s is still there" % p, p in f["files"])
    have = {x["id"]: x.get("kind") for x in flt}
    for fid in BASELINE["filters"]:
        need("P2 the declared filter %s is still declared" % fid, fid in have)
    # P3
    main = code(f["main"])
    need("P3 the Filters button exists on the Camera page", "filters_option" in views)
    need("P3 the Filters button opens the VR screen", "finalizeMode(CameraMode.VR)" in main and "filtersIcon.setOnClickListener" in main)
    need("P3 VR stays reachable by the swipe order", "allSections" in code(f["nav"]) and "decl.sections.filter" in code(f["nav"]))
    # P4
    need("P4 Scanner is a declared section", "scanner" in secs and secs["scanner"].get("icon") == "scanner")
    need("P4 the island opens ScannerActivity", "ScannerActivity.intent(this)" in main and "onLaunch" in code(f["nav"]))
    need("P4 ScannerActivity is in the manifest", ".scanner.ScannerActivity" in f["manifest"])
    need("P4 the scan FileProvider is in the manifest", ".scanshare" in f["manifest"] and "FILE_PROVIDER_PATHS" in f["manifest"])
    need("P4 the Scanner icon exists", f["icon"] == "yes")
    sc = code(f["scan_code"])
    need("P4 the camera permission goes through libs:perms", "PermsLink.open(" in sc)
    need("P4 no raw ACTION_*_SETTINGS in the Scanner (perms guard P7)", re.search(r"ACTION_[A-Z_]*SETTINGS", sc) is None)
    return bad


def mutate(f, key, fn):
    g = dict(f); before = g[key]; g[key] = fn(before); assert g[key] != before, "no-op mutation on " + key
    return g


def edit_json(fn):
    def go(t):
        d = json.loads(t); fn(d); return json.dumps(d)
    return go


real = load()
fails = checks(real)
for x in fails:
    print("FAIL     " + x)
if fails:
    sys.exit(1)
print("PASS     bottom nav and Filters screen: every baseline field present, Filters button wired, Scanner in the fifth slot")

M = [
    ("the VR section is dropped", "build", edit_json(lambda d: d["ui"].__setitem__("sections", [s for s in d["ui"]["sections"] if s["id"] != "vr"]))),
    ("the Camera family loses a page", "build", edit_json(lambda d: [s.__setitem__("pages", s["pages"][1:]) for s in d["ui"]["sections"] if s["id"] == "photo"])),
    ("the Objects icon changes", "build", edit_json(lambda d: [s.__setitem__("icon", "x") for s in d["ui"]["sections"] if s["id"] == "objects"])),
    ("the bar loses Video", "build", edit_json(lambda d: d["ui"]["bottom_nav"].remove("video"))),
    ("the bar keeps VR beside Scanner", "build", edit_json(lambda d: d["ui"]["bottom_nav"].insert(4, "vr"))),
    ("the default moves", "build", edit_json(lambda d: d["ui"].__setitem__("default_section", "video"))),
    ("a page id is declared twice", "build", edit_json(lambda d: [s["pages"].append({"id": "auto", "label": "x"}) for s in d["ui"]["sections"] if s["id"] == "photo"])),
    ("the VR panel is removed", "layout", lambda t: t.replace('android:id="@+id/vr_panel"', 'android:id="@+id/vr_panel_gone"')),
    ("the record button is removed", "layout", lambda t: t.replace('android:id="@+id/vr_record"', 'android:id="@+id/vr_rec"')),
    ("a Filters string is removed", "strings", lambda t: t.replace('name="vr_pick"', 'name="vr_pick_gone"')),
    ("CameraMode loses VR", "cam", lambda t: t.replace("VR(ExtensionMode.NONE, R.string.vr_mode)", "")),
    ("a declared filter is removed", "filters", edit_json(lambda d: d["filters"].pop(0))),
    ("the Filters button opens nothing", "main", lambda t: t.replace("finalizeMode(CameraMode.VR)", "")),
    ("the Filters button is gone", "layout", lambda t: t.replace('android:id="@+id/filters_option"', 'android:id="@+id/other"')),
    ("VR drops off the swipe order", "nav", lambda t: t.replace("allSections", "bottomOnly")),
    ("the island stops opening the Scanner", "main", lambda t: t.replace("ScannerActivity.intent(this)", "null")),
    ("the manifest forgets the Scanner", "manifest", lambda t: t.replace(".scanner.ScannerActivity", ".scanner.Gone")),
    ("the FileProvider is dropped", "manifest", lambda t: t.replace(".scanshare", ".other")),
    ("the Scanner asks for the camera on its own", "scan_code", lambda t: t.replace("PermsLink.open(", "startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)); x(")),
    ("the Scanner icon is missing", "icon", lambda t: ""),
]
survivors = []
for name, key, fn in M:
    try:
        caught = bool(checks(mutate(real, key, fn)))
    except AssertionError:
        print("FAIL     mutation is a no-op: " + name); survivors.append(name); continue
    if caught:
        print("PASS     mutation caught: " + name)
    else:
        survivors.append(name); print("FAIL     mutation survived: " + name)
if survivors:
    sys.exit(1)
PY
