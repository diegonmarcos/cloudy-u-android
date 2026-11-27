#!/usr/bin/env python3
"""#1976 Pers > Achieves > Geo: everything the page derives from data is GENERATED here, nothing is typed twice.

Inputs (data/geo/):
  travel-data.json            copy of MyTrips public/travel-data.json (cloud-me-pub-shared b_MyData/mymaps-mytrips): the trips, the one source of the days and the visited countries
  mytrips-themes.json         the Collections of MyTrips (its src/data/themes.ts, parsed by --sync)
  ne110-countries.min.json    Natural Earth 1:110m Admin 0 Countries (public domain), reduced (see --from-ne)
  geo-config.json             the owner's declared long-stay cities, the continents of the map and their frames
Outputs:
  data/web/geo-map.html       visited countries painted per continent, a continent switcher (no global view), offline SVG
  data/web/geo-mytrips.html   MyTrips natively: Home (globe) | Atlas | Collections | Stats | Timeline, offline canvas, same data
  data/ui/pers/geo.json       the blocks with id geo-long-stay-cities and geo-places (Long Stay cities with the time in each; Places with NM and Days)

  regen-geo-1976.py             rewrite the outputs from the inputs
  regen-geo-1976.py --check     exit 1 when an output differs from what the inputs give; with the MyTrips clone beside this repo (or --mytrips DIR), also when the
                                copies of travel-data.json / themes differ from the MyTrips source
  regen-geo-1976.py --sync      copy travel-data.json and parse themes.ts from the MyTrips clone, then rewrite the outputs
  regen-geo-1976.py --from-ne F rebuild ne110-countries.min.json from the Natural Earth ne_110m_admin_0_countries.geojson F
"""
import json, math, os, re, sys, unicodedata
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__)); W = os.path.join(HERE, "web"); G = os.path.join(HERE, "geo")
GEO_JSON = os.path.join(HERE, "ui", "pers", "geo.json")
DASH = "\u2014"
IND = "\u2003"  # em space: the indent of a region under its continent


def rd(p): return json.load(open(p, encoding="utf-8"))
def wr_json(p, o): open(p, "w", encoding="utf-8").write(json.dumps(o, ensure_ascii=False, separators=(",", ":")))
def norm(s): return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower().strip()
def days(t): return (date.fromisoformat(t["dateOut"]) - date.fromisoformat(t["dateIn"])).days
def esc(o): return json.dumps(o, ensure_ascii=False, separators=(",", ":")).replace("</", "\\u003c/")


# ---------------------------------------------------------------- derived data
def derive(cfg, trips, geo_blocks):
    """Long stay rows and Places rows (pure: the same inputs give the same rows)."""
    by_city = {}
    for t in trips:
        by_city[(norm(t["city"]), norm(t["country"]))] = by_city.get((norm(t["city"]), norm(t["country"])), 0) + days(t)
    def span(d): return "%d days" % d if d < 60 else ("%d days (%.1f mo)" % (d, d / 30.4) if d < 730 else "%s days (%.1f y)" % (format(d, ","), d / 365.25))
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
CSS = """*{box-sizing:border-box}html,body{margin:0;height:100%;background:#0B0414;color:#E8E4F3;font:13px/1.4 system-ui,sans-serif}
button{font:inherit;color:inherit;background:#1A0F2E;border:1px solid #3a2a5c;border-radius:999px;padding:6px 12px;cursor:pointer}
button.on{background:#7C3AED;border-color:#A78BFA;color:#fff}.row{display:flex;gap:6px;flex-wrap:wrap;padding:8px}
.card{background:#150B26;border:1px solid #2a1c46;border-radius:12px;padding:10px;margin:8px}.dim{color:#9d93b8}
.kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(96px,1fr));gap:8px;margin:8px}.kpi div{background:#150B26;border:1px solid #2a1c46;border-radius:12px;padding:8px}
.kpi b{display:block;font-size:20px;color:#C4B5FD}.kpi span{font-size:11px;color:#9d93b8}h3{margin:12px 8px 4px;font-size:14px;color:#C4B5FD}
table{border-collapse:collapse;width:100%}th,td{padding:4px 6px;text-align:left;border-bottom:1px solid #2a1c46;white-space:nowrap}th{color:#9d93b8;cursor:pointer}
.chip{display:inline-block;margin:2px;padding:2px 8px;border-radius:999px;background:#1A0F2E;border:1px solid #3a2a5c}.chip.v{background:#4c1d95;border-color:#A78BFA}
"""

MAP_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Visited countries</title>
<style>__CSS__svg{width:100%;height:auto;display:block;background:#0E0720;border-radius:12px}.c{fill:#241638;stroke:#0B0414;stroke-width:.4}.c.v{stroke:#fff;stroke-width:.5}
.m{stroke:#fff;stroke-width:1}#list{padding:0 8px 8px}</style></head><body>
<div class="row" id="sw" role="tablist" aria-label="continent"></div>
<div style="padding:0 8px"><svg id="map" role="img" aria-label="visited countries"></svg></div>
<div id="sum" class="dim" style="padding:6px 10px"></div><div id="list"></div>
<script id="d" type="application/json">__DATA__</script>
<script>
var D=JSON.parse(document.getElementById('d').textContent),NS='http://www.w3.org/2000/svg',cur=D.default;
function el(n,a){var e=document.createElementNS(NS,n);for(var k in a)e.setAttribute(k,a[k]);return e}
function draw(){var f=D.frames[cur],W=360,k=W/((f[2]-f[0])*Math.cos((f[1]+f[3])/2*Math.PI/180)),H=Math.round((f[3]-f[1])*k),cx=Math.cos((f[1]+f[3])/2*Math.PI/180),s=document.getElementById('map');
s.setAttribute('viewBox','0 0 '+W+' '+H);s.textContent='';
function P(o){return ((o[0]-f[0])*cx*k).toFixed(1)+','+((f[3]-o[1])*k).toFixed(1)}
var col=D.colors[cur],vis=D.visited,n=0,names=[];
D.countries.forEach(function(c){var v=vis[c.iso],eff=v?v.continent:c.continent;if(eff!==cur)return;
var d=c.rings.map(function(r){return 'M'+r.map(P).join('L')+'Z'}).join('');var p=el('path',{d:d,'class':'c'+(v?' v':''),'data-iso':c.iso,'data-visited':v?1:0});if(v)p.setAttribute('fill',col);
var t=el('title',{});t.textContent=c.name+(v?' ('+v.days+' days)':'');p.appendChild(t);s.appendChild(p)});
for(var iso in vis){var v=vis[iso];if(v.continent!==cur)continue;n++;names.push(v);if(!D.countries.some(function(c){return c.iso===iso}))
{var m=el('circle',{cx:((v.lng-f[0])*cx*k).toFixed(1),cy:((f[3]-v.lat)*k).toFixed(1),r:3.5,fill:col,'class':'m','data-iso':iso,'data-visited':1});var t=el('title',{});t.textContent=v.name;m.appendChild(t);s.appendChild(m)}}
document.getElementById('sum').textContent=cur+': '+n+' visited '+(n==1?'country':'countries');
names.sort(function(a,b){return b.days-a.days});
document.getElementById('list').innerHTML=names.map(function(v){return '<span class="chip v">'+v.flag+' '+v.name+' \\u00b7 '+v.days+'d</span>'}).join('')||'<span class="dim">No visit recorded in '+cur+' yet.</span>';
[].forEach.call(document.querySelectorAll('#sw button'),function(b){b.className=b.dataset.continent===cur?'on':''})}
var sw=document.getElementById('sw');D.order.forEach(function(c){var b=document.createElement('button');b.textContent=c;b.dataset.continent=c;b.setAttribute('role','tab');b.onclick=function(){cur=c;draw()};sw.appendChild(b)});draw();
</script></body></html>
"""

TRIPS_HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MyTrips</title>
<style>__CSS__body{display:flex;flex-direction:column}#main{flex:1;overflow:auto}canvas{display:block;width:100%;background:#0E0720;border-radius:12px;touch-action:none}
.tabs{display:flex;gap:6px;padding:8px;overflow-x:auto}.tabs button{flex:none}.bar{height:8px;border-radius:4px;background:#7C3AED}.lg i{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:4px}</style></head><body>
<div class="tabs" id="tabs" role="tablist" aria-label="MyTrips"></div><div id="main"></div>
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
var W=D.world,pan={x:0,y:0,z:1},mode='country';
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
atlas:function(){var modes=[['political','Cultural Regions'],['country','Countries'],['region','NomadMania Regions'],['pin','Cities']];
M.innerHTML='<div class="row" id="mm">'+modes.map(function(m){return '<button data-m="'+m[0]+'" class="'+(m[0]===mode?'on':'')+'">'+m[1]+'</button>'}).join('')+'<button id="zi">+</button><button id="zo">\u2212</button></div><div style="padding:0 8px"><canvas id="a" data-r=".52"></canvas></div><div id="lg" class="card"></div>';
var pal=['#60a5fa','#f59e0b','#f472b6','#34d399','#a78bfa','#22d3ee','#fb7185','#facc15','#4ade80','#c084fc'],cul={};cults.forEach(function(c,i){cul[c.k]=pal[i%pal.length]});
var isoCul={},isoCont={};T.forEach(function(t){isoCul[t.countryISO]=t.culture;isoCont[t.countryISO]=t.continent});
function go(){var paint=function(k){return mode==='political'&&isoCul[k.iso]?cul[isoCul[k.iso]]:(mode==='country'&&isoCont[k.iso]?CC[isoCont[k.iso]]:(isoCont[k.iso]?'#3b2a63':null))},dots=[];
if(mode==='pin')cities.forEach(function(x){var t=T.filter(function(t){return t.city===x.city&&t.country===x.country})[0];dots.push({lat:t.lat,lng:t.lng,continent:t.continent,r:2})});
if(mode==='region')regions.forEach(function(x){var ts=T.filter(function(t){return t.nomadRegion===x.k}),la=0,lo=0;ts.forEach(function(t){la+=t.lat;lo+=t.lng});dots.push({lat:la/ts.length,lng:lo/ts.length,continent:ts[0].continent,r:2+Math.min(5,x.days/60)})});
flat($('a'),paint,dots);
$('lg').innerHTML=mode==='political'?cults.map(function(c){return '<span class="chip"><i style="background:'+cul[c.k]+'" class="lg"></i>'+c.k+' \u00b7 '+c.days+'d</span>'}).join(''):mode==='region'?'<b>'+regions.length+' NomadMania regions</b> <span class="dim">of '+D.world_regions+'</span>':mode==='pin'?'<b>'+cities.length+' cities</b>':'<b>'+countries.length+' countries</b> <span class="dim">of '+D.world_countries+' (UN)</span>'}
go();[].forEach.call(document.querySelectorAll('#mm [data-m]'),function(b){b.onclick=function(){mode=b.dataset.m;VIEWS.atlas()}});$('zi').onclick=function(){pan.z=Math.min(8,pan.z*1.5);go()};$('zo').onclick=function(){pan.z=Math.max(1,pan.z/1.5);go()};drag($('a'),function(dx,dy){pan.x+=dx;pan.y+=dy;go()})},
collections:function(){var sel=null;function r(){M.innerHTML=D.themes.map(function(t){var v=t.query.filter(function(q){return CITY[q.toLowerCase()]});return '<div class="card" data-t="'+t.id+'"><b>'+t.title+'</b> <span class="dim">'+v.length+'/'+t.query.length+' visited</span><div class="dim">'+t.desc+'</div>'+(sel===t.id?'<div>'+t.query.map(function(q){return '<span class="chip'+(CITY[q.toLowerCase()]?' v':'')+'">'+q+'</span>'}).join('')+'</div><div class="dim">Filtered view</div><canvas id="tm" data-r=".5"></canvas>':'')+'</div>'}).join('');
[].forEach.call(M.querySelectorAll('[data-t]'),function(e){e.onclick=function(){sel=sel===e.dataset.t?null:e.dataset.t;r()}});
if(sel){var t=D.themes.filter(function(x){return x.id===sel})[0],q=t.query.map(function(s){return s.toLowerCase()}),dots=[];T.forEach(function(x){if(q.indexOf(x.city.toLowerCase())>=0)dots.push({lat:x.lat,lng:x.lng,continent:x.continent,r:3})});pan={x:0,y:0,z:1};flat($('tm'),function(){return null},dots)}}r()},
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
var tabs=[['home','Home'],['atlas','Atlas'],['collections','Collections'],['stats','Stats'],['timeline','Timeline']];
function show(v){view=v;$('tabs').innerHTML=tabs.map(function(t){return '<button role="tab" data-v="'+t[0]+'" class="'+(t[0]===v?'on':'')+'">'+t[1]+'</button>'}).join('');[].forEach.call(document.querySelectorAll('#tabs button'),function(b){b.onclick=function(){show(b.dataset.v)}});M.scrollTop=0;VIEWS[v]()}
show('home');
</script></body></html>
"""


def build_pages(cfg, trips, world, themes):
    vis = {}
    for t in trips:
        v = vis.setdefault(t["countryISO"], {"name": t["country"], "flag": t["countryFlag"], "continent": t["continent"], "days": 0,
                                             "lat": t["countryCapital"]["lat"], "lng": t["countryCapital"]["lng"]})
        v["days"] += days(t)
    ne = {c["iso"]: c for c in world["countries"]}
    order = cfg["map_continents"]
    mp = {"default": cfg["map_default"], "order": order, "frames": cfg["map_frames"], "colors": cfg["map_colors"], "visited": vis,
          "countries": [{"iso": c["iso"], "name": c["name"], "continent": c["continent"], "rings": c["rings"]} for c in world["countries"]
                        if (vis.get(c["iso"], {}).get("continent") or c["continent"]) in order]}
    cols = ["continent", "country", "nomadRegion", "state", "city", "dateIn", "dateOut", "lat", "lng", "language", "culture", "countryISO", "countryFlag"]
    th = {"cols": cols, "trips": [[t[k] for k in cols] for t in trips], "home": cfg["home"], "themes": themes,
          "world_countries": cfg["world_countries_un"], "world_regions": cfg["world_nomad_regions"],
          "world": [{"iso": c["iso"], "rings": c["rings"]} for c in world["countries"]]}
    return MAP_HTML.replace("__CSS__", CSS).replace("__DATA__", esc(mp)), TRIPS_HTML.replace("__CSS__", CSS).replace("__DATA__", esc(th))


def parse_themes(ts):
    out = []
    for m in re.finditer(r"\{\s*id:\s*'([^']+)',\s*title:\s*'([^']+)',\s*icon:\s*'([^']+)',\s*query:\s*\[([^\]]*)\],\s*img:\s*'([^']*)',\s*desc:\s*'([^']*)'\s*\}", ts):
        out.append({"id": m.group(1), "title": m.group(2), "icon": m.group(3), "query": re.findall(r"'([^']*)'", m.group(4)), "desc": m.group(6)})
    return out


def outputs():
    cfg = rd(os.path.join(G, "geo-config.json")); trips = rd(os.path.join(G, "travel-data.json"))["trips"]
    world = rd(os.path.join(G, "ne110-countries.min.json")); themes = rd(os.path.join(G, "mytrips-themes.json"))
    geo = rewrite_geo(rd(GEO_JSON), cfg, trips)
    m, t = build_pages(cfg, trips, world, themes)
    return {os.path.join(W, "geo-map.html"): m, os.path.join(W, "geo-mytrips.html"): t,
            GEO_JSON: json.dumps(geo, ensure_ascii=False, indent=2) + "\n"}


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
