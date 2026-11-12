#!/usr/bin/env python3
"""#1666 The location-class call-site scanner behind test-location-use-scoped.sh.

Lists every call of an API Android counts as LOCATION USE (the location app-ops behind the quick panel's "being used" and the
privacy dot) in the SuperApp and every lib it links (transitively, from the build.gradle project() lines), one line per
(file, api, count). Comments are ignored. A read that the platform hands WITHOUT location data (INCLUDE_LOCATION_DATA_NONE) is
not location use and is not listed.

usage: location_use_scan.py <repo-root>            prints  path|api|count
"""
import os, re, sys

APIS = {
    "fix":        r"\brequestLocationUpdates\b|\bgetCurrentLocation\b|\brequestSingleUpdate\b|\bgetLastKnownLocation\b|\.lastLocation\b",
    "gnss":       r"\bregisterGnssStatusCallback\b|\bregisterGnssMeasurementsCallback\b|\baddNmeaListener\b",
    "wifi-scan":  r"\bwm\??\.startScan\(|\bWifiManager\b.*\.startScan\(|\bscanResults\b|\bgetScanResults\b",
    "wifi-info":  r"\bconnectionInfo\b|\bgetConnectionInfo\b",
    "ble-scan":   r"\bscanner\.startScan\(|\bbluetoothLeScanner\??\.startScan\(|\b\w+\.startDiscovery\(\)",
    "cell":       r"\bgetAllCellInfo\b|\ballCellInfo\b|\brequestCellInfoUpdate\b|CellInfoListener|CellLocationListener|\bgetCellLocation\b|\bcellLocation\b|LISTEN_CELL_INFO|LISTEN_CELL_LOCATION",
    "service-state": r"\btm\.serviceState\b|\.getServiceState\(|LISTEN_SERVICE_STATE",
    "hotspot":    r"\bstartLocalOnlyHotspot\b",
    "wifi-p2p":   r"\.createGroup\(|\.discoverPeers\(",
    "fgs-location": r'foregroundServiceType="location"',
}
NOT_LOCATION = re.compile(r"INCLUDE_LOCATION_DATA_NONE")


def libs_closure(root):
    def deps(gradle):
        try:
            txt = open(gradle).read()
        except OSError:
            return set()
        return set(re.findall(r"project\(\s*['\"]:libs:([\w-]+)['\"]\s*\)", txt))
    seen, todo = set(), deps(os.path.join(root, "aa_cloud-superapp/app/build.gradle"))
    while todo:
        l = todo.pop()
        if l in seen:
            continue
        seen.add(l)
        todo |= deps(os.path.join(root, "ab_cloud-libs-shared/libs", l, "build.gradle"))
    return sorted(seen)


STRING = re.compile(r'"(?:[^"\\\n]|\\.)*"')


def code_lines(text, xml=False):
    """Source lines with comments blanked (line, block, XML), so a comment naming an API is not a call. String literals are
    blanked first so a "/*" inside a string (a path glob) cannot open a block comment."""
    if xml:
        text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
        return text.splitlines()
    out, block = [], False
    for line in text.splitlines():
        s = line if xml else STRING.sub('""', line)
        if block:
            if "*/" in s:
                s = s.split("*/", 1)[1]; block = False
            else:
                out.append(""); continue
        s = re.sub(r"/\*.*?\*/", "", s)
        if "/*" in s:
            s = s.split("/*", 1)[0]; block = True
        s = re.sub(r"//.*$", "", s)
        if re.match(r"^\s*\*", s):
            s = ""
        out.append(s)
    return out


def roots(root):
    yield os.path.join(root, "aa_cloud-superapp/app/src/main")
    for l in libs_closure(root):
        yield os.path.join(root, "ab_cloud-libs-shared/libs", l, "src/main")


def scan(root, override=None):
    """{(relpath, api): count}. [override] maps relpath -> text (tests plant calls without touching the tree)."""
    hits = {}
    files = []
    for r in roots(root):
        for dp, _, fs in os.walk(r):
            for f in fs:
                if f.endswith((".kt", ".java")) or f == "AndroidManifest.xml":
                    files.append(os.path.relpath(os.path.join(dp, f), root))
    for rel in (override or {}):
        if rel not in files:
            files.append(rel)
    for rel in files:
        text = (override or {}).get(rel)
        if text is None:
            try:
                text = open(os.path.join(root, rel), encoding="utf-8", errors="replace").read()
            except OSError:
                continue
        for line in code_lines(text, xml=rel.endswith(".xml")):
            if not line.strip() or NOT_LOCATION.search(line):
                continue
            for api, rx in APIS.items():
                if re.search(rx, line):
                    hits[(rel, api)] = hits.get((rel, api), 0) + 1
    return hits


if __name__ == "__main__":
    for (p, a), n in sorted(scan(sys.argv[1]).items()):
        print(f"{p}|{a}|{n}")
