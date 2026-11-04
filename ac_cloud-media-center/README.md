# cloud-media-center

The fleet's media hub, as an APK — Rust and static web only. A Tauri 2 Android
app (`com.diegonmarcos.cloudmediacenter`) whose one webview shows seven
mobile-first pages built from the media data of
[diegonmarcos.github.io](https://github.com/diegonmarcos/diegonmarcos.github.io),
pinned at one commit (`build.json::data.source`).

| Page | Data | A tap opens |
|---|---|---|
| `web/music/` | `b-Media/mySocials/src/data/tidal.json` — every section, folder, playlist and track | a TIDAL search for "artist title" |
| `web/videos/` | `youtube.json` — ids parsed from the thumbnail URLs | the video on YouTube |
| `web/gallery/` | `pinterest.json` — board URLs by `main.ts`'s slug rule | the board on Pinterest |
| `web/books/` | `shelf.json` — covers from Open Library by ISBN | the book on Open Library |
| `web/feed/` | `b-Data/myfeed/src/data/sampleFeed.ts`, newest first | the article (markdown posts read in place) |
| `web/movies/` | `c-Circus/movies/src/data` — the site's shelves and posters | a detail sheet, then IMDb or a trailer search |
| `web/documentaries/` | the YouTube "Docs" playlist plus every Documentary-genre title | YouTube / the detail sheet |

**Library and playlist sync (#1469).** `web/library/` shows the owner's synced playlists, boards and video lists as dense rows;
Sync now runs on demand and Configs > General sets a schedule (run when the app opens and the last sync is old enough). What is
synced is declared in `web/data/media-sources.json`: each service has a method (`direct` = the official API with the owner's token:
Spotify Web API, YouTube Data API, Deezer; `apify` = an actor id run through the backend chosen in Configs > Apify, Apify Cloud by
default, self-hosted Cloudy Crawlee opt-in) and a default actor. Configs order: Music, Video, Images / Boards (each with Apify | Direct
subsections), Books, Feed, Movies, Apify, General, Perms, About (the order is allowed for this app in
`1_cicd/src/data/configs-pages.json::tabs.app_order`). Tokens are AES-GCM encrypted in the webview storage with a non-extractable key.
Music is matched against the collection with a JS port of Cloud Browser's ISRC-then-fuzzy matcher (libs:scraper is Kotlin, which this
app does not have). Metadata only, no DRM. Tester: `test/test-media-center-sync.sh`.

Every link out goes through `tauri-plugin-opener` (`opener:default`, the one
capability), so TIDAL, YouTube and Pinterest open in their own apps.

| Piece | Where |
|---|---|
| Pages: pure render half (tested under node) + browser boot | `web/assets/pages.js` |
| The one stylesheet | `web/assets/app.css` |
| Page data, generated, never fetched at runtime | `web/data/*.json` ← `data/regen.py` |
| The app: runtime + opener plugin | `src-tauri/src/lib.rs` |
| Tauri's Android project (template, committed) | `src-tauri/gen/android` |
| Shared modules (fleet mesh membership), read by `settings.gradle` | `build.json::modules` |
| Toolchain pins: tauri CLI + sha256, NDK, AGP, gradle | `build.json::toolchain` |

Moving the data pin: change `build.json::data.source.commit`, run
`python3 data/regen.py`, commit `web/data/`. `python3 data/regen.py --check`
fails when the committed data no longer matches the pin.

**The name.** Until 2026-10 `cloud-media-center` was the gallery app; that app
is now `cloud-gallery` (`ac_cloud-gallery/`) and keeps its applicationId
`com.diegonmarcos.mediacenter`. This app has its own identity and reuses only
the freed names: `cloud-media-center.apk`, `cloud-media-center-x86_64.apk`,
the `cloud-media-center` GHCR image and `extapp:cloud-media-center`, which
Cloud Me's Media tiles and the SuperApp's Data Apps tile point at.

Build: `./build.sh build` (Nix devShell) or the `ship-cloud-media-center.yml`
workflow, which builds arm64 and x86_64 on ubuntu-latest and re-signs both
with the one shared constellation key. The only Kotlin is Tauri's template
glue, pinned by sha256 in `build.json::tauri.glue`;
`test/test-media-center-no-kotlin.sh` fails CI and `build.sh` fails the build
on anything else.

**Movies player (legal sources only).** The detail sheet's "Watch · sources"
opens `web/assets/player.js`: a per-title picker over the list in
`web/data/sources.json` — Jellyfin/Emby/Plex by IMDb id, a cloud-drive folder
(`index.json`) or files picked on the phone, TMDB where-to-watch (logos + deep
links into each service's app), the official YouTube trailer, Internet Archive
public-domain films and Blender open movies, and Internet Archive / pasted
torrents (WebTorrent, sequential). Buffer modes 30 s / 2 min / 10 min / full /
offline. Only sources that have the title are listed. The list, the logic
(`web/assets/sources-core.js`) and the denylist (`test/legal-denylist.json`)
are copies from diegonmarcos.github.io `c_Circus/movies` at
`build.json::data.shared.commit` (regen.py). Keys and tokens are entered in the
player's ⚙ and stay in the webview's storage. DLNA/UPnP discovery and
ExoPlayer/libtorrent would need Kotlin or new Rust plugins, which this app's
Rust-and-web rule does not allow yet.

Testers: `test/test-media-center-legal.sh` (repo-wide denylist guard, the shared core, the player wiring), `test/test-media-center-pages.sh` (every page renders its data, the
data's pins, the link rules, the Tauri wiring), `test/test-media-center-no-kotlin.sh`,
`test/test-fleet-setup.sh`.
