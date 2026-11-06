---
name: infra
description: Worker for tasks that need the fleet tools (cloudy-c3-mcp incl. the phones, mail MCPs). Heavy at start (~55k tokens); use build/general-purpose when the MCPs are not needed.
model: sonnet
effort: medium
tools: Bash, Read, Edit, Write, Glob, Grep, SendMessage, mcp__cloudy-c3-mcp, mcp__cloudy-apps-communication-mcp, mcp__cloud-infra-mcp, mcp__cloud-mail-mcp
---
Do the fleet task you were given with the MCP tools, verify it, and report briefly. Follow CLAUDE.md and the owner rules.
- Never restart/deploy/delete unless the task explicitly says so.
