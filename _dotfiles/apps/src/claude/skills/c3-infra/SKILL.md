---
name: c3-infra
description: Owner rules for ANY fleet, Android app, CI, GitHub Actions, backlog/ticket, MCP, Caddy/addressing, secrets or agent-orchestration work in the c3 repos (cloud-*, _git-repos-master). Load before acting.
---
# c3-infra: owner rules

Hard rules first, index last. Each rule has a one-line why in `rules/*.md` (read the file for your topic on demand). Guards are named where one exists: `hooks/` scripts block at tool-call time, `scripts/guard.sh` checks this plugin in CI.

## Hard rules (every session, every agent)
1. Main only. No branches, no PRs; commit and push straight to main. Why: owner order, branches rot. Guard: hooks no-branch-create, main-only-push.
2. Never print or hardcode a secret; secrets files stay encrypted; no credentials as CLI args. Why: logs and transcripts leak. Guard: hook no-secrets-commit.
3. Never quote the owner verbatim in commits, comments, specs or tickets; neutral third-person prose. Why: publication rule. Guard: none.
4. **Decisions are the agent's by default. Never wait on the owner.** An undeployed task costs more than a bad decision: decide, deploy, verify, then report each decision to the owner through the C3 infra bot (cloud-infra-mcp `infra_devops telegram_send`, one short line per decision: ticket, decision, why) and keep working without waiting for any reply. Questions to the owner are not allowed (hook owner-auto-answer answers them). The only things that still stop an action are the permission system's refusals and the hard rules below; report those once through the bot and move on to other work. Why: owner order 2026-11-25. Guard: hook owner-auto-answer.
5. Do only what was asked; explain/how/what-is questions are read-only. Why: scope creep costs trust. Guard: none.
6. Reports are short: result, files touched, verification, blockers. Why: token budget. Guard: none.
7. "New task" is a ticket: write it to the Product Backlog (repo cloud-me-pvt-tasks) first, build, test, push (rules/backlog.md). Why: single task ledger. NEVER use TaskCreate/TaskUpdate for tickets (they truncate the session store and delete tickets); tickets are files, never deleted or blanked. Guard: test-backlog-metadata.sh, hooks ticket-guard, backlog-session-start, backlog-stop-push.
8. Builds run in CI only. No local Gradle, no npm/npx, never start servers; `build.sh build` after source edits. Why: runners are the only trusted builders. Guard: hook no-local-gradle.
9. Android work is not done on green CI: the on-phone check stays pending on every close. Why: CI cannot see the device. Guard: none.
10. One agent at a time, within about 14% of the daily token budget; Opus orchestrates, Sonnet workers. Why: the budget is shared across sessions. Guard: none.
11. Never `pkill -f`/`pgrep -f`; never glob-delete in /tmp or /dev/shm; one Gradle/Android build at a time. Why: it killed the shell (2026-10-15). Guard: hooks no-pkill-pgrep-f, no-glob-rm-tmp.
12. Declarative: edit the source, never a generated copy (DO NOT EDIT headers); dist is committed code. Why: drift. Guard: per-repo byte-equal guards.
13. Agents start at ≤20k tokens. Pick the lean type; use `ops`/`infra` only when the task needs the fleet MCPs. Measure with a probe. Why: fleet MCP schemas cost about 45k tokens per agent. Guard: guard.sh lean-agent check.
13. Addressing: mesh first, public bearer fallback; c3-pvt/c3-pub; mesh names in the mesh namespace, never a public TLD; the vault is never put behind an edge gate the client cannot satisfy. Why: tickets #1100/#1104/#1117/#1123. Guard: fleet-addressing guard in cloudy-u-containers CI.
14. GitHub work uses the `gh` CLI or cloudy-c3-mcp. The GitHub MCP server is denied (`deniedMcpServers`). Why: about 28k tokens per session for tools that duplicate gh. Guard: settings.json deniedMcpServers.
15. No cost-affecting changes (machine types, paid instances) without explicit approval. Why: free-tier fleet. Guard: none.
16. **NEVER SIMPLIFY. 'Organize', 'clean up', 'redesign' or 'reorg' never means removing or reducing anything. Every declaration, group, filter, action, column, status detail, page and data point stays; organization only adds structure (grouping, filters, search, sort, hierarchy, detail views). UIs are professional operations-console grade: dense, rich, complete. Before shipping any reorg, diff the previous version and prove (with a test) that nothing was removed. Status indicators must reflect the real underlying state, never a proxy (e.g. ADB 'up' only from a real adb round-trip with Wireless debugging on). Why: owner order, reorgs silently dropped data. Guard: none.**
   **Definition (owner): REORGANISE = new UI display, NO data field lost; fields are only rearranged. SIMPLIFY happens only when the owner literally says 'simplify'. Any reorg must ship with a field-parity test against the previous version.** Why: owner order, same as rule 16. Guard: none.

## Index (load on demand)
| File | Topic |
|---|---|
| rules/owner.md | workflow, reporting, quality, publication |
| rules/declarative.md | source of truth, generated copies, lifecycle, submodules |
| rules/addressing.md | mesh vs public, c3-pvt/c3-pub, mesh namespace, vault, exposure |
| rules/backlog.md | tickets: schema, build, test, push, closing, phone check |
| rules/ci.md | CI-only builds, Gradle, GHA tools, emulator tests |
| rules/session-survival.md | dead shell recovery, disk, /tmp, one shared checkout per repo |
| rules/agents.md | orchestration, token budget, lean agents, tools |
| rules/security.md | secrets, gitleaks, geo-fence, MCP auth |
| rules/relay-inbox.md | c3-bot Telegram relay inbox, lease, reply routing, session tag |

## Delivery
Source of truth: `0_claude-sot/` in the repo `_git-repos-master` (GitHub `diegonmarcos/_git-repos-master`, local clone `/home/user/git-repos-master`), which holds this plugin (`0_claude-sot/cloud-marketplace/c3-infra`), `agents/`, `settings.project.json`, `permissions-fleet.json` and the dotfiles engine (`0_claude-sot/engine/deploy-dotfiles.sh`). Owner order 2026-11-25: everything flows from there to every child repo. cloudy-u-linux `da_my-ai/data/claude` is a generated child mirror (README header "GENERATED ... DO NOT EDIT"), kept so local sessions can still load the plugin from its `cloud-marketplace` path. The former plugin repo `c3-infra-plugin` is archived (`z-archived_c3-infra-plugin`) and is not a source. Local sessions use the plugin via `cloud-marketplace` + `enabledPlugins`. Claude Code cloud sessions do NOT install plugins (docs: cloud-environments, "What carries over"), so `sh 0_claude-sot/deploy-all.sh` runs the engine on this repo and on every repo in `mirror-repos.txt` with a local clone (`--check` exits 1 on drift): it refreshes `_dotfiles/apps/src/claude`, renders `_dotfiles/apps/dist/dotfiles/claude`, and copies this skill to `<repo>/.claude/skills/c3-infra`, the hooks to `<repo>/.claude/hooks/c3`, the agents to `<repo>/.claude/agents`, and `settings.project.json` (hooks wired) to `<repo>/.claude/settings.json`. Edit `0_claude-sot` in `_git-repos-master`, never a mirror, never cloudy-u-linux `da_my-ai/data/claude`. Verify with `0_claude-sot/test-claude-sot.sh`.
