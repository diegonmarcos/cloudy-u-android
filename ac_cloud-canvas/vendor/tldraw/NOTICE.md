# The fleet's tldraw web app, pinned but NOT shipped

**Not in the APK.** tldraw 5.x refuses to render a production build without a licence key: its LicenseProvider classifies it as
`unlicensed-production` and after 5 seconds replaces the editor with `<div data-testid="tl-license-expired">` (the published
Suite page is blank for the same reason). Bypassing that would mean patching tldraw's licence enforcement, so Board ships
Quickdraw (MIT, `app/src/main/assets/board`). This copy is kept so Board can move back once the Suite build carries a licence key
valid for `https://appassets.androidplatform.net`.

* **What**: tldraw 5.2.3 as built by the fleet's Suite (repository diegonmarcos/cloudy-h-webapps-desk, `c_Suite/tldraw`),
  published at https://diegonmarcos.github.io/tldraw/, plus the files tldraw loads from https://cdn.tldraw.com/5.2.3/
  (fonts, icon sprite, English translation, embed icons, watermark).
* **Pin**: `PIN.json` (every file with its sha256 and source URL; patched files also carry the upstream sha256 and the patch).
  Re-vendor with `ac_cloud-canvas/tools/vendor-board.py fetch tldraw`; `check` runs in test-canvas-app.sh (T7). Never edit by hand.
* **Licences**:
  * tldraw SDK: the tldraw licence (`LICENSE.md`, https://github.com/tldraw/tldraw/blob/main/LICENSE.md). A production build needs
    a licence key; without one the editor does not render (see above).
  * The Suite wrapper (the published app around the SDK): own code of the fleet owner.
  * Fonts in `cdn/5.2.3/fonts`: IBM Plex Sans/Serif/Mono (IBM, SIL Open Font License 1.1) and Shantell Sans (Arrow Type,
    SIL Open Font License 1.1).
  * `cdn/5.2.3/embed-icons`: the logos of the embeddable services, as tldraw ships them; trademarks of their owners.
* **Patches** (see PIN.json): the analytics snippets are removed from index.html and the bridge `tldraw/cloud-board-bridge.js`
  (own code, #1787) is loaded; IndexedDB persistence is switched off and the editor is published as `window.tldrawEditor`, so
  the boards live in app storage through the bridge.
