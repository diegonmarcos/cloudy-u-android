# CG-Omniverse

An app of the Lnktree family (#1335), behind the SuperApp's Cloud > Lnktree row. Package `com.diegonmarcos.cgomniverse`, folder `ad_CG-Omniverse` (the `ad_` prefix of the
Lnktree family, declared in `1_cicd/src/data/app-families.json`; the Cloud family is `ac_cloud-<x>`).

Bottom nav: **Avatar | Galaxy | Store** (`build.json::ui`; each is a page kind drawn by `ui/SectionPage.kt`).

* **Avatar** (#1373): the owner's avatar (`app/src/main/assets/avatar/models/owner.glb`, a MetaPerson Creator export converted offline to a
  rigged, meshopt-compressed GLB) in an editor laid out like MetaPerson Creator's mobile UI (dark stage, Home/AI, Rotate, Export GLB/PNG, a
  bottom sheet of option cards over a bar of nine categories), drawn by `libs:cgc-3d` (three.js + three-vrm in a WEB_WORLD view; three.js and
  OrbitControls are Cloud Nav's copies, `copyThreeAssets` in `app/build.gradle`). Categories, cards, the default avatar and the OpenRouter
  "describe your avatar" connector are `data/avatar.json`; the configuration is saved in the `avatar` prefs file (backed up), the user's
  OpenRouter key in the encrypted `cg_omniverse_ai_keys` store. `cg-omniverse-avatar-proof.yml` (dispatch) mutates the lib's rules and walks
  the tab on an emulator.
* **Store**: the worlds of `data/store.json`, GENERATED from `data/fwa.md` by `python3 data/gen-store.py` (never edit the JSON; `--check` is the
  drift check, run by `test/test-store-data.sh`). The same generator writes `res/xml/network_security_config.xml`: cleartext is forbidden except for the hosts of the http-only Store entries, which the tester holds equal. Filters Type / Dimension / Device are multi-select chip rows, all selected by default
  (`Store.kt`; 2D/3D and 3D/Video count as both 2D and 3D, VR Yes as VR Glass and Flat Screen, VR No as Flat Screen only).
* **Galaxy**: a browser-style world viewer (#1400): a tab strip of open worlds (close per tab, "+" opens the Store to pick one), the
  selected tab in a WebView (libs:web-core CloudWeb, WEB_WORLD; WebGL, hardware acceleration) with desktop mode, reload, the WebGPU line
  and open-in-browser. Tabs persist (`Tabs.kt`); the first launch opens https://floor796.com; a Store world, the `world` extra or
  `cgomniverse://world/<id>` adds or focuses its tab (a world that cannot load gets a fallback card with Open in browser and Reload); a VR world reports whether this WebView has WebXR and otherwise offers the browser for VR.
* Per-world launch rules: `data/world-rules.json` (keyed by Store id) is copied by `gen-store.py` into store.json as `launch_query`, and Galaxy loads `Store.launchUrl(world)`. floor796 declares `old-render`: its default engine pulls a 175 MB F796 file into a worker (held twice, plus a Cache API write), which a phone WebView renderer can fail to hold and then paints nothing, with no console error; the legacy engine streams the viewport's MP4 tiles instead. Delete the rule to get the best-quality engine back. Evidence: headless Chromium paints both paths and needs no SharedArrayBuffer, WebGL, WebGPU or cross-origin isolation (floor796 is Canvas2D only), so those are not the cause.
* Tests: `test/test-lnktree-app.sh` (family template `1_cicd/src/templates/test-lnktree-app.sh`), `test/test-store-data.sh`, JVM `PagesTest`,
  `StoreTest`, `AvatarTest`. `cg-omniverse-proof.yml` (dispatch) proves the filter tests against planted mutants and takes emulator screenshots.
* Ship: `1_cicd/src/cicd/ship-cg-omniverse.yml` (APK to GHCR and the rolling release, installable from the SuperApp's Constellation).
