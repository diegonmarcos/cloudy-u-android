#!/usr/bin/env python3
"""#1981 Geo > Collections: ONE real photo per MyTrips collection (and the two generic slideshow photos), sourced once from Wikimedia Commons, kept as WebP.

Declared input   data/geo/collections-images-src.json   key (the 16 collection ids of mytrips-themes.json, plus "travel" and "landscape", the two generic slideshow
                                                        slides of the MyTrips overlay) -> the Commons file title. Free licences only (CC0, public domain, CC BY, CC BY-SA).
Outputs (one set, kept equal in every place that serves it):
  data/web/geo-img/<key>.webp     hero, 800 px wide (the overlay slideshow)         bundled into the Cloud Me APK (same-origin, offline)
  data/web/geo-img/<key>-t.webp   thumbnail, 400 px wide (the card)
  data/geo/collections-images.json   manifest: per key the Commons title, author, licence, licence URL, source URL, the files with width/height/bytes/sha256
  MyTrips clone  public/collections/ (the same WebP files) and src/data/collections-images.json (the same manifest): the site serves and credits the same images
  cloudy_db_assets-cdn clone  b_Media/MyTrips/collections/ (the same files + manifest): the durable media store
  regen-geo-images-1981.py            validate (same as --check, fails with the list) and nothing else
  regen-geo-images-1981.py --check    exit 1 when an image is missing, tiny (a placeholder), duplicated, not a WebP of the declared width, has no author / non-free licence /
                                      source URL, or when a copy (MyTrips clone, assets-cdn clone) differs from data/web/geo-img
  regen-geo-images-1981.py --fetch    source every declared file from the Commons API (imageinfo, one request), download the 1280 px rendition, encode the WebP with
                                      ffmpeg, write the manifest and copy the set to the other places. Run once; the committed files are the durable copy.
regen-geo-1976.py imports validate() and refuses to write the Collections page when it reports anything.
"""
import hashlib, json, os, re, shutil, subprocess, sys, tempfile, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__)); G = os.path.join(HERE, "geo"); IMG = os.path.join(HERE, "web", "geo-img")
SRC = os.path.join(G, "collections-images-src.json"); MAN = os.path.join(G, "collections-images.json"); THEMES = os.path.join(G, "mytrips-themes.json")
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MT = os.path.join(ROOT, "cloud-me-pub-shared", "b_MyData", "mymaps-mytrips"); MT_IMG = os.path.join(MT, "public", "collections"); MT_MAN = os.path.join(MT, "src", "data", "collections-images.json")
CDN = os.path.join(ROOT, "cloudy_db_assets-cdn", "b_Media", "MyTrips", "collections")
HERO_W, THUMB_W, TOL, MIN_BYTES = 800, 400, 8, 6000
FREE = re.compile(r"^(CC0|Public domain|PD[- ]|CC BY( |-)|CC BY$|CC-BY|CC-Zero)", re.I)
NONFREE = re.compile(r"\b(NC|ND)\b|non-?commercial|no[- ]deriv", re.I)
API = "https://commons.wikimedia.org/w/api.php"; UA = "cloud-me-geo-images/1.0 (offline photo manifest)"


def rd(p): return json.load(open(p, encoding="utf-8"))


def keys():
    return [t["id"] for t in rd(THEMES)] + ["travel", "landscape"]


def webp_size(b):
    """(width, height) of a WebP byte string, None when it is not one."""
    if len(b) < 30 or b[:4] != b"RIFF" or b[8:12] != b"WEBP": return None
    k = b[12:16]
    if k == b"VP8 ": return (b[26] | (b[27] << 8)) & 0x3FFF, (b[28] | (b[29] << 8)) & 0x3FFF
    if k == b"VP8L": return 1 + (((b[22] & 0x3F) << 8) | b[21]), 1 + (((b[24] & 0xF) << 10) | (b[23] << 2) | ((b[22] & 0xC0) >> 6))
    if k == b"VP8X": return 1 + int.from_bytes(b[24:27], "little"), 1 + int.from_bytes(b[27:30], "little")
    return None


def files_of(k): return {"hero": k + ".webp", "thumb": k + "-t.webp"}


def validate(img=IMG, man=MAN, with_copies=True):
    """The list of problems (empty = every Collections image is a real, attributed, free-licence WebP of the declared width)."""
    bad = []
    if not os.path.exists(man): return ["manifest %s missing" % os.path.relpath(man, HERE)]
    m = rd(man); ent = m.get("images", {}); seen = {}
    for k in keys():
        e = ent.get(k)
        if not e: bad.append("%s: no manifest entry" % k); continue
        for f in ("author", "license", "source_url", "title"):
            if not str(e.get(f, "")).strip(): bad.append("%s: manifest has no %s" % (k, f))
        lic = str(e.get("license", ""))
        if lic and (NONFREE.search(lic) or not FREE.search(lic)): bad.append("%s: licence %r is not a free licence" % (k, lic))
        if not str(e.get("source_url", "")).startswith("https://commons.wikimedia.org/wiki/File:"): bad.append("%s: source_url is not a Commons file page" % k)
        for role, want in (("hero", HERO_W), ("thumb", THUMB_W)):
            fn = files_of(k)[role]; p = os.path.join(img, fn)
            if not os.path.exists(p): bad.append("%s: %s missing" % (k, fn)); continue
            b = open(p, "rb").read(); sz = webp_size(b)
            if sz is None: bad.append("%s: %s is not a WebP" % (k, fn)); continue
            if abs(sz[0] - want) > TOL: bad.append("%s: %s is %d px wide, want about %d" % (k, fn, sz[0], want))
            if len(b) < (MIN_BYTES if role == "hero" else MIN_BYTES // 3): bad.append("%s: %s is %d bytes: a placeholder, not a photo" % (k, fn, len(b)))
            h = hashlib.sha256(b).hexdigest()
            if e.get(role, {}).get("sha256") != h: bad.append("%s: %s differs from the manifest sha256" % (k, fn))
            if h in seen: bad.append("%s: %s is byte-identical to %s (one image for two collections)" % (k, fn, seen[h]))
            seen[h] = fn
    if with_copies:
        for name, d, md in (("MyTrips clone", MT_IMG, MT_MAN), ("assets-cdn clone", CDN, os.path.join(CDN, "manifest.json"))):
            if not os.path.isdir(d): continue
            for k in keys():
                for fn in files_of(k).values():
                    a, b = os.path.join(img, fn), os.path.join(d, fn)
                    if not os.path.exists(b): bad.append("%s: %s missing" % (name, fn))
                    elif os.path.exists(a) and open(a, "rb").read() != open(b, "rb").read(): bad.append("%s: %s differs from data/web/geo-img" % (name, fn))
            if not os.path.exists(md) or open(md, "rb").read() != open(man, "rb").read(): bad.append("%s: manifest differs from data/geo/collections-images.json" % name)
    return bad


def get(url, tries=8):
    for i in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=90).read()
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503): raise
            time.sleep(min(60, int(e.headers.get("Retry-After") or 0) or 6 * (i + 1)))
        except Exception: time.sleep(5 * (i + 1))
    raise SystemExit("Commons did not answer: " + url)


def strip(h): return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", h or "")).strip()


def fetch():
    src = rd(SRC)["images"]; want = keys(); miss = [k for k in want if k not in src]
    if miss: sys.exit("collections-images-src.json has no title for: " + ", ".join(miss))
    titles = {k: src[k] for k in want}
    q = {"action": "query", "format": "json", "titles": "|".join(titles.values()), "prop": "imageinfo", "iiprop": "url|size|extmetadata", "iiurlwidth": 1280}
    d = json.loads(get(API + "?" + urllib.parse.urlencode(q)))
    norm = {n["from"]: n["to"] for n in d["query"].get("normalized", [])}
    info = {p["title"]: p["imageinfo"][0] for p in d["query"]["pages"].values() if "imageinfo" in p}
    ent = {}; tmp = tempfile.mkdtemp(prefix="geoimg-"); os.makedirs(IMG, exist_ok=True)
    try:
        for k, t in titles.items():
            i = info.get(norm.get(t, t))
            if not i: sys.exit("%s: Commons has no file %s" % (k, t))
            em = i["extmetadata"]; lic = strip(em.get("LicenseShortName", {}).get("value")); au = strip(em.get("Artist", {}).get("value")) or strip(em.get("Credit", {}).get("value"))
            if NONFREE.search(lic) or not FREE.search(lic): sys.exit("%s: licence %r is not free" % (k, lic))
            if not au: sys.exit("%s: Commons names no author" % k)
            raw = os.path.join(tmp, k + ".jpg"); open(raw, "wb").write(get(i.get("thumburl") or i["url"]))
            e = {"title": t, "author": au, "license": lic, "license_url": strip(em.get("LicenseUrl", {}).get("value")), "source_url": i["descriptionurl"], "orig_size": [i["width"], i["height"]]}
            for role, w in (("hero", HERO_W), ("thumb", THUMB_W)):
                out = os.path.join(IMG, files_of(k)[role])
                subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", raw, "-vf", "scale=%d:-2" % w, "-c:v", "libwebp", "-quality", "76" if role == "hero" else "72", "-compression_level", "6", out], check=True)
                b = open(out, "rb").read(); sz = webp_size(b)
                e[role] = {"file": files_of(k)[role], "width": sz[0], "height": sz[1], "bytes": len(b), "sha256": hashlib.sha256(b).hexdigest()}
            ent[k] = e; print("ok", k, lic, au[:40])
            time.sleep(1)
    finally: shutil.rmtree(tmp, ignore_errors=True)
    man = {"_doc": "#1981 the Collections photos: sourced once from the Wikimedia Commons API (free licences only), re-encoded to WebP (hero 800 px, thumbnail 400 px). Generated by data/regen-geo-images-1981.py --fetch; the same files serve the MyTrips site and the Cloud Me APK.",
           "sourced": time.strftime("%Y-%m-%d"), "images": ent}
    json.dump(man, open(MAN, "w", encoding="utf-8"), ensure_ascii=False, indent=2); open(MAN, "a").write("\n")
    for root, d, md in ((MT, MT_IMG, MT_MAN), (os.path.join(ROOT, "cloudy_db_assets-cdn", "b_Media"), CDN, os.path.join(CDN, "manifest.json"))):
        if not os.path.isdir(root): continue
        os.makedirs(d, exist_ok=True)
        for k in want:
            for fn in files_of(k).values(): shutil.copyfile(os.path.join(IMG, fn), os.path.join(d, fn))
        shutil.copyfile(MAN, md)


def main():
    a = sys.argv[1:]
    if "--fetch" in a: fetch()
    bad = validate()
    if bad: sys.exit("Collections images invalid (run data/regen-geo-images-1981.py --fetch):\n  " + "\n  ".join(bad))
    print("collections images: %d keys, every image a real attributed free-licence WebP" % len(keys()))


if __name__ == "__main__": main()
