#!/usr/bin/env bash
# #1888 the share-sheet guard must be GREEN on the current manifests and RED for each mutation: each Cloud entry loses its SEND or
# SEND_MULTIPLE, its label, its export, or a terminal gets an enabled SEND receiver back. A copy of the real manifests per case.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
GUARD="$HERE/../cloud-android-share-sheet-guard.py"
fails=0; OUT="$(mktemp)"
FILES="ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/res/values/strings.xml
ac_cloud-drive/app/src/main/AndroidManifest.xml ac_cloud-drive/app/src/main/res/values/strings.xml
aa_cloud-superapp/app/src/main/AndroidManifest.xml aa_cloud-superapp/app/src/main/res/values/strings.xml
ac_cloud-termux/app/src/main/AndroidManifest.xml ac_cloud-nix-on-droid/app/src/main/AndroidManifest.xml"
mk() { local r; r="$(mktemp -d)"; for f in $FILES; do mkdir -p "$r/$(dirname "$f")"; cp "$REPO/$f" "$r/$f"; done; echo "$r"; }
expect() { python3 "$GUARD" "$3" > "$OUT" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$OUT"; fails=$((fails+1)); fi; rm -rf "$3"; }
# mut ROOT FILE PYTHON-EXPR(s) : s is the text
mut() { python3 - "$1/$2" "$3" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
n = eval(expr)
assert n != s, "mutation changed nothing: " + expr
open(p, "w", encoding="utf-8").write(n)
PY
}
N=ac_cloud-notes/packages/frontend/apps/android/App/app/src/main
D=ac_cloud-drive/app/src/main; S=aa_cloud-superapp/app/src/main
expect "the current manifests are green" 0 "$(mk)"
r="$(mk)"; mut "$r" $N/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.SEND_MULTIPLE\" />","")'; expect "Notes without SEND_MULTIPLE is red" 1 "$r"
r="$(mk)"; mut "$r" $N/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.SEND\" />","")'; expect "Notes without SEND is red" 1 "$r"
r="$(mk)"; mut "$r" $N/res/values/strings.xml 's.replace("Open with","Share")'; expect "Notes label changed is red" 1 "$r"
r="$(mk)"; mut "$r" $D/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.SEND_MULTIPLE\" />","")'; expect "Drive without SEND_MULTIPLE is red" 1 "$r"
r="$(mk)"; mut "$r" $D/AndroidManifest.xml 's.replace(".share.ShareReceiverActivity",".share.Gone")'; expect "Drive without the Save to activity is red" 1 "$r"
r="$(mk)"; mut "$r" $D/AndroidManifest.xml 's.replace(".share.ShareOpenActivity",".share.Gone")'; expect "Drive without the Open with activity is red" 1 "$r"
r="$(mk)"; mut "$r" $D/res/values/strings.xml 's.replace("Save to…","Save")'; expect "Drive Save to label changed is red" 1 "$r"
r="$(mk)"; mut "$r" $S/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.SEND_MULTIPLE\" />","",1)'; expect "SuperApp without SEND_MULTIPLE is red" 1 "$r"
r="$(mk)"; mut "$r" $S/res/values/strings.xml 's.replace("Send to peer","Share")'; expect "SuperApp label changed is red" 1 "$r"
r="$(mk)"; mut "$r" ac_cloud-nix-on-droid/app/src/main/AndroidManifest.xml 's.replace("android:enabled=\"false\"\n            ","")'; expect "Nix share alias enabled again is red" 1 "$r"
r="$(mk)"; mut "$r" ac_cloud-termux/app/src/main/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.VIEW\" />","<action android:name=\"android.intent.action.SEND\" />",1)'; expect "Termux with a SEND receiver again is red" 1 "$r"
rm -f "$OUT"; [ "$fails" -eq 0 ] && echo "share-sheet-guard.test: all cases hold" || { echo "share-sheet-guard.test: $fails wrong"; exit 1; }
