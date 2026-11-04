#!/usr/bin/env bash
# #1405 Cloud Store > Cloud > Ventoy, statically (no build, no device, no network):
#   V1 build.json::ventoy places the tab after a DECLARED constellation group (after_group, the SuperApp's
#      constellation.groups) and agrees with the catalogue asset (after_group, label, catalogue file name)
#   V2 the catalogue: the four tables in order; every link and feed https; Android Mobile rows are DEVICE links
#      ("flash, not Ventoy"); Ventoy itself is its GitHub release checked against the release's sha256 file, and
#      Copy installer names the Linux and Windows archives
#   V3 the build carries the placement: libs:appstore bakes VENTOY_AFTER_GROUP from the CONSUMING app's build.json and
#      StoreCloudFragment draws ventoy.Content for that tab
#   V4 no spoofed User-Agent and https only: no setRequestProperty / User-Agent in ventoy/, VentoyNet refuses non-https
#   V5 the phone's own storage is not reachable: every drive write is SafDriveFs, whose init refuses a tree that is not
#      the root of a removable volume; the page never opens a java.io.File or an Environment path
#   V6 the module graph: libs:usb-drive is a Store module and libs:appstore depends on it; storeUnitTests runs
#      :libs:appstore:testDebugUnitTest (the Ventoy suites)
#   V7 the raw install says what it is: VentoyRawInstall.WHY is drawn, and the erase button is enabled only by
#      VentoyRawInstall.AVAILABLE
#   V8 (#1444) the install runs through the Cloud Terminal: ventoy-build.py formats with mkfs.exfat over https, the
#      terminal's rootfs declares exfatprogs/mkfs.exfat, VentoyRawInstall drives it through StoreExec and writes with
#      RawDiskWriter, and nothing but the typed-confirm gate (VentoyGate.arm) reaches it; no other file opens a raw disk
#   V9 (#1444) the distro list is the Apps list's rows: VentoyPage draws through StoreRow/StoreVerb/StoreQuick, no copy
# Then every planted defect must turn it red.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
CHECK=$(cat <<'PY'
import json, os, re, sys
R = sys.argv[1]
def need(c, msg):
    if not c: print("  FAIL  " + msg); sys.exit(1)
A = os.path.join(R, "ab_cloud-libs-shared/libs/appstore")
V = os.path.join(A, "src/main/java/com/diegonmarcos/superapp/appstore/ventoy")
bj = json.load(open(os.path.join(R, "ac_cloud-store/build.json")))
cat = json.load(open(os.path.join(A, "src/main/assets/appstore-ventoy.json")))
sa = json.load(open(os.path.join(R, "aa_cloud-superapp/build.json")))
v = bj.get("ventoy") or {}
groups = [g["id"] for g in (sa.get("constellation", {}).get("groups") or [])]
need(v.get("after_group") in groups, "V1 build.json ventoy.after_group names a declared constellation group: %s" % v.get("after_group"))
need(v.get("after_group") == cat.get("after_group") and v.get("label") == cat.get("label"), "V1 build.json ventoy agrees with appstore-ventoy.json (after_group, label)")
need(v.get("catalogue") == "appstore-ventoy.json", "V1 build.json ventoy.catalogue names the asset")
need([t["id"] for t in cat["tables"]] == ["linux", "android-desktop", "android-mobile", "customised"], "V2 the four tables, in order")
https = lambda u: isinstance(u, str) and u.startswith("https://")
for t in cat["tables"]:
    for r in t["rows"]:
        need(https(r["link"]), "V2 %s: link is https" % r["id"])
        s = r.get("source") or {"type": "link"}
        if s["type"] in ("sums", "json"): need(https(s["feed"]), "V2 %s: feed is https" % r["id"])
        if t["id"] == "android-mobile": need(r.get("kind") == "device" and s["type"] == "link", "V2 %s: Android Mobile rows are device links (flash, not Ventoy)" % r["id"])
vs = cat["ventoy"]["source"]
need(vs["type"] == "github" and vs["feed"] == "ventoy/Ventoy" and vs.get("sums_asset"), "V2 Ventoy is its GitHub release, checked against the release's sha256 file")
ia = " ".join(cat["ventoy"]["installer_assets"])
need("linux" in ia and "windows" in ia, "V2 Copy installer names the Linux and Windows archives")
g = open(os.path.join(A, "build.gradle")).read()
need('file("${rootDir}/build.json")' in g and "VENTOY_AFTER_GROUP" in g and "ventoyDecl?.after_group" in g, "V3 libs:appstore bakes VENTOY_AFTER_GROUP from the consuming app's build.json")
f = open(os.path.join(A, "src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt")).read()
need("BuildConfig.VENTOY_AFTER_GROUP" in f and "ventoy.Content(ctx)" in f, "V3 StoreCloudFragment places the tab from BuildConfig and draws ventoy.Content")
src = {n: open(os.path.join(V, n)).read() for n in os.listdir(V) if n.endswith(".kt")}
for n, s in src.items():
    need("setRequestProperty" not in s and '"User-Agent"' not in s and "addRequestProperty" not in s, "V4 %s sets no User-Agent" % n)
need("require(VentoyCatalogue.isOfficialUrl(url))" in src["VentoyNet.kt"] and 'u.protocol != "https"' in src["VentoyNet.kt"], "V4 VentoyNet refuses non-https, redirects included")
need(re.search(r"init \{[^}]*isDriveTree", src["SafDriveFs.kt"]) is not None, "V5 SafDriveFs refuses a tree that is not a removable drive's root")
page = src["VentoyPage.kt"]
need(not re.search(r"\bFile\(|Environment\.|getExternalStorage|openFileOutput", page), "V5 the Ventoy page opens no java.io.File / Environment path")
need("SafDriveFs(" in page and "VentoyTarget.targets(" in page, "V5 the page lists VentoyTarget's drives and writes through SafDriveFs")
mods = bj["modules"]
need("libs:usb-drive" in mods and "libs:usb-drive" in mods["libs:appstore"].get("depends_on", []), "V6 libs:usb-drive is a Store module and libs:appstore depends on it")
rg = open(os.path.join(R, "ac_cloud-store/build.gradle")).read()
need("':libs:appstore:testDebugUnitTest'" in rg, "V6 storeUnitTests runs the libs:appstore suites")
need("VentoyRawInstall.WHY" in page and "VentoyRawInstall.AVAILABLE && drive != null" in page, "V7 the page states the raw-install limit and only AVAILABLE enables erase")
py = open(os.path.join(A, "src/main/assets/ventoy-build.py")).read()
need('"mkfs.exfat"' in py and 'startswith("https://")' in py and "/dev/" not in py.replace("never /dev/anything", ""), "V8 ventoy-build.py formats with mkfs.exfat, https only, touches no device node")
rj = json.load(open(os.path.join(R, "ac_cloud-termux/rootfs/rootfs.json")))
need("exfatprogs" in rj["apt_packages"] and "mkfs.exfat" in rj["binaries"], "V8 the Cloud Terminal rootfs declares exfatprogs, mkfs.exfat rostered (verify-inside runs it)")
ri = src["VentoyRawInstall.kt"]
need("StoreExec(ctx)" in ri and "exec.job(" in ri and "RawDiskWriter.open(" in ri and "fun install(armed: VentoyGate.Armed" in ri, "V8 VentoyRawInstall builds through StoreExec and writes with RawDiskWriter, only for an Armed token")
need(re.search(r"VentoyGate\.arm\(d, typed, shown\)[^}]*rawInstall\(armed\)", page, re.S) is not None and len(re.findall(r"rawInstall\(", page)) == 2, "V8 the page reaches rawInstall only from the typed-confirm gate")
others = [n for n, t in src.items() if n != "VentoyRawInstall.kt" and "RawDiskWriter" in t]
need(not others, "V8 no other ventoy file opens a raw disk: %s" % others)
need("StoreRow.Item(" in page and "StoreVerb(" in page and "StoreQuick(" in page and "StoreRow.Actions(" in page, "V9 the Ventoy lists are StoreRow rows with their buttons in the expand")
need("OutlinedButton" not in page and "FilterChip(" not in page and "Surface(" not in page, "V9 the page keeps no private card/button/chip copies")
print("  PASS  V1-V9")
PY
)
fails=0
python3 -I -c "$CHECK" "$ROOT" || fails=$((fails+1))

T=$(mktemp -d)
FILES="ac_cloud-store/build.json ac_cloud-store/build.gradle aa_cloud-superapp/build.json ab_cloud-libs-shared/libs/appstore/build.gradle
ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-ventoy.json
ab_cloud-libs-shared/libs/appstore/src/main/assets/ventoy-build.py ac_cloud-termux/rootfs/rootfs.json
ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt
ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ventoy"
copy() { for p in $FILES; do mkdir -p "$T/m/$(dirname "$p")"; cp -r "$ROOT/$p" "$T/m/$p"; done; }
mutate() { python3 -I -c 'import sys; p, a, b = sys.argv[1:]; a, b = a.replace(chr(92) + "n", chr(10)), b.replace(chr(92) + "n", chr(10)); s = open(p).read(); assert a in s, "anchor missing: " + a; open(p, "w").write(s.replace(a, b, 1))' "$T/m/$1" "$2" "$3"; }
VD=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ventoy
CA=ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-ventoy.json
proven=0; stayed=0
while IFS='|' read -r name file a b; do
  [ -z "$name" ] && continue
  rm -rf "$T/m"; copy
  mutate "$file" "$a" "$b" || { echo "  FAIL  mutant $name: anchor missing"; stayed=$((stayed+1)); continue; }
  if python3 -I -c "$CHECK" "$T/m" >/dev/null 2>&1; then echo "  FAIL  mutant stayed green: $name"; stayed=$((stayed+1)); else proven=$((proven+1)); fi
done <<MUTANTS
unknown-group|ac_cloud-store/build.json|"after_group": "libs-t-ml"|"after_group": "libs-x-ml"
asset-disagrees|$CA|"after_group": "libs-t-ml"|"after_group": "libs"
table-order|$CA|"id": "android-mobile"|"id": "android-mobiles"
http-link|$CA|"link": "https://ubuntu.com/download/desktop"|"link": "http://ubuntu.com/download/desktop"
http-feed|$CA|"feed": "https://releases.ubuntu.com/24.04/SHA256SUMS"|"feed": "http://releases.ubuntu.com/24.04/SHA256SUMS"
mobile-installable|$CA|"note": "Device image: flash, not Ventoy.",\n     "kind": "device"|"note": "Device image: flash, not Ventoy.",\n     "kind": "iso"
no-sums|$CA|"sums_asset": "sha256\\\\.txt"|"sums_asset": ""
bake|ab_cloud-libs-shared/libs/appstore/build.gradle|ventoyDecl?.after_group|ventoyDecl?.group
draw|ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt|ventoy.Content(ctx)|Unit
ua|$VD/VentoyNet.kt|requestMethod = method;|requestMethod = method; setRequestProperty("User-Agent", "Mozilla/5.0");
http-ok|$VD/VentoyNet.kt|u.protocol != "https"|u.protocol == "ftp"
saf-guard|$VD/SafDriveFs.kt|if (!VentoyTarget.isDriveTree(tree.authority, treeId))|if (false)
page-file|$VD/VentoyPage.kt|private fun volumes(ctx: Context) =|private val f = java.io.File("/sdcard")\n    private fun volumes(ctx: Context) =
module|ac_cloud-store/build.json|"libs:core",\n        "libs:usb-drive",|"libs:core",
unit|ac_cloud-store/build.gradle|':libs:appstore:testDebugUnitTest'|':libs:core:testDebugUnitTest'
erase-enabled|$VD/VentoyPage.kt|VentoyRawInstall.AVAILABLE && drive != null|true && drive != null
no-exfatprogs|ac_cloud-termux/rootfs/rootfs.json|"fuse3",\n    "exfatprogs"|"fuse3"
vfat|ab_cloud-libs-shared/libs/appstore/src/main/assets/ventoy-build.py|["mkfs.exfat", "-L"|["mkfs.vfat", "-L"
page-copy|$VD/VentoyPage.kt|StoreQuick("Install"|Unit.also("Install"
raw-elsewhere|$VD/VentoyPage.kt|private fun remove(path: String) {|private val w = RawDiskWriter::class\n    private fun remove(path: String) {
no-gate|$VD/VentoyPage.kt|else rawInstall(armed)|else rawInstall(armed); rawInstall(armed)
MUTANTS
rm -rf "$T"
[ "$stayed" -eq 0 ] && echo "  PASS  $proven mutant(s) proven red" || fails=$((fails+1))
[ $fails -eq 0 ] && echo "test-store-ventoy: all checks passed" || { echo "test-store-ventoy: $fails FAILED"; exit 1; }
