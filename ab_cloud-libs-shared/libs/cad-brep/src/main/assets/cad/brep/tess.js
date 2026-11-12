// libs:cad-brep (#1695) the tessellated STEP/IGES result of occt-import-js -> a GLB, ONE NODE PER SOLID named as in the file, its own colour.
// Classic script, shared by the page worker (worker.js, importScripts) and Node (tools and tests load it with vm): defines self.brepTess.
(function (g) {
  // A solid's name: its own (occt mesh name) when the file names it, else the product node that holds it plus its number in that node
  // (a single-part export such as SolidWorks' 'save as STEP' names the product only).
  function owners(res) {
    var o = {};
    (function walk(n) { if (!n) return; var nm = n.name && String(n.name).trim(); (n.meshes || []).forEach(function (mi, k) { o[mi] = { name: nm, k: k + 1, of: n.meshes.length }; }); (n.children || []).forEach(walk); })(res.root);
    return o;
  }
  function part(m, i, own) {
    var nm = m.name && String(m.name).trim(); if (nm) return nm;
    var w = own && own[i]; if (w && w.name) return w.of > 1 ? w.name + ' · solid ' + w.k : w.name;
    return 'Solid ' + (i + 1);
  }
  function hex(c) { return c ? '#' + c.map(function (x) { return ('0' + Math.round(Math.max(0, Math.min(1, x)) * 255).toString(16)).slice(-2); }).join('') : '#cfd4dc'; }
  // res: occt-import-js ReadStepFile/ReadIgesFile result; upZ: rotate Z-up to glTF Y-up. Returns { glb: Uint8Array, parts: [{name, color, triangles}], triangles }.
  function toGlb(res, upZ) {
    var own = owners(res), bin = [], off = 0, views = [], accessors = [], meshes = [], nodes = [], materials = [], matIdx = {}, parts = [], tris = 0;
    function push(arr, target) {
      var b = new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength); bin.push(b);
      views.push({ buffer: 0, byteOffset: off, byteLength: b.length, target: target }); off += b.length;
      var pad = (4 - (off % 4)) % 4; if (pad) { bin.push(new Uint8Array(pad)); off += pad; }
      return views.length - 1;
    }
    function material(c) {
      var k = hex(c);
      if (!(k in matIdx)) { matIdx[k] = materials.length; materials.push({ name: k, pbrMetallicRoughness: { baseColorFactor: c ? [c[0], c[1], c[2], 1] : [0.81, 0.83, 0.86, 1], metallicFactor: 0.1, roughnessFactor: 0.6 }, doubleSided: true }); }
      return matIdx[k];
    }
    (res.meshes || []).forEach(function (m, i) {
      var p = m.attributes && m.attributes.position && m.attributes.position.array; if (!p || !p.length || !m.index) return;
      var pos = new Float32Array(p), idx = new Uint32Array(m.index.array);
      var min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
      for (var j = 0; j < pos.length; j++) { var a = j % 3; if (pos[j] < min[a]) min[a] = pos[j]; if (pos[j] > max[a]) max[a] = pos[j]; }
      var attrs = { POSITION: accessors.push({ bufferView: push(pos, 34962), componentType: 5126, count: pos.length / 3, type: 'VEC3', min: min, max: max }) - 1 };
      if (m.attributes.normal && m.attributes.normal.array.length === pos.length) attrs.NORMAL = accessors.push({ bufferView: push(new Float32Array(m.attributes.normal.array), 34962), componentType: 5126, count: pos.length / 3, type: 'VEC3' }) - 1;
      var ia = accessors.push({ bufferView: push(idx, 34963), componentType: 5125, count: idx.length, type: 'SCALAR' }) - 1;
      var name = part(m, i, own);
      meshes.push({ name: name, primitives: [{ attributes: attrs, indices: ia, material: material(m.color) }] });
      nodes.push({ name: name, mesh: meshes.length - 1 });
      parts.push({ name: name, color: hex(m.color), triangles: idx.length / 3 }); tris += idx.length / 3;
    });
    if (!nodes.length) throw new Error('the file holds no solid with a surface');
    var root = { name: 'root', children: nodes.map(function (_, i) { return i; }) };
    if (upZ) root.rotation = [-Math.SQRT1_2, 0, 0, Math.SQRT1_2];
    nodes.push(root);
    var gltf = { asset: { version: '2.0', generator: 'libs:cad-brep tess.js (occt-import-js)' }, scene: 0, scenes: [{ nodes: [nodes.length - 1] }], nodes: nodes, meshes: meshes, materials: materials, accessors: accessors, bufferViews: views, buffers: [{ byteLength: off }] };
    var json = new TextEncoder().encode(JSON.stringify(gltf)); var jl = json.length + ((4 - (json.length % 4)) % 4);
    var total = 12 + 8 + jl + 8 + off, out = new Uint8Array(total), dv = new DataView(out.buffer);
    dv.setUint32(0, 0x46546c67, true); dv.setUint32(4, 2, true); dv.setUint32(8, total, true);
    dv.setUint32(12, jl, true); dv.setUint32(16, 0x4e4f534a, true); out.set(json, 20); for (var s = 20 + json.length; s < 20 + jl; s++) out[s] = 0x20;
    var o = 20 + jl; dv.setUint32(o, off, true); dv.setUint32(o + 4, 0x004e4942, true); o += 8;
    bin.forEach(function (b) { out.set(b, o); o += b.length; });
    return { glb: out, parts: parts, triangles: tris };
  }
  // The up axis of a STEP: SolidWorks writes Y-up (its header names it: SwSTEP / SolidWorks), the other CAD systems Z-up.
  function yUp(bytes, format) {
    if (format === 'iges') return false;
    var h = ''; for (var i = 0; i < Math.min(bytes.length, 4096); i++) h += String.fromCharCode(bytes[i]);
    return /SwSTEP|SolidWorks/i.test(h.split('DATA;')[0]);
  }
  // Read + tessellate + pack. occt: the initialised occt-import-js module. progress(text) is optional.
  function read(occt, bytes, format, progress) {
    var prm = { linearUnit: 'meter', linearDeflectionType: 'bounding_box_ratio', linearDeflection: 0.004, angularDeflection: 0.5 };
    if (progress) progress('Tessellating ' + (format === 'iges' ? 'IGES' : 'STEP') + ' (' + (bytes.length / 1048576).toFixed(1) + ' MB)…');
    var r = format === 'iges' ? occt.ReadIgesFile(bytes, prm) : occt.ReadStepFile(bytes, prm);
    if (!r || !r.success) throw new Error('occt could not read this ' + format + ' file');
    if (progress) progress('Building ' + r.meshes.length + ' parts…');
    return toGlb(r, !yUp(bytes, format));
  }
  g.brepTess = { toGlb: toGlb, read: read, partName: part, yUp: yUp };
})(typeof self !== 'undefined' ? self : globalThis);
