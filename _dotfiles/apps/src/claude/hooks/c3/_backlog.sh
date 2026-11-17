# Shared by the backlog hooks: locate the Product Backlog repo checkout.
# SINGLE SETTING for the backlog location (#1585): C3_BACKLOG_REPO (absolute path) wins; otherwise the repo is found by
# name from C3_BACKLOG_REPO_NAMES (space separated, first match wins). The backlog lives in cloud-me-pvt-tasks (moved from cloudy-a-docs, #1699); to relocate it again
# change ONLY the default names below (or export one of the variables); no other hook hardcodes the repo.
# Prints the repo root, or nothing when no checkout is present.
C3_BACKLOG_REPO_NAMES="${C3_BACKLOG_REPO_NAMES:-cloud-me-pvt-tasks}"
backlog_repo() {
  local c n
  set -- "$C3_BACKLOG_REPO" "${CLAUDE_PROJECT_DIR:-.}"
  for n in $C3_BACKLOG_REPO_NAMES; do set -- "$@" "${CLAUDE_PROJECT_DIR:-.}/../$n" "$HOME/$n" "$HOME/git/$n" "/home/user/$n"; done
  for c in "$@"; do
    [ -n "$c" ] && [ -d "$c/1___BACKLOG___/1.1.Product-Backlog/tasks" ] && { (cd "$c" && pwd); return 0; }
  done
  return 1
}
