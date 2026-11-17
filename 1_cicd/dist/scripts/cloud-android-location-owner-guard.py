# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-location-owner-guard.py ───
#!/usr/bin/env python3
"""
cloud-android-location-owner-guard -- #1770: Cloud Nav is the ONLY fleet app that ever starts a location fix.

WHY. The owner saw the phone ask for location about every minute. Every other app now reads Cloud Nav's last known fix
over the fleet IPC door through libs:location-client (which never locates); only Cloud Nav (ac_cloud-nav and its
libs:maps) may call a location API. Passive observers that start nothing (GnssStatus callbacks, isProviderEnabled, the
Wi-Fi / BLE scans that merely need the permission held) are not fix requests and stay allowed.

RULES (ROOT is the repository root)
  L1  no Kotlin / Java source outside the owner references a fix-producing or wake API: requestLocationUpdates,
      requestSingleUpdate, getCurrentLocation, getLastKnownLocation, getLastLocation, FusedLocationProviderClient,
      LocationServices, LocationRequest(Compat), Geofencing*, ActivityRecognition*, requestActivityTransitionUpdates,
      TYPE_SIGNIFICANT_MOTION, registerGnssMeasurementsCallback, addGpsStatusListener, com.google.android.gms.location
      (comments and string literals are ignored)
  L2  no Gradle / catalog file outside the owner depends on play-services-location
  L3  libs:location-client declares no location permission and imports no location API (its manifest, its sources)
  L4  libs:location-client speaks the fleet IPC wire exactly as libs:devtools FleetIpcWire does (METHOD, K_STATUS, K_BODY, K_PIPE)
  L5  libs:maps (the owner's lib) is linked by no app except ac_cloud-nav

EXIT 0 green, 1 a violation, 2 usage
"""
import json, os, re, sys

OWNER = ("ac_cloud-nav/", "ab_cloud-libs-shared/libs/maps/")
CLIENT = "ab_cloud-libs-shared/libs/location-client/"
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", "test", "androidTest", "testFixtures", "dist", "out", "1_cicd", "a0_docs", "licenses"}
BANNED = re.compile(
    r"\b(?:requestLocationUpdates|requestSingleUpdate|getCurrentLocation|getLastKnownLocation|getLastLocation|"
    r"FusedLocationProviderClient|LocationServices|LocationRequestCompat|LocationRequest|"
    r"GeofencingClient|GeofencingRequest|GeofencingEvent|addGeofences|ActivityRecognitionClient|ActivityRecognition|"
    r"ActivityTransitionRequest|requestActivityTransitionUpdates|TYPE_SIGNIFICANT_MOTION|"
    r"registerGnssMeasurementsCallback|addGpsStatusListener)\b|com\.google\.android\.gms\.location")
GMS = re.compile(r"play-services-location")
LOCPERM = re.compile(r"ACCESS_(?:FINE|COARSE|BACKGROUND)_LOCATION")


def strip(text, kt=True):
    """Comments and string literals out, so a word in prose or a log line is not a call."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.splitlines():
        line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
        out.append(line.split("//")[0])
    return "\n".join(out)


def walk(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS and not x.startswith("wt-")]
        for f in files:
            yield os.path.join(d, f)


def owner(rel):
    return rel.startswith(OWNER)


def read(p):
    try:
        return open(p, errors="ignore").read()
    except OSError:
        return ""


def const(text, name):
    m = re.search(r"const val %s\s*=\s*\"([^\"]*)\"" % name, text)
    return m.group(1) if m else None


def main(root):
    bad = []
    for p in walk(root):
        rel = os.path.relpath(p, root)
        if owner(rel):
            continue
        if rel.endswith((".kt", ".java")):
            hits = sorted(set(m.group(0) for m in BANNED.finditer(strip(read(p)))))
            if hits:
                bad.append("L1 %s references %s: only Cloud Nav starts a location fix, read it with libs:location-client" % (rel, ", ".join(hits)))
        elif rel.endswith((".gradle", ".kts", ".toml")) and GMS.search(read(p)):
            bad.append("L2 %s depends on play-services-location: only Cloud Nav (ac_cloud-nav, libs:maps) may" % rel)
    # L3 the client lib
    for p in walk(os.path.join(root, CLIENT)) if os.path.isdir(os.path.join(root, CLIENT)) else []:
        rel = os.path.relpath(p, root)
        t = read(p)
        if rel.endswith("AndroidManifest.xml") and LOCPERM.search(re.sub(r"<!--.*?-->", "", t, flags=re.S)):
            bad.append("L3 %s declares a location permission: the client lib never locates" % rel)
        if rel.endswith(".kt") and re.search(r"android\.location|LocationManager", strip(t)):
            bad.append("L3 %s imports a platform location class: the client lib never locates" % rel)
        if rel.endswith(".gradle") and GMS.search(t):
            bad.append("L3 %s depends on play-services-location" % rel)
    # L4 the wire constants
    wire = read(os.path.join(root, "ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpcWire.kt"))
    cli = read(os.path.join(root, CLIENT, "src/main/java/com/diegonmarcos/superapp/locationclient/NavLocationClient.kt"))
    if wire and cli:
        for n in ("METHOD", "K_STATUS", "K_BODY", "K_PIPE"):
            if const(wire, n) != const(cli, n):
                bad.append("L4 NavLocationClient.%s is %r, FleetIpcWire.%s is %r: the client speaks a different wire" % (n, const(cli, n), n, const(wire, n)))
    # L5 libs:maps linked only by the owner
    for p in walk(root):
        rel = os.path.relpath(p, root)
        if os.path.basename(p) == "build.json" and not owner(rel) and rel.count("/") == 1:
            try:
                mods = json.loads(read(p)).get("modules", {})
            except ValueError:
                continue
            if "libs:maps" in mods:
                bad.append("L5 %s links libs:maps: the tracker (and its location APIs) belongs to Cloud Nav alone" % rel)
    return bad


if __name__ == "__main__":
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print("usage: cloud-android-location-owner-guard.py ROOT", file=sys.stderr)
        sys.exit(2)
    problems = main(sys.argv[1])
    for b in problems:
        print("FAIL: " + b)
    print("location-owner guard: %s" % ("%d violation(s)" % len(problems) if problems else "green"))
    sys.exit(1 if problems else 0)
