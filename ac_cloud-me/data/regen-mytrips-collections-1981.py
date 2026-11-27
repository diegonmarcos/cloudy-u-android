#!/usr/bin/env python3
"""#1981 the MyTrips site shows the same Collections photos as Cloud Me.

The MyTrips repo (cloud-me-pub-shared, b_MyData/mymaps-mytrips) keeps its built dist/ committed and the Pages deploy serves it (its sources are overlaid after the checkout, the
deployer only rebuilds on a change of its own tree). The SOURCE change (src/components/MyTripsApp.vue, src/data/collection-images.ts, the credit rule in _mytrips.scss, publicDir in
vite.config.ts) is what a vite build turns into dist/; this script applies the same change to the committed dist/ so the deployed site matches without a local npm build:
  dist/collections/*.webp                          the photos (from public/collections, same bytes)
  dist/assets/main-<hash>.js                       card <img> src -> collections/<id>-t.webp, overlay slides -> collections/<id>.webp + travel + landscape, the credit line (manifest embedded)
  dist/assets/main-<hash>.css                      .theme-overlay__credit
  dist/index.html                                  points at the re-hashed files
  regen-mytrips-collections-1981.py          apply (idempotent) to the MyTrips clone beside this repo (or --mytrips DIR)
  regen-mytrips-collections-1981.py --check  exit 1 when the dist or the sources still use loremflickr, miss a photo, differ from public/collections or carry another credit table
"""
import hashlib, json, os, re, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MARK = "/*#1981*/"
CARD_OLD = 'K("img",{src:`https://loremflickr.com/600/400/${at.img.split(",")[0]}/all`,class:"theme-card__img"},null,8,nV)'
CARD_NEW = 'K("img",{src:`collections/${at.id}-t.webp`,alt:at.title,class:"theme-card__img"},null,8,nV)'
PROPS_OLD, PROPS_NEW = 'nV=["src"]', 'nV=["src","alt"]'
SLIDE_OLD = 'yt.value=[`https://loremflickr.com/1920/1080/${Mt.img.split(",")[0]}/all`,"https://loremflickr.com/1920/1080/travel/all","https://loremflickr.com/1920/1080/landscape/all"]'
SLIDE_NEW = 'yt.value=[`collections/${Mt.id}.webp`,"collections/travel.webp","collections/landscape.webp"]'
CR_ANCHOR = 'te(Z.value.query.length),1)])])]),K("div",gV,'
CR_NEW = ('te(Z.value.query.length),1)]),K("p",{class:"theme-overlay__credit"},["Photo: "+__cr(Z.value.id,pt.value).author+" \\u00b7 ",K("a",{href:__cr(Z.value.id,pt.value).license_url||__cr(Z.value.id,pt.value).source_url,target:"_blank",rel:"noopener"},__cr(Z.value.id,pt.value).license)," \\u00b7 ",'
          'K("a",{href:__cr(Z.value.id,pt.value).source_url,target:"_blank",rel:"noopener"},"Wikimedia Commons")])])]),K("div",gV,')
CSS_ADD = ".theme-overlay__credit{margin:14px 0 0;font-size:11px;color:#94a3b8}.theme-overlay__credit a{color:#cbd5e1}"


def mt_dir(a): return a[a.index("--mytrips") + 1] if "--mytrips" in a else os.path.join(ROOT, "cloud-me-pub-shared", "b_MyData", "mymaps-mytrips")


def credits():
    m = json.load(open(os.path.join(HERE, "geo", "collections-images.json"), encoding="utf-8"))["images"]
    return {k: {f: e[f] for f in ("title", "author", "license", "license_url", "source_url")} for k, e in m.items()}


def head(c): return MARK + "const __CR=" + json.dumps(c, ensure_ascii=True, separators=(",", ":")) + ";function __cr(i,n){return __CR[[i,\"travel\",\"landscape\"][n]]||{}}"


def names(d):
    h = open(os.path.join(d, "dist", "index.html"), encoding="utf-8").read()
    return re.search(r'src="\./assets/(main-[^"]+\.js)"', h).group(1), re.search(r'href="\./assets/(main-[^"]+\.css)"', h).group(1)


def apply(d):
    dist = os.path.join(d, "dist"); js, css = names(d); jp, cp = os.path.join(dist, "assets", js), os.path.join(dist, "assets", css)
    s = open(jp, encoding="utf-8").read(); c = open(cp, encoding="utf-8").read(); want = head(credits())
    if s.startswith(MARK):   # already patched: only the embedded credit table can have changed
        s = want + s[s.index(";function __cr(i,n)") + len(";function __cr(i,n){return __CR[[i,\"travel\",\"landscape\"][n]]||{}}"):]
    else:
        for a in (CARD_OLD, PROPS_OLD, SLIDE_OLD, CR_ANCHOR): assert s.count(a) == 1, "dist anchor not found once: " + a[:50]
        s = want + s.replace(CARD_OLD, CARD_NEW).replace(PROPS_OLD, PROPS_NEW).replace(SLIDE_OLD, SLIDE_NEW).replace(CR_ANCHOR, CR_NEW)
    if CSS_ADD not in c: c += "\n" + CSS_ADD
    nj = "main-" + hashlib.sha256(s.encode()).hexdigest()[:8] + ".js"; nc = "main-" + hashlib.sha256(c.encode()).hexdigest()[:8] + ".css"
    for old, new, body, p in ((js, nj, s, jp), (css, nc, c, cp)):
        open(os.path.join(dist, "assets", new), "w", encoding="utf-8").write(body)
        if old != new: os.remove(p)
    h = open(os.path.join(dist, "index.html"), encoding="utf-8").read().replace(js, nj).replace(css, nc); open(os.path.join(dist, "index.html"), "w", encoding="utf-8").write(h)
    os.makedirs(os.path.join(dist, "collections"), exist_ok=True)
    for f in os.listdir(os.path.join(d, "public", "collections")): shutil.copyfile(os.path.join(d, "public", "collections", f), os.path.join(dist, "collections", f))


def check(d):
    bad = []; dist = os.path.join(d, "dist"); js, css = names(d)
    s = open(os.path.join(dist, "assets", js), encoding="utf-8").read(); c = open(os.path.join(dist, "assets", css), encoding="utf-8").read()
    if "loremflickr" in s or "picsum" in s: bad.append("dist js still loads images from an image service")
    for a in (CARD_NEW, SLIDE_NEW, CR_NEW, PROPS_NEW):
        if a not in s: bad.append("dist js lacks: " + a[:60])
    if not s.startswith(head(credits())): bad.append("dist js credit table differs from data/geo/collections-images.json")
    if CSS_ADD not in c: bad.append("dist css lacks .theme-overlay__credit")
    pub, dst = os.path.join(d, "public", "collections"), os.path.join(dist, "collections")
    for f in sorted(os.listdir(os.path.join(HERE, "web", "geo-img"))):
        for nm, dd in (("public/collections", pub), ("dist/collections", dst)):
            p = os.path.join(dd, f)
            if not os.path.exists(p): bad.append("%s/%s missing" % (nm, f))
            elif open(p, "rb").read() != open(os.path.join(HERE, "web", "geo-img", f), "rb").read(): bad.append("%s/%s differs from Cloud Me data/web/geo-img" % (nm, f))
    for rel in ("src/components/MyTripsApp.vue", "src/data/collection-images.ts"):
        t = open(os.path.join(d, rel), encoding="utf-8").read()
        if "loremflickr" in t: bad.append(rel + " still loads images from loremflickr")
    if "thumbUrl(theme.id)" not in open(os.path.join(d, "src/components/MyTripsApp.vue"), encoding="utf-8").read(): bad.append("MyTripsApp.vue cards do not use thumbUrl")
    return bad


def main():
    a = sys.argv[1:]; d = mt_dir(a)
    if not os.path.isdir(d): print("no MyTrips clone at %s: skipped" % d); return
    if "--check" in a:
        bad = check(d)
        if bad: sys.exit("MyTrips site out of date (run data/regen-mytrips-collections-1981.py):\n  " + "\n  ".join(bad))
        print("MyTrips site uses the same Collections photos and credits"); return
    apply(d); bad = check(d)
    if bad: sys.exit("after apply:\n  " + "\n  ".join(bad))


if __name__ == "__main__": main()
