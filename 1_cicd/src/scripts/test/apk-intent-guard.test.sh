#!/usr/bin/env bash
# #1675 the apk-intent guard must be RED for each way store code can hand an APK to an intent (ACTION_INSTALL_PACKAGE, the
# APK mime on an intent as a literal or through a val, a FileProvider .apk URI + ACTION_VIEW start) and GREEN on a tree whose
# only APK mime use is a MediaStore copy to Downloads. A fixture tree per case.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-apk-intent-guard.py"
fails=0; OUT="$(mktemp)"
mk() {
  local r; r="$(mktemp -d)"; local d="$r/ac_cloud-store/app/src/main/java/a"; mkdir -p "$d" "$r/ac_cloud-store/app/src/test/java"
  cat > "$d/Save.kt" <<'K'
fun save() { val mime = "application/vnd.android.package-archive"; cv.put(MediaStore.MediaColumns.MIME_TYPE, mime) }
// startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE))  -- a comment is not code
K
  echo 'class T { val i = Intent(Intent.ACTION_INSTALL_PACKAGE) }' > "$r/ac_cloud-store/app/src/test/java/T.kt"
  echo "$r"
}
expect() { python3 "$GUARD" "$3" > "$OUT" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$OUT"; fails=$((fails+1)); fi; rm -rf "$3"; }
D=ac_cloud-store/app/src/main/java/a
expect "a MediaStore copy, a comment and a test file are green" 0 "$(mk)"
r="$(mk)"; echo 'fun f() = Intent(Intent.ACTION_INSTALL_PACKAGE)' > "$r/$D/A1.kt"; expect "A1 ACTION_INSTALL_PACKAGE is red" 1 "$r"
r="$(mk)"; echo 'fun f() = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")' > "$r/$D/A2.kt"; expect "A2 the APK mime literal on an intent is red" 1 "$r"
r="$(mk)"; printf 'val m = "application/vnd.android.package-archive"\nfun f() = Intent(Intent.ACTION_VIEW).setDataAndType(u, m)\n' > "$r/$D/A2b.kt"; expect "A2 the APK mime through a val is red" 1 "$r"
r="$(mk)"; printf 'fun f() { val u = FileProvider.getUriForFile(c, a, File("x.apk")); c.startActivity(Intent(Intent.ACTION_VIEW, u)) }\n' > "$r/$D/A3.kt"; expect "A3 a FileProvider .apk URI opened with ACTION_VIEW is red" 1 "$r"
r="$(mk)"; mkdir -p "$r/aa_cloud-superapp/app/src/main/java"; echo 'fun f() = Intent(Intent.ACTION_INSTALL_PACKAGE)' > "$r/aa_cloud-superapp/app/src/main/java/L.kt"; expect "the SuperApp is in scope" 1 "$r"
rm -f "$OUT"; [ "$fails" -eq 0 ] && echo "apk-intent-guard.test: all cases hold" || { echo "apk-intent-guard.test: $fails wrong"; exit 1; }
