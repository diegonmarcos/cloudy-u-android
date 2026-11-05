#!/usr/bin/env bash
# test-account-shell-runbook: Cloud Account has its OWN uid-2000 channel and the runbook's shell +
# store steps (account redesign spec 2.4 + 4.6). Static, no build, no network. Checks the real tree,
# then plants each mutation in a scratch copy and requires RED, so a check that cannot fail does
# not count as a check.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

check() {
python3 - "$1" <<'PY'
import json, re, sys
R = sys.argv[1]
fails = 0
def rd(p): return open(f"{R}/{p}", encoding="utf-8").read()
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

bj = json.loads(rd("ac_cloud-account/build.json"))
mods = bj["modules"]
ok("libs:shell-client" in mods.get("app", {}).get("depends_on", []), "app module depends on the broker client", "app module dropped libs:shell-client")
ok("libs:shell-client" in mods.get("libs:account", {}).get("depends_on", []), "libs:account depends on the broker client", "libs:account dropped libs:shell-client")
ok("libs:shizuku-adb-debug-tools" not in mods and not any("libs:shizuku-adb-debug-tools" in json.dumps(v.get("depends_on", [])) for v in mods.values() if isinstance(v, dict)), "#1512 no module of Cloud Account is the shizuku lib", "#1512 Cloud Account lists the shizuku lib again")
ok(not any(k in bj for k in ("shizuku_client", "shizuku_diagnostics", "privileged_channel")), "#1512 no shizuku_client / shizuku_diagnostics / privileged_channel block (the broker owns the channel)", "#1512 a channel block is back in build.json")
steps = bj.get("ui", {}).get("account", {}).get("runbook", {}).get("steps", [])
ok(steps == ["connected", "profile", "shell", "store", "apps", "configs", "perms", "verified"], "runbook declares the spec's eight steps in order", f"runbook steps wrong: {steps}")

g = rd("ac_cloud-account/app/build.gradle")
ok("implementation project(':libs:shell-client')" in g and "shizuku-adb-debug-tools" not in g, "app links the broker client and not the shizuku lib", "app does not link libs:shell-client / still links the shizuku lib")
ga = rd("ab_cloud-libs-shared/libs/account/build.gradle")
ok("implementation project(':libs:shell-client')" in ga and "project(':libs:shizuku-adb-debug-tools')" not in ga, "libs:account links the broker client and not the shizuku lib", "libs:account does not link libs:shell-client / still links the shizuku lib")

mf = rd("ac_cloud-account/app/src/main/AndroidManifest.xml")
mfc = re.sub(r"<!--.*?-->", "", mf, flags=re.S)
ok("adbdebug" not in mfc and "ShizukuProvider" not in mfc and "<service" not in mfc, "#1512 no pairing service, keep-alive service or Shizuku provider in the manifest", "#1512 the manifest declares a channel service or provider again")
ok("<receiver" not in mfc and "RECEIVE_BOOT_COMPLETED" not in mf, "#1502 no boot receiver, no RECEIVE_BOOT_COMPLETED (Account never wakes itself)", "#1502 Account declares a boot receiver again")
ok("android.permission.WRITE_SECURE_SETTINGS" in mf, "WRITE_SECURE_SETTINGS declared (the grant target)", "WRITE_SECURE_SETTINGS not declared")

app = rd("ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt")
ok("HostShell" not in re.sub(r"//[^\n]*", "", app) and "adbdebug" not in app, "#1512 App.onCreate arms no channel of its own (no HostShell)", "#1512 App.onCreate still names HostShell / adbdebug")
bp = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/BrokerShell.kt")
ok("ShellClient.status(ctx).armed" in bp and "ShellClient.exec(ctx" in bp, "the runbook's shell is the SuperApp's broker (armed = a live status)", "the account shell is not the broker client")

rb = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SetupRunbook.kt")
rbc = re.sub(r"//[^\n]*|/\*.*?\*/", "", rb, flags=re.S)
ok("ShellClient.arm(ctx)" in rbc and "HostShell" not in rbc and "WdKeeper" not in rbc, "runShell opens the SuperApp's arm flow, no pairing of its own", "runShell carries a pairing flow / HostShell again")
ok("SHELL -> Step(id, { checkShell() }, { runShell() })" in rb and "STORE -> Step(id, { checkStore() })" in rb, "runbook wires shell + store", "runbook does not wire shell/store")
ok('private fun checkStore(): State =\n        storeInfo()?.let { State.Already("Cloud Store ${it.versionName} installed") }' in rb, "store check says ALREADY when Cloud Store is installed", "store check never says ALREADY")
ok('if (ch == null && !allowPrompt)\n            return State.Failed("no shell channel' in rb, "no channel + no prompt -> a named FAILED", "no channel falls through silently")
ok("theirs.intersect(ours).isEmpty()" in rb, "store refuses an APK not signed with the fleet key", "store installs without the signer check")
ok('else -> Step(id, { notHere() }, { notHere() })' in rb, "an unknown step id answers TODO", "an unknown step id is not TODO")
api = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountDebugApi.kt")
ok('"runbook" -> SetupRunbook(ctx)' in api and 'Op("runbook"' in api, "debug API serves /api/account/runbook", "debug API has no runbook op")

# task 5: the remaining steps, Run all, the runbook + apps pages (spec 4.6 / 4.7)
for sid in ("CONNECTED", "PROFILE", "APPS", "CONFIGS", "PERMS", "VERIFIED"):
    ok(f"{sid} -> Step(id," in rb, f"runbook wires {sid.lower()}", f"runbook does not wire {sid.lower()}")
ok("if (st is State.Failed || st is State.Running) { stopped = s.id; break }" in rb, "Run all stops at the first FAILED", "Run all does not stop at FAILED")
ok("if (it.app.pkg == SUPERAPP_PKG) 0 else 1" in rb, "configs pushes SuperApp first", "configs does not order SuperApp first")
ok("StoreImport.EXTRA_IMPORT" in rb, "apps hands the inventory over EXTRA_IMPORT", "apps does not use the Store hand-off")
ok("PermsPlan.plan(ctx, prof, ch)" in rb and "PermsPlan.apply(ctx, plan, ch)" in rb, "perms step runs PermsPlan over the channel", "perms step does not use PermsPlan")
dr = rb.split("private fun drift")[1].split("private fun checkConfigs")[0]
ok("i.value" not in dr.replace("FleetSetup.same(back.opt(i.key), i.value)", ""), "drift lines carry key names only", "drift line prints a value")
ok(' need a source"' in rb, "apps line counts what needs a source", "apps line lost its need-a-source count")
pg = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/RunbookPage.kt")
ra = pg.split("RunbookTags.RUN_ALL")[1].split("sheet?.let")[0] if "RunbookTags.RUN_ALL" in pg else ""
ok("rb.plan()" in ra and "runAll" not in ra, "Run all opens the plan sheet first", "Run all acts without the plan sheet")
ok("rb.runAll" in pg.split("RunbookTags.SHEET_GO")[-1], "Run all acts only from the sheet's confirm", "Run all is not behind the sheet")
ok(not any(x in pg for x in (".value", "\"value\"", "workingProfile", "settings")), "runbook rows print state + detail only", "runbook page prints a value")
ap = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AppsPage.kt")
ok("\"no source declared: add it to the source map\"" in ap and "play" not in ap.lower().replace("display", ""), "apps page says 'no source declared'", "apps page lost 'no source declared'")
ok(not any(x in ap for x in (".intent", "startActivity", "storePage", "play.google", "market://")), "apps page never offers a Play page", "apps page offers a store/Play page")
ok("versionName" not in ap and "settings" not in ap, "apps rows are names and classes only", "apps page prints a value")
ok("handToStore()" in ap and "DeviceVault(ctx).backup(" in ap, "apps page: Install missing via Store + Capture", "apps page lost an action")
ma = rd("ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt")
# #1439 the Configs router and pages are libs:account-ui's; Cloud Account's host hands the Runbook setup to RunbookPage.
ok('"configs:runbook" -> RunbookTogglesPage(go)' in rd("ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/superapp/accountui/AccountUi.kt") and 'AccountUiHost.current.RunbookSetup(go)' in rd("ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/configs/ConfigsPages.kt") and '= RunbookPage(go)' in rd("ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/AccountUiHostImpl.kt") and '"push" -> FleetSetupPage(model)' in ma, "Setup mounts runbook / apps / configs", "Setup does not mount runbook/apps/configs")
ok('q["step"].isNullOrBlank() -> r.runAll(' in api and 'Op("apps"' in api and '"apps" -> SetupRunbook(ctx).appsPlan()' in api, "debug API: run=1 = Run all, apps op", "debug API lacks Run all or apps")

# spec 4.6 step 3: a foreground service holds the process up (channel + debug server are process state)
# /api/adb/exec + status on Account's port, behind the fleet token
ok('AppDebugServer.route("adb", listOf(' in api and 'Op("exec", "cmd=' in api and 'Op("status"' in api, "Account serves /api/adb/exec and /api/adb/status (in /api/docs)", "Account has no adb route group")
ad = api.split("private fun adb(")[1] if "private fun adb(" in api else ""
ok('"exec" ->' in ad and "ShellClient.exec(ctx, cmd)" in ad, "adb/exec runs through the shell broker (this app's allowlist entry)", "adb/exec does not run through the broker")
# #ipc no app listens any more: a route group is reached through the app's IPC door (signature re-check)
# or the SuperApp gateway (fleet token) -- a0_docs/eng-specs/fleet-ipc.md
door = rd("ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpc.kt")
gw = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/MeshGateway.kt")
ok("FleetIpcWire.gate(granted) ?:" in door and "checkCallingPermission(FleetToken.PERMISSION)" in door
   and 'if (!authed) return FleetIpcWire.Reply(401' in gw,
   "route groups (adb/exec included) sit behind the IPC door's signature check and the gateway's fleet token",
   "adb/exec reachable without the signature check or the fleet token")
# the device id: never a fabricated 404, derived from the model, set only to a declared id
pr = rb.split("private fun runProfile")[1].split("private fun workingProfile")[0]
ok('if (id.isBlank()) return State.Failed("this phone has no device id' in pr and pr.index("id.isBlank()") < pr.index("v.load(id)"),
   "blank device id fails by name before any forge call", "blank device id still reaches the forge")
ok('"status", 404' not in pr, "runProfile never fabricates a 404", "runProfile fabricates a 404")
ok("neither $path nor $defPath exists" in pr, "no device file and no DEFAULT names both paths", "missing DEFAULT is not named")
ap2 = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountPages.kt")
dv = ap2.split("object AccountDevice")[1].split("fun setId")[0]
ok("listOf(Build.MODEL.orEmpty(), Build.DEVICE.orEmpty())" in dv and '?.opt("model")' in dv and 'row.opt("model")' in dv and "if (m != null && m in want) out += id" in dv,
   "derivation matches Build.MODEL/DEVICE against fleet + devices/ models", "derivation ignores the model")
ok("if (c.size == 1)" in dv and 'putConnection("device.id", c[0])' in dv, "a single match is persisted to Connections", "derivation does not persist / accepts several")
ok('"derived from model ${resolved.model}"' in ap2, "picker hints the derived model", "picker lost the derived hint")
dd = api.split("private fun device(")[1].split("/**")[0] if "private fun device(" in api else ""
ok('if (set !in known && q["new"] != "1")' in dd and 'Op("device"' in api and '"device" -> device(ctx, q)' in api,
   "device?set= refuses an undeclared id without new=1", "device?set= accepts an undeclared id")
# configs = the setup op's plan (vault bundle Configs) merged with the working file; backup captures first
cp = rb.split("fun configsPlan()")[1].split("private fun drift")[0]
ok("vault.appConfigs()" in cp and "AccountModel.get(ctx).shown()" in cp and "SetupPlan.merge(fleet, device)" in cp,
   "configs step = setup's plan (vault bundle) merged with the working file", "configs step ignores the vault bundle")
dvv = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/DeviceVault.kt")
bk = dvv.split("fun backup(")[1].split("DeviceProfile.capture(ctx, deviceId)")[0]
ok("if (!capture) emptyList() else" in bk and "AccountMigrate.capture(vault," in bk, "backup captures every app's export first", "backup skips the capture")
ok('q["capture"] != "0"' in api, "backup?capture=0 skips the capture", "backup has no capture=0 switch")
sp = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SetupPlan.kt")
ok(sp.count("store.name == VAULT_STORE") >= 3, "setup never pushes into the Account vault's own file", "setup can overwrite the vault file")
# phone-seen: the alias migration runs at app start, and the card says what the id was for one session
ok("AccountDevice.migrateAtStart(this)" in app, "App.onCreate runs the device alias migration", "the alias migration waits for the profile step")
ms = ap2.split("fun migrateAtStart(")[1].split("\n    }\n")[0] if "fun migrateAtStart(" in ap2 else ""
ok("runCatching { resolve(app) }" in ms and "DeviceVault(app).devices()" in ms, "start-up migration resolves now and after refreshing the listing", "start-up migration does not resolve")
ok("renamedFrom = conn" in ap2 and '"was $it"' in ap2, "the device card notes the legacy id (was galaxy)", "the card never names the legacy id")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="ac_cloud-account/build.json ac_cloud-account/app/build.gradle ac_cloud-account/app/src/main/AndroidManifest.xml
ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt
ab_cloud-libs-shared/libs/account/build.gradle
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/BrokerShell.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SetupRunbook.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountDebugApi.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/RunbookPage.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AppsPage.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountPages.kt
ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt
ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/configs/ConfigsPages.kt
ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/superapp/accountui/AccountUi.kt
ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/AccountUiHostImpl.kt
ac_cloud-store/app/src/main/AndroidManifest.xml
ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt
ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpc.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/MeshGateway.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/DeviceVault.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SetupPlan.kt"
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
A=ac_cloud-account; K=$A/app/src/main/java/com/diegonmarcos/cloudaccount
P=ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile
M=0
mutate "a pairing service returns" $A/app/src/main/AndroidManifest.xml '<application' '<application><service android:name="com.diegonmarcos.superapp.adbdebug.AdbPairingService"/>' || M=$((M+1))
mutate "boot receiver returns" $A/app/src/main/AndroidManifest.xml '<application' '<application><receiver android:name="x"/>' || M=$((M+1))
mutate "HostShell returns in App" $K/App.kt 'runCatching { AccountVault.install(this) }' 'runCatching { HostShell.install(this) }' || M=$((M+1))
mutate "app no longer links the broker client" $A/app/build.gradle "implementation project(':libs:shell-client')" '' || M=$((M+1))
mutate "the shizuku lib is linked again" $A/app/build.gradle "implementation project(':libs:shell-client')" "implementation project(':libs:shizuku-adb-debug-tools')" || M=$((M+1))
mutate "a channel block returns" $A/build.json '"name": "cloud-account",' '"name": "cloud-account", "shizuku_client": {},' || M=$((M+1))
mutate "runShell pairs by itself" $P/SetupRunbook.kt 'com.diegonmarcos.superapp.shellclient.ShellClient.arm(ctx)' 'HostShell.reconnect(ctx, 1)' || M=$((M+1))
mutate "runbook step dropped" $A/build.json '"store",' '' || M=$((M+1))
mutate "store never ALREADY" $P/SetupRunbook.kt 'State.Already("Cloud Store ${it.versionName} installed")' 'State.Todo("x")' || M=$((M+1))
mutate "no channel is silent" $P/SetupRunbook.kt 'if (ch == null && !allowPrompt)' 'if (false)' || M=$((M+1))
mutate "signer check removed" $P/SetupRunbook.kt 'theirs.intersect(ours).isEmpty()' 'false' || M=$((M+1))
mutate "debug op missing" $P/AccountDebugApi.kt '"runbook" -> SetupRunbook(ctx)' '"runbookX" -> SetupRunbook(ctx)' || M=$((M+1))
mutate "Run all does not stop at FAILED" $P/SetupRunbook.kt 'if (st is State.Failed || st is State.Running) { stopped = s.id; break }' 'if (false) { stopped = s.id; break }' || M=$((M+1))
mutate "plan sheet skipped" $P/RunbookPage.kt 'runCatching { rb.plan() }.getOrNull()' 'runCatching { rb.runAll() }.getOrNull()' || M=$((M+1))
mutate "a value printed in a drift row" $P/SetupRunbook.kt 'out += "${ap.app.id}.${sf.first}.${i.key}"' 'out += "${ap.app.id}.${sf.first}.${i.key}=${i.value}"' || M=$((M+1))
mutate "a value printed in a runbook row" $P/RunbookPage.kt 'detail = r?.optString("detail").orEmpty()' 'detail = r?.optString("detail").orEmpty() + rb.configsPlan()?.apps?.firstOrNull()?.items?.firstOrNull()?.value' || M=$((M+1))
mutate "a value printed in an apps row" $P/AppsPage.kt 'pl.installed.map { it.pkg to (if (it.ours) "fleet" else "installed") }' 'pl.installed.map { it.pkg to it.versionName }' || M=$((M+1))
mutate "a Play page offered" $P/AppsPage.kt '(pl.store.map { it.entry } + pl.manual)' '(pl.store.also { ctx.startActivity(it.first().intent) }.map { it.entry } + pl.manual)' || M=$((M+1))
mutate "no source declared dropped" $P/AppsPage.kt '"no source declared: add it to the source map"' '"open in Play"' || M=$((M+1))
mutate "perms step not wired to PermsPlan" $P/SetupRunbook.kt 'PermsPlan.apply(ctx, plan, ch)' 'PermsPlan.Outcome(emptyList())' || M=$((M+1))
mutate "runbook page unmounted" ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/superapp/accountui/AccountUi.kt '"configs:runbook" -> RunbookTogglesPage(go)' '"configs:runbook" -> AccountPlaceholderPage(section, page, "x")' || M=$((M+1))
mutate "setup steps dropped from the Runbook" ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/configs/ConfigsPages.kt 'if (tab == "setup") AccountUiHost.current.RunbookSetup(go) else' 'if (tab == "setup") Unit else' || M=$((M+1))
mutate "debug run=1 without step is not Run all" $P/AccountDebugApi.kt 'q["step"].isNullOrBlank() -> r.runAll(' 'false -> r.runAll(' || M=$((M+1))

D=ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools
mutate "exec route missing" $P/AccountDebugApi.kt 'Op("exec", "cmd=' 'Op("execX", "cmd=' || M=$((M+1))
mutate "exec not over the broker" $P/AccountDebugApi.kt 'ShellClient.exec(ctx, cmd)' 'null' || M=$((M+1))
mutate "exec not gated at the IPC door" $D/FleetIpc.kt 'FleetIpcWire.gate(granted) ?: ' '' || M=$((M+1))
mutate "exec not token-gated at the gateway" aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/MeshGateway.kt 'if (!authed) return FleetIpcWire.Reply(401' 'if (false) return FleetIpcWire.Reply(401' || M=$((M+1))
mutate "blank id still reaches the forge" $P/SetupRunbook.kt 'if (id.isBlank()) return State.Failed("this phone has no device id' 'if (false) return State.Failed("this phone has no device id' || M=$((M+1))
mutate "derivation ignores the model" $P/AccountPages.kt 'if (m != null && m in want) out += id' 'out += id' || M=$((M+1))
mutate "device?set= accepts an undeclared id without new" $P/AccountDebugApi.kt 'if (set !in known && q["new"] != "1")' 'if (false)' || M=$((M+1))
mutate "configs step ignores the vault bundle" $P/SetupRunbook.kt 'SetupPlan.merge(fleet, device)' 'device' || M=$((M+1))
mutate "backup skips capture" $P/DeviceVault.kt 'if (!capture) emptyList() else' 'if (true) emptyList() else' || M=$((M+1))
mutate "setup pushes into the vault file" $P/SetupPlan.kt 'if (store.name == VAULT_STORE) continue' 'if (false) continue' || M=$((M+1))
mutate "alias migration not run at app start" $K/App.kt 'AccountDevice.migrateAtStart(this)' 'toString()' || M=$((M+1))
mutate "start-up migration never resolves" $P/AccountPages.kt 'runCatching { resolve(app) }' 'runCatching { app }' || M=$((M+1))
mutate "card drops the alias note" $P/AccountPages.kt 'renamedFrom = conn' 'Unit' || M=$((M+1))
echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
