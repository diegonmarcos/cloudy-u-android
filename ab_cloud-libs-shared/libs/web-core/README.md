# libs:web-core: the fleet's one embedded web view (#1371)

Every WebView in every app is built here: `CloudWeb.create(ctx, WebProfile.X)`, or `CloudWeb.configure(view, WebProfile.X)` for a view another framework built (Capacitor, a subclass). `cloud-android-webview-guard.py` fails on any WebView built or configured anywhere else. `1_cicd/src/data/webview-guard.json` lists every usage and its profile.

| Profile | For | JS | Storage | Files | Remote images | Mixed content | Autoplay | Zoom | 3P cookies |
|---|---|---|---|---|---|---|---|---|---|
| `UNTRUSTED_HTML` | mail bodies, foreign markup | off (`sandboxedJs` for our own editor) | off | off | gated (`remoteImages`) | never | no | pinch | no |
| `APP_PAGE` | trusted fleet page | on | on | bundled assets only | on | compatibility | no | no | no |
| `WEB_WORLD` | 3D/GPU worlds | on | on | off | on | compatibility | yes | no (the page owns its gestures) | yes |
| `BROWSER` | full browsing | on (per-site `site`) | on | per offline archive (`site`) | on | compatibility | no | pinch | yes |

All profiles get the same base: hardware-accelerated drawing, offscreen pre-raster (except `UNTRUSTED_HTML`), `RENDERER_PRIORITY_IMPORTANT` (a world keeps it while hidden), Safe Browsing, no universal file access, `CloudWebClient` (bundled assets over `https://appassets.androidplatform.net`, render-process recovery) and `CloudChromeClient` (full-screen video and canvases).

## Files
- `WebProfile.kt`: the four profiles (the guard reads their names here).
- `CloudWeb.kt`: factory, `configure`, desktop mode, `remoteImages`, `sandboxedJs`, `site`, `userAgent`, `appendUserAgent`, `clearCache`, `openInChrome`, asset loader.
- `CloudWebClient.java`, `CloudChromeClient.java`: the base clients. They are Java so that an app's Kotlin subclass may override with nullable or non-null parameters.
- `GpuProbe.kt`: measures WebGL2, `navigator.gpu`, the adapter and its features once per process; `GpuCaps.summary()` is the line a UI shows.
- `SiteCache.kt`, `SiteMirror.kt`, `SiteMirrorAndroid.kt`, `PinPolicy.kt`: the offline copy of a pinned site (#1518) and what its intercept does with a request (#1711).

## Pin offline (#1711)
`SiteMirrorStore.intercept` answers a pinned site's request from the copy and never fetches anything itself. `PinPolicy.decide` is the rule: what the copy holds is served cache-first (never expiring); what it does not hold, a document of a host that shows documents live (Galaxy; Cloud-Browser keeps pinned documents cache-first) and everything on a reload go straight to the WebView's own network stack, and `PinRecorder` stores them afterwards on one background thread (conditional on the stored ETag; a document or a cache-defeating address again only once the copy's answer is an hour old). Before, such a request was fetched inside the WebView's request by a second HTTP client, so a page whose first data request can never be in the copy (floor796: `data/matrix.json?r=<random>`) waited on that client for its first frame. A host that shows the page from the copy because the live site did not answer passes `PinPolicy.Source.COPY`: the copy then also answers an address whose query was never stored with the newest stored query of that path (`SiteCache.newest`). `SiteCache` writes every file whole or not at all (temp file + rename), serializes its writers (parallel requests no longer lose each other's manifest entries), serves an entry only when its blob holds every byte, and keeps the newest `MAX_VARIANTS` queries of a path. `PinLog` (logcat tag `CloudWebPin`) records what the intercept did with each request.

Scroll and nav-chrome integration stays in `libs:bottomnav` (`ChromeWebScroll`, `ChromeWebView`). Browser-only features (tabs, downloads, autofill, Web Bluetooth, reader) stay in `libs:browser`.

## GPU
- Hardware acceleration: the window is hardware-accelerated by default and the view keeps `LAYER_TYPE_NONE`, so the WebView draws through its GL functor straight into the window. `LAYER_TYPE_HARDWARE` would add an offscreen copy of the whole page per frame. A host may still latch a device onto software after `configure` (Mail's `WebViewLayerGuard`, for HWUI/GPU blob crashes).
- WebGL2 and WebGPU have no `WebSettings` switch. They are on whenever the installed Android System WebView and the GPU driver allow them. `GpuProbe` measures them at runtime in a secure context (`navigator.gpu` only exists there).
- WebGPU in Android WebView depends on the System WebView version, the Android version (Chrome ships it on Android 12+), and a Vulkan driver that is not blocklisted. A device can expose `navigator.gpu` and still return no adapter, or an adapter without a feature a site requires.

## What Android WebView cannot do, and the fallback
| Missing in WebView | Fallback |
|---|---|
| WebGPU on an older WebView, an unsupported GPU, or the emulator's software Vulkan | `GpuProbe` reports it; the world shows a capability line and **Open in Chrome** (`openInChrome`, `com.android.chrome`, any browser if Chrome is absent). |
| Immersive WebXR (`immersive-vr`/`immersive-ar` sessions) | Galaxy offers the browser for a VR world. |
| `navigator.userAgentData` overrides | Desktop mode changes `navigator.userAgent` and the viewport only; a site that reads client hints still sees a phone. |
| A per-view GPU or memory budget | Renderer death is survived (`onRenderProcessGone`), not prevented; the view is rebuilt and reloaded. A subclass (Mail) stays blank instead. |
| Downloads, file chooser, permission prompts by default | The host wires them (Browser: `libs:browser`; Mail mini browser: system hand-off). |
| Desktop mode cannot add capability | A site gated by user agent loads its desktop path; a capability check (`navigator.gpu`) still answers for this device. |

## Reference world: foodforfish.org
The site answers phones with "No mobile for now". The gate has two stages, both in its page JS: first a user-agent regex (`/Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i` on `navigator.userAgent`), then `navigator.gpu.requestAdapter()` and `requestDevice({requiredFeatures: ["rg11b10ufloat-renderable", "float32-filterable"]})`. Any failure draws the same fail image. Desktop mode passes the first stage. Only a WebView with WebGPU and both features passes the second; `GpuCaps.has(*FOODFORFISH_FEATURES)` answers it. Otherwise Chrome is the fallback.

## WebGPU on Exynos phones (#1430)
On a Samsung Galaxy A37 (Exynos 1480, Xclipse 530, Android 16, WebView 156) `navigator.gpu` exists and `requestAdapter()` returns null. Chromium enables WebGPU on Android only for allowlisted GPUs (ARM, Qualcomm, Intel on Android 12+, Imagination on 16+); Samsung Xclipse is listed as "TBD", so Dawn drops the Vulkan adapter. Chrome on the same phone reads the same list. Nothing an embedder sets changes that: there is no `WebSettings` or `androidx.webkit` WebGPU switch, no origin trial, and `enable-unsafe-webgpu` only applies through the WebView DevTools flags (debug builds of WEB_WORLD/BROWSER enable remote debugging for this). `GpuProbe` now also asks for a compatibility-mode (OpenGL ES) adapter and `GpuCaps.reason()` explains the gap. `WebEngine` declares GeckoView (MPL-2.0, about 50-80 MB per ABI) as a future per-profile engine; it is not linked, because Firefox for Android still keeps WebGPU behind `dom.webgpu.enabled`.
