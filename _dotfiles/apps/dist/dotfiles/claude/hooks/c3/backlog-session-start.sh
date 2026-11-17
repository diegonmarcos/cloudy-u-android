#!/bin/bash
# SessionStart: inject the OPEN-ticket index (id, status, subject) of the Product Backlog (cloud-me-pvt-tasks) into the
# session context so no session misses the backlog (#1585). Best-effort fast-forward pull first. Never blocks (always exit 0).
. "$(dirname "$0")/_backlog.sh"
R="$(backlog_repo)"
if [ -z "$R" ]; then
  MSG='PRODUCT BACKLOG NOT CHECKED OUT: repo cloud-me-pvt-tasks (1___BACKLOG___/1.1.Product-Backlog/tasks/<session>/<id>.json) is the single task ledger. Attach/clone it before any ticket work. NEVER use TaskCreate/TaskUpdate for tickets (they truncate the store and delete tickets).'
else
  timeout 8 git -C "$R" pull -q --ff-only >/dev/null 2>&1
  MSG="$(node -e '
const fs=require("fs"),p=require("path");const d=process.argv[1]+"/1___BACKLOG___/1.1.Product-Backlog/tasks";const open=[];let total=0;
for(const s of fs.readdirSync(d)){const sd=p.join(d,s);let st;try{st=fs.statSync(sd)}catch(e){continue}if(!st.isDirectory())continue;
 for(const f of fs.readdirSync(sd)){if(!/\.json$/.test(f))continue;total++;try{const t=JSON.parse(fs.readFileSync(p.join(sd,f),"utf8"));
  if(!/^(completed|done)$/.test(t.status||""))open.push([parseInt(t.id,10)||0,t.id,t.status,(t.subject||"").slice(0,140)])}catch(e){open.push([0,f,"UNREADABLE-JSON",sd+"/"+f])}}}
open.sort((a,b)=>a[0]-b[0]);
let o="PRODUCT BACKLOG (repo cloud-me-pvt-tasks at "+process.argv[1]+"): "+open.length+" open of "+total+" tickets. Open index (id | status | subject):\n";
for(const t of open)o+=t[1]+" | "+t[2]+" | "+t[3]+"\n";
o+="RULES: tickets are files tasks/<session-id>/<id>.json; read/edit the file, then node build.js + test-backlog-metadata.sh, commit, push main. NEVER use TaskCreate/TaskUpdate for backlog tickets (they truncate the session store and delete tickets). Never delete or blank a ticket JSON (guard hook ticket-guard). Never close without DONE evidence. Full detail: skill c3-infra rules/backlog.md.";
process.stdout.write(o)' "$R" 2>/dev/null)"
fi
node -e 'process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:process.argv[1]}}))' "$MSG"
exit 0
