# Fleet Data migration pattern (#1547): move one dataset out of an .aar/APK

Rule: shared libs are CODE ONLY; every dataset is served at runtime over the cloud-store / cloud-account ContentProvider IPC.

## Where the providers get the bytes (no bundled dir, no build staging)
`FleetDataEndpoint` (libs/fleet-data-provider) keeps a per-authority store `filesDir/fleetdata` and a `FleetDataSync` (WorkManager, ETag conditional GET):
- Cloud Store (`cloud.store.data`, tiers 3-4): `PublicRepoSource` pulls `https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/data/fleet/manifest.json`, then every dataset it lists. Source path of a dataset = manifest `source`, else `data/fleet/<dataset>.json`, else (app dataset) `data/fleet/app/<pkg>/<dataset>.json`. So the source of truth is this repo's `data/fleet/` on main: a push to main IS the publish, no rebuild.
- Cloud Account (`com.diegonmarcos.cloudaccount.accountdata`, tiers 1-2): `FleetApiSource` pulls from the authenticated cloudy-c3-api `GET /fleet-data/<tier>/<dataset>` (private repo cloud-me-pvt-configs) with the fleet bearer. Never from the public repo.
- A body replaces the stored copy only if it parses and its `schemaVersion` is accepted; otherwise last-known-good stays. A missing dataset nudges one sync (at most every 5 min).
- Client side `FleetData.get/observe` (libs/fleet-data-client) opens `content://<authority>/shared/<dataset>` (or `/app/<pkg>/<dataset>` with `own=true`), keeps its own last-known-good in `filesDir/fleetdata-lkg`, returns null while nothing is served.

## Migrating one tier-3 dataset (4 steps)
1. Source: `git mv` the asset to `data/fleet/<dataset>.json` (shared; or `data/fleet/app/<pkg>/<dataset>.json` for one app), add a top-level integer `"schemaVersion": 1`, and list it in `data/fleet/manifest.json` (`shared.<dataset>` = `{tier:3, consumers:["*"|pkgs]}`, or `apps.<pkg>: [<dataset>]`). Tier 1-2 data goes to the private route instead (Account), never data/fleet.
2. Reader: replace `assets.open(NAME)` with `FleetData.get(ctx, "<dataset>") { parse(it) }` (or `observe`), `implementation project(':libs:fleet-data-client')`; on null/exception return the empty value and do NOT cache it, so the next call retries. Never throw.
3. Delete the asset from the lib; drop its line from `1_cicd/src/data/runtime-data-baseline.json` and lower `json_max` by one (guard D1/D5, shrink-only).
4. Proof: a JVM test asserting no asset left, reader uses `FleetData.get(`, manifest lists the dataset, served file has `schemaVersion` and parses (see `FleetPermsDatasetTest`). Fix any other test/guard that read the old asset path. Runtime proof is the sync: `Cloud Store > Data` page lists the dataset after "Sync now".
Generated datasets: change the generator's output path (e.g. perms guard `FLEET_REL`), not the file by hand.
