# Hardtech-BioMed

An app of the Lnktree family (#1335), behind the SuperApp's Cloud > Lnktree row. Package `com.diegonmarcos.hardtechbiomed`, folder `ad_Hardtech-BioMed` (the `ad_` prefix of the
Lnktree family, declared in `1_cicd/src/data/app-families.json`; the Cloud family is `ac_cloud-<x>`).

It is the former simulation app rebranded (#1777): a NEW app with a new application id rather than a rename in place, because the fleet's application id, the Store
listing, the SuperApp tile, the GHCR image, the release asset and the debug port are all keyed by the app's name; the old id is retired (`RETIRED.json`, the Store's
retired-apps list) and the SAP2000, MATLAB and Ansys pages live on in Hardtech-CAD under Sim2Real.

* Seven tabs on the shared bottom nav: **Physiology | BioChem | BioMech | Anatomy | Genetic | Neuro | Immuno**. Each is one `scene` entry (`build.json::ui.stacks`): a 3D
  model in the viewer of `libs:cad-core` (three.js; `libs:cad-mesh` reads the GLB, `libs:cgc-3d` supplies the three.js loaders) with the tab's topics as chips. A topic lights
  its parts on the model (the rest dims) and frames them; a tap on the model names the part under the finger; layers show or hide a body system.
* The models and the texts are served at RUN TIME from `data/fleet/biomed` (public repo path, `ui.model_repo` + `ui.data_dir`, address built by `app/build.gradle`), each
  verified by its sha256 and cached on the phone; nothing of them is in the APK (the runtime-data guard). `content.json` holds the topics and the part names each lights.
  `body.glb` (Anatomy, Physiology, BioChem, BioMech, Immuno) and `brain.glb` (Neuro) are derived from BodyParts3D (CC BY-SA 2.1 Japan, shared under the same licence);
  `dna.glb` (Genetic) is generated (CC0). Every file is in `data/fleet/biomed/assets.json` and `LICENSE.md`.
* `tools/build_models.py` rebuilds the GLBs from the public BodyParts3D archive (HTTP range requests, only the parts it needs); `tools/build_content.py` writes
  `content.json` and `assets.json` (sha256 of every file). Run them from a scratch directory, then copy the result into `data/fleet/biomed`.
* Testers (`test/`): `test-lnktree-app.sh` (the shared template `1_cicd/src/templates/test-lnktree-app.sh`), `test-biomed-nav.sh` (the seven tabs and the run-time-data wiring),
  `test-no-sim2real.sh` (no old identity outside the retirement records), `test-assets-licensed.sh` (every served asset is registered with an open licence),
  `test-fleet-setup.sh`. JVM: `PagesTest`, `HoldingsTest`, `BioContentTest` (the parser, and the REAL served files: hashes, and every topic lights real parts), `ViewerHealthTest`.
* Ship: `1_cicd/src/cicd/ship-hardtech-biomed.yml` (APK to GHCR and the rolling release, installable from the SuperApp's Constellation). Screens:
  `1_cicd/src/cicd/hardtech-nav-screens.yml` (emulator walk of the seven tabs and of Hardtech-CAD's nav).
* The texts are educational overviews in neutral wording, not medical advice.
