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
