# Security
- Never print secrets: no `sops -d` to output, no credentials as CLI args, no tokens in URLs. Why: transcripts and logs persist. Guard: hook no-secrets-commit (commits), gitleaks in CI.
- Never hardcode secrets; secrets files are always encrypted (sops). Why: several repos are public. Guard: gitleaks, hook no-secrets-commit.
- Public requests are geo-fenced to Europe and the USA at Cloudflare/Caddy; auth surfaces included (ticket #1102). Why: cuts hostile traffic. Guard: edge config tests.
- Fleet MCPs reach the fleet mesh-first, else public with a bearer (FLEET_BEARER / AUTHELIA_BEARER_TOKEN from the environment's own secrets), never OAuth. Why: owner decision 31. Guard: .claude/mcp-connect.mjs.
- The GitHub MCP is denied; use gh or cloudy-c3-mcp. Why: token cost and a second credential path. Guard: settings.json deniedMcpServers.
- Rotate any credential that touched a third-party host (for example the vault master password after the .app leak). Why: exposure is permanent. Guard: none.
