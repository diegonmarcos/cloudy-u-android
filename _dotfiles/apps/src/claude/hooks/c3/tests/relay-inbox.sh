#!/bin/bash
# relay-inbox.sh (#1795): mesh path, non-mesh (cloud) path, no token, relay down, lease winner, reply routing,
# token never in argv/output, exit 0 and under 5 s. curl is stubbed on PATH (no server is started).
H="$(cd "$(dirname "$0")/.." && pwd)"; fail=0; n=0
W="$(mktemp -d)"; mkdir -p "$W/bin"
cat > "$W/bin/curl" <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "$FAKE_LOG"; cat > /dev/null
for a in "$@"; do u="$a"; done
case "$u" in
  */c3bot-relay/health) [ "${FAKE_HEALTH:-up}" = up ] && exit 0; [ "$FAKE_HEALTH" = slow ] && sleep 10; exit 7;;
  */c3bot-relay/claim) printf '%s' "${FAKE_CLAIM:-}";;
  */c3bot-relay/poll*) printf '%s' "${FAKE_POLL:-}";;
esac
EOF
chmod +x "$W/bin/curl"
TOKV="tok-$(date +%s%N)-secret"
run() { # dir tag [VAR=value...] ; prints hook stdout (token and cloud flag unset unless given)
  local d="$1" tag="$2"; shift 2
  env -u C3BOT_RELAY_TOKEN -u CLAUDE_CODE_REMOTE PATH="$W/bin:$PATH" FAKE_LOG="$W/argv.log" C3BOT_RELAY_DIR="$d" C3BOT_SESSION_TAG="$tag" C3BOT_RELAY_URL=http://relay.test "$@" \
    bash "$H/relay-inbox.sh" < /dev/null; }
sent() { env C3BOT_RELAY_DIR="$1" C3BOT_SESSION_TAG="$2" bash "$H/relay-inbox.sh" sent "$3"; }
ck() { n=$((n+1)); if eval "$2"; then :; else echo "FAIL relay-inbox: $1"; fail=$((fail+1)); fi; }
P='{"messages":[{"id":11,"message_id":501,"text":"plain one"},{"id":12,"message_id":502,"text":"reply to A","reply_to_message":{"message_id":900,"text":"[c3:AAAAAA] done"}},{"id":13,"message_id":503,"text":"reply to B by log","reply_to_message":{"message_id":777,"text":"status"}},{"id":14,"message_id":504,"text":"reply to B by tag","reply_to_message":{"message_id":778,"text":"[c3:BBBBBB] x"}}],"cursor":14}'

# 1 mesh path: holder A gets the plain message and its own reply; cursor saved; token never on argv or stdout
D="$W/a"; mkdir -p "$D"; : > "$W/argv.log"
o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"holder":"c3:AAAAAA"}' FAKE_POLL="$P")"; rc=$?
ck "mesh exit 0" '[ $rc = 0 ]'
ck "mesh injects additionalContext" 'printf "%s" "$o" | grep -q additionalContext'
ck "mesh holder gets plain message" 'printf "%s" "$o" | grep -q "plain one"'
ck "mesh reply by tag goes to A" 'printf "%s" "$o" | grep -q "reply to A"'
ck "mesh reply tagged for B not shown to A" '! printf "%s" "$o" | grep -q "reply to B by tag"'
ck "cursor saved" '[ "$(cat "$D/c3bot-relay-cursor")" = 14 ]'
ck "poll used after=0&wait=0" 'grep -q "poll?after=0&wait=0" "$W/argv.log"'
ck "token never in argv" '! grep -q "$TOKV" "$W/argv.log"'
ck "token never printed" '! printf "%s" "$o" | grep -q "$TOKV"'
# second run: nothing new -> silent, polls after the saved cursor
: > "$W/argv.log"; o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"holder":"c3:AAAAAA"}' FAKE_POLL='{"messages":[],"cursor":14}')"
ck "nothing new is silent" '[ -z "$o" ]'
ck "poll after saved cursor" 'grep -q "poll?after=14" "$W/argv.log"'

# 2 lease: one winner. B is not the holder -> no plain message; B gets the reply addressed to it through its sent log
D="$W/b"; mkdir -p "$D"
sent "$D" c3:BBBBBB 777 >/dev/null; ck "sent logs the id with the tag" 'grep -qx "777 c3:BBBBBB" "$D/c3bot-relay-sent.log"'
o="$(run "$D" c3:BBBBBB C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"holder":"c3:AAAAAA"}' FAKE_POLL="$P")"
ck "non-holder does not get the plain message" '! printf "%s" "$o" | grep -q "plain one"'
ck "reply routed to B by sent log" 'printf "%s" "$o" | grep -q "reply to B by log"'
ck "reply tagged for B goes to B" 'printf "%s" "$o" | grep -q "reply to B by tag"'
ck "reply for A not shown to B" '! printf "%s" "$o" | grep -q "reply to A"'

# 3 shared host: A and B share one dir/cursor; B runs first, A still gets what was not B's (pending file)
D="$W/shared"; mkdir -p "$D"; sent "$D" c3:BBBBBB 777 >/dev/null
o="$(run "$D" c3:BBBBBB C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"holder":"c3:AAAAAA"}' FAKE_POLL="$P")"
ck "shared host: B takes only its reply" 'printf "%s" "$o" | grep -q "reply to B by log" && ! printf "%s" "$o" | grep -q "plain one"'
o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"holder":"c3:AAAAAA"}' FAKE_POLL='{"messages":[],"cursor":14}')"
ck "shared host: A gets the plain message and its reply from pending" 'printf "%s" "$o" | grep -q "plain one" && printf "%s" "$o" | grep -q "reply to A" && ! printf "%s" "$o" | grep -q "reply to B"'
o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"holder":"c3:AAAAAA"}' FAKE_POLL='{"messages":[],"cursor":14}')"
ck "shared host: delivered once" '[ -z "$o" ]'

ck "shared host: B's tagged reply also to B, not A" '! printf "%s" "$o" | grep -q "reply to B by tag"'
# 3b relay shapes (orchestrator spec): events[] with seq/type, reply_to as an id or object, claim granted:false
D="$W/shape"; mkdir -p "$D"; sent "$D" c3:BBBBBB 777
E='{"ok":true,"head":23,"cursor":23,"events":[{"seq":20,"type":"telegram.message","message_id":601,"text":"ev plain"},{"seq":21,"type":"relay.ack","text":"not an owner msg"},{"seq":22,"type":"telegram.message","message_id":602,"text":"ev reply B","reply_to":777},{"seq":23,"type":"telegram.message","message_id":603,"text":"ev reply A","reply_to":{"message_id":905,"text":"[c3:AAAAAA] hi"}}]}'
o="$(run "$D" c3:BBBBBB C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"ok":true,"granted":false,"holder":"c3:AAAAAA","expiresAt":1}' FAKE_POLL="$E")"
ck "events: B gets reply by numeric reply_to" 'printf "%s" "$o" | grep -q "ev reply B"'
ck "events: granted:false gets no plain message" '! printf "%s" "$o" | grep -q "ev plain"'
ck "events: non-message types are skipped" '! printf "%s" "$o" | grep -q "not an owner msg"'
ck "events: cursor = relay cursor" '[ "$(cat "$D/c3bot-relay-cursor")" = 23 ]'
o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"ok":true,"granted":true,"holder":"c3:AAAAAA","renewed":true,"expiresAt":1}' FAKE_POLL='{"ok":true,"head":23,"cursor":23,"events":[]}')"
ck "events: granted holder A gets plain + its reply from pending" 'printf "%s" "$o" | grep -q "ev plain" && printf "%s" "$o" | grep -q "ev reply A" && ! printf "%s" "$o" | grep -q "ev reply B" && ! printf "%s" "$o" | grep -q "not an owner"'
o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"ok":false}' FAKE_POLL='{"ok":true,"cursor":23,"events":[]}')"
ck "claim failed: nothing delivered" '[ -z "$o" ]'
ck "tag fits relay session regex" 'printf "%s" "$(env C3BOT_RELAY_DIR="$D" C3BOT_SESSION_TAG="c3:bad tag/x" bash "$H/relay-inbox.sh" tag)" | grep -qxE "[A-Za-z0-9_.:-]{1,128}"'
# 3c real relay event: reply target in update.message.reply_to_message; telegram.sent fills the sent log
D="$W/real"; mkdir -p "$D"
X='{"ok":true,"head":33,"cursor":33,"events":[{"seq":30,"type":"telegram.sent","chat_id":1,"text":"[c3:BBBBBB] build green","message_ids":[880]},{"seq":31,"type":"telegram.message","source":"telegram","update_id":9,"chat_id":1,"from":{"id":1,"username":"o","name":"O"},"message_id":701,"text":"real reply B","date":1,"update":{"update_id":9,"message":{"message_id":701,"text":"real reply B","reply_to_message":{"message_id":880}}}},{"seq":32,"type":"telegram.message","message_id":702,"text":"real edit A","update":{"edited_message":{"message_id":702,"reply_to_message":{"message_id":881,"text":"[c3:AAAAAA] hi"}}}},{"seq":33,"type":"telegram.message","message_id":703,"text":"real plain","update":{"message":{"message_id":703}}}]}'
o="$(run "$D" c3:BBBBBB C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"ok":true,"granted":false,"holder":"c3:AAAAAA"}' FAKE_POLL="$X")"
ck "real: telegram.sent tagged B logs 880 for B" 'grep -qx "880 c3:BBBBBB" "$D/c3bot-relay-sent.log"'
ck "real: B gets the reply to 880 via update.message" 'printf "%s" "$o" | grep -q "real reply B" && printf "%s" "$o" | grep -q "reply to 880"'
ck "real: B gets neither the edit for A nor the plain" '! printf "%s" "$o" | grep -q "real edit A" && ! printf "%s" "$o" | grep -q "real plain"'
o="$(run "$D" c3:AAAAAA C3BOT_RELAY_TOKEN="$TOKV" FAKE_CLAIM='{"ok":true,"granted":true,"holder":"c3:AAAAAA"}' FAKE_POLL='{"ok":true,"cursor":33,"events":[]}')"
ck "real: holder A gets its edited reply and the plain, not B's" 'printf "%s" "$o" | grep -q "real edit A" && printf "%s" "$o" | grep -q "real plain" && ! printf "%s" "$o" | grep -q "real reply B"'
# 4 no token on a mesh host: silent, no poll
D="$W/c"; mkdir -p "$D"; : > "$W/argv.log"
o="$(run "$D" c3:CCCCCC FAKE_POLL="$P")"; rc=$?
ck "no token: exit 0 and silent" '[ $rc = 0 ] && [ -z "$o" ]'
ck "no token: no poll" '! grep -q poll "$W/argv.log"'

# 5 relay down off a cloud container: silent; in a cloud container: the send_later instruction
o="$(run "$D" c3:CCCCCC C3BOT_RELAY_TOKEN="$TOKV" FAKE_HEALTH=down)"; rc=$?
ck "relay down local: exit 0 and silent" '[ $rc = 0 ] && [ -z "$o" ]'
o="$(run "$D" c3:CCCCCC FAKE_HEALTH=down CLAUDE_CODE_REMOTE=true)"; rc=$?
ck "cloud path: exit 0" '[ $rc = 0 ]'
ck "cloud path: send_later instruction" 'printf "%s" "$o" | grep -q "send_later" && printf "%s" "$o" | grep -q "docker_exec" && printf "%s" "$o" | grep -q "c3bot-relay" && printf "%s" "$o" | grep -q "telegram_send"'
ck "cloud path: tagged and one pending check" 'printf "%s" "$o" | grep -q "\[c3:CCCCCC\]" && printf "%s" "$o" | grep -q "never two"'
ck "cloud path: valid JSON" 'printf "%s" "$o" | node -e "let s=\"\";process.stdin.on(\"data\",d=>s+=d).on(\"end\",()=>JSON.parse(s))"'

# 6 deadline: a hanging relay still returns in under 5 s with exit 0
s=$(date +%s%N); o="$(run "$D" c3:CCCCCC FAKE_HEALTH=slow C3BOT_RELAY_TOKEN="$TOKV")"; rc=$?; e=$(( ($(date +%s%N)-s)/1000000 ))
ck "hang: exit 0 under 5 s (${e} ms)" '[ $rc = 0 ] && [ $e -lt 5000 ]'
# 7 disabled
o="$(run "$D" c3:CCCCCC C3BOT_RELAY=0 C3BOT_RELAY_TOKEN="$TOKV" FAKE_POLL="$P")"; ck "C3BOT_RELAY=0 is silent" '[ -z "$o" ]'
# 8 session tag from the SessionStart JSON
o="$(echo '{"session_id":"session_01NDsNpzm1"}' | env C3BOT_RELAY_DIR="$W/t" bash -c '_C3_RELAY_INNER=1 _C3_RELAY_IN="$(cat)" bash "$0" tag' "$H/relay-inbox.sh")"
ck "tag from session_id" '[ "$o" = c3:01NDsN ]'
rm -rf "$W"
echo "relay-inbox tests: $n cases, $fail failed"; [ "$fail" = 0 ]
