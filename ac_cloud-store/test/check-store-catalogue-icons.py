#!/usr/bin/env python3
"""#1791 HEAD-checks every icon URL of the Store catalogue (libs:appstore assets/store-catalogue.json): 200 and an image content type.
Network, so it is NOT part of the ship tests (they must not flake): run it by dispatch (.github/workflows/store-catalogue-icons.yml).
A URL that fails twice is reported; exit 1 if any. Usage: check-store-catalogue-icons.py [catalogue.json]"""
import concurrent.futures as cf, json, sys, time, urllib.request
path = sys.argv[1] if len(sys.argv) > 1 else "ab_cloud-libs-shared/libs/appstore/src/main/assets/store-catalogue.json"
apps = json.load(open(path, encoding="utf-8"))["apps"]
def one(a):
    last = ""
    for attempt in range(3):
        for method in ("HEAD", "GET"):
            try:
                r = urllib.request.urlopen(urllib.request.Request(a["icon"], method=method, headers={"User-Agent": "CloudStore/1 (icon check)"}), timeout=25)
                if r.status == 200 and "image" in (r.headers.get("content-type") or ""): return a["pkg"], None
                last = "%s %s" % (r.status, r.headers.get("content-type"))
            except Exception as e:
                last = str(e)[:80]
        time.sleep(2 * (attempt + 1))
    return a["pkg"], last
with cf.ThreadPoolExecutor(8) as ex: res = list(ex.map(one, apps))
bad = [(p, e) for p, e in res if e]
print("%d icons checked, %d failed" % (len(res), len(bad)))
for p, e in bad: print("  FAIL %s: %s" % (p, e))
sys.exit(1 if bad else 0)
