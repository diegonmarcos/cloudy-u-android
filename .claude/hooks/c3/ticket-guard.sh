#!/bin/bash
# PreToolUse guard (#1585). Blocks (exit 2):
#  - Write/Edit/MultiEdit that would empty or corrupt a ticket JSON (tasks/<session>/<id>.json): empty, invalid JSON, or missing id/subject/status
#  - Bash that deletes, truncates or moves-away a ticket JSON (rm, unlink, shred, truncate, git rm, find -delete, `> f`, cp /dev/null f)
#  - TaskCreate/TaskUpdate (they truncate the session store and delete tickets; tickets are files in cloud-me-pvt-tasks)
node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
const fs=require("fs");let j;try{j=JSON.parse(s)}catch(e){process.exit(0)}
const tool=j.tool_name||"",ti=j.tool_input||{};
const T=/(^|[\s"'"'"'=:(])(\.{0,2}\/)?([^\s"'"'"';|&<>]*\/)?tasks\/[^\/\s"'"'"']+\/[0-9]+\.json\b/;
const block=m=>{process.stderr.write("BLOCKED by c3-infra hook ticket-guard: "+m+"\n");process.exit(2)};
if(/^Task(Create|Update)$/.test(tool))block(tool+" is banned for backlog tickets (it truncates the session store and deletes tickets). Edit the ticket file tasks/<session-id>/<id>.json in cloud-me-pvt-tasks instead (skill c3-infra rules/backlog.md).");
const isT=p=>/(^|\/)tasks\/[^\/]+\/[0-9]+\.json$/.test(p||"");
const valid=c=>{try{const t=JSON.parse(c);return !!(t&&t.id&&t.subject&&t.status)}catch(e){return false}};
if(tool==="Write"&&isT(ti.file_path)){if(!valid(ti.content||""))block("Write would blank or corrupt ticket "+ti.file_path+" (needs valid JSON with id, subject, status).")}
if((tool==="Edit"||tool==="MultiEdit")&&isT(ti.file_path)){
  let c;try{c=fs.readFileSync(ti.file_path,"utf8")}catch(e){process.exit(0)}
  const eds=tool==="Edit"?[ti]:(ti.edits||[]);
  for(const e of eds){if(typeof e.old_string!=="string")continue;c=e.replace_all?c.split(e.old_string).join(e.new_string):c.replace(e.old_string,()=>e.new_string)}
  if(!valid(c))block("Edit would leave ticket "+ti.file_path+" empty or invalid JSON (needs id, subject, status).")}
if(tool==="Bash"&&typeof ti.command==="string"){
  const cmd=ti.command;if(!/tasks/.test(cmd))process.exit(0);
  const F="(\\S*tasks\\/[^\\/\\s]+\\/(?:[0-9]+\\.json|\\*[^\\s]*)|\\S*tasks\\/\\*\\S*)";
  const seg=cmd.split(/&&|\|\||;|\n/);
  for(const g of seg){
    if(!/tasks/.test(g))continue;
    const hasT=new RegExp(F).test(g);
    const gt=g.trim();
    const bad=(hasT&&(/(^|\s)(rm|unlink|shred|truncate)\s/.test(g)||/git\s+(-C\s+\S+\s+)?rm\s/.test(g)))||/find\s+\S*tasks\b.*(-delete|-exec\s+rm)/.test(g)
      ||new RegExp("^(:|true|echo(\\s+-n)?(\\s+\"\")?|printf\\s+\"\")?\\s*>\\s*"+F).test(gt)
      ||new RegExp("cp\\s+/dev/null\\s+"+F).test(g);
    const mv=/(^|\s)mv\s/.test(g)&&(g.match(/tasks\/[^\/\s]+\/[0-9]+\.json/g)||[]).length<2&&new RegExp(F).test(g)&&/(^|\s)mv\s+\S*tasks\/[^\/\s]+\/[0-9]+\.json\s/.test(g);
    if(bad||mv)block("command would delete, blank or move away a ticket JSON (tasks/*/<id>.json): "+g.trim().slice(0,160)+". Tickets are never deleted; set status instead.")}
}
process.exit(0)})'
