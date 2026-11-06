# CI and builds
- Builds run only in GHA/CI or declared runners, never on termux, VMs or locally; no local `gradle`/`gradlew`. Why: wrong arch, disk and time limits. Guard: hook no-local-gradle.
- After source edits run `build.sh build` when present; never start servers. Why: servers outlive the session. Guard: none.
- Drive CI with cloudy-c3-mcp (old name cloud-infra-mcp, alias for one release) devops_workflows_gha_* (trigger, run, run_logs) or the gh CLI; read the workflow definition before dispatching inputs. Why: undeclared inputs are rejected or silently skipped. Guard: none.
- Never weaken or skip a test to get green; an emulator test that SKIPs without an emulator needs a CI job that runs it. Why: a skipped test proves nothing. Guard: CI jobs.
- Never run `nix eval` on VMs. Why: memory spikes kill them. Guard: none.
- Android: one Gradle/Android build at a time; check `df -h /` first. Why: disk allowance is 2-3 GB. Guard: none.
- Cost budget (owner rule, 2026-11-04): agents default to a green Ship plus unit tests as proof. Emulator / screenshot walks, before/after shot pairs, mutation-test runs, manual Ship re-dispatches and long CI-polling loops are NOT part of the default workflow; use them only to debug a failure or when the owner asks. Why: each costs 15-90 min of runner time or a full re-read of a large agent context per poll. Guard: agent briefs.
- One clone per repo (owner rule): the container holds ONE shared shallow clone per repo at /home/user/<repo>; agents never clone, they `git worktree add` from it, push to main and `worktree remove` when done. Why: private clones filled the disk. Guard: agent briefs.
