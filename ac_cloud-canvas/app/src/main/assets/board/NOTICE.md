# Board: Quickdraw, vendored offline

* **What**: Quickdraw, an infinite-canvas whiteboard engine (`@quickdrawjs/core`, https://github.com/quickdrawjs/quickdraw,
  https://tryquickdraw.com): `quickdraw/src/` is the package's `src/` from its npm tarball, byte for byte, with no build step.
* **Licence**: MIT (`LICENSE`, Copyright (c) 2026 Quickdraw contributors). Fonts in `quickdraw/src/fonts`: Libre Baskerville,
  SIL Open Font License 1.1 (`quickdraw/src/fonts/OFL.txt`). The engine's "Quickdraw" corner mark is kept as attribution.
* **Pin**: `PIN.json` (version, tarball URL and integrity, every file's sha256 and source URL). Re-vendor with
  `ac_cloud-canvas/tools/vendor-board.py fetch quickdraw`; `check` runs in test-canvas-app.sh (T7). Never edit by hand.
* **Own code (#1787)**: `quickdraw/index.html` (mounts the engine) and `quickdraw/cloud-board-bridge.js` (saves and loads boards
  through the app's `CloudBoard` web message listener).
* **Why not tldraw**: the brief's first choice, the fleet's tldraw 5.2.3 build, does not render in a production build without a
  licence key (the editor is replaced after 5 seconds). It is kept pinned in `ac_cloud-canvas/vendor/tldraw`, outside the APK.
