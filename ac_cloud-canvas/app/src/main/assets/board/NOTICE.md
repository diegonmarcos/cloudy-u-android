# Board: the fleet's tldraw web app, vendored offline

* **What**: tldraw 5.2.3 as built by the fleet's Suite (repository diegonmarcos/cloudy-h-webapps-desk, `c_Suite/tldraw`),
  published at https://diegonmarcos.github.io/tldraw/, plus the files tldraw loads from https://cdn.tldraw.com/5.2.3/
  (fonts, icon sprite, English translation, embed icons, watermark).
* **Pin**: `PIN.json` (every file with its sha256 and source URL; patched files also carry the upstream sha256 and the patch).
  Re-vendor with `ac_cloud-canvas/tools/vendor-tldraw.py fetch`; `check` runs in test-canvas-app.sh (T7). Never edit by hand.
* **Licences**:
  * tldraw SDK: the tldraw licence (`LICENSE.md`, https://github.com/tldraw/tldraw/blob/main/LICENSE.md). Without a licence
    key the production build shows the "Made with tldraw" watermark; that is accepted for this personal app.
  * The Suite wrapper (the published app around the SDK): own code of the fleet owner.
  * Fonts in `cdn/5.2.3/fonts`: IBM Plex Sans/Serif/Mono (IBM, SIL Open Font License 1.1) and Shantell Sans (Arrow Type,
    SIL Open Font License 1.1).
  * `cdn/5.2.3/embed-icons`: the logos of the embeddable services, as tldraw ships them; trademarks of their owners.
* **Patches** (see PIN.json): the analytics snippets are removed from index.html and the bridge `tldraw/cloud-board-bridge.js`
  (own code, #1787) is loaded; IndexedDB persistence is switched off and the editor is published as `window.tldrawEditor`, so
  the boards live in app storage through the bridge.
