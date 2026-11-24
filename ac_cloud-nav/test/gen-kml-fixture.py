#!/usr/bin/env python3
"""#1887 generator of the SYNTHETIC Timeline KML fixture (run by hand, output committed; no build runs it).

  python3 -I gen-kml-fixture.py OUT_DIR      (OUT_DIR = app/src/test/resources/kml)

Writes Synthetic.kml.xml and gazetteer.tsv. The KML has the structure of a Google Timeline export converted to KML (a Document of
Placemarks; a Point visit named HOME / WORK / UNKNOWN / SEARCHED_ADDRESS / INFERRED_HOME / INFERRED_WORK / ALIASED_LOCATION; a LineString
named Path, some with several coordinates, some with one, some with none; the times as an HTML-escaped "Start Time / End Time"
description with a UTC offset). Every city, country and coordinate is invented: home in Aldoria (Northland), a two-night trip to
Brenvik, then a trip to Dunmore Bay (Eastreach, nights on Tokyo time) and Castamar (Southmark). No real person's data.
"""
import os, sys
from datetime import datetime, timedelta, timezone

CET = timezone(timedelta(hours=1)); JST = timezone(timedelta(hours=9))
HOME = (41.1010, -1.2010); WORK = (41.1100, -1.1900); CAFE = (41.0950, -1.2100)
BREN = (41.6020, -0.4010); BREN2 = (41.6100, -0.3900)
DUN = (35.1020, 139.2030); DUN2 = (35.1100, 139.2200)
CAS = (38.2010, 15.3020); CAS2 = (38.2100, 15.3200)

GAZ = [("Aldoria", "Northland", 41.1000, -1.2000, "Europe/Madrid"), ("Brenvik", "Northland", 41.6000, -0.4000, "Europe/Madrid"),
       ("Ostrel", "Northland", 42.5000, 1.0000, "Europe/Madrid"), ("Castamar", "Southmark", 38.2000, 15.3000, "Europe/Rome"),
       ("Dunmore Bay", "Eastreach", 35.1000, 139.2000, "Asia/Tokyo")]

pms = []
def t(d): return d.isoformat(timespec="milliseconds")
def desc(a, b): return "&lt;b&gt;Start Time:&lt;/b&gt; %s&lt;br/&gt;&lt;b&gt;End Time:&lt;/b&gt; %s" % (t(a), t(b))
def c(p): return "%.7f,%.7f,0" % (p[1], p[0])
def visit(name, a, b, p):
    pms.append("    <Placemark>\n      <name>%s</name>\n      <description>%s</description>\n      <Point>\n        <coordinates>%s</coordinates>\n      </Point>\n    </Placemark>\n" % (name, desc(a, b), c(p)))
def path(a, b, pts):
    geo = "" if not pts else "      <LineString>\n        <tessellate>1</tessellate>\n        <coordinates>%s</coordinates>\n      </LineString>\n" % " ".join(c(p) for p in pts)
    pms.append("    <Placemark>\n      <name>Path</name>\n      <description>%s</description>\n%s    </Placemark>\n" % (desc(a, b), geo))
def mid(p, q): return ((p[0] + q[0]) / 2, (p[1] + q[1]) / 2)
def at(day, h, m=0, tz=CET): return datetime(2025, 3, day, h, m, tzinfo=tz)

def home_day(d, home="HOME", work="WORK"):
    path(at(d, 8), at(d, 8, 30), [HOME, mid(HOME, WORK), WORK])
    visit(work, at(d, 8, 30), at(d, 17), WORK)
    path(at(d, 17), at(d, 17, 20), [WORK, CAFE])
    visit("UNKNOWN", at(d, 17, 20), at(d, 19), CAFE)
    path(at(d, 19), at(d, 19, 15), [CAFE])                      # a path of one coordinate
    path(at(d, 18), at(d, 20), [])                              # a raw two-hour window without coordinates, over the visits
    visit(home, at(d, 19, 15), at(d + 1, 8), HOME)

visit("HOME", datetime(2025, 2, 28, 19, 0, tzinfo=CET), at(1, 8), HOME)
for d in range(1, 6): home_day(d, "INFERRED_HOME" if d == 3 else "HOME", "INFERRED_WORK" if d == 4 else "WORK")
# trip 1: two nights in Brenvik
path(at(6, 8), at(6, 8, 30), [HOME, WORK]); visit("WORK", at(6, 8, 30), at(6, 17), WORK)
path(at(6, 17), at(6, 18, 30), [WORK, mid(WORK, BREN), BREN])
visit("ALIASED_LOCATION", at(6, 18, 30), at(7, 9), BREN)
visit("SEARCHED_ADDRESS", at(7, 9, 20), at(7, 18), BREN2); path(at(7, 9), at(7, 9, 20), [BREN, BREN2]); path(at(7, 18), at(7, 18, 20), [BREN2, BREN])
visit("UNKNOWN", at(7, 18, 20), at(8, 9), BREN)
path(at(8, 9), at(8, 10, 30), [BREN, mid(BREN, HOME), HOME])
visit("HOME", at(8, 10, 30), at(9, 8), HOME)
for d in range(9, 12): home_day(d)
# trip 2: fly to Dunmore Bay (nights on Tokyo time), on to Castamar, home again
path(at(12, 7), datetime(2025, 3, 12, 21, 0, tzinfo=JST), [HOME, DUN])
visit("UNKNOWN", datetime(2025, 3, 12, 21, 0, tzinfo=JST), datetime(2025, 3, 13, 9, 0, tzinfo=JST), DUN)
for d in (13, 14):
    path(datetime(2025, 3, d, 9, 0, tzinfo=JST), datetime(2025, 3, d, 9, 30, tzinfo=JST), [DUN, DUN2])
    visit("UNKNOWN", datetime(2025, 3, d, 9, 30, tzinfo=JST), datetime(2025, 3, d, 18, 0, tzinfo=JST), DUN2)
    path(datetime(2025, 3, d, 18, 0, tzinfo=JST), datetime(2025, 3, d, 18, 30, tzinfo=JST), [DUN2, DUN])
    visit("UNKNOWN", datetime(2025, 3, d, 18, 30, tzinfo=JST), datetime(2025, 3, d + 1, 9, 0, tzinfo=JST), DUN)
path(datetime(2025, 3, 15, 10, 0, tzinfo=JST), at(15, 15), [DUN, CAS])
visit("UNKNOWN", at(15, 15), at(16, 10), CAS)
path(at(16, 10), at(16, 10, 30), [CAS, CAS2]); visit("SEARCHED_ADDRESS", at(16, 10, 30), at(16, 18), CAS2); path(at(16, 18), at(16, 18, 30), [CAS2, CAS])
visit("UNKNOWN", at(16, 18, 30), at(17, 9), CAS)
path(at(17, 9), at(17, 12), [CAS, HOME])
visit("HOME", at(17, 12), at(18, 8), HOME)
for d in range(18, 21): home_day(d)

def main(out):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "Synthetic.kml.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" ?>\n<kml xmlns="http://www.opengis.net/kml/2.2">\n  <Document>\n    <name>Timeline from Synthetic.json</name>\n'
                "    <description>Location history converted from JSON.</description>\n")
        f.write("".join(pms)); f.write("  </Document>\n</kml>\n")
    with open(os.path.join(out, "gazetteer.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# #1887 invented places for the JVM tests: name, country, lat, lon, zone\n")
        for g in GAZ: f.write("%s\t%s\t%.4f\t%.4f\t%s\n" % g)
    pts = sum(1 for p in pms if "<Point>" in p); print(len(pms), "placemarks,", pts, "points,", len(pms) - pts, "paths")

if __name__ == "__main__":
    main(sys.argv[1])
