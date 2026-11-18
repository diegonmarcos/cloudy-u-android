#!/bin/bash
# SessionStart (#1795): the c3-bot Telegram relay inbox for every Claude session, local and cloud.
#   relay-inbox.sh            SessionStart entry (reads the hook JSON on stdin), prints additionalContext or nothing
#   relay-inbox.sh check      same, run by hand or from a loop later in the session (no stdin needed)
#   relay-inbox.sh sent <id>  log a Telegram message_id this session sent (reply routing, rules/relay-inbox.md)
#   relay-inbox.sh tag        print this session's short tag, e.g. c3:01NDsN
# Mesh host (relay health answers): claim/renew the lease, poll after the cursor, route, inject new owner messages.
# Cloud container (CLAUDE_CODE_REMOTE=true, relay not reachable): inject the instruction to arm ONE self re-arming
# 10-minute send_later check through cloud-infra-mcp. Anything else (off-mesh laptop, relay down, no token): silent.
# Contract: always exit 0, finishes in under 5 s (hard deadline below), prints nothing when nothing is new, never prints
# the token (it reaches curl on stdin via -K -, never as an argument).
# Env: C3BOT_RELAY_TOKEN (bearer), C3BOT_RELAY_URL (default http://10.0.0.6:3119), C3BOT_RELAY_DIR (default ~/.claude),
#      C3BOT_SESSION_TAG (override the tag), C3BOT_LEASE_TTL (s, default 900), C3BOT_RELAY=0 disables the hook.
set -u
[ "${C3BOT_RELAY:-1}" = 0 ] && exit 0
if [ -z "${_C3_RELAY_INNER:-}" ]; then
  IN=""; [ "${1:-}" = "" ] && [ ! -t 0 ] && IN="$(timeout 1 cat 2>/dev/null)"
  _C3_RELAY_INNER=1 _C3_RELAY_IN="$IN" timeout 4.5 bash "$0" "$@" </dev/null 2>/dev/null
  exit 0
fi

URL="${C3BOT_RELAY_URL:-http://10.0.0.6:3119}"; URL="${URL%/}"
DIR="${C3BOT_RELAY_DIR:-${CLAUDE_CONFIG_DIR:-$HOME/.claude}}"
CUR="$DIR/c3bot-relay-cursor"; SENT="$DIR/c3bot-relay-sent.log"; PEND="$DIR/c3bot-relay-pending.jsonl"
TTL="${C3BOT_LEASE_TTL:-900}"; case "$TTL" in ""|*[!0-9]*) TTL=900;; esac
mkdir -p "$DIR" 2>/dev/null

sid="$(printf '%s' "${_C3_RELAY_IN:-}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.stdout.write(String(JSON.parse(s).session_id||""))}catch(e){}})' 2>/dev/null)"
[ -z "$sid" ] && sid="${CLAUDE_SESSION_ID:-${CLAUDE_CODE_SESSION_ID:-}}"
if [ -n "${C3BOT_SESSION_TAG:-}" ]; then TAG="$C3BOT_SESSION_TAG"
elif [ -n "$sid" ]; then s="${sid#session_}"; TAG="c3:${s:0:6}"
else TAG="c3:$(hostname 2>/dev/null | cut -c1-6)"; fi
TAG="$(printf '%s' "$TAG" | tr -c 'A-Za-z0-9_.:-' '_' | cut -c1-128)"

case "${1:-}" in
  tag) printf '%s\n' "$TAG"; exit 0;;
  sent) case "${2:-}" in ""|*[!0-9]*) exit 0;; esac; printf '%s %s\n' "$2" "$TAG" >> "$SENT"; exit 0;;
esac

# curl with the bearer on stdin (never argv, never printed)
rq() { local m="$1" p="$2" b="${3:-}" t="${4:-2}"
  if [ -n "$b" ]; then printf 'header = "Authorization: Bearer %s"\n' "$C3BOT_RELAY_TOKEN" | curl -sf -m "$t" -K - -X "$m" -H 'Content-Type: application/json' --data "$b" "$URL$p"
  else printf 'header = "Authorization: Bearer %s"\n' "$C3BOT_RELAY_TOKEN" | curl -sf -m "$t" -K - "$URL$p"; fi; }

cloud_msg() {
cat <<EOF
C3BOT RELAY INBOX (cloud session, tag [$TAG]): the relay is not reachable from this container, so check it through cloud-infra-mcp. Arm exactly ONE send_later check now (delay_minutes 10, name "c3bot relay inbox $TAG"), and when it fires: (1) read the relay with devops docker_exec on vm oci-apps, container c3bot-relay, command: sh -c 'wget -qO- --header "Authorization: Bearer \$C3BOT_RELAY_TOKEN" "http://127.0.0.1:3119/c3bot-relay/poll?after=<cursor>&wait=0"' (the container's own token env; never print it; keep the last seen id as <cursor> in this conversation, start at 0); (2) for a non-reply message, claim the lease the same way: wget -qO- --header ... --header "Content-Type: application/json" --post-data '{"session":"$TAG","ttl":$TTL}' http://127.0.0.1:3119/c3bot-relay/claim, and answer only if the holder is $TAG; for a reply, answer only if reply_to_message.message_id is one you logged or its text carries [$TAG]; (3) answer and acknowledge with devops telegram_send, prefixing every send with [$TAG] and logging each returned message_id; (4) arm the next send_later 10 minutes out (one pending check at a time, never two). Rules: skill c3-infra rules/relay-inbox.md.
EOF
}

out() { node -e 'process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:process.argv[1]}}))' "$1"; }

if ! curl -sf -m 3 -o /dev/null "$URL/c3bot-relay/health" 2>/dev/null; then
  [ "${CLAUDE_CODE_REMOTE:-}" = true ] && out "$(cloud_msg)"
  exit 0
fi
[ -n "${C3BOT_RELAY_TOKEN:-}" ] || exit 0

cursor="$(cat "$CUR" 2>/dev/null)"; case "$cursor" in ""|*[!0-9]*) cursor=0;; esac
claim="$(rq POST /c3bot-relay/claim "{\"session\":\"$TAG\",\"ttl\":$TTL}" 0.7)"
poll="$(rq GET "/c3bot-relay/poll?after=$cursor&wait=0" "" 1.2)" || poll=""
[ -z "$poll" ] && [ ! -s "$PEND" ] && exit 0

R="$(C3_POLL="$poll" C3_CLAIM="$claim" node -e '
const fs=require("fs");const [tag,cur,sentF,pendF]=process.argv.slice(1);
const J=s=>{try{return JSON.parse(s)}catch(e){return null}};
const p=J(process.env.C3_POLL||"")||{};const c=J(process.env.C3_CLAIM||"")||{};
const holder=String(c.holder||c.session||(c.lease&&c.lease.session)||"");
// relay shape: {ok, granted, holder, renewed?, expiresAt}; fallbacks kept
const iHold=typeof c.granted==="boolean"?(c.granted&&(!holder||holder===tag)):(holder?holder===tag:(c.ok===true||c.won===true));
// relay shape: {ok, head, cursor, events:[{seq,type,...}]}; only type telegram.message is an owner message
let all=Array.isArray(p)?p:(p.events||p.messages||p.items||p.updates||p.result||[]);
const isMsg=e=>!e.type||e.type==="telegram.message";
let pend=[];try{pend=fs.readFileSync(pendF,"utf8").split("\n").filter(Boolean).map(J).filter(Boolean)}catch(e){}
const mine=new Set();try{for(const l of fs.readFileSync(sentF,"utf8").split("\n")){const [id,t]=l.split(" ");if(t===tag)mine.add(id)}}catch(e){}
const others=new Set();try{for(const l of fs.readFileSync(sentF,"utf8").split("\n")){const [id,t]=l.split(" ");if(id&&t&&t!==tag)others.add(id)}}catch(e){}
const idOf=m=>Number(m.cursor??m.seq??m.update_id??m.id??(m.message&&m.message.message_id)??0)||0;
let max=Number(cur)||0;const take=[],keep=[];
for(const e of all)max=Math.max(max,idOf(e));const list=all.filter(isMsg);
// telegram.sent {text, message_ids}: a send tagged [c3:x] gives the ids of what session x sent (auto sent log)
const newSent=[];for(const e of all){if(e.type!=="telegram.sent")continue;const tm=String(e.text||"").match(/^\s*\[(c3:[^\]\s]+)\]/);if(!tm)continue;
 for(const id of (e.message_ids||[])){const k=String(id);if(tm[1]===tag){if(!mine.has(k)){mine.add(k);newSent.push(k+" "+tag)}}else others.add(k)}}
for(const raw of pend.concat(list)){
 const m=raw.message||raw;// relay telegram.message: the reply target is in the raw update (message or edited_message)
const up=raw.update||m.update||{};const um=up.message||up.edited_message||{};
let rt=um.reply_to_message||m.reply_to_message||raw.reply_to_message||m.reply_to||raw.reply_to||null;
 if(rt!==null&&typeof rt!=="object")rt={message_id:rt};let to="lease";
 if(rt){const rid=String(rt.message_id||"");const rtx=String(rt.text||"");
  const tm=rtx.match(/\[(c3:[^\]\s]+)\]/);
  if(mine.has(rid)||(tm&&tm[1]===tag))to="me";else if(others.has(rid)||tm)to="other";else to="lease"}
 // others on this host share the cursor: what is not ours stays in the pending file for its owner (capped at 50)
 if(to==="me"||(to==="lease"&&iHold))take.push(m);else keep.push(raw)}
keep.splice(0,Math.max(0,keep.length-50));
const nc=String(Math.max(Number(p.cursor??p.next??p.last_id??0)||0,max));
const txt=take.map(m=>{const uq=((m.update||{}).message||(m.update||{}).edited_message||{}).reply_to_message;const q=uq||m.reply_to_message||m.reply_to;const r=q?" (reply to "+(typeof q==="object"?q.message_id:q)+")":"";
 return "- id "+(m.message_id||m.id||"?")+r+": "+String(m.text||m.caption||"").slice(0,2000)}).join("\n");
if(newSent.length)try{fs.appendFileSync(sentF,newSent.join("\n")+"\n")}catch(e){}
process.stdout.write(JSON.stringify({cursor:nc,keep:keep,txt:txt,holder:iHold}))' "$TAG" "$cursor" "$SENT" "$PEND" 2>/dev/null)"
[ -z "$R" ] && exit 0
nc="$(printf '%s' "$R" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);process.stdout.write(r.cursor)})')"
case "$nc" in ""|*[!0-9]*) ;; *) printf '%s\n' "$nc" > "$CUR";; esac
printf '%s' "$R" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);process.stdout.write(r.keep.map(k=>JSON.stringify(k)+"\n").join(""))})' > "$PEND.new" && mv "$PEND.new" "$PEND"
[ -s "$PEND" ] || rm -f "$PEND"
txt="$(printf '%s' "$R" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).txt))')"
[ -z "$txt" ] && exit 0
out "C3BOT RELAY INBOX (tag [$TAG]): new owner message(s) routed to this session:
$txt
Answer through the c3-bot (devops telegram_send), prefix every send with [$TAG], and log each sent message_id with: \"\$CLAUDE_PROJECT_DIR/.claude/hooks/c3/relay-inbox.sh\" sent <message_id>. Run \"\$CLAUDE_PROJECT_DIR/.claude/hooks/c3/relay-inbox.sh\" check to read again. Rules: skill c3-infra rules/relay-inbox.md."
exit 0
