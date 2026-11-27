#!/usr/bin/env bash
# Tester (#1980 part 2): the Repos explorer. List lines carry the name only (indented under the parent) with four state icons left of the name
# (Pvt/Pub, LFS/Code, HTML/NoHTML, Hub/NoHub); the Table has the same icons as its leftmost column; the 'role' filter is gone and visibility, kind and search stay;
# filter chips are coloured apart from the view-mode chips; kind and visibility sit on separate rows. The meta text removed from the list line stays in the Table
# (field parity is test-repos-1973.sh). Each check is proven red by a mutation of a copy of the page.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 -I - "$1" <<'PY'
import json, os, re, subprocess, sys, tempfile
app = sys.argv[1]; H = open(os.path.join(app, "data/web/repos-explorer.html"), encoding="utf-8").read()
def E(c, m): print("%s\t%s" % (c, m))
m = re.search(r"var J=(\{.*?\}),R=J\.rows", H, re.S); R = json.loads(m.group(1).replace("\\u003c", "<"))["rows"]
# I: run the page's own icon/row functions (segment /*IC*/ .. /*/IC*/) in node for every repo
seg = re.search(r"/\*IC\*/(.*?)/\*/IC\*/", H, re.S)
if not seg: E("I0", "no icon segment in the page"); sys.exit(0)
js = "var esc=function(s){return String(s).replace(/[&<>\"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})};" + seg.group(1) + \
     ";var R=JSON.parse(require('fs').readFileSync(0,'utf8'));console.log(JSON.stringify(R.map(function(r){return{n:r.name,i:icons(r),l:rowHtml(r,'\u25be',false,10)}})))"
p = subprocess.run(["node", "-e", js], input=json.dumps(R), capture_output=True, text=True)
if p.returncode: E("I0", "icon segment does not run: %s" % p.stderr[-300:]); sys.exit(0)
out = {o["n"]: o for o in json.loads(p.stdout)}
LBL = {"pvt": "Pvt", "pub": "Pub", "lfs": "LFS", "code": "Code", "html": "HTML", "nohtml": "NoHTML", "hub": "Hub", "nohub": "NoHub"}
kids = {}
for r in R:
    if r["parent"]: kids[r["parent"]] = kids.get(r["parent"], 0) + 1
if len(R) < 70: E("I0", "only %d rows" % len(R))
for r in R:
    o = out.get(r["name"])
    if not o: E("I1", "%s has no row" % r["name"]); continue
    want = ["pvt" if r["visibility"] == "private" else "pub", "lfs" if r["lfs"] else "code", "html" if r["html"] else "nohtml", "hub" if (r["hub"] or kids.get(r["name"])) else "nohub"]
    got = re.findall(r'<svg class="ic" data-ic="(\w+)" role="img" aria-label="([^"]+)" [^>]*><title>([^<]+)</title>', o["i"])
    if [g[0] for g in got] != want: E("I1", "%s icons %s, registry says %s" % (r["name"], [g[0] for g in got], want))
    for g, w in zip(got, want):
        if "(%s)" % LBL[w] not in g[1] or g[1] != g[2]: E("I2", "%s icon %s lacks an aria-label/title naming %s" % (r["name"], g[0], LBL[w]))
    # L: the list line is the name only, the icons left of it
    l = o["l"]; txt = re.sub(r"<title>.*?</title>", "", l); txt = re.sub(r'<span class="tg"[^>]*>.*?</span>', "", txt); txt = re.sub(r"<[^>]+>", "", txt).strip()
    if txt != r["name"]: E("L1", "%s list line reads %r, want the name only" % (r["name"], txt))
    if not (l.index('class="ics"') < l.index('class="nm"')): E("L2", "%s icons are not left of the name" % r["name"])
if len({tuple(re.findall(r'data-ic="(\w+)"', o["i"])) for o in out.values()}) < 6: E("I3", "the registry states are not told apart by the icons")
# the two states of each pattern differ in drawing and colour
for a, b in (("pvt", "pub"), ("lfs", "code"), ("html", "nohtml"), ("hub", "nohub")):
    pa = re.search(r'%s:\["[^"]*",\'([^\']*)\'' % a, H); pb = re.search(r'%s:\["[^"]*",\'([^\']*)\'' % b, H)
    if not pa or not pb or pa.group(1) == pb.group(1): E("I4", "%s and %s draw the same icon" % (a, b))
    ca = re.search(r"svg\.ic\[data-ic=%s\]\{color:([^;}]+)" % a, H); cb = re.search(r"svg\.ic\[data-ic=%s\]\{color:([^;}]+)" % b, H)
    if not ca or not cb or ca.group(1) == cb.group(1): E("I4", "%s and %s have the same colour" % (a, b))
# G: legend naming all eight states
if 'id="lg"' not in H or "SV[p[0]][0]" not in H or "SV[p[1]][0]" not in H: E("G1", "the icon legend is missing")
# T: Table: icons are the leftmost column
th = re.search(r"function tableHtml\(\)\{(.*?)\n// canvas", H, re.S)
if not th or th.group(1).find('<th class="ic"') < 0 or th.group(1).find('<th class="ic"') > th.group(1).find("COLS.map") or th.group(1).find("<td class=\"ic\">'+icons(R[i])") < 0 or th.group(1).find("<td class=\"ic\">") > th.group(1).find("COLS.map(function(k){var v"): E("T1", "the Table lacks the leftmost icon column")
# F: filters
if re.search(r'id="fr"|st\.role|"role"\s*,\s*vals|data-k="role"|>role<', H): E("F1", "the role filter is still there")
for need in ('id="fv"', 'id="fk"', 'id="q"', 'st.vis&&', 'st.kind&&', 'st.q&&'):
    if need not in H: E("F2", "filter %s was lost" % need)
if not re.search(r'chips\("fv","vis"', H) or not re.search(r'chips\("fk","kind"', H): E("F2", "a filter chip group is no longer drawn")
def css(sel):
    mm = re.search(r"(?:^|[\n}])%s\{([^}]*)\}" % re.escape(sel), H.split("</style>")[0]); return dict(x.split(":", 1) for x in mm.group(1).split(";") if ":" in x) if mm else {}
view, viewon, fl, flon = css(".chip"), css(".chip.on"), css(".fchip"), css(".fchip.on")
if not (fl and flon and view and viewon): E("C1", "chip css missing")
else:
    for k, a, b in (("background", view, fl), ("color", view, fl), ("border", view, fl), ("background", viewon, flon), ("color", viewon, flon), ("border-color", viewon, flon)):
        if k in a and a[k] == b.get(k) or k not in a and k not in b: E("C1", "filter and view chips share %s (%s)" % (k, a.get(k)))
if H.count('class="fchip') < 2 or 'class="chip sm\'+(st[key]' in H: E("C2", "the filter chips are not drawn with the filter class")
rv = re.search(r'<div class="frow"[^>]*>(?:(?!</div>).)*?id="fv"(?:(?!</div>).)*</div>', H, re.S); rk = re.search(r'<div class="frow"[^>]*>(?:(?!</div>).)*?id="fk"(?:(?!</div>).)*</div>', H, re.S)
if not rv or not rk or rv.group(0) == rk.group(0) or 'id="fk"' in rv.group(0) or 'id="fv"' in rk.group(0): E("R1", "kind and visibility are not on separate rows")
PY
}
if [ "${1:-}" = --check ]; then o=$(MUTANT=1 check "${2:-$APP}"); [ -z "$o" ] && exit 0; echo "$o"; exit 1; fi
out=$(check "$APP"); if [ -z "$out" ]; then ok "icons per repo match the registry, name-only list lines, role filter gone, chip colours, separate rows, Table icon column"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/data/web"; cp "$APP/data/web/repos-explorer.html" "$T/app/data/web/"
  python3 -I -c 'import sys;p=sys.argv[1];s=open(p,encoding="utf-8").read();a,b=sys.argv[2],sys.argv[3];assert a in s,a;open(p,"w",encoding="utf-8").write(s.replace(a,b,1))' "$T/app/data/web/repos-explorer.html" "$2" "$3" || { bad "mutation does not apply: $1"; return; }
  r=$(MUTANT=1 check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
sub() { mut "$@"; }
sub "the LFS state is inverted" 'r.lfs?"lfs":"code"' 'r.lfs?"code":"lfs"'
sub "Pvt/Pub ignores visibility" 'r.visibility==="private"?"pvt":"pub"' '"pub"'
sub "HTML/NoHTML is always HTML" 'r.html?"html":"nohtml"' '"html"'
sub "Hub/NoHub ignores the hub flag" '(r.hub||r.children_count>0)?"hub":"nohub"' 'r.children_count>0?"hub":"nohub"'
sub "Hub/NoHub ignores the children" '(r.hub||r.children_count>0)?"hub":"nohub"' '"nohub"'
sub "an icon loses its aria-label" 'role="img" aria-label="'"'"'+SV[k][0]+'"'"'" ' ''
sub "the list line carries the code again" "'+esc(r.name)+\"</span></div>\"}" "'+esc(r.name)+\"</span><span class=cd>x</span></div>\"}"
sub "the icons move right of the name" "\"</span>\"+icons(r)+'<span class=\"nm\">'+esc(r.name)+\"</span></div>\"}" "\"</span>\"+'<span class=\"nm\">'+esc(r.name)+\"</span>\"+icons(r)+\"</div>\"}"
sub "the NoHTML icon looks like the HTML icon" '5v15H6zM15 2v5h5"/><path d="M4 4l16 16"/>' '5v15H6zM15 2v5h5M9 13h8M9 17h8"/>'
sub "the Pub colour equals the Pvt colour" 'svg.ic[data-ic=pub]{color:var(--pub)}' 'svg.ic[data-ic=pub]{color:var(--priv)}'
sub "the legend is gone" 'id="lg"' 'id="lgx"'
sub "the Table icon column is gone" "'+icons(R[i])+\"</td>\"+COLS.map" "'+\"\"+COLS.map"
sub "the role filter is back" '<div class="frow" id="rowk">' '<div class="frow"><span class="chips" id="fr"></span></div><div class="frow" id="rowk">'
sub "the kind filter is lost" 'st.kind&&' 'st.kindx&&'
sub "the search filter is lost" 'st.q&&H[i]' 'st.qx&&H[i]'
sub "the filter chips use the view chip colours" '.fchip.on{background:#1f8f80;border-color:#5eead4;color:#04201c;' '.fchip.on{background:var(--accb);border-color:var(--acc);color:var(--acc);'
sub "the filter chips are drawn with the view class" "<button class=\"fchip'+(st[key]===\"\"" "<button class=\"chip sm'+(st[key]===\"\""
sub "kind and visibility share a row" '<span id="count" style="margin-left:auto"></span></div>' '<span id="count" style="margin-left:auto"></span><span class="chips" id="fk"></span></div>'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
