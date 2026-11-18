# libs:torrent

The fleet's BitTorrent engine, as code only (no assets, no resources, no BuildConfig data). Linked by Cloud Store only.

- **Engine** (#1790): libtorrent 2.1 through libtorrent4j 2.1.0-39 (`org.libtorrent4j`, MIT, OpenSSL 3.5), prebuilt `lib/arm64-v8a/libtorrent4j.so`
  and `lib/x86_64/libtorrent4j.so`, both 16 KB LOAD-aligned. Nothing is cross-compiled in CI. It replaced jlibtorrent 1.2.0.18 (libtorrent 1.2.0.0,
  2019), whose x86_64 build was 4 KB-aligned (the emulator APK had no engine, so downloads were never testable end to end) and whose 2019
  interface enumeration predates Android 11's netlink limits for apps targeting API 30+. Web seeds (BEP 19 `url-list`) are libtorrent's own;
  `Magnet.parseTorrent` reads them and every row reports how many the session uses. An Internet Archive torrent's baked data-server seed goes stale when
  the item moves, so `ArchiveSeeds` adds the item's live servers from archive.org/metadata as plain-HTTP web seeds. The emulator proof is `store-torrent-emulator.yml`
  (a public-domain Mozart item from the Internet Archive, downloaded to completion and hash-checked against archive.org's metadata).
- **API** (`TorrentEngine`, one per process via `TorrentEngines.get(ctx)`):
  `start()` / `stop()` / `startPolling()`; `addMagnet(uri)`, `addTorrentFile(file)`, `addTorrentBytes(label, bytes)` -> `AddResult`;
  `refresh()` -> `List<TorrentStatus>` (state, progress, rates, seeds, peers, uploaded, save path); `pause` / `resume` / `remove(id, deleteFiles)`;
  `setSeeding(id, on)`; `settings.saveDir` (default `Downloads/Cloudy-store/Torrent`, created if missing), `settings.seedAfterComplete`, `settings.seedRatioLimit`.
- **Seam**: `TorrentBackend` is the only thing the engine knows of the session; `LibtorrentBackend` is the one class that names native types; it also keeps the session's last tracker / web seed / file complaint per torrent (`TorrentStatus.note`) and logs alerts under the `CloudTorrent` tag.
  The tests run the engine against a fake backend (no peers, no `.so`).
- **Search**: `TorrentIndexers.all` is the declared source list (The Pirate Bay API, YTS, Internet Archive). A source is an object with a `searchUrl`
  and a pure `parse(body)`; add one to the list and Search > Torrent shows it.
- **Paths**: `TorrentPaths` (default folder, prepare / free space, volume roots, picker tree id -> path).
- **Permissions**: INTERNET is declared here. The storage grant (all-files access) is the host's, asked on its libs:perms page; without it the default
  folder degrades to the app's own Downloads folder (`PrefsSettings.usingFallback`).
