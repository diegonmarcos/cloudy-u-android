# GitSync — vendored tree record

What this directory is, where it came from, and the licence verdict on it.
Read this before changing anything under this tree.

## What was cloned

Upstream: https://github.com/ViscousPot/GitSync.git

Pinned revision: `0f4902fceab3b1572ffea65e332f14605237f32e`
("bump version number", upstream default branch `main`, cloned 2026-09-17).

The tree is a CLONE, not a fork and not a submodule — the fleet rule set by
#253 (firestack) and #80 (keyboard): a vendored owned tree with our own naming,
edited directly, tracking no upstream branch. The upstream `.git` history is not
carried; this directory is an ordinary part of the cloud-u-android monorepo, so
the monorepo's own history records every change we make to it.

`fastlane/` (43 MB of store-listing marketing metadata) and the root
`flutter_01.png` screenshot are not vendored: they describe the Play Store
listing, are not part of any build or embeddable surface, and upstream's own
`LICENSE.md` (retained, next section) still covers the tree as shipped.
Everything else — `lib/`, `android/`, `ios/`, `rust/`, `rust_builder/`,
`assets/`, `fonts/`, tests, `pubspec.*`, the Dart/Flutter and flutter_rust_bridge
config — is here verbatim.

## Licence verdict — GPL-3.0

`LICENSE.md` at this tree's root is the GNU General Public License, version 3
(GPL-3.0). The whole upstream tree is uniformly GPL-3.0 (a single licence named
by the GitHub API metadata and by the repo's own licence file, with no
per-directory carve-outs).

GPL-3.0 grants permission to copy, modify and redistribute the source and
derived works, provided the result stays under GPL-3.0 and carries the licence
and its notices. Vendoring the tree here is a copy made under those terms;
modifications — including any surface we build on top of it — must be released
under GPL-3.0 with the same attribution. `LICENSE.md`, `README.md` and the
upstream notices therefore stay untouched in this tree by design, the same way
every other vendored-owned-code tree in this fleet keeps its upstream licence
and notices with the code (see e.g. ac_cloud-notes/VENDORING-LICENCE.md).

Distribution consequence for anything that links this tree into an APK: the
APK must be distributable under GPL-3.0-compatible terms. This is recorded in
the cloud-drive commit that first shipped this vendored tree; re-check it
before embedding the tree into a release pipeline, exactly as the licence guard
(1_cicd/src/data/licence-boundaries.json) re-checks the restricted upstreams it
names. GPL-3.0 is not an Enterprise-grade restriction, so no entry is needed
there; the verdict is recorded here instead.

## What this lib is

GitSync is a mobile Git client for syncing a repository between a remote and a
local directory. It is a Dart/Flutter application (Flutter 3.35.2, Dart SDK
3.9.0 per `pubspec.yaml`) with a Rust core bridged through
flutter_rust_bridge (`rust/` + `rust_builder/`). Its whole user interface lives
under `lib/` and is built with Flutter widgets, NOT Compose and not WebView —
the answer to "can cloud-drive embed this UI in-process?" is "only through the
Flutter Android add-to-app embedding", not through a Kotlin/Compose view.

## Why it cannot block any release (#254)

This directory is intentionally NOT a Gradle module. It has no `build.gradle`
at its root, so:

- the shared-libs build (`ab_cloud-libs-shared/lib-apks/settings.gradle`)
  treats it as data, not as a module — it compiles into no APK;
- cloud-drive's release path (`ac_cloud-drive`) does not reference it, so a
  break anywhere under this tree can never fail cloud-drive's APK build or
  publish. That is the #253/#254 rule: a lib the app does not maintain must
  never have veto power over the app's release.

When the embedding work lands (Flutter add-to-app engine integrated behind the
Sync ▸ Git tab), it must land the same way firestack did: the engine is built
and published on its OWN path, and the app pins the artifact. The tree itself
stays out of the app's module graph.

## Changes

All modifications to this tree — renames, package identity moves, UI work done
by the fleet — are recorded in the cloud-u-android monorepo history under this
directory. Keep `VENDORED.md`, `LICENSE.md` and the upstream notices in step
with the tree whenever it changes.
- #1833 (2026-11-20): the `GitSyncAccessibilityService` declaration was removed from
  `android/app/src/main/AndroidManifest.xml`; no fleet library registers an accessibility
  service. The class is kept so `AccessibilityServiceHelper` still compiles.
