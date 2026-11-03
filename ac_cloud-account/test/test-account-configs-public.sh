#!/usr/bin/env bash
# test-account-configs-public: the Configs section of Cloud Account is PUBLIC (#1081), statically.
#
# Holds that:
#  1. build.json::ui.sections[configs] declares exactly the allowed leaves (#1438: cloud-apps, phone-apps, runbook,
#     desktop, infra, containers): the pages that hold or show secrets (providers, keys, grants, import, bundle, signin,
#     settings, connections) are not in it;
#  2. MainActivity's "configs" branch mounts only the allowed composables, and names no secret-bearing one;
#  3. the #1438 pages (configs/ConfigsPages.kt) never touch AccountVault / a reveal / the clipboard / a secret store,
#     a container row is drawn only from C3Decl's names (containerLines), and PublicConfig (libs:account, still used by
#     the SuperApp) drops secret-looking keys AND values.
# Then each mutation is planted in a scratch copy and the check must go RED. The runtime half is SectionsTest.kt.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"
# #1439 the Personal Info / Configs router is libs:account-ui's AccountUiPage (Cloud Account's MainActivity hands both sections to it).
MA="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/superapp/accountui/AccountUi.kt"
SP="ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/configs/ConfigsPages.kt"
SK="ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SectionKit.kt"

check() {
python3 - "$1" "$A" "$MA" "$SP" "$SK" <<'PY'
import json, os, re, sys
R, A, MA, SP, SK = sys.argv[1:6]
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//[^\n]*|\s//\s[^\n]*", "", s)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

ui = json.loads(rd(f"{A}/build.json"))["ui"]
def leaves(pages):
    for p in pages:
        if p.get("pages"): yield from leaves(p["pages"])
        else: yield p["id"]
cfg = next(s for s in ui["sections"] if s["id"] == "configs")
allowed = {"cloud-apps", "phone-apps", "runbook", "desktop", "infra", "containers"}
have = set(leaves(cfg["pages"]))
ok(have == allowed, "Configs declares exactly the allowed pages", f"Configs leaves are {sorted(have)}, allowed {sorted(allowed)}")

ma = code(rd(MA))
host = code(rd(A + "/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"))
hb = re.search(r'^\s*"configs" -> (.*)$', host, re.M)
ok(hb is not None and hb.group(1).strip() == "AccountUiPage(section, page, go)", "MainActivity hands Configs to libs:account-ui's router only",
   f"MainActivity's configs branch mounts {hb.group(1).strip() if hb else 'nothing'}")
block = ma[ma.index('AccountUi.CONFIGS -> when (page) {'):ma.index('object AccountUiTags')]
calls = set(re.findall(r"\b([A-Z]\w*)\(", block))
okcalls = {"CloudAppsPage", "PhoneAppsPage", "RunbookTogglesPage", "DesktopTreePage", "InfraPage", "ContainersPage"}
ok(calls <= okcalls, "the configs branch mounts only allowed composables", f"the configs branch mounts {sorted(calls - okcalls)}")
bad = [w for w in ("Secret", "Reveal", "AccountVault", "Password", "Providers", "Grants", "Import", "ConnectionsTab", "SettingsPage") if w in block]
ok(not bad, "the configs branch names no secret-bearing composable", f"the configs branch names {bad}")

sp = code(rd(SP))
leak = [w for w in ("AccountVault", "connection(", "PasswordVisualTransformation", "RevealGate", "SecretClipboard", "HubAndroid", "SecretsTab", "TokensClient", "Log.") if w in sp]
ok(not leak, "the Configs pages touch no secret store", f"the Configs pages touch {leak}")
body = sp[sp.index("fun ContainersPage("):]
ok("containerLines(s)" in body and not any(w in body for w in ("envNames.join", "envNames.map", "fields", "ComposeDecl.parse")), "a container row draws only containerLines (names)", "a container row draws something besides containerLines")
cl = sp[sp.index("fun containerLines("):sp.index("fun ContainersPage(")]
ok("s.envNames" in cl and "s.secretNames" in cl and "s.envFiles" in cl and "s.volumes" in cl and "value" not in cl.lower(), "containerLines: env / secret NAMES, env files, volumes", "containerLines draws a value or lost a class")
sk = code(rd(SK))
ok("SECRET_KEY.containsMatchIn(key)" in sk and "SECRET_VALUE.containsMatchIn(value)" in sk and "if (isSecretKey(k)) return@mapNotNull null" in sk and "looksSecret(s)" in sk,
   "PublicConfig drops secret-looking keys and values", "PublicConfig no longer filters keys and values")
ok(all(w in sk for w in ("pass", "token", "key", "private", "secret", "ghp_", "-----BEGIN")), "PublicConfig's patterns cover passwords, tokens, keys, PEM and token prefixes", "PublicConfig's patterns lost a class")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$A"; cp "$ROOT/$A/build.json" "$TMP/r/$A/"
  for f in "$MA" "$SP" "$SK" "$A/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"; do mkdir -p "$TMP/r/$(dirname "$f")"; cp "$ROOT/$f" "$TMP/r/$f"; done
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
mutate "Cloud Account mounts a secret page in its own Configs branch" "$A/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt" '"configs" -> AccountUiPage(section, page, go)' '"configs" -> SecretsTab(openVault = {})' || M=1
mutate "the secret list mounted in Configs" "$MA" '"desktop" -> DesktopTreePage()' '"desktop" -> DesktopTreePage()
                "keys" -> SecretsTab(openVault = {})' || M=1
mutate "Settings mounted in Configs" "$MA" '"infra" -> InfraPage()' '"infra" -> InfraPage()
                "settings" -> AccountSettingsPage("")' || M=1
mutate "a secrets page declared under Configs" "$A/build.json" '"id": "containers",' '"id": "providers",' || M=1
mutate "Containers reads the vault" "$SP" 'ConfigsLoad.containers(ConfigsSources.baked)' 'AccountVault(ctx).connections()' || M=1
mutate "a container row draws the raw compose" "$SP" 'listOf("▸ ${s.name}") + containerLines(s) })' 'listOf("▸ ${s.name}") + s.envNames.map { it + "=" + s.fields } })' || M=1
mutate "containerLines drops the secret names" "$SP" '    s.secretNames.takeIf' '    s.image.takeIf' || M=1
mutate "PublicConfig stops filtering keys" "$SK" 'if (isSecretKey(k)) return@mapNotNull null' '' || M=1
mutate "PublicConfig stops filtering values" "$SK" 'if (s.isEmpty() || looksSecret(s)) null else k to s' 'if (s.isEmpty()) null else k to s' || M=1
mutate "the key pattern loses passwords" "$SK" '(pass|secret|token|key|' '(secret|token|key|' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
