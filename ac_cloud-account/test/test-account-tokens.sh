#!/usr/bin/env bash
# test-account-tokens: Data > Secrets > Tokens (fleet token manager), statically.
#
# Holds that:
#  1. the page is a Data page (build.json ui, never Configs) and MainActivity mounts it from the "data" branch;
#  2. a token value is fetched only after device auth: every client.reveal / client.generate in TokensPage sits inside
#     RevealGate.revealOnIo's read lambda, and revealOnIo calls authenticate BEFORE read;
#  3. the client never logs, prints or stores a value (no Log / println / SharedPreferences / AccountVault write);
#  4. the engine is declared as a fleet endpoint (mesh tokens-api.mesh.diegonmarcos.com:4184, public c3-pvt.diegonmarcos.com/tokens-api) with its
#     named cleartext entry.
# Then each mutation is planted in a scratch copy and the check must go RED. The runtime half is TokensTest.kt.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"
H="$A/app/src/main/java/com/diegonmarcos/cloudaccount"
LK="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount"   # #1439 RevealGate / LocalDeviceAuth live in libs:account-ui
FILES="$A/build.json $H/MainActivity.kt $H/hub/TokensPage.kt $H/hub/TokensClient.kt $LK/hub/Reveal.kt $A/app/src/main/res/xml/network_security_config.xml"

check() {
python3 - "$1" "$A" "$H" <<'PY'
import json, os, re, sys
R, A, H = sys.argv[1:4]
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//[^\n]*|\s//\s[^\n]*", "", s)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

bj = json.loads(rd(f"{A}/build.json"))
def ids(pages):
    for p in pages:
        yield p["id"]
        yield from ids(p.get("pages", []))
sec = {s["id"]: set(ids(s["pages"])) for s in bj["ui"]["sections"]}
ok("tokens" in sec["data"] and "tokens" not in sec["configs"], "Tokens is a Data page, not a Configs one", "Tokens is missing from Data or present in Configs")
main = code(rd(f"{H}/MainActivity.kt"))
data_branch = main.split('"data" -> when (page) {')[1].split('"connect" ->')[0] if '"data" -> when (page) {' in main else ""
ok('"tokens" -> TokensPage()' in data_branch, "MainActivity mounts TokensPage in the data branch", "TokensPage is not mounted in the data branch")

page = code(rd(f"{H}/hub/TokensPage.kt")); client = code(rd(f"{H}/hub/TokensClient.kt")); reveal = code(rd("ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/hub/Reveal.kt"))
# every call that returns a value sits in a `read = { ... }` lambda handed to revealOnIo
calls = [m.start() for m in re.finditer(r"client\.(reveal|generate)\(", page)]
def inside_read(pos):
    start = page.rfind("revealOnIo(", 0, pos)
    if start < 0: return False
    seg = page[start:pos]
    return "read = {" in seg and "show =" not in seg
ok(len(calls) >= 3 and all(inside_read(p) for p in calls), "every value-returning call is inside revealOnIo's read", "a value is fetched outside revealOnIo's read")
body = reveal.split("fun revealOnIo(")[1] if "fun revealOnIo(" in reveal else ""
ok("auth.authenticate" in body and "read()" in body and body.index("auth.authenticate") < body.index("read()") and "if (!ok)" in body,
   "revealOnIo authenticates before it reads", "revealOnIo can read before device auth")
ok(not re.search(r"\b(Log\.|println\(|Timber|printStackTrace)", page + client), "the token pages never log or print", "a token page logs or prints")
ok(not re.search(r"SharedPreferences|AccountVault\([^)]*\)\.(put|set|save)|putString", page), "the page keeps no copy of a value", "the page stores a value")
ok('"Authorization" to "Bearer' in client, "the call carries the vault bearer as Authorization", "the call no longer sends the bearer")

svc = {s["id"]: s for s in bj["fleet_endpoints"]["services"]}.get("tokens-api", {})
ok(svc.get("mesh_dns") == "tokens-api.mesh.diegonmarcos.com" and svc.get("mesh_port") == 4184 and svc.get("public_domain") == "c3-pvt.diegonmarcos.com" and svc.get("public_path") == "/tokens-api",
   "tokens-api is a declared fleet endpoint", "tokens-api is not (correctly) declared in fleet_endpoints")
ok('<domain includeSubdomains="false">tokens-api.mesh.diegonmarcos.com</domain>' in rd(f"{A}/app/src/main/res/xml/network_security_config.xml"), "tokens-api.mesh.diegonmarcos.com has its named cleartext entry", "tokens-api.mesh.diegonmarcos.com has no cleartext entry")
sys.exit(1 if fails else 0)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"
  for f in $FILES; do mkdir -p "$TMP/r/$(dirname "$f")"; cp "$ROOT/$f" "$TMP/r/$f"; done
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding="utf-8").read()
if o not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(o, n, 1))
PY
  if check "$TMP/r" >/dev/null; then echo "  MUTATION SURVIVED: $1"; return 1; fi
  echo "  mutation caught: $1"
}
M=0
mutate "Tokens page unmounted" "$H/MainActivity.kt" '"tokens" -> TokensPage()' '"tokens" -> AccountPlaceholderPage(section, page, "x")' || M=1
mutate "a reveal outside the auth gate" "$H/hub/TokensPage.kt" 'read = { (client.reveal(t.id) as? TokensResult.Ok)?.value },
                show = { shown[t.id] = it' 'read = { null }, show = { shown[t.id] = (client.reveal(t.id) as? TokensResult.Ok)?.value ?: ""; ""; it' || M=1
mutate "revealOnIo reads before auth" "$LK/hub/Reveal.kt" 'auth.authenticate(reason) { ok ->
            if (!ok) { denied(); return@authenticate }
            scope.launch {' 'val early = read()
        auth.authenticate(reason) { ok ->
            if (!ok) { denied(); return@authenticate }
            scope.launch {' || M=1
mutate "the client logs" "$H/hub/TokensClient.kt" 'private fun enc(id: String)' 'private fun dbg(s: String) = android.util.Log.d("t", s)
    private fun enc(id: String)' || M=1
mutate "the bearer is dropped" "$H/hub/TokensClient.kt" '"Authorization" to "Bearer $token"' '"Accept" to "Bearer $token"' || M=1
mutate "Tokens declared under Configs" "$A/build.json" '"id": "tokens",' '"id": "tokens-x",' || M=1
mutate "tokens-api loses its cleartext entry" "$A/app/src/main/res/xml/network_security_config.xml" '<domain includeSubdomains="false">tokens-api.mesh.diegonmarcos.com</domain>' '' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
