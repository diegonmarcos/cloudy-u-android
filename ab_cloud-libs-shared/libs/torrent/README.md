# libs:torrent

The fleet's BitTorrent engine, as code only (no assets, no resources, no BuildConfig data). Linked by Cloud Store only.

- **Engine**: libtorrent through jlibtorrent 1.2.0.18 (`com.frostwire`), prebuilt `lib/arm64-v8a/libjlibtorrent-1.2.0.18.so` (64 KB aligned).
  Nothing is cross-compiled in CI. Upstream's x86_64 build is 4 KB aligned and fails the 16 KB page-size guard, so the x86_64 emulator APK
  has no libtorrent: its engine reports `EngineState.Unavailable` with the reason.
- **API** (`TorrentEngine`, one per process via `TorrentEngines.get(ctx)`):
  `start()` / `stop()` / `startPolling()`; `addMagnet(uri)`, `addTorrentFile(file)`, `addTorrentBytes(label, bytes)` -> `AddResult`;
  `refresh()` -> `List<TorrentStatus>` (state, progress, rates, seeds, peers, uploaded, save path); `pause` / `resume` / `remove(id, deleteFiles)`;
  `setSeeding(id, on)`; `settings.saveDir` (default `Downloads/Cloudy-store/Torrent`, created if missing), `settings.seedAfterComplete`, `settings.seedRatioLimit`.
- **Seam**: `TorrentBackend` is the only thing the engine knows of the session; `JlibtorrentBackend` is the one class that names native types.
  The tests run the engine against a fake backend (no peers, no `.so`).
- **Search**: `TorrentIndexers.all` is the declared source list (The Pirate Bay API, YTS, Internet Archive). A source is an object with a `searchUrl`
  and a pure `parse(body)`; add one to the list and Search > Torrent shows it.
- **Paths**: `TorrentPaths` (default folder, prepare / free space, volume roots, picker tree id -> path).
- **Permissions**: INTERNET is declared here. The storage grant (all-files access) is the host's, asked on its libs:perms page; without it the default
  folder degrades to the app's own Downloads folder (`PrefsSettings.usingFallback`).
