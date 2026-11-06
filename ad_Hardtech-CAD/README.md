# Hardtech-CAD

An app of the Lnktree family (#1335), behind the SuperApp's Cloud > Lnktree row. Package `com.diegonmarcos.hardtechcad`, folder `ad_Hardtech-CAD` (the `ad_` prefix of the
Lnktree family, declared in `1_cicd/src/data/app-families.json`; the Cloud family is `ac_cloud-<x>`).

* The pages are `build.json::ui` (`bottom_nav`, `sections`, `stacks`) and are drawn by declared kinds (`Pages.kt`: `entry`,
  `section_title`, `holdings`, `about`). The shell is the fleet's: `libs:ui-nav-bars` (first row, bottom nav), `libs:bottomnav`
  (NavDecl), `libs:ui-kit` (tokens and kit), `libs:ui-icons` (the nav icons), `libs:theme-background`.
* An entry's target is read from a declared source (a linktree label, a registry code or a repo of `ui.app_urls`) by `app/build.gradle`
  at build time; no address is typed here. A `planned` entry is greyed with its note. No market data is shown.
* Tester: `test/test-lnktree-app.sh` (the shared template `1_cicd/src/templates/test-lnktree-app.sh`), JVM: `PagesTest`, `HoldingsTest`.
* Ship: `1_cicd/src/cicd/ship-hardtech-cad.yml` (APK to GHCR and the rolling release, installable from the SuperApp's Constellation).

## Models (#1404, #1542)
* The model data is declared in `app/src/main/assets/cad/models.json` (bundled in the APK, so the table and the model page work offline): the
  Model | Extension | Software filters, the table (Name | Category | Extension | Software | Size | Vertices/Triangles | Updated, sortable) and the model page
  (the 3D view with orbit, zoom, pan, Fit and Wireframe, then every declared fact: dimensions and bounding box, units, materials, layers,
  components, source, licence, hashes, conversion log) are all derived from it. The originals stay in the public LFS repo hardtech-db-models.
* Every model renders from a GLB view **bundled in the APK** (`assets/cad/models/<id>.view.glb`): no download, no LFS quota, no MIME or intercept
  path in between. `tools/derive_models.py` makes the views: a SketchUp (.skp) original is converted with **OpenSKP** (MIT, pure Python, no SketchUp
  SDK; GLB + IFC4, IFC kept in `data/derived/`), the boats' existing B-rep view is mirrored hash-checked. CI: `.github/workflows/hardtech-cad-models.yml`.
* Guards: `test/test-models-renderable.sh` (Ship's shell testers; `tools/check_models.py`) loads every declared model's GLB and requires it to exist, match its
  hash, parse as glTF 2.0 with no extension the viewer cannot decode and hold a non-zero triangle count; the workflow's `verify` job parses the same files
  with `@gltf-transform/core`.
* Correction: an earlier note here and in the app said "no open-source SKP reader exists". That was wrong: OpenSKP is one and runs on Linux CI. The other
  candidates need SketchUp itself or its proprietary SDK. What was checked, with licences: `THIRD-PARTY.md`.
