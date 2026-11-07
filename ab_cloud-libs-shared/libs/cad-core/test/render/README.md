# CAD / Galaxy render-verification (#1404)

`render-verify.mjs` is a Node + Playwright screenshot test that proves three 3D renders are
**genuine** — it screenshots each, samples the canvas pixels, and asserts a real, non-blank,
multi-colour scene (a solid/blank/one-colour canvas fails). It loops (up to `MAX_ATTEMPTS`) and
prints the console/`CadHost` diagnostics on each miss so the real cause can be fixed.

| target | what renders | path exercised |
|--------|--------------|----------------|
| **796floor** | the Omniverse/Galaxy default world `floor796` → the `https://floor796.com` canvas site (see `ad_CG-Omniverse` `Store.kt` / `data/fwa.md`) | the Galaxy world URL, rendered in Chromium |
| **CAD boat** | `one-lilo` 36 m sailing-yacht GLB (`hardtech-db-models`) | `libs:cad-core` viewer + `libs:cad-mesh` (three.js `GLTFLoader`) |
| **CAD house** | a buildingSMART **FZK-Haus** detached house IFC (walls, roof, 22 windows, 19 doors) | `libs:cad-core` viewer + `libs:cad-ifc` (`web-ifc.wasm`, bundled) |

The test drives the **real committed assets** — `cad-core` `viewer.html`/`viewer.js`, the `cad-mesh`
and `cad-ifc` loaders with their bundled `web-ifc.wasm`, the three.js build in `ac_cloud-nav`, and
the `GLTFLoader` vendor in `cgc-3d` — assembled into a temp webroot and served over a tiny static
HTTP server (so wasm/`fetch`/CORS behave as on device; `file://` blocks them).

## Run

```sh
cd ab_cloud-libs-shared/libs/cad-core/test/render
node render-verify.mjs
```

Chromium + Playwright must be pre-installed (`require('playwright')`, `chromium.launch()` with no
options). **Do not** run `playwright install`. Software WebGL is forced with
`--use-gl=angle --use-angle=swiftshader --enable-unsafe-swiftshader --ignore-gpu-blocklist`.
Screenshots are taken at `deviceScaleFactor: 2`. Exit code is 0 iff all three verifiably render.

### Models (resolved local-first, else downloaded and cached in `.cache/`)
- boat: `CAD_BOAT_GLB=/path/to.glb`, else the `hardtech-db-models` LFS mirror.
- house: `CAD_HOUSE_IFC=/path/to.ifc`, else the pinned FZK-Haus sample URL.
  (`architecture` catalogue default `campidoglio` is a `.skp` with no open web view — no
  open-source SKP reader — so the IFC render path is proven with a real house IFC instead.)

### Env
`PROOF_DIR` (where `796floor.png`, `cad-boat.png`, `cad-house.png` are written; default
`./render-proof`), `FLOOR796_URL`, `MAX_ATTEMPTS` (default 6).

## How "real render" is judged
Corner pixel = background. A pass needs non-background fraction ≥ 3 %, ≥ 6 distinct colour
buckets, and luminance std ≥ 6. CAD pixels are read from `viewer.js`'s `cadSnapshot()` (it renders
**and** reads the WebGL buffer in one tick, so the `preserveDrawingBuffer:false` inter-frame clear
cannot blank the read-back — reading the live canvas on a later frame would).
