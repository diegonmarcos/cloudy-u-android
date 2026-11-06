#!/usr/bin/env bash
# Usage beacon (#1451): keeps ONE idle Claude Code CLI alive in tmux so the Cloud SuperApp Notify > AI usage page has a
# fresh weekly sample even where nobody runs an interactive session (cloud containers, servers).
#   ai-usage-beacon.sh start      idempotent: start the tmux CLI + the 5 min tick loop (detached), return at once
#   ai-usage-beacon.sh hook       SessionStart entry: like start, but only in cloud sessions (CLAUDE_CODE_REMOTE=true)
#                                 or when C3_USAGE_BEACON=1; C3_USAGE_BEACON=0 disables it; never blocks, always exit 0
#   ai-usage-beacon.sh stop       stop the loop (by pidfile) and the tmux server (by its own socket)
#   ai-usage-beacon.sh status     print state, last prompt age, last rate_limits render
#   ai-usage-beacon.sh tick       one tick (the loop calls this): prompt only if the data is older than 5 min
#   ai-usage-beacon.sh statusline the statusLine command of the beacon CLI (reads the JSON on stdin, posts via ai-usage-post.sh)
# How the data flows: Claude Code passes rate_limits.seven_day.{used_percentage,resets_at} on the statusLine stdin; the
# beacon's statusLine (refreshInterval 300) forwards them, with a timestamp, to ai-usage-post.sh -> c3-infra-api
# POST /ai/usage/claude.
# TOKEN COST (minimal on purpose): rate_limits only changes after a model response, and no prompt-free read exists
# (checked: `claude -p /usage` prints cost only; there is no CLI subcommand for the plan limits). So each tick, ONLY if the
# last prompt is >= 300 s old, the beacon sends `/clear` (local, free: keeps the context constant) and then one prompt asking
# for a single word, on haiku (cheapest model), thinking off, no tools, empty cwd (no CLAUDE.md): the bare system prompt in
# and 1-2 tokens out, 12 prompts per hour at most. Nothing is sent while the data is fresh.
# Env: C3_BEACON_DIR (state), C3_BEACON_INTERVAL (s, default 300), C3_BEACON_MODEL (default haiku), C3_BEACON_CLAUDE (binary).
set -u
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
HERE="$(dirname "$SELF")"
STATE="${C3_BEACON_DIR:-${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/c3-usage-beacon}"
IVL="${C3_BEACON_INTERVAL:-300}"; case "$IVL" in ""|*[!0-9]*) IVL=300;; esac
MODEL="${C3_BEACON_MODEL:-haiku}"
CLAUDE="${C3_BEACON_CLAUDE:-claude}"
TM() { tmux -L c3-usage-beacon "$@"; }
alive() { [ -f "$STATE/loop.pid" ] && kill -0 "$(cat "$STATE/loop.pid" 2>/dev/null)" 2>/dev/null; }
cli_up() { TM has-session -t beacon 2>/dev/null; }
poster() { for p in "$HERE/ai-usage-post.sh" "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/claude-ai-usage-post.sh"; do [ -f "$p" ] && { echo "$p"; return; }; done; }

statusline() {
    IN="$(cat 2>/dev/null)"
    command -v jq >/dev/null 2>&1 || exit 0
    mkdir -p "$STATE" 2>/dev/null
    vals="$(printf '%s' "$IN" | jq -r '[.rate_limits.five_hour.used_percentage // "-", .rate_limits.five_hour.resets_at // "-",
        .rate_limits.seven_day.used_percentage // "-", .rate_limits.seven_day.resets_at // "-", .session_id // "-"] | map(tostring) | .[]' 2>/dev/null)"
    { read -r p5; read -r r5; read -r p7; read -r r7; read -r sid; } <<EOV
$vals
EOV
    [ "${p5:--}" = "-" ] && { p5=""; r5=""; }; [ "${p7:--}" = "-" ] && { p7=""; r7=""; }; [ "${sid:--}" = "-" ] && sid=""
    if [ -n "$p7" ] || [ -n "$p5" ]; then
        date +%s > "$STATE/last_rl" 2>/dev/null
        P="$(poster)"
        [ -n "$P" ] && AI_USAGE_MIN_INTERVAL="${AI_USAGE_MIN_INTERVAL:-30}" bash "$P" statusline "$p5" "$r5" "$p7" "$r7" "$sid" </dev/null >/dev/null 2>&1
        printf 'beacon 7d %s%%' "${p7:-?}"
    else
        printf 'beacon: no rate_limits yet'
    fi
}

start_cli() {
    mkdir -p "$STATE/cwd" || return 1
    cat > "$STATE/settings.json" <<JSON
{"statusLine":{"type":"command","command":"bash '$SELF' statusline","refreshInterval":$IVL,"padding":0},
 "alwaysThinkingEnabled":false,"model":"$MODEL"}
JSON
    TM kill-server 2>/dev/null
    # a nested CLI must not inherit the parent session's wiring (CLAUDE_CODE_* makes it run as a headless child)
    U="$(env | sed -n 's/^\(CLAUDE_CODE_[A-Za-z0-9_]*\)=.*/-u \1/p' | tr '\n' ' ')-u CLAUDECODE"
    TM new-session -d -s beacon -x 120 -y 30 -c "$STATE/cwd" \
        "env $U C3_BEACON_CHILD=1 $CLAUDE --settings '$STATE/settings.json' --model $MODEL --tools '' --effort low" || return 1
    # trust prompt for the empty cwd ("No, exit" is preselected): Down, then Enter (bounded wait)
    i=0; while [ $i -lt 25 ]; do
        sleep 1; i=$((i+1))
        scr="$(TM capture-pane -p -t beacon 2>/dev/null)"
        case "$scr" in *"trust this folder"*|*"Yes, I trust"*) TM send-keys -t beacon Down; sleep 1; TM send-keys -t beacon Enter; sleep 2;; esac
        case "$scr" in *"Yes, I trust"*) ;; *"shift+tab to cycle"*|*"for shortcuts"*) break;; esac
    done
    rm -f "$STATE/last_prompt"
}

tick() {
    mkdir -p "$STATE"
    cli_up || start_cli || return 0
    now="$(date +%s)"; last=0
    [ -f "$STATE/last_prompt" ] && read -r last < "$STATE/last_prompt"; case "$last" in ""|*[!0-9]*) last=0;; esac
    [ $(( now - last )) -ge "$IVL" ] || return 0      # data younger than one interval: spend nothing
    echo "$now" > "$STATE/last_prompt"
    TM send-keys -t beacon -l '/clear'; sleep 1; TM send-keys -t beacon Enter; sleep 2
    TM send-keys -t beacon -l 'Reply with exactly one word: ok'; sleep 1; TM send-keys -t beacon Enter
}

loop() { echo $$ > "$STATE/loop.pid"; while :; do tick; sleep "$IVL"; done; }

start() {
    [ -n "${C3_BEACON_CHILD:-}" ] && return 0
    command -v tmux >/dev/null 2>&1 && command -v "$CLAUDE" >/dev/null 2>&1 || { echo "beacon: tmux and claude are required" >&2; return 0; }
    mkdir -p "$STATE" && chmod 700 "$STATE"
    alive && { echo "beacon: running (pid $(cat "$STATE/loop.pid"))"; return 0; }
    ( setsid "$SELF" loop </dev/null >>"$STATE/beacon.log" 2>&1 & )
    echo "beacon: started"
}

case "${1:-status}" in
    statusline) statusline ;;
    start) start ;;
    hook) cat >/dev/null 2>&1; [ "${C3_USAGE_BEACON:-}" = 0 ] && exit 0
          if [ "${CLAUDE_CODE_REMOTE:-}" = true ] || [ "${C3_USAGE_BEACON:-}" = 1 ]; then start >/dev/null 2>&1; fi; exit 0 ;;
    loop) loop ;;
    tick) tick ;;
    stop) alive && kill "$(cat "$STATE/loop.pid")" 2>/dev/null; rm -f "$STATE/loop.pid"; TM kill-server 2>/dev/null; echo "beacon: stopped" ;;
    status) alive && echo "loop: running" || echo "loop: down"; cli_up && echo "cli: up" || echo "cli: down"
            now="$(date +%s)"; for f in last_prompt last_rl; do [ -f "$STATE/$f" ] && echo "$f: $(( now - $(cat "$STATE/$f") )) s ago"; done ;;
    *) echo "usage: $0 start|hook|stop|status|tick|statusline" >&2; exit 2 ;;
esac
