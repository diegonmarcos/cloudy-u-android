#!/usr/bin/env bash
# test-setup-schema-coverage: every fleet app with settings has a setup schema Cloud Account can act on.
#
# The "setup schema" of an app is what libs:account SetupPlan reads: fleet-config.json's declared stores
# (used_by the app's module or one of its libs, class config / secret) and the bundle routes into them; the
# app's `items` say where each setting lives (`store` = a declared store the contract writes, `via` = install /
# connect). An item that is config / secret with neither is a setting Fleet Setup cannot place and Profiles > Apps
# cannot show. Holds that:
#  1. every such gap is listed in setup-schema-gaps.json with a reason, every listed gap still exists (no stale
#     entry), and the list never grows past what it holds now (a ratchet: fix the item or name it);
#  2. Cloud Chat (apps.chat) has its schema: the `chat_setup` store (prefs, config, used_by ac_cloud-chat), the
#     bundle routes chat.server_url / chat.server_name into it, its server-link item names the store, the
#     store's handler is the default (prefs), and the fork reads it (CloudFleetSetup native module, registered in
#     MainApplication; the server screen uses it);
#  3. the Account hub builds Cloud Chat's row and Setup from the fleet manifest (HubTest.cloudChatHasASetupSchema).
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FC="fleet-private-data/fleet-config.json"
GAPS="ac_cloud-account/test/setup-schema-gaps.json"
CHAT="ac_cloud-chat"

check() {
python3 - "$1" "$FC" "$GAPS" "$CHAT" <<'PY'
import json, os, re, sys
R, FC, GAPS, CHAT = sys.argv[1:5]
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
d = json.loads(rd(FC)); gaps = {k: v for k, v in json.loads(rd(GAPS)).items() if not k.startswith("_")}
mig = set(d["migrate"]); stores = d["stores"]; apps = d["apps"]; routes = d["bundle"]
found = {}
for aid, a in apps.items():
    for i in a.get("items", []):
        if i.get("class") in mig and not i.get("store") and i.get("via") not in ("install", "connect"):
            found[f"{aid}/{i['id']}"] = i
unlisted = sorted(set(found) - set(gaps)); stale = sorted(set(gaps) - set(found))
ok(not unlisted, f"every setting without a schema is a named gap ({len(found)} listed)", f"settings with no setup schema and no reason: {unlisted}")
ok(not stale, "no stale gap entry", f"gap entries for items that now have a schema or are gone: {stale}")
ok(all(len(v.strip()) >= 30 for v in gaps.values()), "every gap carries a reason", "a gap has no real reason")
bad_store = [f"{aid}/{i['id']}" for aid, a in apps.items() for i in a.get("items", []) if i.get("store") and i["store"] != "<default>" and i["store"] not in stores]
ok(not bad_store, "every item's store is a declared store", f"items naming undeclared stores: {bad_store}")
missing_apps = sorted({k.split("/")[0] for k in found})
print("       apps still missing a schema for some setting: " + (", ".join(missing_apps) or "none"))
ok("chat" not in missing_apps, "Cloud Chat has a schema for every setting", "Cloud Chat still has a setting without a schema")
# 2. Cloud Chat's schema
cs = stores.get("chat_setup") or {}
ok(cs.get("kind") == "prefs" and cs.get("class") == "config" and "ac_cloud-chat" in cs.get("used_by", []), "chat_setup is a prefs/config store used by ac_cloud-chat", f"chat_setup store wrong: {cs}")
ok(routes.get("chat.server_url") == [{"store": "chat_setup", "key": "server_url"}] and routes.get("chat.server_name") == [{"store": "chat_setup", "key": "server_name"}],
   "bundle routes chat.server_url / chat.server_name land in chat_setup", "the chat bundle routes are missing or point elsewhere")
items = {i["id"]: i for i in apps["chat"]["items"]}
ok(items.get("server-link", {}).get("store") == "chat_setup" and items.get("server-link", {}).get("class") == "config", "chat's server-link item names chat_setup", "chat's server-link item is missing or names no store")
ok(items.get("mm-servers", {}).get("via") == "connect", "chat's session tokens come back by signing in (via connect), never pushed", "mm-servers is not marked via connect")
ok("chat_setup" not in rd("1_cicd/src/data/fleet-setup-guard.json"), "chat_setup is served by the default prefs handler (not listed unserved)", "chat_setup is listed as unserved")
mod = rd(f"{CHAT}/android/app/src/main/java/com/mattermost/rnbeta/FleetSetupModule.kt")
ok('const val STORE = "chat_setup"' in mod and 'const val KEY_URL = "server_url"' in mod and 'const val KEY_NAME = "server_name"' in mod and "getSharedPreferences(STORE, Context.MODE_PRIVATE)" in mod,
   "the fork's CloudFleetSetup module reads chat_setup.server_url / server_name", "the fork's native module does not read the declared store/keys")
modcode = re.sub(r"(?m)^\s*//[^\n]*|/\*.*?\*/", "", mod, flags=re.S)
ok(not re.search(r"\.edit\(\)|\.commit\(\)|\.apply\(\)", modcode) and "token" not in modcode.lower(), "the native module is read-only and carries no token", "the native module writes or names a token")
ma = rd(f"{CHAT}/android/app/src/main/java/com/mattermost/rnbeta/MainApplication.kt")
ok("add(FleetSetupPackage())" in ma, "MainApplication registers FleetSetupPackage", "FleetSetupPackage not registered")
srv = rd(f"{CHAT}/app/screens/server/index.tsx")
ok("getFleetSetup()" in srv and "defaultServerUrl || managedConfig?.serverUrl || fleetSetup.serverUrl || LocalConfig.DefaultServerUrl;" in srv
   and "handleConnect(managedConfig?.serverUrl || fleetSetup.serverUrl || LocalConfig.DefaultServerUrl)" in srv and "managedConfig?.serverName || fleetSetup.serverName || LocalConfig.DefaultServerName" in srv,
   "the server screen takes the fleet server after the managed config and before the local default", "the server screen ignores the fleet setup")
ok("fleetSetup.serverUrl, fleetSetup.serverName]" in srv, "the server screen re-runs its connect effect when the fleet setup arrives", "the connect effect does not depend on the fleet setup")
# 3. the hub test exists
ht = rd("ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/HubTest.kt")
ok("fun cloudChatHasASetupSchema" in ht and '"chat"' in ht, "HubTest pins Cloud Chat's row and Setup", "HubTest has no Cloud Chat setup test")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="$FC $GAPS 1_cicd/src/data/fleet-setup-guard.json $CHAT/android/app/src/main/java/com/mattermost/rnbeta/FleetSetupModule.kt $CHAT/android/app/src/main/java/com/mattermost/rnbeta/MainApplication.kt $CHAT/app/screens/server/index.tsx ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/HubTest.kt"
mutate() {  # mutate <label> <file> <old> <new>
  local d="$TMP/m"; rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  python3 - "$d/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding="utf-8").read()
if o not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(o, n, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $1"; return 1; fi
  echo "  mutation caught: $1"
}
M=0
mutate "chat's item loses its store" "$FC" '"store": "chat_setup"' '"store_": "chat_setup"' || M=1
mutate "a new setting with no schema" "$FC" '"id": "mm-watermelon",' '"id": "mm-new", "class": "config", "doc": "x", "path": "p"}, {"id": "mm-watermelon",' || M=1
mutate "a stale gap entry" "$GAPS" '"code/projects"' '"code/gone"' || M=1
mutate "chat_setup dropped from the routes" "$FC" '"chat.server_url": [' '"chat.server_url_": [' || M=1
mutate "chat_setup no longer used by the fork" "$FC" '"ac_cloud-chat"
      ]
    }
  },
  "bundle": {' '"ac_cloud-x"
      ]
    }
  },
  "bundle": {' || M=1
mutate "the native module reads another store" "$CHAT/android/app/src/main/java/com/mattermost/rnbeta/FleetSetupModule.kt" 'const val STORE = "chat_setup"' 'const val STORE = "chat_prefs"' || M=1
mutate "the native module writes" "$CHAT/android/app/src/main/java/com/mattermost/rnbeta/FleetSetupModule.kt" 'promise.resolve(map)' 'p.edit().putString(KEY_URL, "x").apply(); promise.resolve(map)' || M=1
mutate "package not registered" "$CHAT/android/app/src/main/java/com/mattermost/rnbeta/MainApplication.kt" 'add(FleetSetupPackage())' '' || M=1
mutate "server screen ignores the fleet server" "$CHAT/app/screens/server/index.tsx" 'managedConfig?.serverUrl || fleetSetup.serverUrl || LocalConfig.DefaultServerUrl;' 'managedConfig?.serverUrl || LocalConfig.DefaultServerUrl;' || M=1
mutate "hub test dropped" "ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/HubTest.kt" 'fun cloudChatHasASetupSchema' 'fun cloudChatHasNoSetupSchema' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
