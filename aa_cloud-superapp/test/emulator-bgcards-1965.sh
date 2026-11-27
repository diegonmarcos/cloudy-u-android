#!/usr/bin/env bash
# #1965 emulator walk of the ONE "Background Apps" group. Installs the SuperApp (the hub), Cloud Mail, Cloud Store, Cloud Nav and the
# WebServer, starts each one's background service (adb root: the services are not exported), then reads the shade from
# `dumpsys notification --noredact` and asserts: one summary "Background Apps" posted by the hub, every hub child in that one group,
# and every app's own FGS card on the MIN "Background service" channel. Screenshots of the expanded shade.
# Privacy: only package, id, tag, channel, group, flags, the title and the FIELD KEYS of each card are written; never a text body.
# Usage: emulator-bgcards-1965.sh APK_DIR OUT_DIR   (APK_DIR holds superapp.apk mail.apk store.apk nav.apk webserver.apk)
set -u
D="$1"; OUT="$2"; mkdir -p "$OUT"
H=com.diegonmarcos.superapp; M=com.diegonmarcos.comms.mail; S=com.diegonmarcos.cloudstore; N=com.diegonmarcos.cloudnav; W=com.diegonmarcos.cloudwebserver
rc=0; fail() { echo "::error::$*"; rc=1; }
shoot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
for a in superapp mail store nav webserver; do
  [ -f "$D/$a.apk" ] || { fail "missing $a.apk"; continue; }
  adb install -r -g "$D/$a.apk" >"$OUT/install-$a.txt" 2>&1 || { fail "install $a"; tail -3 "$OUT/install-$a.txt"; }
done
for p in $H $M $S $N $W; do adb shell pm grant "$p" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true; done
adb logcat -c
# The hub opened once, as on a phone (a never-opened app is in the stopped state; cards are sent with INCLUDE_STOPPED anyway).
adb shell am start -W -n "$H/.MainActivity" >/dev/null 2>&1 || adb shell monkey -p "$H" 1 >/dev/null 2>&1; sleep 8
adb shell input keyevent KEYCODE_HOME
# Services: Nav tracker (the user's Start action), WebServer keep-alive (its switch file + launch), Mail push, Store batch.
adb shell am start-foreground-service -a com.diegonmarcos.cloudnav.maps.ACTION_USER_START -n "$N/com.diegonmarcos.cloudnav.maps.LocationTrackerService" >"$OUT/start-nav.txt" 2>&1
adb shell "mkdir -p /sdcard/Android/data/$W/files; echo '{\"server\":true,\"keep_alive\":true}' > /sdcard/Android/data/$W/files/power.json"
adb shell am start -W -n "$W/.MainActivity" >/dev/null 2>&1; sleep 12; adb shell input keyevent KEYCODE_HOME
adb shell am start-foreground-service -n "$M/app.sterna.push.PushService" >"$OUT/start-mail.txt" 2>&1
adb shell am start-foreground-service -n "$S/com.diegonmarcos.superapp.updater.BatchForegroundService" >"$OUT/start-store.txt" 2>&1
sleep 2
snap() {   # $1 = label. Filtered, body-free view of our packages' notifications.
  adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' > "$OUT/.raw-$1.txt"
  python3 -I - "$OUT/.raw-$1.txt" "$OUT/notif-$1.json" "$H" "$M" "$S" "$N" "$W" <<'PY'
import json, re, sys
raw, out, *pkgs = sys.argv[1:]
txt = open(raw, errors="replace").read()
recs = []
# One record = its "NotificationRecord(... pkg=P user=U id=I tag=T importance=N key=...: Notification(channel=C ... flags=0xF ... groupKey=G ...))"
# header plus its indented detail lines up to the next record. Only these keys and the title are kept: never a text body.
for block in re.split(r"\n(?=\s+NotificationRecord\()", txt)[1:]:
    head = block.split("\n", 1)[0]
    m = re.search(r"pkg=(\S+) user=\S+ id=(-?\d+) tag=(\S+)", head)
    if not m or m.group(1) not in pkgs: continue
    def g(pat, src=head):
        x = re.search(pat, src)
        return x.group(1) if x else None
    flags = int(g(r"flags=0x([0-9a-fA-F]+)") or "0", 16)
    recs.append({"pkg": m.group(1), "id": int(m.group(2)), "tag": None if m.group(3) == "null" else m.group(3),
                 "importance": g(r"importance=(-?\d+)"), "channel": g(r"channel=(\S+)"), "group": g(r"groupKey=(\S+?)[\s)]") or g(r"groupKey=(\S+?)[\s)]", block),
                 "summary": bool(flags & 0x200), "ongoing": bool(flags & 0x2), "no_clear": bool(flags & 0x20),
                 "title": (g(r"android\.title=\S+ \(([^)\n]{0,80})", block) or ""),
                 "actions": g(r"actions=(\d+)")})
json.dump(recs, open(out, "w"), indent=1)
PY
  rm -f "$OUT/.raw-$1.txt"
}
snap t2
adb shell cmd statusbar expand-notifications; sleep 3; shoot bgcards-1965-shade-expanded
adb shell input swipe 540 600 540 1800 400; sleep 2; shoot bgcards-1965-shade-full
sleep 8; snap t12; shoot bgcards-1965-shade-t12
adb shell cmd statusbar collapse
python3 -I - "$OUT/notif-t12.json" "$OUT/notif-t2.json" "$H" <<'PY' || rc=1
import json, sys
t12, t2, hub = sys.argv[1:]
for path in (t2, t12):
    recs = json.load(open(path))
    hubs = [r for r in recs if r["pkg"] == hub and r["tag"] and r["tag"].startswith("bg:")]
    summ = [r for r in hubs if r["tag"] == "bg:summary"]
    kids = [r for r in hubs if r["tag"] != "bg:summary"]
    apps = [r for r in recs if r["pkg"] != hub]
    print("==", path.rsplit("/", 1)[-1], "hub summary:", len(summ), "hub children:", sorted(k["tag"] for k in kids))
    for r in apps: print("   app card", r["pkg"], r["id"], "channel", r["channel"], "group", r["group"])
def verdict(path):
    recs = json.load(open(path)); ok = True
    hubs = [r for r in recs if r["pkg"] == hub and r["tag"] and r["tag"].startswith("bg:")]
    summ = [r for r in hubs if r["tag"] == "bg:summary"]
    kids = [r for r in hubs if r["tag"] != "bg:summary"]
    if len(summ) != 1 or not summ[0]["summary"]: print("::warning::%s: expected ONE Background Apps summary, got %d" % (path, len(summ))); ok = False
    if len(kids) < 2: print("::warning::%s: expected at least 2 app cards in the group, got %d" % (path, len(kids))); ok = False
    groups = {r["group"] for r in hubs}
    if len(groups) != 1: print("::warning::%s: hub cards are in %d groups: %s" % (path, len(groups), groups)); ok = False
    for r in recs:
        if r["pkg"] != hub and r["id"] in (1, 0xA1, 8002, 0xB47C) and r["channel"] and not r["channel"].startswith("fleet_background_service"):
            print("::warning::%s: FGS card of %s not on the Background service channel: %s" % (path, r["pkg"], r["channel"])); ok = False
    return ok
ok = verdict(t2) or verdict(t12)
if not ok: print("::error::neither snapshot shows one Background Apps group with the app cards")
sys.exit(0 if ok else 1)
PY
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|DidNotStartInTime" "$OUT/crash.txt" | head -5 && fail "a crash in logcat"
exit $rc
