#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1375 — Cloud Drive opens USB-C drives and is in the USB device chooser  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY. Android's "Choose an app for the USB device" lists only apps whose manifest has an activity answering
# USB_DEVICE_ATTACHED with a device_filter matching the drive (mass storage, class 8). Without these lines Cloud Drive
# is simply absent, however good its USB code is. This pins the declaration (UsbVolumeModelTest pins the mapping,
# test-drive-usb-volume-emulator.yml the real removable volume):
#   U1 <uses-feature android.hardware.usb.host required=false>
#   U2 an exported activity: USB_DEVICE_ATTACHED filter + meta-data USB_DEVICE_ATTACHED -> @xml/device_filter
#   U3 res/xml/device_filter.xml: <usb-device class="8"/>
#   U4 an exported receiver: MEDIA_MOUNTED/EJECT/UNMOUNTED/REMOVED/BAD_REMOVAL on the file scheme (= UsbVolumeModel
#      .MEDIA_ACTIONS) and USB_DEVICE_DETACHED
#   U5 the USB grant is libs:perms': catalogue entry usb_host, build.json perms.needs + the generated app-perms.json
#   U6 the raw path: libaums in app/build.gradle (the code itself is libs:usb-drive since #1405) and in the licence inventory
#   U7 no app code asks UsbManager.requestPermission itself (PermsUsb.request does)
# Then every rule is proven: each mutant below is applied to a copy and the checker must fail on it.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
CHECK=$(cat <<'PY'
import json, os, re, sys, xml.etree.ElementTree as ET
R = sys.argv[1]; A = "{http://schemas.android.com/apk/res/android}"
D = os.path.join(R, "ac_cloud-drive"); APP = os.path.join(D, "app/src/main")
def need(c, msg):
    if not c: print("  FAIL  " + msg); sys.exit(1)
man = ET.parse(os.path.join(APP, "AndroidManifest.xml")).getroot()
f = [e for e in man.iter("uses-feature") if e.get(A + "name") == "android.hardware.usb.host"]
need(f and f[0].get(A + "required") == "false", "U1 uses-feature usb.host required=false")
app = man.find("application")
USB = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
acts = [a for a in app.iter("activity") if any(x.get(A + "name") == USB for x in a.iter("action"))]
need(len(acts) == 1, "U2 one activity answers USB_DEVICE_ATTACHED")
a = acts[0]
need(a.get(A + "exported") == "true", "U2 the USB activity is exported")
md = [m for m in a.findall("meta-data") if m.get(A + "name") == USB]
need(md and md[0].get(A + "resource") == "@xml/device_filter", "U2 meta-data USB_DEVICE_ATTACHED -> @xml/device_filter")
kt = os.path.join(APP, "java/com/diegonmarcos/clouddrive", a.get(A + "name").lstrip(".").replace(".", "/") + ".kt")
need(os.path.isfile(kt), "U2 the USB activity class exists: " + kt)
filt = ET.parse(os.path.join(APP, "res/xml/device_filter.xml")).getroot()
need(any(u.get("class") == "8" for u in filt.iter("usb-device")), "U3 device_filter has usb-device class=8")
model = open(os.path.join(R, "ab_cloud-libs-shared/libs/usb-drive/src/main/java/com/diegonmarcos/superapp/usbdrive/UsbVolumeModel.kt")).read()
consts = dict(re.findall(r'const val (ACTION_\w+) = "([^"]+)"', model))
lst = re.search(r"val MEDIA_ACTIONS = listOf\(([^)]*)\)", model)
need(lst, "U4 UsbVolumeModel.MEDIA_ACTIONS")
media = {consts[n.strip()] for n in lst.group(1).split(",") if n.strip()}
need(len(media) == 5, "U4 five media actions in the model")
recv = None
for r in app.iter("receiver"):
    for flt in r.findall("intent-filter"):
        acts_ = {x.get(A + "name") for x in flt.iter("action")}
        schemes = {x.get(A + "scheme") for x in flt.iter("data")}
        if acts_ == media and schemes == {"file"}: recv = r
need(recv is not None, "U4 a receiver with exactly the model's media actions on the file scheme")
need(recv.get(A + "exported") == "true", "U4 the media receiver is exported (system broadcasts)")
need(any(x.get(A + "name") == "android.hardware.usb.action.USB_DEVICE_DETACHED" for x in recv.iter("action")), "U4 the receiver hears USB_DEVICE_DETACHED")
cat = json.load(open(os.path.join(R, "ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json")))
e = [x for x in cat["entries"] if x["id"] == "usb_host"]
need(e and e[0].get("check") == "usb_host" and e[0].get("icon") in cat["icons"], "U5 catalogue entry usb_host (check usb_host, known icon)")
need("usb_host" in json.load(open(os.path.join(D, "build.json")))["perms"]["needs"], "U5 build.json perms.needs has usb_host")
need("usb_host" in json.load(open(os.path.join(APP, "assets/app-perms.json")))["needs"], "U5 generated app-perms.json has usb_host")
need("'me.jahnen.libaums:core:" in open(os.path.join(D, "app/build.gradle")).read(), "U6 libaums in app/build.gradle")
inv = os.path.join(R, "licenses/inventory.json")
need(not os.path.exists(inv) or json.load(open(inv))["entries"].get("maven:me.jahnen.libaums:core", {}).get("licence") == "Apache-2.0", "U6 libaums in the licence inventory as Apache-2.0")
for dp, _, fs in os.walk(os.path.join(APP, "java")):
    for fn in fs:
        if fn.endswith(".kt"):
            need(not re.search(r"\.requestPermission\(\s*\w*[dD]evice", open(os.path.join(dp, fn)).read()), "U7 %s asks the USB permission itself (use PermsUsb.request)" % fn)
bj = json.load(open(os.path.join(D, "build.json")))
ex = set(bj["usb_drives"]["raw_library"].get("native_excludes", []))
need({"**/libusb-lib.so", "**/liberrno-lib.so"} <= ex, "U8 build.json drops libaums' 4 KB JNI libs (native_excludes)")
g = open(os.path.join(D, "app/build.gradle")).read()
need("raw_library?.native_excludes" in g, "U8 app/build.gradle applies native_excludes")
u = os.path.join(R, "ab_cloud-libs-shared/libs/usb-drive/src/main/java/com/diegonmarcos/superapp/usbdrive")
need("JavaUsbCommunication.install()" in open(os.path.join(u, "RawUsbDrive.kt")).read(), "U8 RawUsbDrive installs the JNI-free transport before libaums opens a device")
jc = open(os.path.join(u, "JavaUsbCommunication.kt")).read()
need("UnderlyingUsbCommunication.OTHER" in jc and "throw PipeException()" in jc and "REQ_CLEAR_FEATURE" in jc, "U8 JavaUsbCommunication: OTHER mode, stall -> PipeException, CLEAR_FEATURE halt")
print("  PASS  U1-U8")
PY
)
fails=0
python3 -I -c "$CHECK" "$ROOT" || fails=$((fails+1))

# Mutation proof: each mutant on a copy of exactly the files the checker reads; every one must fail.
T=$(mktemp -d)
copy() { mkdir -p "$T/m"; for p in ac_cloud-drive/app/src/main/AndroidManifest.xml ac_cloud-drive/app/src/main/res/xml/device_filter.xml \
  ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/usb ab_cloud-libs-shared/libs/usb-drive/src/main/java/com/diegonmarcos/superapp/usbdrive ac_cloud-drive/build.json ac_cloud-drive/app/build.gradle \
  ac_cloud-drive/app/src/main/assets/app-perms.json ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json licenses/inventory.json; do
  [ -e "$ROOT/$p" ] || continue; mkdir -p "$T/m/$(dirname "$p")"; cp -r "$ROOT/$p" "$T/m/$p"; done; mkdir -p "$T/m/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive"; }
mutate() { python3 -I -c 'import sys; p, a, b = sys.argv[1:]; a, b = a.replace(chr(92) + "n", chr(10)), b.replace(chr(92) + "n", chr(10)); s = open(p).read(); assert a in s, "anchor missing: " + a; open(p, "w").write(s.replace(a, b, 1))' "$T/m/$1" "$2" "$3"; }
M=ac_cloud-drive/app/src/main/AndroidManifest.xml
proven=0; stayed=0
while IFS='|' read -r name file a b; do
  [ -z "$name" ] && continue
  rm -rf "$T/m"; copy
  mutate "$file" "$a" "$b" || { echo "  FAIL  mutant $name: anchor missing"; stayed=$((stayed+1)); continue; }
  if python3 -I -c "$CHECK" "$T/m" >/dev/null 2>&1; then echo "  FAIL  mutant stayed green: $name"; stayed=$((stayed+1)); else proven=$((proven+1)); fi
done <<MUTANTS
usb-host-required|$M|android:name="android.hardware.usb.host" android:required="false"|android:name="android.hardware.usb.host" android:required="true"
usb-host-missing|$M|<uses-feature android:name="android.hardware.usb.host"|<uses-feature android:name="android.hardware.usb.hostX"
attach-filter|$M|<action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />|<action android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED" />
attach-unexported|$M|android:name=".usb.UsbDriveActivity"\n            android:exported="true"|android:name=".usb.UsbDriveActivity"\n            android:exported="false"
filter-resource|$M|android:resource="@xml/device_filter"|android:resource="@xml/other_filter"
class-8|ac_cloud-drive/app/src/main/res/xml/device_filter.xml|class="8"|class="3"
media-mounted|$M|<action android:name="android.intent.action.MEDIA_MOUNTED" />|
media-scheme|$M|<data android:scheme="file" />|<data android:scheme="content" />
media-detached|$M|<action android:name="android.hardware.usb.action.USB_DEVICE_DETACHED" />|
receiver-unexported|$M|android:name=".usb.UsbVolumeReceiver"\n            android:exported="true"|android:name=".usb.UsbVolumeReceiver"\n            android:exported="false"
model-actions|ab_cloud-libs-shared/libs/usb-drive/src/main/java/com/diegonmarcos/superapp/usbdrive/UsbVolumeModel.kt|ACTION_MEDIA_REMOVED, ACTION_MEDIA_BAD_REMOVAL)|ACTION_MEDIA_REMOVED)
catalogue|ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json|"id": "usb_host"|"id": "usb_hostX"
needs|ac_cloud-drive/build.json|"usb_host",|
asset|ac_cloud-drive/app/src/main/assets/app-perms.json|"usb_host",|
libaums|ac_cloud-drive/app/build.gradle|'me.jahnen.libaums:core:|'me.jahnen.libaumsX:core:
inventory|licenses/inventory.json|"maven:me.jahnen.libaums:core": {|"maven:me.jahnen.libaums:coreX": {
jni-usb|ac_cloud-drive/build.json|"**/libusb-lib.so", |
jni-errno|ac_cloud-drive/build.json|, "**/liberrno-lib.so"|
excludes|ac_cloud-drive/app/build.gradle|raw_library?.native_excludes|raw_library?.other
install|ab_cloud-libs-shared/libs/usb-drive/src/main/java/com/diegonmarcos/superapp/usbdrive/RawUsbDrive.kt|JavaUsbCommunication.install(); |
stall|ab_cloud-libs-shared/libs/usb-drive/src/main/java/com/diegonmarcos/superapp/usbdrive/JavaUsbCommunication.kt|throw PipeException()|throw IOException("stall")
own-request|ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/usb/UsbDriveActivity.kt|PermsUsb.request(this@UsbDriveActivity, device)|getSystemService(UsbManager::class.java).requestPermission(device, null); PermsUsb.request(this@UsbDriveActivity, device)
MUTANTS
rm -rf "$T"
[ "$stayed" -eq 0 ] && echo "  PASS  $proven mutant(s) proven red" || fails=$((fails+1))
[ $fails -eq 0 ] && echo "test-drive-usb-drives: all checks passed" || { echo "test-drive-usb-drives: $fails FAILED"; exit 1; }
