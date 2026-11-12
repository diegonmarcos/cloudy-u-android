#!/usr/bin/env bash
# test-step-reader (#1695): the STEP reader of the viewer (libs:cad-brep worker.js = occt-import-js + tess.js) on a tiny GENERATED STEP
# (tools/step-fixture.mjs: no fixture file in the repo or the APK): one GLB node per solid, named, coloured, opaque double-sided materials, progress
# reported, an empty file refused. Plus the static wiring: the page reads STEP in a Worker (never on its main thread), can cancel, caches the GLB.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
BREP="$ROOT/ab_cloud-libs-shared/libs/cad-brep/src/main/assets/cad/brep"; CORE="$ROOT/ab_cloud-libs-shared/libs/cad-core/src/main/assets/cad"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }
FAILS=0; ok() { echo "  PASS: $1"; }; bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] STEP reader"
out="$(node "$APP/tools/step-reader-test.mjs" "$ROOT" 2>&1)" && ok "the generated STEP reads into one named node per solid" || { bad "the STEP reader test is red"; echo "$out"; }
grep -q "new Worker(" "$BREP/loader.js" && ! grep -q "ReadStepFile" "$BREP/loader.js" && ok "the page parses STEP in a Web Worker, never on its main thread" || bad "loader.js parses on the page thread"
grep -q "importScripts('vendor/occt-import-js.js', 'tess.js')" "$BREP/worker.js" && ok "the worker runs occt-import-js + tess.js" || bad "worker.js does not load the reader"
grep -q "export function cancel" "$BREP/loader.js" && grep -q "window.cadCancel" "$CORE/viewer.js" && ok "a load can be cancelled" || bad "no cancel"
grep -q "indexedDB.open" "$BREP/loader.js" && grep -q "await sha256(buf)" "$BREP/loader.js" && ok "the tessellated GLB is cached under the file's SHA-256" || bad "no GLB cache"
grep -q "type: 'progress'" "$CORE/viewer.js" && grep -q '"progress" ->' "$APP/app/src/main/java/com/diegonmarcos/hardtechcad/ui/ModelViewer.kt" && ok "progress reaches the host" || bad "progress is not forwarded"
grep -q "function partsPanel" "$CORE/viewer.js" && grep -q "window.cadPartVisible" "$CORE/viewer.js" && ok "the viewer lists parts with per-part visibility" || bad "no parts tree"
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS failure(s)"; exit 1; }
