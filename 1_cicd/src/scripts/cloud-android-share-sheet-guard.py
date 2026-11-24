#!/usr/bin/env python3
"""
cloud-android-share-sheet-guard -- #1888: the Android share sheet offers the right Cloud entries.

WHY. Sharing a file from another app listed the two terminals (the upstream Termux share receiver) but neither Cloud Notes nor
Cloud Drive (VIEW only). Each entry below is an exported activity with the declared labelled intent-filter; the terminals keep their
DocumentsProvider (a file system for Cloud Drive) but no ENABLED SEND receiver.

RULES (manifests are parsed with comments stripped)
  S1 Notes: MainActivity has SEND + SEND_MULTIPLE (*/* and text/plain), labelled @string/share_open_with ("Open with"),
     and still has its VIEW + EDIT filters (nothing removed)
  S2 Drive: .share.ShareReceiverActivity (label "Save to...") and .share.ShareOpenActivity (label "Open with"), each exported with
     SEND + SEND_MULTIPLE */*; MainActivity still has its VIEW filters
  S3 SuperApp: .share.ShareToPeerActivity exported with SEND + SEND_MULTIPLE (*/* and text/plain), label "Send to peer"
  S4 Termux and Nix-on-Droid: no ENABLED activity or activity-alias declares SEND or SEND_MULTIPLE (an activity or alias with
     android:enabled="false" does not count); the DocumentsProvider is still declared
  S5 the labelled strings exist with the right text

Usage: cloud-android-share-sheet-guard.py ROOT      EXIT 0 green, 1 a violation, 2 usage
"""
import os
import re
import sys

NOTES = "ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml"
DRIVE = "ac_cloud-drive/app/src/main/AndroidManifest.xml"
SUPER = "aa_cloud-superapp/app/src/main/AndroidManifest.xml"
TERMS = ["ac_cloud-termux/app/src/main/AndroidManifest.xml", "ac_cloud-nix-on-droid/app/src/main/AndroidManifest.xml"]
STR = {
    "notes": ("ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/res/values/strings.xml", "share_open_with", "Open with"),
    "drive_save": (DRIVE.replace("AndroidManifest.xml", "res/values/strings.xml"), "share_to_cloud_drive", "Save to…"),
    "drive_open": (DRIVE.replace("AndroidManifest.xml", "res/values/strings.xml"), "share_open_with", "Open with"),
    "peer": (SUPER.replace("AndroidManifest.xml", "res/values/strings.xml"), "share_peer_label", "Send to peer"),
}


def read(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8") as f:
        return re.sub(r"<!--.*?-->", "", f.read(), flags=re.S)


def nodes(text, tag):
    """(attrs, body) of every <tag ...>...</tag> (not self-closing)."""
    return [(m.group(1), m.group(2)) for m in re.finditer(r"<%s(?![-\w])([^>]*?)(?<!/)>(.*?)</%s>" % (tag, tag), text, re.S)]


def filters(body):
    out = []
    for m in re.finditer(r"<intent-filter\b([^>]*)>(.*?)</intent-filter>", body, re.S):
        out.append(dict(
            label=(re.search(r'android:label="([^"]*)"', m.group(1)) or [None, None])[1],
            actions=set(re.findall(r'android\.intent\.action\.(\w+)"', m.group(2))),
            mimes=set(re.findall(r'android:mimeType="([^"]+)"', m.group(2)))))
    return out


def attr(attrs, name):
    m = re.search(r'android:%s="([^"]*)"' % name, attrs)
    return m.group(1) if m else None


def activity(text, name):
    for a, b in nodes(text, "activity"):
        if attr(a, "name") == name:
            return a, b
    return None


def send_filters(fs):
    return [f for f in fs if {"SEND", "SEND_MULTIPLE"} <= f["actions"]]


def check_share(text, name, label_ref, mimes, who, exported=True):
    bad = []
    act = activity(text, name)
    if not act:
        return ["%s: activity %s is not declared" % (who, name)], None
    a, b = act
    if exported and attr(a, "exported") != "true":
        bad.append("%s: %s is not exported" % (who, name))
    sf = send_filters(filters(b))
    if not sf:
        bad.append("%s: %s has no filter with SEND + SEND_MULTIPLE" % (who, name))
        return bad, b
    have = set().union(*[f["mimes"] for f in sf])
    for m in mimes:
        if m not in have:
            bad.append("%s: %s SEND filters lack %s" % (who, name, m))
    if not any(f["label"] == label_ref for f in sf):
        bad.append("%s: %s SEND filter is not labelled %s" % (who, name, label_ref))
    return bad, b


def check(root):
    bad = []
    # S1
    t = read(root, NOTES)
    b, body = check_share(t, ".MainActivity", "@string/share_open_with", ["*/*", "text/plain"], "S1 Notes")
    bad += b
    if body is not None:
        vf = [f for f in filters(body) if {"VIEW", "EDIT"} <= f["actions"]]
        if len(vf) < 4:
            bad.append("S1 Notes: MainActivity lost VIEW+EDIT filters (%d left)" % len(vf))
    # S2
    t = read(root, DRIVE)
    b, _ = check_share(t, ".share.ShareReceiverActivity", "@string/share_to_cloud_drive", ["*/*"], "S2 Drive Save to")
    bad += b
    b, _ = check_share(t, ".share.ShareOpenActivity", "@string/share_open_with", ["*/*"], "S2 Drive Open with")
    bad += b
    main = activity(t, ".MainActivity")
    if not main or len([f for f in filters(main[1]) if "VIEW" in f["actions"]]) < 3:
        bad.append("S2 Drive: MainActivity lost its VIEW filters")
    # S3
    t = read(root, SUPER)
    b, _ = check_share(t, ".share.ShareToPeerActivity", "@string/share_peer_label", ["*/*", "text/plain"], "S3 SuperApp")
    bad += b
    # S4
    for rel in TERMS:
        t = read(root, rel)
        for tag in ("activity", "activity-alias"):
            for a, b2 in nodes(t, tag):
                if attr(a, "enabled") == "false":
                    continue
                if any(f["actions"] & {"SEND", "SEND_MULTIPLE"} for f in filters(b2)):
                    bad.append("S4 %s: enabled %s %s declares SEND/SEND_MULTIPLE" % (rel.split("/")[0], tag, attr(a, "name")))
        if "DocumentsProvider" not in t and "DOCUMENTS_PROVIDER" not in t:
            bad.append("S4 %s: the DocumentsProvider declaration is gone" % rel.split("/")[0])
    # S5
    for key, (rel, name, text) in STR.items():
        m = re.search(r'<string name="%s">([^<]*)</string>' % name, read(root, rel))
        if not m or m.group(1) != text:
            bad.append("S5 %s: string %s is not %r" % (key, name, text))
    return bad


if __name__ == "__main__":
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print(__doc__.strip().splitlines()[-1]); sys.exit(2)
    try:
        problems = check(sys.argv[1])
    except FileNotFoundError as e:
        print("ERROR missing source %s - unrun, not passing" % e.filename); sys.exit(1)
    for p in problems:
        print("RED  " + p)
    print("share-sheet-guard: %s" % ("RED (%d)" % len(problems) if problems else "green"))
    sys.exit(1 if problems else 0)
