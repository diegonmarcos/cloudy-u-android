# Cloud Search (called Cloud Research from #1162 to #1197) — what it reads, what it reuses, and why

The app is branded Cloud Search (launcher, store, tile; tile caption Search). Its identifiers never changed, on purpose: directory `ac_cloud-search`, fleet id `search` / `cloud-search`, applicationId `com.diegonmarcos.cloudsearch`, GHCR image and APK asset `Cloud-Search*.apk`. Renaming any of them would install as a second app (a new applicationId cannot update the old one) and break the Store's update path.

## Data sources (build.json::search.sources is the one declaration)

| Source | In-app | Licence / terms |
|---|---|---|
| OpenRouter chat completions + model catalogue | AI chat | The user's own token from the fleet Account. |

The test fixtures in `core/src/test/resources/fixtures/` are trimmed responses saved from these
sources on 2026-10-02.

## Reuse, and what was not reused

- libs:core / libs:devtools (mesh member, debug API) and libs:text-tools (the fleet Account's
  OpenRouter token) — shared by reference. #797: libs:bottomnav and libs:ui-kit are NOT linked any
  more; the app draws its own chrome from the owner's mockup.
- Icons: Phosphor Icons (@phosphor-icons/core 2.1.1, MIT, © 2023 Phosphor Icons), converted to
  vector drawables by `app/tools/phosphor2vd.py` from the list in `app/tools/phosphor.json`.
  The app icon (#803) is Phosphor `shooting-star` in the mockup's `.ai-nav-icon` gradient, generated
  by the same script from `phosphor.json::launcher`; cloud-superapp's Search tile carries a
  byte-identical copy of it.

## #1197 Media and the engine list (#1472: News, Shopping and Brokers removed) (all keyless, all declared in build.json::search)

- **Openverse** (images, CC-licensed; every result carries its licence), **Internet Archive** advancedsearch (moving images and its feature_films collection), **TVMaze** show search (CC BY-SA data), **Open Library** search (books): public APIs, no key, read through the same engine and cache as every other source.
- **Search engines** (`search.engines_major`): Google, Google AI Mode, Bing, DuckDuckGo, Brave, Qwant, Kagi, Perplexity, Ecosia, Startpage, Yahoo, SearXNG (searx.be), MetaGer. They are only OPENED in Cloud Browser with the query; nothing is scraped. The fleet declares no SearXNG or metasearch service of its own (ac_cloud-account fleet_endpoints), so none is called.
