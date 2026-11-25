# Fleet-data public-exposure report (#1639, #1566)

Audit of the public repo `diegonmarcos/cloudy-u-android` (head `98e2abe5f`, 2026-11-12) for anything public that is not meant to be.
Values are never quoted here; findings reference files only (rule 2). Re-run: `python3 -I a0_docs/eng-specs/fleet-data-exposure-scan.py .`
(it prints file, line, pattern and length, and masks every match).

## Verdict

- **No live credential found.** Every secret-pattern hit is a test fixture, a placeholder, an upstream vendored file, or a Firebase client key (public by design).
- **Personal data (tier 1) is public by design** (owner verdict on #1566): contact card, wallet, socials, profile, health, repos metadata. Not a leak. No removal and no history purge.
- **Real exposure: fleet topology (tier 2).** Private mesh addresses, internal hostnames and endpoint names are readable in about 500 files of the public repo. Severity: medium. It names no credential, but it maps the attack surface. Remediation is the #1566 migration to the private c3-api fleet-data route.

## 1. Secret-pattern hits (all triaged, none live)

| File | Pattern | Classification |
|---|---|---|
| `.claude/hooks/c3/tests/run.sh` (+ 2 `_dotfiles` mirrors) | GitHub PAT shape | Fake token that tests the no-secrets-commit hook |
| `aa_cloud-superapp/app/src/test/.../XrayExportTest.kt` | PAT shape, PEM header | Redaction-test fixtures |
| `aa_cloud-superapp/build.json` `ui.import_schema.ssh.vault_repo_key` | PEM header | Format template: header and footer only, zero key body |
| `ab_cloud-libs-shared/libs/account/.../DriveCredentials.kt` | PEM header | Code that builds a PEM at runtime, no key material |
| `ab_cloud-libs-shared/libs/firewall/firestack/intra/doh/client_auth_test.go` | PEM with body | Upstream (Intra/firestack) test keys, already public upstream |
| `ac_cloud-me/.../VaultwardenMappingTest.kt`, `ac_cloud-me/test/test-no-bundled-secrets.sh` | PEM header | Test fixtures |
| `ac_cloud-notes/.yarn/releases/yarn-4.18.0.cjs` | AWS key shape | False positive inside an embedded base64 wasm blob |
| `ac_cloud-matrix/.../firebase.xml`, `ac_cloud-vault/**/google-services.json` (debug) | Google API key | Upstream (Element, Bitwarden) Firebase client keys |
| `ac_cloud-chat/android/app/google-services.json`, `ac_cloud-chat/fork-patches/0002-*.patch`, `ac_cloud-chat/PUSH_NOTIFICATIONS.md` | Google API key | The fleet's own Firebase client key. Public by design (shipped in every APK) |
| `ab_cloud-libs-shared/libs/gitsync/lib/l10n/*` | PEM header | UI hint strings |

Recommended (low): confirm the `ac_cloud-chat` Firebase key is restricted to the Android package and signing SHA-1 in the GCP console (an owner console action, free). The CI `leak-scan.yml` is green on the current head.

## 2. Baked datasets: classification

| Dataset (file) | Tier | Status |
|---|---|---|
| `ac_cloud-me/data/**` (profile, profiles, health, wallet incl. ids/vcards/pay/events, lnktree, buro, experiences, inventory, llc, tasks, configs, repos) | 1, personal | Public by design: keep (owner verdict). Moving to cloud-me-api serving is #1566 (a), a hygiene change, not a leak fix |
| `ac_cloud-wallet/app/src/main/assets/qrcodes/*` | 1 | Public by design |
| `ab_cloud-libs-shared/libs/repos/*.sot.json`, `ac_cloud-drive/data/repos.sot.json` | 1 | Public by design (rendered from the public registry) |
| `ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json` | 2, fleet-private | **Exposed**: migrate (#1566 b) |
| `ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json` | 2 | **Exposed**: migrate |
| `ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-install-sources.json` | 2 | **Exposed**: migrate |
| `ab_cloud-libs-shared/libs/decisions-engine/src/main/assets/decisions.json` | 2 | **Exposed**: migrate |
| `ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json` | 2 | **Exposed**: migrate |
| `ab_cloud-libs-shared/libs/model-catalogue/src/main/assets/{models/catalogue,agents/agents}.json` | 2 | **Exposed**: migrate |
| `aa_cloud-superapp/app/src/main/assets/bg-policy.json` | 2 | **Exposed**: migrate |
| `ac_c3-morpheus/app/src/main/assets/probes.json` | 2 | **Exposed**: migrate |
| `aa_cloud-superapp/data/{mesh,services_private,cloud_services,device-tuning}.json` | 2 | **Exposed** (mesh addresses, private service names). Add to the #1566 migration set |
| `ac_cloud-c3/data/{containers_declared,declarations,fleet_slice,infra_slice}.json` | 2 | **Exposed** (infra inventory). Add to the #1566 migration set |
| `*/build.json` endpoint blocks (superapp, libs-shared, c3, c3-webserver, c3-morpheus, c3-watchtower, account, browser) | 2 | Endpoint names and mesh addresses in build config. Lowest priority: names only (this audit did not probe each endpoint for auth) |
| `.mcp.json`, `_dotfiles/apps/**/mcp.json` | 2 | MCP endpoint URLs (the c3 MCPs use bearer or OIDC auth). Accepted: low |

Private-topology pattern hits (mesh `10.x` / CGNAT `100.64/10` addresses, `.mesh`, `.internal`, `ts.net` names) by top directory: libs-shared 179, superapp 74, matrix 45 (upstream examples), c3 28, gallery 15, notes 14, c3-webserver 14, account 13, drive 12, 1_cicd 12, others under 10.

## 3. Remediation and status

1. Tier-2 migration (#1566 b): serve every row marked **Exposed** from the private repo `cloud-me-pvt-configs/fleet-data/` through the c3-api route `GET /fleet-data/<tier>/<dataset>` (`cloudy-u-containers/infra-api_cloudy-c3-api/src/code/shared/libs/fleet-data-core.ts`), then drop the baked copies.
   Blocker found: the route reads that repo with a read-only deploy key in `FLEET_DATA_SSH_KEY_B64`. The key is **not provisioned**. It is absent from the c3-api sops file (`cloud-me-pvt-vault: Linux-Containers/infra-api_cloudy-c3-api/src/secrets.yaml`) and from the running `cloudy-c3-api` container env. No existing CI secret holds it.
   Provisioning path (no cost, no owner device action): generate an ed25519 key pair, add the public half as a read-only deploy key on `cloud-me-pvt-configs`, and add the private half (base64) to that sops file with the CI age key (`SOPS_AGE_KEY`, used by `cloudy-t-infra/.github/actions/fetch-vault`). The Ship deploy then renders it into the container `.env`.
2. Git history: no purge (no credential was ever found; tier-1 data is public by design).
3. Firebase key restriction check (low, owner console).

## 4. Status 2026-11-25 (re-measured)

- Public tier 3 and 4 data moved out of the APKs and into `data/fleet` (served by Cloud Store from raw.githubusercontent.com, public by design): the guard (`runtime-data-guard.yml`) fails if `data/fleet/manifest.json` lists a tier 1 or 2 dataset, so personal and fleet-private data cannot land there.
- Still baked and **fleet-private (tier 2), exposed in the public repo**, 13 files, all awaiting the private c3-api route: `bg-policy`, `super-saving`, `ai` (libs:ai), `apps-mcp-api`, `appstore-feeds`, `appstore-install-sources`, `decisions`, `fleet-config`, `perms-catalogue`, `shell-allowlist`, `probes`, SuperIntel `apis` and `cost` (the last two hold labels and endpoint names only). Tier 1 (5 files) stays public by design.
- Probes (anonymous, from this session): `raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/data/fleet/manifest.json` answers 200 (intended); `.../cloud-me-pvt-configs/main/README.md` answers 404 (the private repo is not reachable anonymously).
- Remediation of the 13 files stays blocked on one step: `FLEET_DATA_SSH_KEY_B64` must reach the c3-api sops file; `sops-set.yml` in cloudy-t-infra cannot read `cloud-me-pvt-vault` with the job `GITHUB_TOKEN` (run 43396743680). It needs a vault-read token or a run context with vault access. Moving the files before the route can serve them would break the apps, so they stay until the route is live.

## 5. Status 2026-11-25 later (private route key provisioned)

- The blocker of section 4 is removed. `FLEET_DATA_SSH_KEY_B64` (ed25519, base64) was generated INSIDE a CI job (cloudy-t-infra `sops-gen-sshkey.yml`, run 45639171848), sops-encrypted into the c3-api secrets file of the private vault (`Linux-Containers/infra-api_cloudy-c3-api/src/secrets.yaml`, with its `_credentials` entry) and committed to the vault; no plaintext left the job. Its public half is registered as a read-only deploy key on `cloud-me-pvt-configs` (id 171105001); the previous key (169747971, private half lost) was deleted.
- The route is deployed with `Ship` (service `infra-api_cloudy-c3-api` on oci-apps, not a hub VM), run 45639480103: the container was recreated with `.secrets.d CHANGED FLEET_DATA_SSH_KEY_B64`, healthy, image carries the built source. (The run's overall verdict is red because of an unrelated provenance check on the neighbouring `cloudy-ai-claude-api` deploy.)
- Verified live (from oci-apps, authorised mesh caller): `GET /fleet-data/manifest` 200, `/fleet-data/2/fleet/morpheus-probes` 200, `/fleet-data/2/fleet/shell-allowlist` 200, an unknown dataset 404. Anonymous over the public edge (`api.diegonmarcos.com/c3-infra-api/fleet-data/...`) answers 302 to the login, never data; `raw.githubusercontent.com/.../cloud-me-pvt-configs/...` stays 404.
- Tier-2 files out of the public repo so far: `ac_c3-morpheus/app/src/main/assets/probes.json` (served as `fleet/morpheus-probes`, private copy already in `cloud-me-pvt-configs/fleet-data/2/fleet/`). Still baked (12): the rest of section 4's list. Their private copies already exist in `fleet-data/2/fleet/`; what remains is rewiring each reader to a not-served-yet state, and for `apps-mcp-api` re-aiming the generator (`cloud-android-apps-mcp-api.py`, which writes into the public repo) at the private repo.
- Tier 3 moved out of the APKs this round: `appstore-controls`, `appstore-fleet-actions`, and the SuperIntel pages, mcp and plugins got a cold-start seed (tier-3, public) so a first run is never empty.

## 6. Status 2026-11-25 (tier-2 batch 2: SuperIntel apis and cost)

- Private copies refreshed: all 12 remaining files were re-synced from the public sources into `cloud-me-pvt-configs/fleet-data/2/fleet/` (the older copies had drifted: decisions, fleet-config, perms-catalogue, shell-allowlist, appstore-feeds, appstore-install-sources) and `super-saving`, `superintel-apis`, `superintel-cost` were added, with their manifest entries (private commit e4d2a57).
- Moved out of the public APK: SuperIntel `ai/apis.json` and `ai/cost.json` are the served datasets `fleet/superintel-apis` and `fleet/superintel-cost` (`AiData.asset` reads them through the fleet data client, last-known-good offline; not served yet = a labelled page and no image key, never a crash). Their JVM and shell testers read synthetic sample fixtures under `ac_cloud-superintel/test/fixtures` (the files are declared sample data only: fake accounts, no secret), `test-superintel-private-served.sh` pins the move with 3 mutations, runtime-data `json_max` 80 -> 78.
- Still baked (10): bg-policy, super-saving, ai, apps-mcp-api, appstore-feeds, appstore-install-sources, decisions, fleet-config, perms-catalogue, shell-allowlist. Each is read at BUILD or TEST time by CI (Gradle bakes `ai.json` and `decisions.json`, the perms, decisions and apps-mcp-api guards and generator, about 90 JVM and shell testers assert on the real content, 176 files reference fleet-config.json). Removing the public copy before CI can read the private repo would turn those red, so the next step is a CI read path for `cloud-me-pvt-configs/fleet-data` (a read-only deploy key as Actions secret) plus per-dataset test rewiring.

## 7. Status 2026-11-25 (CI read path, tier-2 batch 3 started)

- CI read path: the vault token (`ANDROID_SIGNING_VAULT_TOKEN`) reads `cloud-me-pvt-configs` (probe run 45651255277). Every workflow that checks out this repository now runs the generated step `Checkout private fleet data` (composite action `.github/actions/fleet-private`: ref main, depth 1, no persisted credentials, sparse `fleet-data`), which materialises the datasets listed in `1_cicd/src/data/fleet-private-paths.json` into the gitignored `fleet-private-data/` before Gradle, the guards or any tester run. `fleet-private-guard` (with mutations) fails on a checkout without the step, a tracked or un-ignored dataset, or a materialiser that does not fail loud. Sparse emulator checkouts (screens only) are exempt: they never read these datasets.
- Moved: `bg-policy` (BgHost reads `fleet/bg-policy` through the fleet data client, last-known-good; not served = nothing restricted and a labelled note). Tests read the materialised copy (`FLEET_PRIVATE_DIR`, default `fleet-private-data`). runtime-data `json_max` 78 -> 77. Still baked (9).
