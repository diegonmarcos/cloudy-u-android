#!/usr/bin/env python3
"""#1853 synthetic Google location-history fixtures for the import tests (no personal data: invented ids, public landmark coordinates).
Writes the four fixture files under ab_cloud-libs-shared/libs/maps/src/test/resources/takeout/. Re-run to regenerate; the files are committed."""
import json, math, os, sys
from datetime import datetime, timedelta, timezone

root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..", "ab_cloud-libs-shared/libs/maps/src/test/resources/takeout")
HOME = (52.5163, 13.3777)     # Brandenburg Gate (a public landmark), the fixture's "home"
WORK = (52.5219, 13.4132)     # Alexanderplatz, the fixture's "office"
CAFE = (52.5208, 13.4094)

def e7(v): return int(round(v * 1e7))
def iso(dt, ms=True): return dt.strftime("%Y-%m-%dT%H:%M:%S") + (".%03dZ" % (dt.microsecond // 1000) if ms else "Z")
def t(day, h, m=0, s=0): return datetime(2026, 3, day, h, m, s, tzinfo=timezone.utc)
def epoch_ms(dt): return int(dt.timestamp() * 1000)
def lerp(a, b, f): return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
def jitter(p, k): return (p[0] + (3e-5 if k % 2 else -3e-5), p[1] + (3e-5 if k % 3 else -3e-5))

def still(day, h0, m0, h1, m1, place, every):
    out = []; cur = t(day, h0, m0); end = t(day, h1, m1); k = 0
    while cur <= end: out.append((cur, jitter(place, k))); cur += timedelta(minutes=every); k += 1
    return out
def travel(day, h, m, a, b, secs, every):
    out = []; start = t(day, h, m)
    for s in range(every, secs, every): out.append((start + timedelta(seconds=s), lerp(a, b, s / secs)))
    return out

# ------------------------------------------------------------------ legacy Records.json
walk = still(10, 6, 0, 8, 0, HOME, 10) + travel(10, 8, 0, HOME, WORK, 300, 30) + still(10, 8, 5, 16, 0, WORK, 20) + travel(10, 16, 0, WORK, HOME, 300, 30) + still(10, 16, 5, 22, 0, HOME, 20)
locs = []
for i, (when, p) in enumerate(walk):
    r = {"latitudeE7": e7(p[0]), "longitudeE7": e7(p[1]), "accuracy": 12 + (i % 5), "source": "WIFI" if i % 2 else "GPS", "deviceTag": -1234567}
    if i % 3 == 0: r["timestampMs"] = str(epoch_ms(when))     # the older spelling
    else: r["timestamp"] = iso(when)
    if i % 4 == 0: r["velocity"] = 0 if i < 12 else 2; r["heading"] = (i * 17) % 360; r["altitude"] = 34 + (i % 3); r["verticalAccuracy"] = 3
    locs.append(r)
# the first record carries every field the format can have
locs[0] = {"timestamp": iso(walk[0][0]), "latitudeE7": e7(walk[0][1][0]), "longitudeE7": e7(walk[0][1][1]), "accuracy": 20, "altitude": 34, "verticalAccuracy": 3, "velocity": 0,
           "heading": 270, "source": "WIFI", "deviceTag": -1234567, "platformType": "ANDROID", "formFactor": "PHONE", "serverTimestamp": iso(walk[0][0] + timedelta(seconds=5)),
           "deviceTimestamp": iso(walk[0][0]), "batteryCharging": True, "osLevel": 34, "placeId": "ChIJ-FIXTURE-REC",
           "activity": [{"timestamp": iso(walk[0][0] + timedelta(seconds=1)), "activity": [{"type": "STILL", "confidence": 90}, {"type": "IN_VEHICLE", "confidence": 5}]}],
           "locationMetadata": [{"wifiScan": {"deliveryTime": iso(walk[0][0]), "accessPoints": [{"mac": "00:00:5e:00:53:af", "strength": -60, "frequencyMhz": 2412}]}, "timestampMs": str(epoch_ms(walk[0][0]))}],
           "inferredLocation": [{"latitudeE7": e7(HOME[0]), "longitudeE7": e7(HOME[1]), "source": "INFERRED"}]}

# ------------------------------------------------------------------ legacy Semantic Location History
def loc(p, pid, name, addr, stype=None, conf=None):
    d = {"latitudeE7": e7(p[0]), "longitudeE7": e7(p[1]), "placeId": pid, "address": addr, "name": name, "sourceInfo": {"deviceTag": -1234567}}
    if stype: d["semanticType"] = stype
    if conf is not None: d["locationConfidence"] = conf; d["calibratedProbability"] = 91.5
    return d
HOME_L = loc(HOME, "ChIJ-FIXTURE-HOME", "Fixture Home", "Fixture Strasse 1, 10117 Berlin, Germany", "TYPE_HOME", 94.5)
WORK_L = loc(WORK, "ChIJ-FIXTURE-WORK", "Fixture Office", "Fixture Platz 2, 10178 Berlin, Germany", "TYPE_WORK", 88.0)
CAFE_L = loc(CAFE, "ChIJ-FIXTURE-CAFE", "Fixture Cafe", "Fixture Weg 3, 10178 Berlin, Germany")
visit_full = {"location": HOME_L, "duration": {"startTimestamp": iso(t(11, 7)), "endTimestamp": iso(t(11, 8))}, "placeConfidence": "HIGH_CONFIDENCE", "visitConfidence": 85,
    "locationConfidence": 94, "placeVisitType": "SINGLE_PLACE", "placeVisitImportance": "MAIN", "centerLatE7": e7(HOME[0]), "centerLngE7": e7(HOME[1]),
    "editConfirmationStatus": "NOT_CONFIRMED", "lastEditedTimestamp": iso(t(12, 9)),
    "otherCandidateLocations": [loc(CAFE, "ChIJ-FIXTURE-OTH1", "Fixture Neighbour", "Fixture Weg 4, 10117 Berlin, Germany", "TYPE_SEARCHED_ADDRESS", 20.1), loc(WORK, "ChIJ-FIXTURE-OTH2", "Fixture Second", "Fixture Weg 5, 10117 Berlin, Germany", "TYPE_ALIASED_LOCATION", 11.0)],
    "childVisits": [{"location": loc(CAFE, "ChIJ-FIXTURE-CHILD", "Fixture Child", "Fixture Weg 6, 10117 Berlin, Germany", "TYPE_UNKNOWN", 60.0),
                     "duration": {"startTimestamp": iso(t(11, 7, 10)), "endTimestamp": iso(t(11, 7, 40))}, "placeConfidence": "MEDIUM_CONFIDENCE", "visitConfidence": 70,
                     "locationConfidence": 60, "placeVisitType": "CHILD", "placeVisitImportance": "MAIN", "centerLatE7": e7(CAFE[0]), "centerLngE7": e7(CAFE[1]),
                     "editConfirmationStatus": "NOT_CONFIRMED", "lastEditedTimestamp": iso(t(12, 9, 1)),
                     "otherCandidateLocations": [loc(HOME, "ChIJ-FIXTURE-CHILDOTH", "Fixture Child Other", "Fixture Weg 7, 10117 Berlin, Germany", "TYPE_HOME", 5.0)]}],
    "simplifiedRawPath": {"points": [{"latE7": e7(HOME[0]), "lngE7": e7(HOME[1]), "accuracyMeters": 15, "timestamp": iso(t(11, 7, 5))}], "source": "BACKFILLED", "distanceMeters": 12.5}}
visit_old = {"location": WORK_L, "duration": {"startTimestampMs": str(epoch_ms(t(11, 8, 30))), "endTimestampMs": str(epoch_ms(t(11, 17)))}, "placeConfidence": "HIGH_CONFIDENCE",
    "visitConfidence": 90, "locationConfidence": 88, "placeVisitType": "SINGLE_PLACE", "placeVisitImportance": "MAIN", "centerLatE7": e7(WORK[0]), "centerLngE7": e7(WORK[1]), "editConfirmationStatus": "CONFIRMED"}
visit_night = {"location": HOME_L, "duration": {"startTimestamp": iso(t(11, 18)), "endTimestamp": iso(t(12, 7))}, "placeConfidence": "HIGH_CONFIDENCE", "visitConfidence": 92, "placeVisitType": "SINGLE_PLACE"}
act_full = {"startLocation": {"latitudeE7": e7(HOME[0]), "longitudeE7": e7(HOME[1]), "placeId": "ChIJ-FIXTURE-HOME", "address": "Fixture Strasse 1, 10117 Berlin, Germany", "name": "Fixture Home", "sourceInfo": {"deviceTag": -1234567}},
    "endLocation": {"latitudeE7": e7(WORK[0]), "longitudeE7": e7(WORK[1]), "placeId": "ChIJ-FIXTURE-WORK", "address": "Fixture Platz 2, 10178 Berlin, Germany", "name": "Fixture Office", "sourceInfo": {"deviceTag": -1234567}},
    "duration": {"startTimestamp": iso(t(11, 8)), "endTimestamp": iso(t(11, 8, 30))}, "distance": 3100, "activityType": "IN_TRAIN", "confidence": "HIGH",
    "activities": [{"activityType": "IN_TRAIN", "probability": 71.2}, {"activityType": "WALKING", "probability": 20.3}, {"activityType": "IN_BUS", "probability": 8.5}],
    "waypointPath": {"waypoints": [{"latE7": e7(HOME[0]), "lngE7": e7(HOME[1])}, {"latE7": e7(CAFE[0]), "lngE7": e7(CAFE[1])}], "source": "INFERRED", "roadSegment": [{"placeId": "ChIJ-FIXTURE-ROAD", "duration": "300s"}], "distanceMeters": 3100.5, "travelMode": "TRANSIT", "confidence": 0.8},
    "simplifiedRawPath": {"points": [{"latE7": e7(HOME[0]), "lngE7": e7(HOME[1]), "accuracyMeters": 20, "timestamp": iso(t(11, 8, 1))}, {"latE7": e7(WORK[0]), "lngE7": e7(WORK[1]), "accuracyMeters": 18, "timestampMs": str(epoch_ms(t(11, 8, 29)))}], "source": "BACKFILLED", "distanceMeters": 3099.0},
    "transitPath": {"transitStops": [{"latitudeE7": e7(HOME[0]), "longitudeE7": e7(HOME[1]), "placeId": "ChIJ-FIXTURE-STOP1", "name": "Fixture Stop A"}, {"latitudeE7": e7(WORK[0]), "longitudeE7": e7(WORK[1]), "placeId": "ChIJ-FIXTURE-STOP2", "name": "Fixture Stop B"}],
                    "name": "Fixture Line 1", "hexRgbColor": "FF0000", "linePlaceId": "ChIJ-FIXTURE-LINE", "source": "INFERRED"},
    "parkingEvent": {"location": {"latitudeE7": e7(WORK[0]), "longitudeE7": e7(WORK[1]), "accuracyMetres": 10}, "method": "EXPLICIT_USER_INPUT", "locationSource": "FROM_ACTIVITY_END", "timestamp": iso(t(11, 8, 30))},
    "editConfirmationStatus": "NOT_CONFIRMED", "lastEditedTimestamp": iso(t(12, 9, 2))}
act_old = {"startLocation": {"latitudeE7": e7(WORK[0]), "longitudeE7": e7(WORK[1])}, "endLocation": {"latitudeE7": e7(HOME[0]), "longitudeE7": e7(HOME[1])},
    "duration": {"startTimestampMs": str(epoch_ms(t(11, 17))), "endTimestampMs": str(epoch_ms(t(11, 17, 40)))}, "distance": 3200, "activityType": "IN_PASSENGER_VEHICLE", "confidence": "MEDIUM",
    "activities": [{"activityType": "IN_PASSENGER_VEHICLE", "probability": 90.0}]}
semantic = {"timelineObjects": [{"placeVisit": visit_full}, {"activitySegment": act_full}, {"placeVisit": visit_old}, {"activitySegment": act_old}, {"placeVisit": visit_night}]}

# ------------------------------------------------------------------ on-device Timeline export (object flavour)
def ll(p): return "%.7f°, %.7f°" % p
tl = {"semanticSegments": [
    {"startTime": "2026-03-12T07:00:00.000+01:00", "endTime": "2026-03-12T08:00:00.000+01:00", "startTimeTimezoneUtcOffsetMinutes": 60, "endTimeTimezoneUtcOffsetMinutes": 60,
     "visit": {"hierarchyLevel": 0, "probability": 0.85, "isTimelessVisit": False, "topCandidate": {"placeId": "ChIJ-FIXTURE-HOME", "semanticType": "HOME", "probability": 0.92, "placeLocation": {"latLng": ll(HOME)}}}},
    {"startTime": "2026-03-12T08:00:00.000+01:00", "endTime": "2026-03-12T08:30:00.000+01:00", "startTimeTimezoneUtcOffsetMinutes": 60, "endTimeTimezoneUtcOffsetMinutes": 60,
     "activity": {"start": {"latLng": ll(HOME)}, "end": {"latLng": ll(WORK)}, "distanceMeters": 3100.4, "probability": 0.77, "topCandidate": {"type": "IN_SUBWAY", "probability": 0.81}},
     "timelineMemory": {"destinations": [{"identifier": {"placeId": "ChIJ-FIXTURE-WORK"}}], "distanceFromOriginKms": 3.1}},
    {"startTime": "2026-03-12T08:30:00.000+01:00", "endTime": "2026-03-12T16:30:00.000+01:00", "startTimeTimezoneUtcOffsetMinutes": 60, "endTimeTimezoneUtcOffsetMinutes": 60,
     "visit": {"hierarchyLevel": 0, "probability": 0.9, "topCandidate": {"placeId": "ChIJ-FIXTURE-WORK", "semanticType": "WORK", "probability": 0.88, "placeLocation": {"latLng": ll(WORK)}}}},
    {"startTime": "2026-03-12T16:30:00.000+01:00", "endTime": "2026-03-12T16:45:00.000+01:00", "startTimeTimezoneUtcOffsetMinutes": 60, "endTimeTimezoneUtcOffsetMinutes": 60,
     "timelinePath": [{"point": ll(WORK), "time": "2026-03-12T16:30:00.000+01:00"}, {"point": ll(CAFE), "time": "2026-03-12T16:40:00.000+01:00"}]},
  ],
  "rawSignals": [
    {"position": {"LatLng": ll(HOME), "accuracyMeters": 14, "altitudeMeters": 36.5, "source": "GPS", "speedMetersPerSecond": 0.4, "timestamp": "2026-03-12T07:10:00.000+01:00"}},
    {"position": {"LatLng": ll(WORK), "accuracyMeters": 20, "altitudeMeters": 38, "source": "WIFI", "speedMetersPerSecond": 0, "timestamp": "2026-03-12T09:10:00.000+01:00"}},
    {"activityRecord": {"probableActivities": [{"type": "STILL", "confidence": 0.9}, {"type": "WALKING", "confidence": 0.1}], "timestamp": "2026-03-12T07:10:01.000+01:00"}},
    {"wifiScan": {"deliveryTime": "2026-03-12T07:10:02.000+01:00", "devicesRecords": [{"mac": 1234567, "rawRssi": -61}]}}],
  "userLocationProfile": {"frequentPlaces": [{"placeId": "ChIJ-FIXTURE-HOME", "placeLocation": ll(HOME), "label": "HOME"}, {"placeId": "ChIJ-FIXTURE-WORK", "placeLocation": ll(WORK), "label": "WORK"}],
                          "frequentTrips": [{"startTimeMinutes": 480, "endTimeMinutes": 510, "durationMinutes": 30, "confidence": 0.7, "commuteDirection": "HOME_TO_WORK", "waypointIds": ["ChIJ-FIXTURE-HOME", "ChIJ-FIXTURE-WORK"], "modeDistribution": [{"mode": "IN_SUBWAY", "rate": 0.9}]}],
                          "persona": {"travelModeAffinities": [{"mode": "WALKING", "affinity": 0.4}, {"mode": "IN_SUBWAY", "affinity": 0.6}]}}}

# ------------------------------------------------------------------ Android export (top-level array, geo: points, numbers as strings)
def geo(p): return "geo:%.7f,%.7f" % p
android = [
    {"startTime": "2026-03-13T07:00:00.000+01:00", "endTime": "2026-03-13T08:00:00.000+01:00",
     "visit": {"hierarchyLevel": "0", "probability": "0.8", "topCandidate": {"placeId": "ChIJ-FIXTURE-HOME", "semanticType": "Home", "probability": "0.9", "placeLocation": geo(HOME)}}},
    {"startTime": "2026-03-13T08:00:00.000+01:00", "endTime": "2026-03-13T08:20:00.000+01:00",
     "activity": {"start": geo(HOME), "end": geo(WORK), "distanceMeters": "3100.0", "probability": "0.7", "topCandidate": {"type": "walking", "probability": "0.6"}}},
    {"startTime": "2026-03-13T08:20:00.000+01:00", "endTime": "2026-03-13T08:35:00.000+01:00",
     "timelinePath": [{"point": geo(WORK), "durationMinutesOffsetFromStartTime": "0"}, {"point": geo(CAFE), "durationMinutesOffsetFromStartTime": "10"}]}]

def put(rel, obj):
    p = os.path.join(root, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f: json.dump(obj, f, indent=1, sort_keys=False); f.write("\n")
put("records/Records.json", {"locations": locs})
put("semantic/2026/2026_MARCH.json", semantic)
put("timeline/Timeline.json", tl)
put("android/location-history.json", android)
print("fixtures written to", root)
