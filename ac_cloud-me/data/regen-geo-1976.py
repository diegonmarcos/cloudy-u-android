#!/usr/bin/env python3
"""#1976 Pers > Achieves > Geo: everything the page derives from data is GENERATED here, nothing is typed twice.

Inputs (data/geo/):
  travel-data.json            copy of MyTrips public/travel-data.json (cloud-me-pub-shared b_MyData/mymaps-mytrips): the trips, the one source of the days and the visited countries
  mytrips-themes.json         the Collections of MyTrips (its src/data/themes.ts, parsed by --sync)
  ne110-countries.min.json    Natural Earth 1:110m Admin 0 Countries (public domain), reduced (see --from-ne)
  geo-config.json             the owner's declared long-stay cities, the continents of the map and their frames
Outputs:
  data/web/geo-atlas.html     (#1981) per continent with a switcher (no global view): visited countries, visited cities, visited NomadMania regions, cultural regions; offline SVG
  data/web/geo-globe.html, geo-stats.html, geo-timeline.html   the MyTrips Home, Stats and Timeline views, offline, same data
  data/web/geo-collections.html   the MyTrips Collections cards with their photos (same-origin WebP, data/web/geo-img, #1981) and the theme overlay with the photo credit
  data/ui/pers/geo/geo-summary.json   the blocks with id geo-long-stay-cities (time as a bucket) and geo-places (NM and Days); geo-<page>.json the five web subpages

  regen-geo-1976.py             rewrite the outputs from the inputs
  regen-geo-1976.py --check     exit 1 when an output differs from what the inputs give; with the MyTrips clone beside this repo (or --mytrips DIR), also when the
                                copies of travel-data.json / themes differ from the MyTrips source
  regen-geo-1976.py --sync      copy travel-data.json and parse themes.ts from the MyTrips clone, then rewrite the outputs
  regen-geo-1976.py --from-ne F rebuild ne110-countries.min.json from the Natural Earth ne_110m_admin_0_countries.geojson F
"""
import importlib.util, json, math, os, re, sys, unicodedata
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__)); W = os.path.join(HERE, "web"); G = os.path.join(HERE, "geo")
GEO_DIR = os.path.join(HERE, "ui", "pers", "geo"); GEO_JSON = os.path.join(GEO_DIR, "geo-summary.json")
DASH = "\u2014"
IND = "\u2003"  # em space: the indent of a region under its continent


def rd(p): return json.load(open(p, encoding="utf-8"))


_sp = importlib.util.spec_from_file_location("regen_geo_images_1981", os.path.join(HERE, "regen-geo-images-1981.py")); IMAGES = importlib.util.module_from_spec(_sp); _sp.loader.exec_module(IMAGES)
def wr_json(p, o): open(p, "w", encoding="utf-8").write(json.dumps(o, ensure_ascii=False, separators=(",", ":")))
def norm(s): return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower().strip()
def days(t): return (date.fromisoformat(t["dateOut"]) - date.fromisoformat(t["dateIn"])).days
def esc(o): return json.dumps(o, ensure_ascii=False, separators=(",", ":")).replace("</", "\\u003c/")


# ---------------------------------------------------------------- derived data
def bucket(d):
    """#1981 the time spent in a city, as text: Up to 1 m (30 days), Up to 3 m (90), Up to 1 y (365), More than 1 y."""
    return "Up to 1 m" if d <= 30 else "Up to 3 m" if d <= 90 else "Up to 1 y" if d <= 365 else "More than 1 y"


def derive(cfg, trips, geo_blocks):
    """Long stay rows and Places rows (pure: the same inputs give the same rows)."""
    by_city = {}
    for t in trips:
        by_city[(norm(t["city"]), norm(t["country"]))] = by_city.get((norm(t["city"]), norm(t["country"])), 0) + days(t)
    def span(d): return bucket(d)
    rows, seen = [], set()
    for c in cfg["long_stay_cities"]:
        k = (norm(c.get("match", c["city"])), norm(c["match_country"] if "match_country" in c else c["country"]))
        seen.add(k); d = by_city.get(k)
        rows.append({"cells": [c["city"], c["country"], span(d) if d else DASH]})
    extra = sorted(((d, k) for k, d in by_city.items() if d >= cfg["long_stay_min_days"] and k not in seen), key=lambda x: -x[0])
    names = {(norm(t["city"]), norm(t["country"])): (t["city"], t["country"]) for t in trips}
    for d, k in extra: rows.append({"cells": [names[k][0], names[k][1], span(d)]})
    # Places: the existing table (cells Region, "Cities (NM)", Countries) gains NM and Days; Days from the trips, NM from the table, never invented
    cont_days = {}
    for t in trips: cont_days[t["continent"]] = cont_days.get(t["continent"], 0) + days(t)
    return rows, cont_days


def places_rows(old_rows, cfg, cont_days, title):
    """Rows in, rows out: the 3-column table of before #1976 (Region, "Cities (NM)", Countries) or the 5-column one this makes (idempotent)."""
    out = []
    for r in old_rows:
        c = r["cells"]
        if c[0] == "TOTAL": continue
        if len(c) == 5: label, cities, nm, countries = c[0].lstrip(IND), c[1], c[2], c[3]
        else:
            m = re.match(r"^\s*(\d+)\s*(?:\((\d+)\))?\s*$", c[1])
            label, cities, nm, countries = c[0], (m.group(1) if m else c[1]), (m.group(2) if m and m.group(2) is not None else DASH), c[2]
        if r.get("group"):
            conts = cfg["places_group_continents"].get(label)
            out.append({"cells": [label, cities, nm, countries, DASH if conts is None else str(sum(cont_days.get(x, 0) for x in conts))], "group": True})
        else:
            out.append({"cells": [IND + label, cities, nm, countries, DASH]})
    m = re.search(r"\{(\d+) Cities \((\d+) NM\) \| (\d+) Countries\}", title)
    if m: out.append({"cells": ["TOTAL", m.group(1), m.group(2), m.group(3), str(sum(cont_days.values()))], "group": True})
    return out


PLACES_COLS = ["Continent / Region", "Cities", "NM", "Countries", "Days"]


def rewrite_geo(blocks, cfg, trips):
    rows, cont_days = derive(cfg, trips, blocks)
    for b in blocks:
        if b.get("id") == "geo-long-stay-cities": b["rows"] = rows
        if b.get("id") == "geo-places":
            b["columns"] = PLACES_COLS
            b["rows"] = places_rows(b["rows"], cfg, cont_days, b["title"])
    return blocks


# ---------------------------------------------------------------- HTML
CSS = """:root{color-scheme:dark}*{box-sizing:border-box}html,body{margin:0;height:100%;background:#0B0414;color:#E8E4F3;font:13px/1.4 system-ui,sans-serif}
button{font:inherit;color:inherit;background:#1A0F2E;border:1px solid #3a2a5c;border-radius:999px;padding:6px 12px;cursor:pointer}
button.on{background:#7C3AED;border-color:#A78BFA;color:#fff}.row{display:flex;gap:6px;flex-wrap:wrap;padding:8px}
.card{background:#150B26;border:1px solid #2a1c46;border-radius:12px;padding:10px;margin:8px}.dim{color:#9d93b8}
.kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(96px,1fr));gap:8px;margin:8px}.kpi div{background:#150B26;border:1px solid #2a1c46;border-radius:12px;padding:8px}
.kpi b{display:block;font-size:20px;color:#C4B5FD}.kpi span{font-size:11px;color:#9d93b8}h3{margin:12px 8px 4px;font-size:14px;color:#C4B5FD}
table{border-collapse:collapse;width:100%}th,td{padding:4px 6px;text-align:left;border-bottom:1px solid #2a1c46;white-space:nowrap}th{color:#9d93b8;cursor:pointer}
.chip{display:inline-block;margin:2px;padding:2px 8px;border-radius:999px;background:#1A0F2E;border:1px solid #3a2a5c}.chip.v{background:#4c1d95;border-color:#A78BFA}
"""

ATLAS_HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Atlas</title>
<style>__CSS__svg{width:100%;height:auto;display:block;background:#0E0720;border-radius:12px}.c{fill:#1a1230;stroke:#4b3b74;stroke-width:.5}.c.v{stroke:#fff;stroke-width:.9}
.m{stroke:#fff;stroke-width:1}.sw{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:6px;vertical-align:-1px}.sw.n{background:#1a1230;border:1px solid #4b3b74}
.bar{height:10px;border-radius:5px;background:#1a1230;border:1px solid #4b3b74;overflow:hidden}.bar i{display:block;height:100%}#list{padding:0 8px 12px}.pt{stroke:#fff;stroke-width:1}</style></head><body style="overflow:auto">
<div class="row" id="sw" role="tablist" aria-label="continent"></div><div class="row" id="md" role="tablist" aria-label="layer" style="padding-top:0"></div>
<div style="padding:0 8px"><svg id="map" role="img" aria-label="atlas"></svg></div>
<div class="card" id="lg"></div><div id="list"></div>
<script id="d" type="application/json">__DATA__</script>
<script>
var D=JSON.parse(document.getElementById('d').textContent),NS='http://www.w3.org/2000/svg',cur=D.default,mode='country',$=function(i){return document.getElementById(i)};
var MODES=[['country','Countries'],['city','Cities'],['region','NomadMania regions'],['culture','Cultural regions']];
function el(n,a){var e=document.createElementNS(NS,n);for(var k in a)e.setAttribute(k,a[k]);return e}
var cul={},pal=['#60a5fa','#f59e0b','#f472b6','#34d399','#a78bfa','#22d3ee','#fb7185','#facc15','#4ade80','#c084fc'];D.cultures.forEach(function(c,i){cul[c]=pal[i%pal.length]});
function draw(){var f=D.frames[cur],W=360,cx=Math.cos((f[1]+f[3])/2*Math.PI/180),k=W/((f[2]-f[0])*cx),H=Math.round((f[3]-f[1])*k),s=$('map'),col=D.colors[cur],vis=D.visited,total=0,nv=0,names=[];
s.setAttribute('viewBox','0 0 '+W+' '+H);s.textContent='';
function X(lo){return ((lo-f[0])*cx*k).toFixed(1)}function Y(la){return ((f[3]-la)*k).toFixed(1)}
var paintC=mode==='country'||mode==='culture';
D.countries.forEach(function(c){var v=vis[c.iso],eff=v?v.continent:c.continent;if(eff!==cur)return;total++;if(v){nv++;names.push(v)}
var d=c.rings.map(function(r){return 'M'+r.map(function(q){return X(q[0])+','+Y(q[1])}).join('L')+'Z'}).join('');var on=v&&paintC;
var p=el('path',{d:d,'class':'c'+(on?' v':''),'data-iso':c.iso,'data-visited':v?1:0});if(on)p.style.fill=(mode==='culture'?(cul[D.culture[c.iso]]||col):col);
var t=el('title',{});t.textContent=c.name+(v?' (visited, '+v.days+' days)':' (not visited)');p.appendChild(t);s.appendChild(p)});
for(var iso in vis){var v=vis[iso];if(v.continent!==cur)continue;if(!D.countries.some(function(c){return c.iso===iso})){total++;nv++;names.push(v);if(paintC){var m=el('circle',{cx:X(v.lng),cy:Y(v.lat),r:3.5,fill:col,'class':'m','data-iso':iso,'data-visited':1});s.appendChild(m)}}}
var pts=[],lg='';
if(mode==='city'){pts=D.cities.filter(function(c){return c.continent===cur});pts.forEach(function(c){var q=el('circle',{cx:X(c.lng),cy:Y(c.lat),r:(1.8+Math.min(4,c.days/80)).toFixed(1),fill:col,'class':'pt','data-city':c.city,'fill-opacity':.9});var t=el('title',{});t.textContent=c.city+', '+c.country+' ('+c.days+' days)';q.appendChild(t);s.appendChild(q)})}
if(mode==='region'){pts=D.regions.filter(function(c){return c.continent===cur});pts.forEach(function(c){var q=el('circle',{cx:X(c.lng),cy:Y(c.lat),r:(3+Math.min(7,c.days/40)).toFixed(1),fill:col,'fill-opacity':.35,stroke:col,'stroke-width':1.6,'data-region':c.name});var t=el('title',{});t.textContent=c.name+' ('+c.days+' days)';q.appendChild(t);s.appendChild(q)})}
var pct=total?Math.round(nv/total*100):0;
if(mode==='country')lg='<div><span class="sw" style="background:'+col+'"></span><b>Visited</b> '+nv+' &nbsp; <span class="sw n"></span><b>Not visited</b> '+(total-nv)+'</div><div class="bar" style="margin:8px 0"><i style="width:'+pct+'%;background:'+col+'"></i></div><div class="dim">'+cur+': '+nv+' of '+total+' countries on the map ('+pct+'%)</div>';
if(mode==='city')lg='<b>'+pts.length+' visited '+(pts.length==1?'city':'cities')+'</b> in '+cur+' <span class="dim">(point size = days, land is not painted)</span>';
if(mode==='region')lg='<b>'+pts.length+' NomadMania '+(pts.length==1?'region':'regions')+'</b> in '+cur+' <span class="dim">of '+D.regions.length+' visited worldwide, '+D.world_regions+' in the world (point = the region centre of its trips; no region borders in the data)</span>';
if(mode==='culture'){var seen={};names.forEach(function(v){});lg='<b>Cultural regions</b> '+D.cultures.map(function(c){return '<span class="chip"><i class="sw" style="background:'+cul[c]+'"></i>'+c+'</span>'}).join('')}
$('lg').innerHTML=lg;
var L=mode==='city'?pts.slice().sort(function(a,b){return b.days-a.days}).map(function(c){return '<span class="chip v">'+c.city+' · '+c.days+'d</span>'}):mode==='region'?pts.slice().sort(function(a,b){return b.days-a.days}).map(function(c){return '<span class="chip v">'+c.name+' · '+c.days+'d</span>'}):names.slice().sort(function(a,b){return b.days-a.days}).map(function(v){return '<span class="chip v">'+v.flag+' '+v.name+' · '+v.days+'d</span>'});
$('list').innerHTML=L.join('')||'<span class="dim">Nothing recorded in '+cur+' yet.</span>';
[].forEach.call(document.querySelectorAll('#sw button'),function(b){b.className=b.dataset.continent===cur?'on':''});[].forEach.call(document.querySelectorAll('#md button'),function(b){b.className=b.dataset.mode===mode?'on':''})}
D.order.forEach(function(c){var b=document.createElement('button');b.textContent=c;b.dataset.continent=c;b.setAttribute('role','tab');b.onclick=function(){cur=c;draw()};$('sw').appendChild(b)});
MODES.forEach(function(m){var b=document.createElement('button');b.textContent=m[1];b.dataset.mode=m[0];b.setAttribute('role','tab');b.onclick=function(){mode=m[0];draw()};$('md').appendChild(b)});draw();
</script></body></html>
"""

COLL_CSS = """:root{color-scheme:dark}*{box-sizing:border-box}html,body{margin:0;background:#020617;color:#f8fafc;font:14px/1.5 system-ui,sans-serif}.page{padding:16px}
.collections-header{margin-bottom:20px}.collections-header__title{font-size:26px;font-weight:300;margin:0 0 6px;color:#f8fafc}.collections-header__subtitle{color:#94a3b8;margin:0}
.themes-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:14px;padding-bottom:32px}@media(min-width:700px){.themes-grid{grid-template-columns:repeat(4,1fr);gap:24px}}
.theme-card{position:relative;height:200px;border-radius:16px;overflow:hidden;cursor:pointer;border:1px solid rgba(255,255,255,.05);box-shadow:0 10px 40px rgba(0,0,0,.3);background:#0f172a}
.theme-card__img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:.6}.theme-card__overlay{position:absolute;inset:0;background:linear-gradient(to top,#020617,transparent,transparent)}
.theme-card__content{position:absolute;bottom:0;left:0;right:0;padding:14px}.theme-card__header{display:flex;align-items:center;gap:8px;margin-bottom:6px}.theme-card__icon{color:#06b6d4;font-size:18px}
.theme-card__title{font-size:16px;font-weight:700;margin:0;color:#f8fafc}.theme-card__desc{font-size:12px;color:#94a3b8;margin:0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.theme-overlay{position:fixed;inset:0;z-index:100;background:#020617;display:none;flex-direction:column;overflow:auto}.theme-overlay--visible{display:flex}
.theme-overlay__slideshow{position:absolute;inset:0;z-index:0;background:#000}.slide-img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:0;transition:opacity 1s}.slide-img.active{opacity:.5}
.theme-overlay__gradient{position:absolute;inset:0;background:linear-gradient(to bottom,#020617,rgba(2,6,23,.9),transparent);z-index:10}
.theme-overlay__left{position:relative;z-index:20;padding:56px 20px 12px}.theme-overlay__back{position:absolute;top:16px;left:16px;display:flex;gap:8px;color:#94a3b8;background:none;border:none;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.1em;cursor:pointer;padding:0}
.theme-overlay__icon{width:56px;height:56px;border-radius:14px;background:rgba(6,182,212,.2);color:#06b6d4;display:flex;align-items:center;justify-content:center;font-size:26px;margin-bottom:16px;border:1px solid rgba(6,182,212,.3)}
.theme-overlay__title{font-size:30px;font-weight:300;margin:0 0 12px;line-height:1}.theme-overlay__desc{font-size:16px;color:#cbd5e1;font-weight:300;margin:0 0 16px;padding-left:14px;border-left:2px solid #06b6d4}
.theme-overlay__count{padding:14px;border-radius:12px;width:fit-content;background:rgba(255,255,255,.06)}.theme-overlay__count-label{font-size:12px;color:#94a3b8;text-transform:uppercase;font-weight:700}.theme-overlay__count-value{font-size:26px;font-weight:300;margin-top:2px}
.theme-overlay__right{position:relative;z-index:20;padding:12px 20px 28px}.theme-overlay__map-container{border-radius:16px;overflow:hidden;border:1px solid rgba(255,255,255,.1);box-shadow:0 25px 50px -12px rgba(0,0,0,.5);background:#0f172a;position:relative}
canvas{display:block;width:100%}.theme-overlay__credit{margin:14px 0 0;font-size:11px;color:#94a3b8}.theme-overlay__credit a{color:#cbd5e1}.theme-overlay__map-label{position:absolute;bottom:10px;left:10px;background:rgba(0,0,0,.8);padding:4px 12px;border-radius:4px;font-size:12px;color:#94a3b8}
"""

COLL_HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Collections</title>
<script src="https://unpkg.com/@phosphor-icons/web"></script><style>__CSS__</style></head><body>
<div class="page" id="grid"></div>
<div class="theme-overlay" id="ov"><div class="theme-overlay__slideshow" id="ss"><div class="theme-overlay__gradient"></div></div>
<div class="theme-overlay__left"><button class="theme-overlay__back" id="bk"><i class="ph-bold ph-arrow-left"></i> Back</button><div class="theme-overlay__icon"><i id="oi"></i></div><h1 class="theme-overlay__title" id="ot"></h1><p class="theme-overlay__desc" id="od"></p>
<div class="theme-overlay__count"><div class="theme-overlay__count-label">Cities Included</div><div class="theme-overlay__count-value" id="oc"></div></div><p class="theme-overlay__credit" id="cr"></p></div>
<div class="theme-overlay__right"><div class="theme-overlay__map-container"><canvas id="tm"></canvas><div class="theme-overlay__map-label">Filtered View</div></div></div></div>
<script id="d" type="application/json">__DATA__</script>
<script>
var D=JSON.parse(document.getElementById('d').textContent),$=function(i){return document.getElementById(i)},tmr=null;
var P='geo-img/',thumb=function(k){return P+k+'-t.webp'},hero=function(k){return P+k+'.webp'};   /* #1981 the photos are bundled WebP (data/geo/collections-images.json): no image service, works offline */
function credit(k){var c=D.credits[k],e=$('cr');e.textContent='';var a=document.createElement('a');a.href=c.source_url;a.target='_blank';a.rel='noopener';a.textContent='Wikimedia Commons';e.appendChild(document.createTextNode('Photo: '+c.author+' \u00b7 '));var l=document.createElement('a');l.href=c.license_url||c.source_url;l.target='_blank';l.rel='noopener';l.textContent=c.license;e.appendChild(l);e.appendChild(document.createTextNode(' \u00b7 '));e.appendChild(a)}
$('grid').innerHTML='<div class="collections-header"><h3 class="collections-header__title">Curated Collections</h3><p class="collections-header__subtitle">Explore the world through '+D.themes.length+' unique lenses.</p></div><div class="themes-grid">'+D.themes.map(function(t,i){return '<div class="theme-card" data-t="'+t.id+'" style="background:linear-gradient(135deg,hsl('+(i*47%360)+',45%,24%),#020617)"><img class="theme-card__img" alt="'+t.title+'" src="'+thumb(t.id)+'"><div class="theme-card__overlay"></div><div class="theme-card__content"><div class="theme-card__header"><i class="ph-fill ph-'+t.icon+' theme-card__icon"></i><h4 class="theme-card__title">'+t.title+'</h4></div><p class="theme-card__desc">'+t.desc+'</p></div></div>'}).join('')+'</div>';
function ring(g,r,X){g.beginPath();r.forEach(function(p,i){var q=X(p[0],p[1]);i?g.lineTo(q[0],q[1]):g.moveTo(q[0],q[1])});g.closePath()}
function open(t){var ss=$('ss');[].slice.call(ss.querySelectorAll('img')).forEach(function(i){i.remove()});
var KS=[t.id,'travel','landscape'];KS.forEach(function(q,i){var m=document.createElement('img');m.className='slide-img'+(i?'':' active');m.alt=i?'':t.title;m.src=hero(q);ss.appendChild(m)});credit(t.id);
var k=0;clearInterval(tmr);tmr=setInterval(function(){var l=ss.querySelectorAll('img');if(!l.length)return;l[k%l.length].className='slide-img';k++;l[k%l.length].className='slide-img active';credit(KS[k%KS.length])},4000);
$('oi').className='ph-fill ph-'+t.icon;$('ot').textContent=t.title;$('od').textContent=t.desc;$('oc').textContent=t.query.length;$('ov').className='theme-overlay theme-overlay--visible';
var c=$('tm'),w=c.clientWidth||340,h=Math.round(w*.55);c.width=w*2;c.height=h*2;c.style.height=h+'px';var g=c.getContext('2d');g.scale(2,2);g.fillStyle='#0f172a';g.fillRect(0,0,w,h);var s=w/360;function X(lo,la){return [(lo+180)*s,(90-la)*s]}
g.fillStyle='#1e293b';D.world.forEach(function(k){k.rings.forEach(function(r){ring(g,r,X);g.fill()})});
var q=t.query;D.cities.forEach(function(x){if(q.indexOf(x.city)<0)return;var p=X(x.lng,x.lat);g.fillStyle='#fff';g.strokeStyle='#06b6d4';g.lineWidth=2;g.beginPath();g.arc(p[0],p[1],5,0,6.3);g.fill();g.stroke()})}
[].forEach.call(document.querySelectorAll('.theme-card'),function(e){e.onclick=function(){open(D.themes.filter(function(t){return t.id===e.dataset.t})[0])}});
$('bk').onclick=function(){$('ov').className='theme-overlay';clearInterval(tmr)};
</script></body></html>
"""


TRIPS_HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>MyTrips __VIEW__</title>
<style>__CSS__body{display:flex;flex-direction:column}#main{flex:1;overflow:auto}canvas{display:block;width:100%;background:#0E0720;border-radius:12px;touch-action:none}
.tabs{display:flex;gap:6px;padding:8px;overflow-x:auto}.tabs button{flex:none}.bar{height:8px;border-radius:4px;background:#7C3AED}.lg i{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:4px}</style></head><body>
<div id="main"></div>
<script id="d" type="application/json">__DATA__</script>
<script>
var D=JSON.parse(document.getElementById('d').textContent),C=D.cols,T=D.trips.map(function(a){var o={};C.forEach(function(k,i){o[k]=a[i]});o.days=Math.round((new Date(o.dateOut)-new Date(o.dateIn))/864e5);return o}),
CC={'Europe':'#60a5fa','Asia':'#f59e0b','South America':'#f472b6','North America':'#34d399','Africa':'#a78bfa','Oceania':'#22d3ee'},
$=function(i){return document.getElementById(i)},M=$('main'),view='home',ISO={},CITY={};
T.forEach(function(t){ISO[t.countryISO]=t});
function by(f,v){var o={};T.forEach(function(t){var k=f(t);(o[k]=o[k]||{k:k,days:0,n:0,cities:{}});o[k].days+=v?v(t):t.days;o[k].n++;o[k].cities[t.city+'|'+t.country]=1});return Object.keys(o).map(function(k){var x=o[k];x.c=Object.keys(x.cities).length;return x})}
var cities=by(function(t){return t.city+'|'+t.country}),countries=by(function(t){return t.country}),conts=by(function(t){return t.continent}),regions=by(function(t){return t.nomadRegion}),langs=by(function(t){return t.language}),cults=by(function(t){return t.culture}),
total=T.reduce(function(s,t){return s+t.days},0);
cities.forEach(function(c){var p=c.k.split('|');c.city=p[0];c.country=p[1];CITY[c.city.toLowerCase()]=1});
function srt(a,f){return a.slice().sort(function(x,y){return f(y)-f(x)})}
function kpi(l){return '<div class="kpi">'+l.map(function(x){return '<div><b>'+x[1]+'</b><span>'+x[0]+'</span></div>'}).join('')+'</div>'}
function tier(title,a,v,lo,hi,fmt){var r=srt(a,v).filter(function(x){return v(x)>=lo&&v(x)<hi});if(!r.length)return '';return '<div class="card"><b>'+title+'</b>'+r.map(function(x){return '<div>'+(x.city||x.k)+' <span class="dim">'+fmt(x)+'</span></div>'}).join('')+'</div>'}
function bars(a,v,fmt){var m=Math.max.apply(null,a.map(v));return a.map(function(x){return '<div style="margin:6px 8px"><div style="display:flex;justify-content:space-between"><span>'+x.k+'</span><span class="dim">'+fmt(x)+'</span></div><div class="bar" style="width:'+Math.max(2,v(x)/m*100)+'%;background:'+(CC[x.k]||'#7C3AED')+'"></div></div>'}).join('')}
var W=D.world,pan={x:0,y:0,z:1};
function ring(g,r,X){g.beginPath();var st=false;r.forEach(function(p){var q=X(p[0],p[1]);if(!q){st=false;return}st?g.lineTo(q[0],q[1]):g.moveTo(q[0],q[1]);st=true});g.closePath()}
function fitCanvas(c){var w=c.clientWidth||340,h=Math.round(w*(c.dataset.r||.62));c.width=w*2;c.height=h*2;c.style.height=h+'px';var g=c.getContext('2d');g.scale(2,2);return [g,w,h]}
function flat(c,paint,dots,lines){var a=fitCanvas(c),g=a[0],w=a[1],h=a[2],s=w/360*pan.z;function X(lo,la){return [(lo+180)*s+pan.x,(90-la)*s+pan.y]}g.fillStyle='#0E0720';g.fillRect(0,0,w,h);
W.forEach(function(k){g.fillStyle=paint(k)||'#241638';k.rings.forEach(function(r){ring(g,r,X);g.fill()})});
if(lines){g.strokeStyle='rgba(196,181,253,.25)';var o=X(D.home.lng,D.home.lat);lines.forEach(function(d){var p=X(d.lng,d.lat);g.beginPath();g.moveTo(o[0],o[1]);g.lineTo(p[0],p[1]);g.stroke()})}
dots.forEach(function(d){var p=X(d.lng,d.lat);g.fillStyle=CC[d.continent]||'#fff';g.beginPath();g.arc(p[0],p[1],d.r||2.2,0,6.3);g.fill()})}
function drag(c,fn){var d=null;c.onpointerdown=function(e){d=[e.clientX,e.clientY];c.setPointerCapture(e.pointerId)};c.onpointermove=function(e){if(!d)return;fn(e.clientX-d[0],e.clientY-d[1]);d=[e.clientX,e.clientY]};c.onpointerup=function(){d=null}}
var VIEWS={
home:function(){M.innerHTML='<div style="padding:8px"><canvas id="g" data-r="1"></canvas></div><div class="row lg dim">'+Object.keys(CC).map(function(k){return '<span><i style="background:'+CC[k]+'"></i>'+k+'</span>'}).join('')+'</div>'+kpi([['Cities',cities.length],['Countries',countries.length],['Continents',conts.length]]);
var c=$('g'),lo=-20,la=25,vis={};T.forEach(function(t){vis[t.countryISO]=t.continent});
function paint(){var a=fitCanvas(c),g=a[0],w=a[1],R=w/2-6,cx=w/2,cy=w/2,l0=lo*Math.PI/180,p0=la*Math.PI/180;g.clearRect(0,0,w,w);g.fillStyle='#0E0720';g.beginPath();g.arc(cx,cy,R,0,6.3);g.fill();
function X(lo2,la2){var l=(lo2)*Math.PI/180-l0,p=la2*Math.PI/180,cc=Math.sin(p0)*Math.sin(p)+Math.cos(p0)*Math.cos(p)*Math.cos(l);if(cc<0)return null;return [cx+R*Math.cos(p)*Math.sin(l),cy-R*(Math.cos(p0)*Math.sin(p)-Math.sin(p0)*Math.cos(p)*Math.cos(l))]}
W.forEach(function(k){g.fillStyle=vis[k.iso]?CC[vis[k.iso]]:'#2d1f4a';g.globalAlpha=vis[k.iso]?.75:1;k.rings.forEach(function(r){ring(g,r,X);g.fill()})});g.globalAlpha=1;
cities.forEach(function(x){var t=T.filter(function(t){return t.city===x.city&&t.country===x.country})[0],p=X(t.lng,t.lat);if(!p)return;g.fillStyle=CC[t.continent];g.beginPath();g.arc(p[0],p[1],2,0,6.3);g.fill()})}
paint();drag(c,function(dx,dy){lo-=dx/2;la=Math.max(-80,Math.min(80,la+dy/2));paint()})},
stats:function(){var h='<h3>Overview</h3>'+kpi([['Continents',conts.length],['Countries',countries.length],['NomadMania Regions',regions.length],['Cities',cities.length],['Time Abroad',(total/365).toFixed(1)+'y'],['% Countries (UN)',Math.round(countries.length/D.world_countries*100)+'%'],['% NomadMania Regions',Math.round(regions.length/D.world_regions*100)+'%']]);
var f=function(x){return x.days+'d'};
h+='<h3>Long Stay Cities</h3>'+tier('Extended Stays (6+ months)',cities,function(x){return x.days},180,1e9,function(x){return Math.round(x.days/30)+' months'})+tier('Long Stays (1.5-6 months)',cities,function(x){return x.days},45,180,function(x){return x.days>=60?Math.round(x.days/30)+' months':x.days+' days'})+tier('Medium Stays (2-5 weeks)',cities,function(x){return x.days},14,36,f);
h+='<h3>Most Time Countries</h3>'+tier('Most Time (1+ year)',countries,function(x){return x.days},365,1e9,function(x){return (x.days/365).toFixed(1)+' years'})+tier('Long Time (3-12 months)',countries,function(x){return x.days},90,365,function(x){return Math.round(x.days/30)+' months'})+tier('Medium Time (1-3 months)',countries,function(x){return x.days},30,90,f);
h+='<h3>Most Visited Countries</h3>'+tier('Most Cities (10+)',countries,function(x){return x.c},10,1e9,function(x){return x.c+' cities'})+tier('Many Cities (5-9)',countries,function(x){return x.c},5,10,function(x){return x.c+' cities'})+tier('Few Cities (2-4)',countries,function(x){return x.c},2,5,function(x){return x.c+' cities'});
var yrs={};T.forEach(function(t){var y=t.dateIn.slice(0,4);(yrs[y]=yrs[y]||{c:{},k:{}});yrs[y].c[t.city+t.country]=1;yrs[y].k[t.country]=1});
h+='<h3>Timeline</h3><div class="card"><table><tr><th>Year</th><th>Cities</th><th>Countries</th></tr>'+Object.keys(yrs).sort().map(function(y){return '<tr><td>'+y+'</td><td>'+Object.keys(yrs[y].c).length+'</td><td>'+Object.keys(yrs[y].k).length+'</td></tr>'}).join('')+'</table></div>';
h+='<h3>Geographic Coverage</h3>'+bars(srt(conts,function(x){return x.c}),function(x){return x.c},function(x){return x.c+' cities'});
h+='<h3>Languages Spoken ('+langs.length+')</h3>'+bars(srt(langs,function(x){return x.days}),function(x){return x.days},f)+'<h3>Cultural Zones ('+cults.length+')</h3>'+bars(srt(cults,function(x){return x.days}),function(x){return x.days},f);
h+='<h3>Nomad Regions ('+regions.length+')</h3><div class="card"><table><tr><th>Region</th><th>Cities</th><th>Days</th></tr>'+srt(regions,function(x){return x.days}).map(function(x){return '<tr><td>'+x.k+'</td><td>'+x.c+'</td><td>'+x.days+'</td></tr>'}).join('')+'</table></div>';
var st={};T.forEach(function(t){st[t.state]=1});var N=cities.length,hs=function(p){var s={};T.forEach(function(t){if(p(t))s[t.city+t.country]=1});return Object.keys(s).length};
h+='<h3>Special Categories</h3>'+kpi([['States/Provinces',Object.keys(st).length],['Languages',langs.length],['Cultures',cults.length]])+'<h3>Hemisphere Distribution</h3>'+kpi([['Northern',hs(function(t){return t.lat>=0})],['Southern',hs(function(t){return t.lat<0})],['Eastern',hs(function(t){return t.lng>=0})],['Western',hs(function(t){return t.lng<0})]]);
var bk=[['Day trips',0,1],['2-3 Days',2,3],['4-7 Days',4,7],['2-4 Weeks',8,28],['1-3 Months',29,90],['3-6 Months',91,180],['6+ Months',181,1e9]];
h+='<h3>Trip Duration Breakdown</h3>'+kpi(bk.map(function(b){return [b[0],T.filter(function(t){return t.days>=b[1]&&t.days<=b[2]}).length]}).concat([['Avg. Stay',(total/T.length).toFixed(1)+' days']]));
h+='<h3>Days by Continent</h3>'+bars(srt(conts,f2),f2,function(x){return x.days>=365?(x.days/365).toFixed(1)+' yrs':x.days+'d'});function f2(x){return x.days}
function ex(k,s){return T.slice().sort(function(a,b){return s*(b[k]-a[k])})[0]}
h+='<h3>Geographic Extremes</h3>'+kpi([['Northernmost',ex('lat',1).city+', '+ex('lat',1).lat.toFixed(2)+'\u00b0N'],['Southernmost',ex('lat',-1).city+', '+Math.abs(ex('lat',-1).lat).toFixed(2)+'\u00b0S'],['Easternmost',ex('lng',1).city+', '+ex('lng',1).lng.toFixed(2)+'\u00b0E'],['Westernmost',ex('lng',-1).city+', '+Math.abs(ex('lng',-1).lng).toFixed(2)+'\u00b0W']]);
M.innerHTML=h},
timeline:function(){var fc='',fk='',sk='dateIn',sd=-1;function r(){var rows=T.filter(function(t){return(!fc||t.continent===fc)&&(!fk||t.country===fk)}).sort(function(a,b){var x=a[sk],y=b[sk];return(x>y?1:x<y?-1:0)*sd});
var co=Object.keys(CC).filter(function(c){return conts.some(function(x){return x.k===c})}),ks=countries.map(function(x){return x.k}).sort();
M.innerHTML=kpi([['Continents',new Set(rows.map(function(t){return t.continent})).size],['Countries',new Set(rows.map(function(t){return t.country})).size],['Cities',new Set(rows.map(function(t){return t.city+t.country})).size]])+'<div class="row"><select id="fc"><option value="">All Continents</option>'+co.map(function(c){return '<option'+(c===fc?' selected':'')+'>'+c+'</option>'}).join('')+'</select><select id="fk"><option value="">All Countries</option>'+ks.map(function(c){return '<option'+(c===fk?' selected':'')+'>'+c+'</option>'}).join('')+'</select><button id="rs">Reset</button></div>'+
'<div style="overflow:auto"><table><tr>'+[['continent','Continent'],['country','Country'],['state','State'],['city','City'],['dateIn','Date In'],['days','Days']].map(function(c){return '<th data-s="'+c[0]+'">'+c[1]+(sk===c[0]?(sd>0?' \u25b2':' \u25bc'):'')+'</th>'}).join('')+'</tr>'+rows.map(function(t){return '<tr><td>'+t.continent+'</td><td>'+t.countryFlag+' '+t.country+'</td><td>'+t.state+'</td><td>'+t.city+'</td><td>'+t.dateIn+'</td><td>'+t.days+'d</td></tr>'}).join('')+'</table></div>';
$('fc').onchange=function(){fc=this.value;r()};$('fk').onchange=function(){fk=this.value;r()};$('rs').onclick=function(){fc=fk='';r()};[].forEach.call(M.querySelectorAll('th[data-s]'),function(h){h.onclick=function(){sd=sk===h.dataset.s?-sd:1;sk=h.dataset.s;r()}})}r()}};
function show(v){view=v;M.scrollTop=0;VIEWS[v]()}
show('__VIEW__');
</script></body></html>
"""


def build_pages(cfg, trips, world, themes, credits):
    vis = {}
    for t in trips:
        v = vis.setdefault(t["countryISO"], {"name": t["country"], "flag": t["countryFlag"], "continent": t["continent"], "days": 0,
                                             "lat": t["countryCapital"]["lat"], "lng": t["countryCapital"]["lng"]})
        v["days"] += days(t)
    cit, reg, cul = {}, {}, {}
    for t in trips:
        c = cit.setdefault((t["city"], t["country"]), {"city": t["city"], "country": t["country"], "continent": t["continent"], "iso": t["countryISO"], "lat": t["lat"], "lng": t["lng"], "days": 0})
        c["days"] += days(t)
        r = reg.setdefault(t["nomadRegion"], {"name": t["nomadRegion"], "continent": t["continent"], "days": 0, "n": 0, "la": 0.0, "lo": 0.0, "cities": set()})
        r["days"] += days(t); r["n"] += 1; r["la"] += t["lat"]; r["lo"] += t["lng"]; r["cities"].add(t["city"])
        cul[t["countryISO"]] = t["culture"]
    regions = [{"name": r["name"], "continent": r["continent"], "days": r["days"], "lat": round(r["la"] / r["n"], 3), "lng": round(r["lo"] / r["n"], 3), "cities": len(r["cities"])} for r in reg.values()]
    order = cfg["map_continents"]
    wc = [{"iso": c["iso"], "name": c["name"], "continent": c["continent"], "rings": c["rings"]} for c in world["countries"]]
    at = {"default": cfg["map_default"], "order": order, "frames": cfg["map_frames"], "colors": cfg["map_colors"], "visited": vis,
          "countries": [c for c in wc if (vis.get(c["iso"], {}).get("continent") or c["continent"]) in order],
          "cities": list(cit.values()), "regions": regions, "culture": cul, "cultures": sorted(set(cul.values())), "world_regions": cfg["world_nomad_regions"]}
    cols = ["continent", "country", "nomadRegion", "state", "city", "dateIn", "dateOut", "lat", "lng", "language", "culture", "countryISO", "countryFlag"]
    th = {"cols": cols, "trips": [[t[k] for k in cols] for t in trips], "home": cfg["home"], "themes": themes,
          "world_countries": cfg["world_countries_un"], "world_regions": cfg["world_nomad_regions"],
          "world": [{"iso": c["iso"], "rings": c["rings"]} for c in world["countries"]]}
    co = {"themes": themes, "cities": list(cit.values()), "world": th["world"], "credits": credits}
    mk = lambda view: TRIPS_HTML.replace("__CSS__", CSS).replace("__DATA__", esc(th)).replace("__VIEW__", view)
    return {"geo-atlas.html": ATLAS_HTML.replace("__CSS__", CSS).replace("__DATA__", esc(at)), "geo-globe.html": mk("home"), "geo-stats.html": mk("stats"), "geo-timeline.html": mk("timeline"),
            "geo-collections.html": COLL_HTML.replace("__CSS__", COLL_CSS).replace("__DATA__", esc(co))}


SUBPAGES = [("geo-globe", "geo-globe.html", "Globe: MyTrips Home, the trips on a globe you turn with a finger"),
            ("geo-atlas", "geo-atlas.html", "Atlas: visited countries (visited vs not visited, legend with counts and %), visited cities (points) and visited NomadMania regions (points), per continent, no global view"),
            ("geo-collections", "geo-collections.html", "Collections: the MyTrips Curated Collections cards with images and the theme overlay"),
            ("geo-stats", "geo-stats.html", "Stats: the MyTrips Stats view"), ("geo-timeline", "geo-timeline.html", "Timeline: the MyTrips Timeline view")]


def parse_themes(ts):
    out = []
    for m in re.finditer(r"\{\s*id:\s*'([^']+)',\s*title:\s*'([^']+)',\s*icon:\s*'([^']+)',\s*query:\s*\[([^\]]*)\],\s*img:\s*'([^']*)',\s*desc:\s*'([^']*)'\s*\}", ts):
        out.append({"id": m.group(1), "title": m.group(2), "icon": m.group(3), "query": re.findall(r"'([^']*)'", m.group(4)), "img": m.group(5), "desc": m.group(6)})
    return out


def outputs():
    cfg = rd(os.path.join(G, "geo-config.json")); trips = rd(os.path.join(G, "travel-data.json"))["trips"]
    world = rd(os.path.join(G, "ne110-countries.min.json")); themes = rd(os.path.join(G, "mytrips-themes.json"))
    geo = rewrite_geo(rd(GEO_JSON), cfg, trips)
    bad = IMAGES.validate()   # #1981 a missing, tiny, duplicated or unattributed Collections image fails the generator
    if bad: sys.exit("Collections images invalid (data/regen-geo-images-1981.py --fetch):\n  " + "\n  ".join(bad))
    credits = {k: {f: e[f] for f in ("title", "author", "license", "license_url", "source_url")} for k, e in rd(os.path.join(G, "collections-images.json"))["images"].items()}
    out = {os.path.join(W, k): v for k, v in build_pages(cfg, trips, world, themes, credits).items()}
    out[GEO_JSON] = json.dumps(geo, ensure_ascii=False, indent=2) + "\n"
    for pid, url, doc in SUBPAGES:
        out[os.path.join(GEO_DIR, pid + ".json")] = json.dumps([{"kind": "fragment", "id": "web", "_doc": "#1981 Geo > " + doc + " (data/regen-geo-1976.py).", "url": url}], ensure_ascii=False, indent=2) + "\n"
    return out


def main():
    a = sys.argv[1:]
    mt = a[a.index("--mytrips") + 1] if "--mytrips" in a else os.path.abspath(os.path.join(HERE, "..", "..", "..", "cloud-me-pub-shared", "b_MyData", "mymaps-mytrips"))
    if "--from-ne" in a:
        g = rd(a[a.index("--from-ne") + 1]); out = []
        def ring(r):
            o, last = [], None
            for x, y in r:
                p = [round(x, 2), round(y, 2)]
                if p != last: o.append(p); last = p
            return o
        for f in g["features"]:
            p = f["properties"]
            if p["ISO_A2_EH"] in ("-99", "AQ") or p["CONTINENT"] in ("Antarctica", "Seven seas (open ocean)"): continue
            geo = f["geometry"]; polys = geo["coordinates"] if geo["type"] == "MultiPolygon" else [geo["coordinates"]]
            out.append({"iso": p["ISO_A2_EH"], "name": p["NAME"], "continent": p["CONTINENT"], "rings": [ring(q[0]) for q in polys]})
        out.sort(key=lambda c: c["iso"]); old = rd(os.path.join(G, "ne110-countries.min.json"))
        wr_json(os.path.join(G, "ne110-countries.min.json"), {"_doc": old["_doc"], "countries": out}); return
    src_td, src_th = os.path.join(mt, "public", "travel-data.json"), os.path.join(mt, "src", "data", "themes.ts")
    if "--sync" in a:
        open(os.path.join(G, "travel-data.json"), "wb").write(open(src_td, "rb").read())
        wr_json(os.path.join(G, "mytrips-themes.json"), parse_themes(open(src_th, encoding="utf-8").read()))
    outs = outputs()
    if "--check" in a:
        bad = [p for p, s in outs.items() if open(p, encoding="utf-8").read() != s]
        if os.path.exists(src_td):
            if open(src_td, "rb").read() != open(os.path.join(G, "travel-data.json"), "rb").read(): bad.append("travel-data.json differs from the MyTrips source")
            if parse_themes(open(src_th, encoding="utf-8").read()) != rd(os.path.join(G, "mytrips-themes.json")): bad.append("mytrips-themes.json differs from the MyTrips source")
        if bad: sys.exit("geo outputs out of date (run data/regen-geo-1976.py): " + ", ".join(bad))
        print("geo outputs match their inputs"); return
    for p, s in outs.items(): open(p, "w", encoding="utf-8").write(s)


if __name__ == "__main__": main()
