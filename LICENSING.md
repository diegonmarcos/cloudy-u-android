# Licensing map — cloud-u-android

**Not legal advice.** This file records what the repository contains, where each part came
from and which licence its files carry today, with the evidence for each statement. Where a
choice is open it is marked **PROPOSED**: nothing here relicenses anything, and no existing
`LICENSE` file was changed. The owner decides (#815).

Machine-readable sources, all under [`licenses/`](licenses/):

| File | What it is | Produced by |
|---|---|---|
| `upstreams.json` | every in-tree directory that came from an upstream project, its licence and pinned revision | hand-written; pins are read from each app's `build.json` where one is declared |
| `provenance.json`, `provenance/<id>.tsv` | upstream-derived vs newly authored, per module and per file | `1_cicd/src/scripts/cloud-android-licence-provenance.py ROOT` (clones each upstream at its pin, read-only) |
| `curated.json` | what every top-level directory is and which licence applies to it; per-asset overrides | hand-written |
| `inventory.json` | 1,149 subjects: every top-level directory, vendored licence-bearing directory, Maven coordinate, npm package and licence-bearing binary, each with its licence and where it is declared; plus which shared libraries each app compiles in (`linkage`) | `1_cicd/src/scripts/cloud-android-licence-inventory.py refresh ROOT` |

CI: `licence-inventory-guard.yml` runs `cloud-android-licence-inventory.py check` on every push
and fails naming any new dependency, vendored directory, binary or top-level directory that has
no inventory entry. Its tester (`1_cicd/src/scripts/test/licence-inventory-guard.test.sh`)
breaks the inventory eight ways on a fixture repository and requires a failure each time.

Limits of the inventory, stated so it is not read as more than it is:
- **Transitive dependencies are not resolved.** Only what build files declare is listed; a full
  transitive list needs a Gradle/npm resolution, which was not run (shared runners). Every
  maven/npm entry says `"transitive": "not resolved"`.
- Versions that come from a BOM or a catalogue reference Gradle would resolve are looked up at
  the latest release; the entry says so.
- 34 subjects are `NOASSERTION` (the licence could not be established from the POM/registry or
  the source): see the conflicts section.

## 1. Two rules that decide everything below

1. **A file taken from an upstream project and then modified stays under the upstream licence**
   (it is a derivative work), whatever share of the module the owner later rewrote. The
   percentages in section 3 describe how much is upstream's; they do not change which licence
   applies to a modified file.
2. **Files the owner wrote from scratch are the owner's to license** — but when they are compiled
   into the same binary as GPL code, that binary as a whole is distributed under the GPL (section 4).
   The source files themselves can still carry the owner's own licence elsewhere.

## 2. Top-level directories → licence today

`NoLicenseGranted` = no licence file applies, so the code is all rights reserved by default.

| Directory | Licence that applies today | Notes |
|---|---|---|
| `ac_cloud-termux` | GPL-3.0-only | Termux fork (`LICENSE.md`) |
| `ac_cloud-nix-on-droid` | GPL-3.0-only | Nix-on-Droid fork (`LICENSE.md`) |
| `ac_cloud-dialer` | GPL-3.0-only | Fossify Phone fork (`LICENSE`) |
| `ac_cloud-mail` | GPL-3.0-only | Sterna Mail fork (`LICENSE`) |
| `ac_cloud-vault` | GPL-3.0-only | Bitwarden Android fork (`LICENSE.txt`, `NOTICE.md`); ships the `fdroid` flavour (no Firebase/ML Kit/Play Billing, `ac_cloud-vault/build.json`) |
| `ac_cloud-matrix` | AGPL-3.0-only OR Element commercial | Element X fork (`LICENSE`, `LICENSE-COMMERCIAL`) |
| `ac_cloud-chat` | Apache-2.0 | Mattermost Mobile fork (`LICENSE.txt`, `NOTICE.txt`) |
| `ac_cloud-gallery` | Apache-2.0 | ReFra/Gallery fork (`LICENSE`, `NOTICE.md`) |
| `ac_cloud-camera` | MIT | GrapheneOS Camera fork (`LICENSE`) |
| `ac_cloud-code` | MIT | Acode fork (`license.txt`); `codemirror-lsp-client/` MIT; some `src/plugins/*` carry their own licence files |
| `ac_cloud-notes` | MIT | AFFiNE fork, MIT part only; the Enterprise-licensed dirs are pruned and `licence-guard.yml` keeps them out |
| `ac_cloud-office` | MPL-2.0 (patches) / own (engine scripts) | Collabora Online patch series; the upstream tree is fetched at build time at the pin in `build.json::upstream.online` |
| `ac_cloud-keyboard` | GPL-3.0-only (as shipped) | own app module, but it compiles `libs/keyboard` (HeliBoard, GPL-3.0-only) |
| `ac_cloud-myterminal` | own (hub) + upstream licence for `forks/` | `forks/files`, `forks/utils`: Amaze, GPL-3.0 patches; `forks/editor`: Acode, MIT |
| `ab_cloud-libs-shared` | own, with the exceptions below | shared libraries |
| ↳ `libs/keyboard` | GPL-3.0-only | HeliBoard-derived (README) |
| ↳ `libs/keyboard` Unexpected layout + corner swipes (#1413) | GPL-3.0-only | `assets/layouts/main/unexpected.json`, `keyboard/CornerSwipe.kt`: original work *in the style of* Unexpected Keyboard (Julow/Unexpected-Keyboard, GPL-3.0-only); no code or assets copied. Compatible either way: the keyboard tree is GPL-3.0-only |
| ↳ `libs/gitsync` | GPL-3.0 | GitSync vendored (`LICENSE.md`, `VENDORED.md`); fonts: Atkinson Hyperlegible OFL-1.1, Roboto Mono Apache-2.0 |
| ↳ `libs/firewall/firestack` | MPL-2.0 | celzero/firestack (`LICENSE`) |
| ↳ `libs/net`, `libs/net-wg` | Apache-2.0 | wireguard-android `tunnel/` |
| ↳ `libs/cropper`, `libs/gesture`, `libs/panoramaviewer`, `libs/scrollbar` | Apache-2.0 | from ReFra (gallery's upstream) |
| ↳ `libs/calc` native engine | GPL-3.0-or-later (as shipped) | statically links libqalculate (GPL-2.0-or-later), GMP, MPFR — `ac_cloud-calc/NOTICE.md` |
| ↳ `libs/contacts/.../SocialImport.kt` | own (clean-room, #828) | the Fossify-derived port was deleted and rewritten clean-room: spec first (`a0_docs/eng-specs/social-import.md`, from the callers and the public LinkedIn/Instagram export formats), black-box tests against it, then a fresh implementation written without the original body |
| ↳ every other `libs/*`, `lib-apks`, `keyboard-engines` | NoLicenseGranted (own) | `aa_cloud-superapp/build.json::upstreams` declares FairEmail, DAVx5, Feeder, KDE Connect and RethinkDNS as *reference* upstreams; `kde-connect` and `firewall` are stated clean-room. `libs/mail`, `libs/cal`, `libs/feed` carry no upstream header and were not measured against those projects |
| `aa_cloud-superapp` | NoLicenseGranted (own) | |
| `ac_cloud-calc`, `ac_cloud-search`, `ac_cloud-me`, `ac_cloud-drive`, `ac_cloud-nav`, `ac_cloud-wallet`, `ac_cloud-writer`, `ac_cloud-news`, `ac_cloud-tasks`, `ac_cloud-browser`, `ac_cloud-contacts`, `ac_cloud-c3`, `ac_cloud-c3-webserver`, `ac_c3-morpheus`, `ac_c3-watchdog`, `ac_c3-watchtower` | NoLicenseGranted (own) | `ac_cloud-calc/NOTICE.md` and `ac_cloud-search/NOTICE.md` list their third-party parts and data terms |
| `a_solutions` | NoLicenseGranted (own) | `ae-tool_termux-boot` is stated not a termux-boot fork |
| `1_cicd`, `9_others`, `_dotfiles/apps`, `ab_cloud-terminal-store`, `ab_cloud-libs`, `ab_cloud-keyboard-libs`, `b_infra`, `c_devices`, `.github` | NoLicenseGranted (own) | build system, CI, guards |
| `a0_docs`, `a0_tasks`, `2_sops`, `2_configs`, `_dotfiles/git`, `3_secrets`, `4_reports`, `licenses`, dot-dirs | NoLicenseGranted | docs, configuration, encrypted secrets, reports |
| `z_archive` | not inventoried | archived, not built |
| submodules `cloud`, `aa_cloud-superapp-mcp`, `aa_cloud-superapp-web`, `aa_cloud-terminal-flakes` | their own repositories | not covered here |

The full per-directory record (with `kind`, `what`, `exceptions`) is `licenses/curated.json::directories`.

### 2.1 A vendored Mozilla Public License 2.0 directory (#1098)

`ac_cloud-notes/packages/common/theme` is the Cloud Notes design-token package,
vendored as build output from a package published under MPL-2.0 (its `LICENSE` is
kept). MPL-2.0 is file-level: those files stay MPL-2.0 and their source is this
directory; the rest of `ac_cloud-notes` stays MIT. Modification: variable and type
names renamed to Cloud Notes naming; source maps and font files left out.

## 3. Upstream-derived vs newly authored, per module

Measured 2026-10-03 against each module's pinned upstream revision, tree at `fe93fb7c4`.
Files: verbatim (byte-identical to an upstream file) / modified (shares lines with its upstream
counterpart) / new. Lines: non-blank lines shared with the upstream counterpart vs the rest.
Method and its limits: the docstring of `cloud-android-licence-provenance.py` (multiset line
overlap, not an ordered diff; a renamed identifier counts as new, so "new" is an upper bound on
what the owner wrote). Per-file detail: `licenses/provenance/<id>.tsv`.

| Module | Path | Upstream | Upstream licence | Pinned revision | % upstream-derived (lines) | Files V/M/N | Lines derived / new |
|---|---|---|---|---|---|---|---|
| dialer | `ac_cloud-dialer` | Fossify Phone | GPL-3.0-only | 1.11.1 | 66.2% | 322/31/67 of 420 | 17,826 / 9,086 |
| lib-net-wg | `ab_cloud-libs-shared/libs/net-wg` | wireguard-android `tunnel/` | Apache-2.0 | **none recorded**; closest upstream commit 1.0.20250522 (`b9969891b8`, 10 identical files) | 71.6% | 10/3/3 of 16 | 916 / 364 |
| nix-on-droid | `ac_cloud-nix-on-droid` | Nix-on-Droid app | GPL-3.0-only | e87b609 | 84.6% | 311/24/27 of 362 | 41,055 / 7,465 |
| termux | `ac_cloud-termux` | Termux (termux-app) | GPL-3.0-only | v0.118.3 | 85.2% | 254/23/32 of 309 | 32,959 / 5,744 |
| camera | `ac_cloud-camera` | GrapheneOS Camera | MIT | 80ff01a955d3 | 85.6% | 129/80/20 of 229 | 31,441 / 5,269 |
| lib-net | `ab_cloud-libs-shared/libs/net` | wireguard-android `tunnel/` | Apache-2.0 | **none recorded**; closest 1.0.20251231 (`a494c62bd9`, 17 identical files) | 88.7% | 17/2/3 of 22 | 2,391 / 304 |
| mail | `ac_cloud-mail` | Sterna Mail | GPL-3.0-only | 6941b3ae50ef | 91.8% | 1058/117/96 of 1271 | 271,574 / 24,222 |
| gallery | `ac_cloud-gallery` | ReFra (ex-Gallery) | Apache-2.0 | 5.1.1-51101-nightly | 94.5% | 197/984/33 of 1214 | 396,095 / 23,169 |
| keyboard | `ab_cloud-libs-shared/libs/keyboard` | HeliBoard | GPL-3.0-only | **none recorded**; closest v4.0-alpha2-4-gf6d66644 (1306 identical files) | 95.3% | 1306/107/43 of 1456 | 138,622 / 6,785 |
| code | `ac_cloud-code` | Acode | MIT | b7dbe3d69dcf | 99.2% | 1038/12/15 of 1065 | 213,632 / 1,750 |
| lib-scrollbar | `ab_cloud-libs-shared/libs/scrollbar` | ReFra | Apache-2.0 | 5.1.1-51101-nightly | 99.3% | 2/5/0 of 7 | 698 / 5 |
| lib-panoramaviewer | `ab_cloud-libs-shared/libs/panoramaviewer` | ReFra | Apache-2.0 | 5.1.1-51101-nightly | 99.4% | 3/11/0 of 14 | 1,917 / 12 |
| chat | `ac_cloud-chat` | Mattermost Mobile | Apache-2.0 | v2.40.0 | 99.6% | 3544/9/9 of 3562 | 454,774 / 1,741 |
| matrix | `ac_cloud-matrix` | Element X Android | AGPL-3.0-only OR commercial | v26.06.2 | 99.6% | 11119/6/13 of 11138 | 426,755 / 1,778 |
| vault | `ac_cloud-vault` | Bitwarden Android | GPL-3.0-only | v2026.7.1-bwpm | 99.6% | 3044/15/11 of 3070 | 540,211 / 2,381 |
| notes | `ac_cloud-notes` | AFFiNE (MIT part) | MIT | cfda4858d550 | 99.7% | 8952/27/18 of 8997 | 962,170 / 3,314 |
| firestack | `ab_cloud-libs-shared/libs/firewall/firestack` | celzero/firestack | MPL-2.0 | **none recorded**; closest `e3c328f4` (223 identical files) | 99.9% | 223/1/1 of 225 | 61,276 / 41 |
| gitsync | `ab_cloud-libs-shared/libs/gitsync` | GitSync | GPL-3.0 | 0f4902fceab3 (`VENDORED.md`) | 99.9% | 525/0/1 of 526 | 96,503 / 65 |
| code-lsp-client | `ac_cloud-code/codemirror-lsp-client` | codemirror-lsp-client | MIT | db6930c2b6db | 100.0% | 28/1/0 of 29 | 8,473 / 2 |
| lib-cropper | `ab_cloud-libs-shared/libs/cropper` | ReFra | Apache-2.0 | 5.1.1-51101-nightly | 100.0% | 39/0/0 of 39 | 4,511 / 0 |
| lib-gesture | `ab_cloud-libs-shared/libs/gesture` | ReFra | Apache-2.0 | 5.1.1-51101-nightly | 100.0% | 8/0/0 of 8 | 1,128 / 0 |
| office | `ac_cloud-office` | Collabora Online | MPL-2.0 | c0be0ce73c0d | not measured | | |

`office` is not measured: the directory is a patch series plus a build engine, not the upstream
tree, so a file-level comparison measures nothing (`licenses/upstreams.json`).

Where no pin is recorded (keyboard, firestack, net, net-wg) the base was **detected**, not
known: the upstream commit (all tags + last 600 default-branch commits) sharing the most
byte-identical files with the tree. Recording these pins is a fix in section 5.

**No forked module is below ~10% upstream-derived** — the least is the dialer at 66%. Every
module in the table is therefore a derivative that stays under its upstream licence. The
clean-room candidates for #806 are the owner's own code in section 4: about **348,000 non-blank
lines of code** outside every upstream module (largest: `aa_cloud-superapp` 82k, `1_cicd` 43k,
`ac_cloud-me` 43k, `ac_cloud-drive` 22k, `ac_cloud-calc` 12k, `ac_cloud-nav` 11k), plus about
93,500 lines newly written inside the forks (the "new" column), which remain bound to the fork's
licence as long as they live in those files.

## 4. The owner's own code, and where it is compiled into a copyleft binary

Own code (no upstream origin): the SuperApp, every `ab_cloud-libs-shared/libs/*` not named in
section 2, the engines and lib-apks, Cloud Calc/Search/Me/Drive/Nav/Wallet/Writer/News/Tasks/
Browser/Contacts, the C3 apps, `a_solutions`, and the build/CI system (`1_cicd`, `9_others`).

`licenses/inventory.json::linkage` lists, per app, the shared modules its `build.json::modules`
compiles in. The copyleft cases:

| APK | Its licence | Own libraries compiled in (ship under that licence in this binary) |
|---|---|---|
| Cloud Keyboard (`ac_cloud-keyboard`) | GPL-3.0-only | libs/analytics, core, devtools, media, text-tools, translate, voice (+ libs/keyboard itself) |
| Cloud Mail (`ac_cloud-mail`) | GPL-3.0-only | libs/bottomnav, core, devtools, ml-l-image, text-tools, updater |
| Cloud Dialer (`ac_cloud-dialer`) | GPL-3.0-only | libs/analytics, core, devtools |
| Cloud Termux, Cloud Vault | GPL-3.0-only | libs/core, devtools |
| Cloud Matrix | AGPL-3.0-only | libs/core, devtools |
| Cloud-Lib-Calc (engine APK) | GPL-3.0-or-later | the native wrapper in `libs/calc/native` |

So `libs/core` and `libs/devtools` already ship under GPL/AGPL inside five APKs, and
`libs/text-tools`, `libs/ml-l-image`, `libs/updater`, `libs/bottomnav`, `libs/analytics` inside
one or two. That does not stop the owner from licensing the same source files differently in the
own apps (as copyright holder), but every recipient of those GPL APKs is entitled to that source
under the GPL. MIT/Apache apps (camera, code, notes, chat, gallery) impose no such effect.

**PROPOSED (owner decides, #815):** license the own code under **PolyForm Noncommercial 1.0.0**
(default proposal) or **Business Source License 1.1** (with a change date to an open licence),
keeping GPL/AGPL/MPL/Apache/MIT exactly where section 2 says they apply. If the shared libraries
that ship inside GPL APKs must remain usable there, they need a licence compatible with that use
— in practice the owner's dual licensing (own licence for own apps; GPL as distributed inside the
GPL APKs). PolyForm/BSL code cannot be *added* to a GPL binary by anyone other than the copyright
holder. Nothing has been relicensed.

### 4.1 Cloud Store's `play-anon` rung and AuroraOSS/gplayapi (decided 2026-10-09)

Cloud Store installs Google Play apps without a Google account the way Aurora Store does
(`libs/appstore` `PlayAnonFetcher.kt`). Its upstream reference is
[AuroraOSS/gplayapi](https://gitlab.com/AuroraOSS/gplayapi), **GPL-3.0** (LICENSE at tag 3.6.3;
files carry `SPDX-License-Identifier: GPL-3.0-or-later`).

**Decision:** gplayapi's Kotlin is **not** vendored. It depends on protobuf-generated classes and
a protobuf runtime, which the offline build cannot fetch, and the five HTTP calls the store needs
are small. They are re-implemented in `PlayAnonFetcher.kt` (own code, HttpURLConnection and a
minimal protobuf reader). What **is** copied, byte for byte at the pinned tag, is one
GPL-3.0-or-later data file — the device profile `gplayapi_sm_s20_plus.properties` — with
gplayapi's LICENSE beside it, in `ab_cloud-libs-shared/libs/gplayapi/` (pin:
`data/gplayapi-pin.json`; declared in `licenses/upstreams.json` as `gplayapi-profile`).

Consequence: every APK that bundles `libs:appstore` (Cloud Store, the SuperApp) ships that GPL
file as an asset. Compliance: the file is unmodified, its licence and upstream are shipped next to
it in this public repository, and the source of the APKs that carry it is this repository. If a
store APK is ever distributed under terms incompatible with GPL-3.0 for the bundle as a whole, the
profile must be replaced with one written from scratch (the keys are device facts, see
`PlayAnonFetcher.userAgent` and the dispenser request).

## 5. Conflicts and gaps, with concrete fixes

| # | Finding | Evidence | Fix |
|---|---|---|---|
| 1 | Own app links GPL-derived code: `libs/contacts/SocialImport.kt` says its parsing rules are "ported verbatim" from Fossify Contacts (GPL-3.0); it is compiled into Cloud Contacts, which has no licence | `ab_cloud-libs-shared/libs/contacts/src/main/java/com/diegonmarcos/superapp/contacts/SocialImport.kt` header; `inventory.json::linkage.ac_cloud-contacts` | RESOLVED #828: rewritten clean-room (spec `a0_docs/eng-specs/social-import.md` -> tests -> fresh implementation); the GPL-derived file is gone, Cloud Contacts links only own code here |
| 2 | No `LICENSE` file in the vendored HeliBoard tree (GPL-3.0-only) | `ab_cloud-libs-shared/libs/keyboard/` has none; README credits HeliBoard | copy HeliBoard's `LICENSE` (GPL-3.0) into `libs/keyboard/` |
| 3 | No `LICENSE`/`NOTICE` in the Apache-2.0 trees `libs/net`, `libs/net-wg`, `libs/cropper`, `libs/gesture`, `libs/panoramaviewer`, `libs/scrollbar` (Apache-2.0 §4 requires a copy of the licence with redistributions) | `git ls-files` shows none | copy wireguard-android's and ReFra's `LICENSE` (and NOTICE if any) into each |
| 4 | No pinned upstream revision for keyboard, firestack, net, net-wg | `licenses/upstreams.json` `_doc_ref` notes | record the detected shas from `provenance.json::detected_base` in the owning `build.json` |
| 5 | No open-source-licences / attribution screen in the SuperApp or the own apps, which ship Apache-2.0 / MIT / BSD / EPL dependencies whose notices must accompany the binary | no `oss-licenses`/AboutLibraries/notice screen found in `aa_cloud-superapp`, `libs/core` | add a generated licences screen (e.g. from `licenses/inventory.json`) in `libs/core` |
| 6 | Proprietary Google SDKs: ML Kit (`libs/ml-l-image-mlkit`, `libs/ml-l-text-mlkit`, and `libs/ml-vision`, which Cloud Camera (MIT) compiles in: Face Detection `com.google.mlkit:face-detection` and Selfie Segmentation `com.google.mlkit:segmentation-selfie`, each with its BUNDLED on-device model inside the AAR, #1533; the Objects page's detection model is the engine's bundled Object Detection + Image Labeling models), Play Services location (`libs/maps`), ARCore (`ac_cloud-calc/app`) | `inventory.json` entries `maven:com.google.mlkit:*`, `maven:com.google.android.gms:play-services-location`, `maven:com.google.ar:core` | fine in own apps; must never be linked into a GPL APK. Today ML Kit ships in Cloud-Keyboard-Libs (a separate APK reached over a binder), not in the GPL keyboard: keep that boundary, and treat it as a reviewed decision since "separate program" is a legal judgement |
| 7 | Cloud Mail (GPL) links `libs/ml-l-image`; that lib must not pull an ML Kit module | `ac_cloud-mail` linkage; `libs/ml-l-image/build.gradle` declares no ML Kit today | keep ML Kit out of `libs/ml-l-image` (the `-mlkit` split already does this) |
| 8 | `libs/shizuku-adb-debug-tools` links `com.github.MuntashirAkon:libadb-android`, whose POM says "Other" | `inventory.json` | the upstream README is reported to dual-license it (GPL-3.0-or-later OR Apache-2.0) — verify, then record the chosen option in `curated.json` |
| 9 | Keyboard dictionaries (34 `.dict` files) have no recorded licence | vendored from codeberg.org/Helium314/aosp-dictionaries (`libs/keyboard/README.md`), whose licences differ per dictionary | record each file's licence in `curated.json::assets` |
| 10 | Unresolved licences (`NOASSERTION`): `com.bitwarden:sdk-android`, `org.fossify:commons`, `org.fossify:IndicatorFastScroll`, `net.zetetic:sqlcipher-android`, `com.gemalto.jp2:jp2-android`, `com.facebook.react:react-native`, a few npm packages | `inventory.json` | look each up once and pin it in `curated.json` (Fossify Commons is GPL-3.0; the Bitwarden SDK has its own licence terms; SQLCipher Community is BSD-style) |
| 11 | AFFiNE Enterprise directories | already guarded by `licence-guard.yml` | none |

### Reference-only upstreams (no code taken)

- **wgcf** (github.com/ViRb3/wgcf, MIT) - #1074: `aa_cloud-superapp/.../network/CloudflareWarp.kt` talks to the
  Cloudflare WARP registration API using the request/response shapes wgcf documents. No wgcf source is vendored,
  copied or translated; the code was written for this repository, so it is the owner's own code and nothing is
  added to `upstreams.json` or `inventory.json`. If wgcf code is ever vendored, add the directory to
  `licenses/upstreams.json` with its MIT licence and keep its copyright notice. The WARP service itself is
  Cloudflare's, under its own terms of service.

## 6. Keeping it true

- New dependency / vendored directory / binary / top-level directory → CI fails. Add the
  directory to `licenses/curated.json::directories` (or an upstream to `licenses/upstreams.json`),
  run `python3 1_cicd/src/scripts/cloud-android-licence-inventory.py refresh .` and commit
  `licenses/inventory.json`.
- A pin moves → `python3 1_cicd/src/scripts/cloud-android-licence-provenance.py . <id>` and commit
  `licenses/provenance*`.

## Hardtech-CAD model pipeline tools (#1542, not vendored)

`ad_Hardtech-CAD/tools/` and `.github/workflows/hardtech-cad-models.yml` use, at pinned versions installed in CI only: OpenSKP 1.5.0 (MIT, reads `.skp`, writes GLB/IFC4; no
SketchUp SDK), its pip dependencies (trimesh MIT, numpy and shapely BSD-3-Clause, mapbox_earcut ISC, defusedxml PSF-2.0; transitive, per upstream metadata) and
`@gltf-transform/core` 4.5.1 (MIT). The SketchUp candidates that need the proprietary SketchUp SDK or the SketchUp application (Sketchup_Importer, skp2gltf GPL-3.0,
export-batch-sketchup-extension) are NOT used and nothing of that SDK is vendored. The derived views of the owner's own models keep the model's licence (all rights
reserved, owner's own). Evidence and per-candidate findings: `ad_Hardtech-CAD/THIRD-PARTY.md`.
