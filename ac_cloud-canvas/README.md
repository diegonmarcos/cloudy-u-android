# Cloud Canvas

A paint app and a music mixer (#1780). Tile `Canvas` sits in Cloud > Apps > Primary (Tools · Primary), right after Camera.

* Package `com.diegonmarcos.cloudcanvas`, image `cloud-canvas`, asset `Cloud-Canvas.apk`, ship workflow `ship-cloud-canvas.yml`.
* **Bottom nav Draw | Play** (`build.json::ui`, default Draw).
* **Draw**: the shared Compose canvas, no WebView. Brush, pencil, marker, eraser, fill, line, rectangle, ellipse, text, eyedropper; colour picker with recent colours, size and opacity; layers (add, hide, reorder, opacity); undo/redo; pinch zoom and pan; canvas size presets; stylus pressure; open a picture to paint over; export PNG to a folder chosen through SAF; share. The document is immutable `DocState` snapshots (undo = the previous snapshot); each layer is the replay of its ops onto a bitmap; the PNG is `Pixels.composite` + `PngEncoder` (pure Kotlin).
* **Play**: piano, drum pads and synth, all synthesized (`audio/Synth.kt`), through a low-latency AudioTrack (`PERFORMANCE_MODE_LOW_LATENCY`, native rate and burst). Mixer of 4 to 8 tracks with volume, pan, mute, solo and a loop recorder (notes stored in beats, so a tempo change re-times them), tempo, metronome, transport, WAV export (`OfflineMix` + `WavEncoder`).
* **Permissions**: none. No microphone (loops are recorded as notes), no storage permission (SAF), no network.
* **Sounds**: synthesized in code, no sample files; nothing to fetch or license beyond the app itself (`licenses/curated.json`).

## Tests

* `test/test-canvas-app.sh` (static, each rule with a planted mutation): navigation, no WebView, permissions, no audio asset, the suites exist.
* `app/src/test`: `DrawTest` (stroke/undo, layers, fill, compositing, PNG), `PlayTest` (mute/solo, pan law, loop timing, WAV, offline mix), `NavShapeTest`.
* `test/emulator-canvas-screens.sh` + `.github/workflows/canvas-screens-emulator.yml` (dispatch only): screenshots of Draw with a drawing, Draw layers, Play instruments and the Play mixer.
