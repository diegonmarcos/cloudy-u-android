# Third-party tools of the model pipeline

Licence inventory of the tools the Hardtech-CAD model pipeline uses to derive and verify models (`tools/`, `.github/workflows/hardtech-cad-models.yml`).
None of them is vendored: CI installs them from their registries at a pinned version. The models themselves keep their own licence (the `licence`
field of each entry in `app/src/main/assets/cad/models.json`; the originals live in the public repo hardtech-db-models).

| Tool | Version | Licence | Used for | Where |
|---|---|---|---|---|
| OpenSKP (`openskp` on PyPI, https://github.com/iamahsanmehmood/openskp) | 1.5.0 (`tools/requirements.txt`) | MIT (Copyright 2024-2026 Ahsan Mehmood) | reads `.skp`, writes the GLB and IFC4 views | `tools/derive_models.py`, `.github/workflows/hardtech-cad-models.yml` |
| trimesh (OpenSKP dependency) | resolved by pip | MIT | GLB serialisation, bounding box | same |
| numpy, shapely, mapbox_earcut, defusedxml (OpenSKP dependencies) | resolved by pip | BSD-3-Clause, BSD-3-Clause, ISC, PSF-2.0 | geometry | same (transitive, licences as stated by upstream metadata, not re-verified here) |
| @gltf-transform/core | 4.5.1 (`tools/package.json`) | MIT | CI guard: parses every GLB, counts triangles | `tools/verify-models.mjs` (installed beside the script in CI, no package.json) |
| occt-import-js 0.0.23 (Open CASCADE inside) | 0.0.23 | LGPL-2.1 (with the OCCT exception) | made the boat's GLB view in the originals repo (`tools/brep2glb.mjs` there); the app's STEP/IGES reader is the same library (libs:cad-brep) | hardtech-db-models |

## SketchUp (.skp): what exists, what was checked (2026-11-06)

Each candidate was cloned read-only into a scratch directory, its README and licence read and, for the one that can run, tried
on the repository's own SketchUp 2021 file.

| Candidate | Exists | Licence | Headless on Linux CI without the SketchUp SDK | SKP versions |
|---|---|---|---|---|
| OpenSKP, iamahsanmehmood/openskp | yes | MIT (`LICENSE`) | **yes**: pure Python (also TypeScript, .NET, Dart, C++), no SDK, no SketchUp install. Tried: `pip install openskp` 1.5.0, Campidoglio.skp (4.0 MB, SketchUp 2021) parsed in 0.1 s into 16 definitions, 4 layers, 41 materials; GLB 482 kB with 10138 vertices and 6444 triangles, IFC4 572 kB | its own docs: modern VFF container (2021+) and classic MFC container (2013-2020); in its 15-file sweep 11 parse, the rest were tracked gaps (the README reports the failing 2019 and older versions as since fixed; that was not re-tested here, only the 2021 file was); stated limits: textures are not embedded unless asked, IFC is mesh-based |
| RedHaloStudio/Sketchup_Importer (a fork of pyslapi) | yes | no licence file at the root; the add-on header says GPL, `Cargo.toml` of the Rust wrapper says MIT: inconsistent | **no**: it is Python/Rust bindings to Trimble's SketchUp SDK (`SketchUpAPI`, proprietary); its README says "Linux Support: Not available" because the SDK has no Linux libraries; it is a Blender add-on | up to SketchUp 2025.1 (README), through the SDK |
| zhuzhaoyun/skp2gltf | yes | GPL-3.0 | **no**: Windows only (README), needs the SketchUp SDK and SketchUp 2019+; the repository itself commits Trimble's `SketchUpAPI.dll`/`.lib` (proprietary, not ours to redistribute) | SketchUp 2019+ |
| autimio/export-batch-sketchup-extension | yes | no licence file; the Ruby file carries TIG's 2011 permissive notice (use, copy, modify, distribute without fee, keep the notice) | **no**: a Ruby extension that runs inside the desktop SketchUp application (Windows/macOS); exports OBJ/DAE/STL | whatever the installed SketchUp opens |

An earlier note in this repository said "no open-source SKP reader exists". That was wrong: OpenSKP is one, under MIT, and it
runs on Linux CI. What is true is narrower: SketchUp's own SDK is proprietary and has no Linux build, and the three other
candidates depend on SketchUp itself or on that SDK, so none of them can run here. No SketchUp SDK file is vendored in this
repository or in the app.

OpenSKP's own README states it was built by reverse engineering a proprietary format and is not affiliated with or endorsed by
Trimble. It is a young project; the derived view is a convenience copy and the `.skp` stays the source of truth. A derived
GLB carries its tool, version and input hash in `models.json`. Known limits of the derived SketchUp view: colours but no textures, millimetres
rescaled to metres, the IFC4 is a mesh export without BIM semantics and is not byte-reproducible between runs (the GLB is).
