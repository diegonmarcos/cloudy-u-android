#!/usr/bin/env bash
# test-painter (#1808): Cloud Canvas > Painter, the Interactive Hexagon Grid page. No build and no network; node runs the page's own
# grid code with its built-in runner (node --test), no npm and no packages.
#   P1 the vendored libraries match their pin: tools/vendor-painter.py check (three.js r128 + OrbitControls, Inter; every file's sha256,
#      nothing unpinned), and three.min.js is r128
#   P2 offline and plain CSS: the page's own files (index.html, painter.css, grid.js, painter.js) name no network address, use no Tailwind
#      and no CDN; index.html loads the vendored three.js, OrbitControls, grid.js and painter.js; the CSS takes Inter from vendor/inter
#   P3 the JS unit suite test/painter/grid.test.js passes (#1823: the tiling list parity, the five new tilings, their shared-edge adjacency
#      and the Hide-frames modes included): the four generators (hex 1951, square 2601, triangle 5151, octagon 2601+2601
#      cells and their key formats), the sphere layout, paint, erase, clear, the one-change-per-stroke rule and persistence
#   P4 the prototype as specified: outline white at opacity 0.25, fill at 0.94 scale lifted by 1, DoubleSide, the five geometries,
#      frustumSize 1500, camera z 1500, fov 45 near 1 far 20000, damping 0.05, pan/rotate off outside View; the UI holds every control
#      (Hex | Square | Triangle | Octagon, Active Cells, Clear, Grid Scale 15..100 default 40 "px", Paint | Rubber | View,
#      2D | 3D Sphere, Center, six colours) and the dock is at most 512px wide; the status bar and cutout insets are honoured
#   P5 licences: licences/curated.json covers painter/vendor/three (MIT) and painter/vendor/inter (OFL-1.1) and each Inter woff2;
#      Configs > About credits name three.js, MIT, Inter and OFL; each vendor directory carries its LICENSE
#   P6 the JVM suites exist: the nav parity test (Painter right after Draw, nothing else moved) and the Painter store tests
# Each rule is then proven red by a planted mutation in a scratch copy.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
command -v node >/dev/null || { echo "ERROR: node is required (the Painter page's JS suite)"; exit 1; }

check() {  # $1 = app dir, $2 = repo root (licences); prints one line per failed rule
  local app="$1" root="$2"
  python3 -I - "$app" "$root" <<'PY'
import json, os, re, subprocess, sys
app, root = sys.argv[1], sys.argv[2]
pg = os.path.join(app, "app/src/main/assets/painter")
rd = lambda p: open(os.path.join(pg, p), encoding="utf-8").read() if os.path.isfile(os.path.join(pg, p)) else ""
pin = subprocess.run([sys.executable, "-I", os.path.join(app, "tools/vendor-painter.py"), "check", app], capture_output=True, text=True)
if pin.returncode != 0: print("P1 the vendored Painter libraries do not match PIN.json: " + pin.stdout.strip().replace("\n", "; ")[:300])
three = os.path.join(pg, "vendor/three/three.min.js")
if not os.path.isfile(three) or not open(three, encoding="utf-8").read(400).count("const e=\"128\""): print("P1 vendor/three/three.min.js is not three.js r128")
html, css, grid, js = rd("index.html"), rd("painter.css"), rd("grid.js"), rd("painter.js")
for name, t in (("index.html", html), ("painter.css", css), ("grid.js", grid), ("painter.js", js)):
    if not t: print("P2 painter/%s is missing" % name); continue
    if re.search(r"https?://|//cdn|tailwind", t, re.I): print("P2 painter/%s names a network address, a CDN or Tailwind" % name)
srcs = re.findall(r'<script src="([^"]+)"', html)
if srcs != ["vendor/three/three.min.js", "vendor/three/OrbitControls.js", "grid.js", "painter.js"]: print("P2 index.html loads %r" % srcs)
if 'href="painter.css"' not in html: print("P2 index.html does not load painter.css")
if css.count("vendor/inter/inter-latin-") < 4 or '"Inter"' not in css: print("P2 painter.css does not take Inter from vendor/inter")
want_js = ["opacity: 0.25", "0.94", "THREE.DoubleSide", "var FRUSTUM = 1500", "position.set(0, 0, 1500)", "new THREE.PerspectiveCamera(45, aspect(), 1, 20000)",
           "dampingFactor = 0.05", "new THREE.CircleGeometry(1, 6)", "new THREE.PlaneGeometry(2, 2)", "new THREE.CircleGeometry(1, 3).rotateZ(Math.PI / 2)",
           "new THREE.CircleGeometry(1, 8).rotateZ(Math.PI / 8)", "2 * Math.sin(Math.PI / 8)", "new THREE.PlaneGeometry(s, s).rotateZ(Math.PI / 4)",
           "place(g.cells[i], 1, 0.94)", "place(g.cells[i], 0, 1)", "controls.enablePan = view && state.layout === \"2d\"",
           "controls.enableRotate = view && state.layout === \"3d\"", "ortho.zoom = 1", "intersectObjects(fills", "G.startStroke(state)", "lastTouchedKey" ]
for w in want_js:
    if w not in js and w not in grid: print("P4 the page lacks the prototype's " + w)
for w in ('data-shape="hex">Hex<', 'data-shape="square">Square<', 'data-shape="triangle">Triangle<', 'data-shape="octagon">Octagon<', ">Active Cells<",
          'data-shape="rhombille">Rhombille<', 'data-shape="trihexagonal">Trihexagonal<', 'data-shape="snubsquare">Snub square<', 'data-shape="cairo">Cairo<',
          'data-shape="elongated">Elongated triangular<', ">Hide frames<", 'data-frames="off">Off<', 'data-frames="between">Only between<',
          'data-frames="outside">Only outside<', 'data-frames="all">All<',
          'id="clear"', ">Clear<", ">Grid Scale<", 'min="15" max="100" step="1" value="40"', ">40px<", 'data-mode="paint">Paint<',
          'data-mode="rubber">Rubber<', 'data-mode="view">View<', 'data-layout="2d">2D<', 'data-layout="3d">3D Sphere<', 'id="center">Center<', 'id="swatches"'):
    if w not in html: print("P4 the UI lacks " + w)
order = [m.start() for m in (re.search('data-shape="%s"' % n, html) for n in ("hex", "square", "triangle", "octagon", "rhombille", "trihexagonal", "snubsquare", "cairo", "elongated")) if m]
if len(order) != 9 or order != sorted(order): print("P4 the shape buttons are not Hex, Square, Triangle, Octagon first, then the five new tilings in order")
if 'frames: "off"' not in grid: print("P4 Hide frames no longer defaults to off (the current look)")
if '["#ffffff", "#ef4444", "#22c55e", "#3b82f6", "#eab308", "#a855f7"]' not in grid: print("P4 the colours are not the six of the prototype, white first")
if "max-width: 512px" not in css: print("P4 the dock is not capped at 512px")
if "safe-area-inset-top" not in css or "safe-area-inset-bottom" not in css or "viewport-fit=cover" not in html: print("P4 the page ignores the insets")
shell = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcanvas/ui/CanvasShell.kt")).read()
if "WindowInsets.statusBars" not in shell: print("P4 the shell no longer clears the status bar")
cur = json.load(open(os.path.join(root, "licenses/curated.json")))
dirs = cur.get("directories", {})
if dirs.get("ac_cloud-canvas/app/src/main/assets/painter/vendor/three", {}).get("licence") != "MIT": print("P5 painter/vendor/three is not a MIT licences/curated.json directory")
if dirs.get("ac_cloud-canvas/app/src/main/assets/painter/vendor/inter", {}).get("licence") != "OFL-1.1": print("P5 painter/vendor/inter is not an OFL-1.1 licences/curated.json directory")
for w in (400, 500, 600, 700):
    e = cur.get("assets", {}).get("ac_cloud-canvas/app/src/main/assets/painter/vendor/inter/inter-latin-%d-normal.woff2" % w)
    if not e or e.get("licence") != "OFL-1.1" or not e.get("attribution"): print("P5 inter-latin-%d-normal.woff2 has no OFL-1.1 licences/curated.json::assets entry" % w); break
for d in ("three", "inter"):
    if not os.path.isfile(os.path.join(pg, "vendor", d, "LICENSE")): print("P5 painter/vendor/%s has no LICENSE" % d)
cred = json.load(open(os.path.join(app, "build.json"))).get("configs_pages", {}).get("about", {}).get("credits", "")
for w in ("three.js", "MIT", "Inter", "OFL"):
    if w not in cred: print("P5 configs_pages.about.credits does not name " + w)
tests = ""
for r, _, fs in os.walk(os.path.join(app, "app/src/test")):
    for f in fs: tests += open(os.path.join(r, f)).read()
for name in ("painterSitsRightAfterDrawAndNothingElseMoved", "paintedCellsPerShapeAndTheChoicesSurviveARestart", "paintEraseAndClearAreSavedAsTheyHappen",
             "writeIsAtomicAndRefusesAMalformedState"):
    if "fun " + name not in tests: print("P6 missing unit test " + name)
PY
  local out
  out="$(cd "$app" && node --test test/painter/grid.test.js 2>&1)" || echo "P3 the Painter JS suite fails: $(printf '%s' "$out" | grep -E '^not ok' | head -3 | tr '\n' ';')"
}

echo "== the JS suite =="
( cd "$APP" && node --test test/painter/grid.test.js 2>&1 | grep -E '^(ok|not ok) ' ) || true
echo "== the real tree =="
OUT="$(check "$APP" "$ROOT")"
[ -z "$OUT" ] && ok "P1-P6 hold on ac_cloud-canvas Painter" || { bad "rules broken:"; echo "$OUT"; }

S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mut() {  # $1 = label, $2 = rule prefix expected, $3.. = edit of the scratch copy
  local label="$1" want="$2"; shift 2
  rm -rf "$S/app" "$S/root"; mkdir -p "$S/app/app/src/main" "$S/app/app/src/test" "$S/app/app/src/main/java/com/diegonmarcos/cloudcanvas/ui" "$S/root/licenses"
  cp -r "$APP/app/src/main/assets" "$S/app/app/src/main/"; rm -rf "$S/app/app/src/main/assets/samples"
  cp -r "$APP/app/src/test/." "$S/app/app/src/test/"; cp -r "$APP/tools" "$APP/test" "$S/app/"; cp "$APP/build.json" "$S/app/"
  cp "$APP/app/src/main/java/com/diegonmarcos/cloudcanvas/ui/CanvasShell.kt" "$S/app/app/src/main/java/com/diegonmarcos/cloudcanvas/ui/"
  cp "$ROOT/licenses/curated.json" "$S/root/licenses/"
  ( cd "$S/app" && "$@" )
  if check "$S/app" "$S/root" | grep -q "^$want"; then ok "mutation '$label' is caught ($want)"; else bad "mutation '$label' went undetected"; fi
}
P=app/src/main/assets/painter
mut "vendored three.js edited" P1 sh -c "echo '//x' >> $P/vendor/three/three.min.js"
mut "unpinned file in vendor" P1 sh -c "echo x > $P/vendor/three/extra.js"
mut "three.js from a CDN" P2 sed -i 's#src="vendor/three/three.min.js"#src="https://cdn.jsdelivr.net/npm/three@0.128.0/build/three.min.js"#' $P/index.html
mut "tailwind back" P2 sed -i 's#</head>#<script src="tailwind.js"></script></head>#' $P/index.html
mut "inter from the network" P2 sed -i 's#url("vendor/inter/inter-latin-400-normal.woff2")#url("https://rsms.me/inter/x.woff2")#' $P/painter.css
mut "new tilings first" P4 sed -i 's#<button type="button" data-shape="rhombille">Rhombille</button>##; s#<button type="button" data-shape="hex">Hex</button>#<button type="button" data-shape="rhombille">Rhombille</button><button type="button" data-shape="hex">Hex</button>#' $P/index.html
mut "frames default changed" P4 sed -i 's/frames: "off", painted/frames: "between", painted/' $P/grid.js
mut "Only outside control removed" P4 sed -i 's#<button type="button" data-frames="outside">Only outside</button>##' $P/index.html
mut "cairo adjacency broken" P3 sed -i 's/Math.round(p\[0\] \* 1e4) + "," + Math.round(p\[1\] \* 1e4)/Math.round(p[0] * 1e4) + "," + Math.round(p[1] * 1e4) + Math.random()/' $P/grid.js
mut "between merges unpainted" P3 sed -i 's/between \&\& !!painted \&\& !!np/between \&\& !!np/' $P/grid.js
mut "outside hides painted too" P3 sed -i 's/outside \&\& !painted ? "hidden"/outside ? "hidden"/' $P/grid.js
mut "hex count wrong" P3 sed -i 's/var r1 = Math.max(-r, -q - r), r2 = Math.min(r, -q + r);/var r1 = -r, r2 = r;/' $P/grid.js
mut "triangle key format changed" P3 sed -i 's/key: "T," + q/key: "Tri," + q/' $P/grid.js
mut "a cell changes twice per stroke" P3 sed -i 's/ || this.touched\[key\]//' $P/grid.js
mut "rubber paints" P3 sed -i 's/else if (st.mode === "rubber") ok = erase(st, key);/else if (st.mode === "rubber") ok = paint(st, key, st.color);/' $P/grid.js
mut "clear wipes every shape" P3 sed -i 's/state.painted\[s\] = {};/state.painted = { hex: {}, square: {}, triangle: {}, octagon: {} };/' $P/grid.js
mut "persistence drops the layout" P3 sed -i 's/if (LAYOUTS.indexOf(o.layout) >= 0) d.layout = o.layout;//' $P/grid.js
mut "fill no longer inset" P4 sed -i 's/place(g.cells\[i\], 1, 0.94)/place(g.cells[i], 1, 1)/' $P/painter.js
mut "outline opacity changed" P4 sed -i 's/opacity: 0.25/opacity: 0.5/' $P/painter.js
mut "View control removed" P4 sed -i 's#<button type="button" data-mode="view">View</button>##' $P/index.html
mut "Center removed" P4 sed -i 's#<button type="button" id="center">Center</button>##' $P/index.html
mut "dock widened" P4 sed -i 's/max-width: 512px/max-width: 900px/' $P/painter.css
mut "a colour dropped" P4 sed -i 's/, "#a855f7"\]/]/' $P/grid.js
mut "inter licence entry dropped" P5 python3 -I -c 'import json;p="../root/licenses/curated.json";d=json.load(open(p));del d["assets"]["ac_cloud-canvas/app/src/main/assets/painter/vendor/inter/inter-latin-700-normal.woff2"];json.dump(d,open(p,"w"))'
mut "three licence directory dropped" P5 python3 -I -c 'import json;p="../root/licenses/curated.json";d=json.load(open(p));del d["directories"]["ac_cloud-canvas/app/src/main/assets/painter/vendor/three"];json.dump(d,open(p,"w"))'
mut "credits drop three.js" P5 python3 -I -c 'import json;d=json.load(open("build.json"));c=d["configs_pages"]["about"];c["credits"]=c["credits"].replace("three.js","t");json.dump(d,open("build.json","w"))'
mut "nav parity test removed" P6 sed -i 's/fun painterSitsRightAfterDrawAndNothingElseMoved/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/NavShapeTest.kt
mut "store test removed" P6 sed -i 's/fun writeIsAtomicAndRefusesAMalformedState/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/PainterTest.kt

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
