#!/bin/bash
# Owner order 2026-11-25: questions to the owner are answered automatically.
# PreToolUse on AskUserQuestion: deny the question and hand back the owner's
# standing answer as the reason, so the agent decides and proceeds.
# It answers QUESTIONS only; it grants no permission (the permission system
# still decides on destructive actions, and those still need an explicit yes).
cat >/dev/null
ANSWER="You should make the best decision to keep the system healthy, safe so you can deploy and the user can navigate the system"
node -e 'process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:"Owner standing answer: "+process.argv[1]}}))' "$ANSWER"
exit 0
