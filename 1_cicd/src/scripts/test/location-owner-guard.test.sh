#!/usr/bin/env bash
# #1770 the location-owner guard must be RED for each way an app other than Cloud Nav can start a location fix (or the
# client lib can learn to), and GREEN on a conforming tree. A fixture tree is planted per case; the real tree is checked
# by the workflow's next step. The mutants are the proof the guard bites.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-location-owner-guard.py"
REPO="$(cd "$HERE/../../../.." && pwd)"
fails=0
OUT="$(mktemp)"
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/ac_cloud-nav/app/src/main/java/n" "$r/ab_cloud-libs-shared/libs/maps/src/main/java/m" \
           "$r/ac_cloud-calc/app/src/main/java/c" "$r/ab_cloud-libs-shared/libs/location-client/src/main/java/com/diegonmarcos/superapp/locationclient" \
           "$r/ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools"
  # the owner may do everything
  echo 'class W { fun f() { fused.requestLocationUpdates(r, cb, l); lm.getLastKnownLocation("gps"); ActivityRecognition.getClient(c) } }' > "$r/ac_cloud-nav/app/src/main/java/n/W.kt"
  echo "implementation 'com.google.android.gms:play-services-location:21.3.0'" > "$r/ac_cloud-nav/app/build.gradle"
  echo 'class T { fun f() { LocationServices.getFusedLocationProviderClient(c) } }' > "$r/ab_cloud-libs-shared/libs/maps/src/main/java/m/T.kt"
  echo '{"modules": {"libs:maps": {"dir": "x"}}}' > "$r/ac_cloud-nav/build.json"
  # a conforming app: reads Nav, only observes the platform (state check, GNSS status)
  echo 'class S { fun f(c: Context) { NavLocationClient.ask(c); lm.isProviderEnabled("gps"); lm.registerGnssStatusCallback(cb, h) } }' > "$r/ac_cloud-calc/app/src/main/java/c/S.kt"
  echo '{"modules": {"libs:location-client": {"dir": "x"}}}' > "$r/ac_cloud-calc/build.json"
  echo 'dependencies { implementation project(":libs:location-client") }' > "$r/ac_cloud-calc/app/build.gradle"
  cp "$REPO/ab_cloud-libs-shared/libs/location-client/src/main/java/com/diegonmarcos/superapp/locationclient/NavLocationClient.kt" "$r/ab_cloud-libs-shared/libs/location-client/src/main/java/com/diegonmarcos/superapp/locationclient/"
  cp "$REPO/ab_cloud-libs-shared/libs/location-client/src/main/AndroidManifest.xml" "$r/ab_cloud-libs-shared/libs/location-client/src/main/"
  cp "$REPO/ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpcWire.kt" "$r/ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/" 2>/dev/null \
    || printf 'object FleetIpcWire {\n const val METHOD = "fleet.ipc.v1"\n const val K_STATUS = "status"\n const val K_BODY = "body"\n const val K_PIPE = "pipe"\n}\n' > "$r/ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpcWire.kt"
  echo "$r"
}
expect() { # name want-exit root
  python3 "$GUARD" "$3" > "$OUT" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$OUT"; fails=$((fails+1)); fi
  rm -rf "$3"
}
A="ac_cloud-calc/app/src/main/java/c"
L="ab_cloud-libs-shared/libs/location-client"
expect "a conforming tree is green (the owner locates, an app reads and observes)" 0 "$(mk)"
r="$(mk)"; echo 'class B { fun g() = lm.requestLocationUpdates("gps", 1000L, 0f, l) }' > "$r/$A/B.kt"; expect "L1 MUTANT an app calling requestLocationUpdates is red" 1 "$r"
r="$(mk)"; echo 'class B { fun g() = lm.getLastKnownLocation("gps") }' > "$r/$A/B.kt"; expect "L1 MUTANT an app calling getLastKnownLocation is red" 1 "$r"
r="$(mk)"; echo 'class B { fun g() = LocationManagerCompat.getCurrentLocation(lm, p, s, e, c) }' > "$r/$A/B.kt"; expect "L1 MUTANT an app calling getCurrentLocation is red" 1 "$r"
r="$(mk)"; echo 'import com.google.android.gms.location.LocationServices' > "$r/$A/B.kt"; expect "L1 MUTANT an app importing Play Services location is red" 1 "$r"
r="$(mk)"; echo 'class B { fun g() = ActivityRecognition.getClient(c) }' > "$r/$A/B.kt"; expect "L1 MUTANT an app arming Activity Recognition is red" 1 "$r"
r="$(mk)"; echo 'class B { fun g() = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }' > "$r/$A/B.kt"; expect "L1 MUTANT an app arming significant motion is red" 1 "$r"
r="$(mk)"; printf 'class B {\n // lm.requestLocationUpdates is Nav only\n val s = "getLastKnownLocation"\n}\n' > "$r/$A/B.kt"; expect "L1 a banned word only in a comment or a string is green" 0 "$r"
r="$(mk)"; mkdir -p "$r/ac_cloud-x/app/src/test/java/x"; echo 'class T { fun f() = lm.getLastKnownLocation("gps") }' > "$r/ac_cloud-x/app/src/test/java/x/T.kt"; expect "L1 a test source is not scanned (green)" 0 "$r"
r="$(mk)"; echo "implementation 'com.google.android.gms:play-services-location:21.3.0'" >> "$r/ac_cloud-calc/app/build.gradle"; expect "L2 MUTANT an app depending on play-services-location is red" 1 "$r"
r="$(mk)"; sed -i 's/<queries>/<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" \/>\n<queries>/' "$r/$L/src/main/AndroidManifest.xml"; expect "L3 MUTANT the client lib declaring a location permission is red" 1 "$r"
r="$(mk)"; echo 'import android.location.Location' >> "$r/$L/src/main/java/com/diegonmarcos/superapp/locationclient/NavLocationClient.kt"; expect "L3 MUTANT the client lib importing a platform location class is red" 1 "$r"
r="$(mk)"; sed -i 's/fleet.ipc.v1/fleet.ipc.v2/' "$r/$L/src/main/java/com/diegonmarcos/superapp/locationclient/NavLocationClient.kt"; expect "L4 MUTANT the client drifting from the fleet IPC wire is red" 1 "$r"
r="$(mk)"; echo '{"modules": {"libs:maps": {"dir": "x"}}}' > "$r/ac_cloud-calc/build.json"; expect "L5 MUTANT an app other than Cloud Nav linking libs:maps is red" 1 "$r"
[ "$fails" -eq 0 ] && echo "location-owner guard test: all green" || { echo "location-owner guard test: $fails failure(s)"; exit 1; }
