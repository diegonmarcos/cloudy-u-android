#!/usr/bin/env python3
# Repeatable secret-pattern scan for the fleet-data exposure report (#1639). Masks every match: prints file:line, pattern, length, context with long tokens redacted.
# Usage: python3 -I a0_docs/eng-specs/fleet-data-exposure-scan.py <repo> [ref=origin/main]
import re,subprocess,sys,collections
repo=sys.argv[1]
P={'gh_pat':r'ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}','aws':r'AKIA[0-9A-Z]{16}','gapi':r'AIza[0-9A-Za-z_-]{35}','slack':r'xox[bpa]-[0-9A-Za-z-]{20,}','sk':r'sk-[A-Za-z0-9]{32,}','pem':r'-----BEGIN [A-Z ]*PRIVATE KEY-----','age':r'AGE-SECRET-KEY-1[0-9A-Z]{20,}','jwt':r'eyJhbGciOi[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}'}
files=subprocess.run(['git','-C',repo,'grep','-lIE','|'.join(P.values()),(sys.argv[2] if len(sys.argv)>2 else 'origin/main'),'--',':!**/node_modules/**'],capture_output=True,text=True).stdout.split()
for f in files:
    path=f.split(':',1)[1]
    txt=subprocess.run(['git','-C',repo,'show',f],capture_output=True,text=True).stdout
    for name,rx in P.items():
        for m in re.finditer(rx,txt):
            line=txt[:m.start()].count('\n')+1
            ctx=txt[max(0,m.start()-40):m.start()].replace('\n',' ')[-40:]
            v=m.group(0)
            # pem: check next line has real base64 body
            extra=''
            if name=='pem':
                after=txt[m.end():m.end()+200].strip().split('\n')[0] if m.end()<len(txt) else ''
                extra=' body=%s'%('b64-%d'%len(after) if re.fullmatch(r'[A-Za-z0-9+/=]{40,}',after) else 'none')
            print(f'{path}:{line} {name} len={len(v)}{extra} ctx=[{re.sub(r"[A-Za-z0-9+/]{24,}","<redacted>",ctx)}]')
