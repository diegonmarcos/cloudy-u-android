// libs:cad-brep (#1695) the STEP/IGES reader OFF the page's main thread: occt-import-js (WASM) parses and tessellates here, tess.js packs a GLB
// with one node per solid; the page is never blocked and cancels by terminating this worker.
importScripts('vendor/occt-import-js.js', 'tess.js');
var occt = null;
self.onmessage = function (e) {
  var d = e.data;
  (occt || (self.postMessage({ type: 'progress', text: 'Starting the STEP reader (OpenCASCADE)…' }), occt = occtimportjs({ locateFile: function (n) { return 'vendor/' + n; } })))
    .then(function (o) {
      var r = brepTess.read(o, new Uint8Array(d.buffer), d.format, function (t) { self.postMessage({ type: 'progress', text: t }); });
      self.postMessage({ type: 'done', glb: r.glb.buffer, parts: r.parts, triangles: r.triangles }, [r.glb.buffer]);
    })
    .catch(function (err) { self.postMessage({ type: 'error', message: String(err && err.message || err) }); });
};
