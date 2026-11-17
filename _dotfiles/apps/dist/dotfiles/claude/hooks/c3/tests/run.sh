#!/bin/bash
# Planted cases per hook: BLOCK lines must exit 2, PASS lines must exit 0.
H="$(cd "$(dirname "$0")/.." && pwd)"; fail=0; n=0
t(){ # hook expect command
  local out rc; out="$(node -e 'process.stdout.write(JSON.stringify({tool_input:{command:process.argv[1]}}))' "$3" | "$H/$1.sh" 2>&1)"; rc=$?
  n=$((n+1)); [ "$rc" = "$2" ] || { echo "FAIL $1 expected $2 got $rc: $3"; fail=$((fail+1)); }
  if [ "$2" = 2 ] && ! printf '%s' "$out" | grep -q "BLOCKED by c3-infra hook $1"; then echo "FAIL $1 no message: $3"; fail=$((fail+1)); fi; }
# pkill / pgrep -f
t no-pkill-pgrep-f 2 'pkill -f GradleDaemon'; t no-pkill-pgrep-f 2 'x && pgrep -fl gradle'; t no-pkill-pgrep-f 2 'pkill -9 -f foo'
t no-pkill-pgrep-f 0 'pgrep GradleDaemon'; t no-pkill-pgrep-f 0 'kill 1234'; t no-pkill-pgrep-f 0 'ps aux | grep foo'
# glob rm
t no-glob-rm-tmp 2 'rm -rf /tmp/*'; t no-glob-rm-tmp 2 'rm -rf /tmp/tmp.*'; t no-glob-rm-tmp 2 'rm -f /dev/shm/a*'
t no-glob-rm-tmp 0 'rm -rf /tmp/claude-x/build'; t no-glob-rm-tmp 0 'rm -rf /home/user/wt/plg/pl'; t no-glob-rm-tmp 0 'rm foo*.txt'
# gradle
t no-local-gradle 2 './gradlew assembleDebug'; t no-local-gradle 2 'cd app && gradle test'; t no-local-gradle 2 'sudo gradle build'
t no-local-gradle 0 'gh workflow run ship.yml'; t no-local-gradle 0 'ls gradle/'; t no-local-gradle 0 'cat gradlew.bat'
# push
t main-only-push 2 'git push origin feature/x'; t main-only-push 2 'git push -u origin HEAD:dev'; t main-only-push 2 'git push --all'
t main-only-push 0 'git push origin main'; t main-only-push 0 'git -C . push origin main'; t main-only-push 0 'git push origin HEAD:main'; t main-only-push 0 'git push origin --delete old'
t main-only-push 0 'git push origin main 2>&1 | tail -1'; t main-only-push 0 'git status'
# branches
t no-branch-create 2 'git checkout -b feat'; t no-branch-create 2 'git switch -c feat'; t no-branch-create 2 'git branch feat'; t no-branch-create 2 'git worktree add -b x ../x'
t no-branch-create 0 'git checkout main'; t no-branch-create 0 'git branch -D old'; t no-branch-create 0 'git branch --show-current'; t no-branch-create 0 'git branch -r'
# secrets
T="$(mktemp -d)"; git -C "$T" init -q; echo "aws=AK""IA$(printf %s ABCDEFGHIJKLMNOP)" > "$T/a.txt"; git -C "$T" add a.txt
t no-secrets-commit 2 "git -C $T commit -m wip"
t no-secrets-commit 2 'git commit -m "token=ghp_abcdefghijklmnopqrstuvwxyz0123456789"'
git -C "$T" rm -qf --cached a.txt; echo hello > "$T/b.txt"; git -C "$T" add b.txt
t no-secrets-commit 0 "git -C $T commit -m wip"; t no-secrets-commit 0 'git commit -m "fix the thing"'; t no-secrets-commit 0 'git log'
# #1291 dated task folder names are allowed, a real-looking sk- key is still blocked (regex path, no gitleaks)
git -C "$T" rm -qf --cached b.txt; echo "a/TASK-20260323-04_morse-code-visual-ui-long-name/b TASK-net-20260508-01_collapse-public-to-443-xx task-2026-02-15-hybrid-mcp-architecture-long" > "$T/c.txt"; git -C "$T" add c.txt
t no-secrets-commit 0 "git -C $T commit -m wip"
git -C "$T" rm -qf --cached c.txt; echo "key=s""k-$(printf %s abcdefghijklmnopqrstuvwxyz0123456789AB)" > "$T/d.txt"; git -C "$T" add d.txt
t no-secrets-commit 2 "git -C $T commit -m wip"
rm -rf "$T"
# mass delete
W="$(mktemp -d)"; git -C "$W" init -q -b main; git -C "$W" config user.email t@t; git -C "$W" config user.name t
for i in $(seq 1 20); do echo $i > "$W/f$i.txt"; done; git -C "$W" add -A
t no-mass-delete 0 "git -C $W commit -m initial"
git -C "$W" commit -qm initial
git -C "$W" mv f1.txt moved1.txt; git -C "$W" mv f2.txt moved2.txt
t no-mass-delete 0 "git -C $W commit -m moves"
git -C "$W" commit -qm moves
git -C "$W" rm -q --cached -r . >/dev/null
t no-mass-delete 2 "git -C $W commit -m wipe"
t no-mass-delete 0 "git -C $W commit -m 'wipe [mass-delete-ok]'"
git -C "$W" commit -qm wipe; git -C "$W" commit -q --allow-empty -m "x"
t no-mass-delete 2 "git -C $W push origin main"
git -C "$W" reset -q --soft HEAD~1; git -C "$W" commit -q --amend -m 'wipe [mass-delete-ok]'
t no-mass-delete 0 "git -C $W push origin main"
t no-mass-delete 0 'git status'
rm -rf "$W"
# ticket guard (#1585): tool-level cases via raw JSON
g(){ # expect json
  local out rc; out="$(printf '%s' "$2" | "$H/ticket-guard.sh" 2>&1)"; rc=$?; n=$((n+1))
  [ "$rc" = "$1" ] || { echo "FAIL ticket-guard expected $1 got $rc: $2"; fail=$((fail+1)); }
  if [ "$1" = 2 ] && ! printf '%s' "$out" | grep -q "BLOCKED by c3-infra hook ticket-guard"; then echo "FAIL ticket-guard no message: $2"; fail=$((fail+1)); fi; }
gj(){ node -e 'process.stdout.write(JSON.stringify({tool_name:process.argv[1],tool_input:JSON.parse(process.argv[2])}))' "$1" "$2"; }
TG="$(mktemp -d)"; mkdir -p "$TG/tasks/s1"; TK="$TG/tasks/s1/42.json"
echo '{"id":"42","subject":"#42 x","status":"pending"}' > "$TK"
g 2 "$(gj Write "{\"file_path\":\"$TK\",\"content\":\"\"}")"
g 2 "$(gj Write "{\"file_path\":\"$TK\",\"content\":\"{broken\"}")"
g 2 "$(gj Write "{\"file_path\":\"$TK\",\"content\":\"{}\"}")"
g 0 "$(gj Write "{\"file_path\":\"$TK\",\"content\":\"{\\\"id\\\":\\\"42\\\",\\\"subject\\\":\\\"#42 y\\\",\\\"status\\\":\\\"completed\\\"}\"}")"
g 0 "$(gj Write '{"file_path":"/tmp/other/notes.json","content":""}')"
g 2 "$(gj Edit "{\"file_path\":\"$TK\",\"old_string\":\"{\\\"id\\\"\",\"new_string\":\"\"}")"
g 0 "$(gj Edit "{\"file_path\":\"$TK\",\"old_string\":\"pending\",\"new_string\":\"in_progress\"}")"
g 2 "$(gj TaskCreate '{"subject":"x"}')"; g 2 "$(gj TaskUpdate '{"taskId":"1","status":"completed"}')"
g 2 "$(gj Bash '{"command":"rm tasks/s1/42.json"}')"; g 2 "$(gj Bash '{"command":"cd x && rm -f 1___BACKLOG___/1.1.Product-Backlog/tasks/abc/1585.json"}')"
g 2 "$(gj Bash '{"command":"git rm tasks/s1/42.json"}')"; g 2 "$(gj Bash '{"command":"rm -rf tasks/s1/*"}')"
g 2 "$(gj Bash '{"command":"truncate -s 0 tasks/s1/42.json"}')"; g 2 "$(gj Bash '{"command":": > tasks/s1/42.json"}')"
g 2 "$(gj Bash '{"command":"> tasks/s1/42.json"}')"; g 2 "$(gj Bash '{"command":"cp /dev/null tasks/s1/42.json"}')"
g 2 "$(gj Bash '{"command":"find tasks -name 42.json -delete"}')"; g 2 "$(gj Bash '{"command":"mv tasks/s1/42.json /tmp/"}')"
g 0 "$(gj Bash '{"command":"cat tasks/s1/42.json"}')"; g 0 "$(gj Bash '{"command":"ls tasks/s1 | wc -l"}')"
g 0 "$(gj Bash '{"command":"rm -rf build/tmp"}')"; g 0 "$(gj Bash '{"command":"mv tasks/s1/42.json tasks/s2/42.json"}')"
g 0 "$(gj Bash '{"command":"node build.js && git add tasks/s1/42.json"}')"
rm -rf "$TG"
# session-start (#1585): open index injected, completed omitted; no checkout -> pointer message
BR="$(mktemp -d)"; BT="$BR/1___BACKLOG___/1.1.Product-Backlog/tasks/s1"; mkdir -p "$BT"
echo '{"id":"7","subject":"#7 open one","status":"pending"}' > "$BT/7.json"; echo '{"id":"8","subject":"#8 done one","status":"completed"}' > "$BT/8.json"
o="$(cd /tmp && C3_BACKLOG_REPO="$BR" "$H/backlog-session-start.sh")"; n=$((n+1))
printf '%s' "$o" | grep -q '7 | pending | #7 open one' && printf '%s' "$o" | grep -q 'additionalContext' && ! printf '%s' "$o" | grep -q 'done one' || { echo "FAIL backlog-session-start index: $o"; fail=$((fail+1)); }
o="$(cd /tmp && HOME=/nonexistent CLAUDE_PROJECT_DIR=/nonexistent C3_BACKLOG_REPO=/nonexistent "$H/backlog-session-start.sh")"; n=$((n+1))
printf '%s' "$o" | grep -q 'NOT CHECKED OUT' || { [ -d /home/user/cloud-me-pvt-tasks ] && true; } || { echo "FAIL backlog-session-start missing: $o"; fail=$((fail+1)); }
T="$(mktemp -d)"; mkdir -p "$T/home/my-tasks/1___BACKLOG___/1.1.Product-Backlog/tasks"; n=$((n+1))
o="$(cd /tmp && HOME="$T/home" CLAUDE_PROJECT_DIR=/nonexistent C3_BACKLOG_REPO= C3_BACKLOG_REPO_NAMES="nope my-tasks" sh -c '. "$0/_backlog.sh"; backlog_repo' "$H")"
[ "$o" = "$T/home/my-tasks" ] || { echo "FAIL backlog_repo by C3_BACKLOG_REPO_NAMES: $o"; fail=$((fail+1)); }; rm -rf "$T"
# stop-push (#1585): commits+pushes edits to a bare remote; refuses a blanked ticket
RM="$(mktemp -d)"; git init -q --bare -b main "$RM/o.git"; git -C "$BR" init -q -b main; git -C "$BR" config user.email t@t; git -C "$BR" config user.name t
git -C "$BR" remote add origin "$RM/o.git"; git -C "$BR" add -A; git -C "$BR" commit -qm init; git -C "$BR" push -q origin main; git -C "$BR" fetch -q
echo '{"id":"9","subject":"#9 new","status":"pending"}' > "$BT/9.json"; (cd /tmp && C3_BACKLOG_REPO="$BR" "$H/backlog-stop-push.sh"); n=$((n+1))
[ "$(git -C "$RM/o.git" ls-tree -r main --name-only | grep -c '9.json')" = 1 ] || { echo "FAIL backlog-stop-push did not push new ticket"; fail=$((fail+1)); }
: > "$BT/9.json"; rm "$BT/7.json"; (cd /tmp && C3_BACKLOG_REPO="$BR" "$H/backlog-stop-push.sh" 2>/dev/null); n=$((n+1))
{ git -C "$RM/o.git" show main:1___BACKLOG___/1.1.Product-Backlog/tasks/s1/7.json >/dev/null 2>&1 && [ -n "$(git -C "$RM/o.git" show main:1___BACKLOG___/1.1.Product-Backlog/tasks/s1/9.json)" ]; } || { echo "FAIL backlog-stop-push committed a delete/blank"; fail=$((fail+1)); }
rm -rf "$BR" "$RM"
echo "hook tests: $n cases, $fail failed"; [ "$fail" = 0 ]
