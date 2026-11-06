# Agents and budget
- Spend about 14% of the daily token budget at most, with one agent running at a time. Why: the budget is shared and parallel agents multiplied burn. Guard: none.
- Opus orchestrates; workers are Sonnet with a pinned effort and a restricted tool list (agents/*.md). Why: cheap, predictable workers. Guard: scripts/guard.sh checks the agent frontmatter.
- One agent per repo checkout; agents work on oci-apps, not termux. Why: concurrent edits in one tree collide. Guard: none.
- Brief every agent with the session-survival rules: one build at a time, disk worktrees, push WIP early, no glob deletes, no pkill -f. Why: agents do not inherit this context. Guard: hooks apply to agents too.
- Agents never commit or push unless the task says so; the commit trailer is the one given in the task. Why: the orchestrator owns history. Guard: none.
- Lean agents: build (edit + build), explore (read-only), review (refute claims), general-purpose (research); none lists an MCP server. ops (CI/fleet status) and infra (fleet work) carry the cloudy-c3-mcp (fleet and phones) and mail MCPs. Why: each connected tool costs context. Guard: guard.sh.
- Agents start at ≤20k tokens. Pick the lean type; use `ops`/`infra` only when the task needs the fleet MCPs. Measure with a probe. Why: the 167 cloud-infra tool schemas alone are about 45k tokens per agent. Guard: guard.sh fails if a lean agent lists any mcp__ tool.
