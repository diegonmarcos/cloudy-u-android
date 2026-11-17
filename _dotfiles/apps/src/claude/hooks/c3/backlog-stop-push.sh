#!/bin/bash
# Stop: auto-commit and push the Product Backlog repo (cloud-me-pvt-tasks) so ticket edits are never left uncommitted/lost (#1585).
# Only touches 1___BACKLOG___/. Refuses to commit when a ticket JSON was deleted or blanked (prints a warning instead).
# Never blocks the session (always exit 0). Pushes main only.
. "$(dirname "$0")/_backlog.sh"
R="$(backlog_repo)" || exit 0
cd "$R" || exit 0
[ "$(git branch --show-current)" = main ] || { echo "backlog-stop-push: $R is not on main, not pushing" >&2; exit 0; }
B=1___BACKLOG___
if [ -n "$(git status --porcelain -- "$B")" ]; then
  git add -A -- "$B" >/dev/null 2>&1
  bad=""
  for f in $(git diff --cached --name-only --diff-filter=D -- "$B" | grep -E 'tasks/[^/]+/[0-9]+\.json$'); do bad="$bad $f"; done
  for f in $(git diff --cached --name-only --diff-filter=M -- "$B" | grep -E 'tasks/[^/]+/[0-9]+\.json$'); do
    node -e 'try{const t=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));if(!t||!t.id||!t.subject||!t.status)process.exit(1)}catch(e){process.exit(1)}' "$f" || bad="$bad $f"
  done
  if [ -n "$bad" ]; then
    git reset -q -- "$B" >/dev/null 2>&1
    echo "backlog-stop-push: NOT committing, ticket(s) deleted or blanked:$bad. Restore with: git -C $R checkout HEAD --$bad" >&2
    exit 0
  fi
  git -c user.name="${GIT_AUTHOR_NAME:-claude-backlog}" -c user.email="${GIT_AUTHOR_EMAIL:-noreply@anthropic.com}" commit -q -m "backlog: auto-save ticket edits at session stop" -m "Co-Authored-By: Claude <noreply@anthropic.com>" >/dev/null 2>&1
fi
if [ -n "$(git log origin/main..main --oneline 2>/dev/null)" ]; then
  timeout 40 git pull -q --rebase origin main >/dev/null 2>&1
  timeout 40 git push -q origin main >/dev/null 2>&1 || echo "backlog-stop-push: push failed, commits kept locally in $R" >&2
fi
exit 0
