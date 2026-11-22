#!/usr/bin/env bash
# #1835 the vault -> Cloud Account importer of the fleet mesh token. Static (no build, no device, no network, never reads a secret).
#  1. the importer exists, files ONLY the catalogue credential fleet.mesh_token through AppCredentialsStore, never logs/prints a value
#  2. the landing hooks call it (AccountModel.landBundle and the Import page)
#  3. build.json::fleet_token declares the importer and the bundle path the Kotlin constants spell
#  4. the credential stays signature-guarded and granted to the webserver alone (seed v21); the webserver still pulls it (deliver)
#  5. the unit suite exists
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -I - "$ROOT" <<'PY'
import json, os, re, sys, tempfile, shutil
R = sys.argv[1]
F = {
 "imp": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/FleetTokenImport.kt",
 "model": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountModel.kt",
 "page": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/ImportConfigsFragment.kt",
 "vault": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt",
 "ws": "ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/FleetTokenAndroid.kt",
 "bj": "ac_cloud-c3-webserver/build.json",
 "test": "ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/FleetTokenImportTest.kt",
}
def checks(root):
    rd = lambda k: open(os.path.join(root, F[k]), encoding="utf-8").read()
    out = []
    imp = rd("imp")
    code = re.sub(r'/\*.*?\*/', '', imp, flags=re.S)
    if 'CREDENTIAL = "fleet.mesh_token"' not in imp: out.append("importer does not target fleet.mesh_token")
    if "AppCredentialsStore.put(vault, CREDENTIAL" not in imp: out.append("importer does not file through AppCredentialsStore")
    if re.search(r'\b(Log\.|println|Timber|logcat)', code): out.append("importer logs or prints")
    if re.search(r'["\'][A-Za-z0-9._~+/=-]{40,}["\']', code): out.append("importer holds a long literal (secret?)")
    if "FleetTokenImport.land(ctx, bundle)" not in rd("model"): out.append("landBundle does not call the importer")
    if "FleetTokenImport.land(ctx, v.bundle)" not in rd("page"): out.append("Import page does not call the importer")
    fl = json.loads(rd("bj"))["fleet_token"]
    vi = fl["delivery"].get("vault_import", {})
    sec, key = re.search(r'SECTION = "(\w+)"', imp), re.search(r'KEY = "(\w+)"', imp)
    if not (sec and key and vi.get("bundle_path") == sec.group(1) + "." + key.group(1)): out.append("build.json vault_import.bundle_path differs from the importer constants")
    if not os.path.exists(os.path.join(root, vi.get("importer", "-"))): out.append("build.json vault_import.importer path is dead")
    if fl["delivery"].get("credential") != "fleet.mesh_token" or not fl["delivery"].get("foreground_only"): out.append("delivery lost credential / foreground_only")
    if 'mapOf("com.diegonmarcos.cloudwebserver" to listOf("cred.fleet.mesh_token"))' not in rd("vault"): out.append("seed v21 grant lost")
    if "fun deliver(" not in rd("ws") or '"app_credential"' not in rd("ws"): out.append("webserver no longer pulls the credential")
    if not re.search(r'class FleetTokenImportTest', rd("test")): out.append("unit suite missing")
    return out
bad = checks(R)
for b in bad: print("  FAIL " + b)
if not bad: print("  ok   fleet token importer holds")
fails = len(bad)
muts = [
 ("imp", 'CREDENTIAL = "fleet.mesh_token"', 'CREDENTIAL = "git.github_token"'),
 ("imp", "AppCredentialsStore.put(vault, CREDENTIAL", "vault.putConnection(CREDENTIAL"),
 ("model", "FleetTokenImport.land(ctx, bundle)", "Unit"),
 ("page", "FleetTokenImport.land(ctx, v.bundle)", "Unit"),
 ("bj", '"bundle_path": "fleet.mesh_token"', '"bundle_path": "x.y"'),
 ("vault", 'to listOf("cred.fleet.mesh_token")', 'to listOf("cred.x")'),
 ("ws", "fun deliver(", "fun xdeliver("),
 ("imp", "val vault = AccountVault(ctx)", 'val vault = AccountVault(ctx); android.util.Log.d("t", "x")'),
]
for k, a, b in muts:
    d = tempfile.mkdtemp()
    try:
        for f in F.values():
            os.makedirs(os.path.dirname(os.path.join(d, f)), exist_ok=True)
            shutil.copy(os.path.join(R, f), os.path.join(d, f))
        p = os.path.join(d, F[k]); s = open(p, encoding="utf-8").read()
        if a not in s: print("  FAIL mutation anchor gone: " + a); fails += 1; continue
        open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
        if checks(d): print("  ok   mutation caught: " + a[:40])
        else: print("  FAIL mutation NOT caught: " + a[:40]); fails += 1
    finally:
        shutil.rmtree(d, ignore_errors=True)
sys.exit(1 if fails else 0)
PY
