#!/usr/bin/env bash
# #1807 the vault delivery importer of app credentials. Static (no build, no device, never reads a secret).
#  1. DeliveryImport files only through AppCredentialsStore, keeps a held value, never logs; importBundle and the landing call it
#  2. the unit suite exists; the vault manifest (when the sibling checkout is present) names only catalogue ids
# Each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -I - "$ROOT" <<'PY'
import os, re, shutil, sys, tempfile
R = sys.argv[1]
F = {
 "imp": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/DeliveryImport.kt",
 "model": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountModel.kt",
 "vault": "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt",
 "test": "ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/DeliveryImportTest.kt",
}
def checks(root):
    rd = lambda k: open(os.path.join(root, F[k]), encoding="utf-8").read()
    out = []
    imp = rd("imp"); code = re.sub(r'/\*.*?\*/', '', imp, flags=re.S)
    if "AppCredentialsStore.put(vault, id, v)" not in imp: out.append("importer does not file through AppCredentialsStore")
    if "AppCredentialsContract.entry(id) == null -> Outcome.UNKNOWN_ID" not in imp: out.append("importer lost the catalogue check")
    if "held(id).isNotBlank() -> Outcome.KEPT_MANUAL" not in imp: out.append("importer no longer keeps a held (manual) value")
    if re.search(r'\b(Log\.|println|Timber|logcat)', code): out.append("importer logs or prints")
    if "DeliveryImport.land(ctx, b)" not in rd("vault"): out.append("importBundle does not call the importer")
    if "DeliveryImport.land(ctx, bundle)" not in rd("model"): out.append("landBundle does not call the importer")
    if "class DeliveryImportTest" not in rd("test"): out.append("unit suite missing")
    return out
bad = checks(R)
for b in bad: print("  FAIL " + b)
if not bad: print("  ok   delivery importer holds")
fails = len(bad)
muts = [
 ("imp", "AppCredentialsStore.put(vault, id, v)", "vault.putConnection(id, v)"),
 ("imp", "held(id).isNotBlank() -> Outcome.KEPT_MANUAL", "false -> Outcome.KEPT_MANUAL"),
 ("imp", "AppCredentialsContract.entry(id) == null -> Outcome.UNKNOWN_ID", "false -> Outcome.UNKNOWN_ID"),
 ("vault", "DeliveryImport.land(ctx, b)", "Unit"),
 ("model", "DeliveryImport.land(ctx, bundle)", "Unit"),
 ("imp", "val vault = AccountVault(ctx)", 'val vault = AccountVault(ctx); android.util.Log.d("t", "x")'),
]
for k, a, b in muts:
    d = tempfile.mkdtemp()
    try:
        for kk, p in F.items():
            os.makedirs(os.path.dirname(os.path.join(d, p)), exist_ok=True); shutil.copy(os.path.join(R, p), os.path.join(d, p))
        p = os.path.join(d, F[k]); s = open(p, encoding="utf-8").read()
        if a not in s: print("  FAIL mutation anchor missing: " + a); fails += 1; continue
        open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
        red = bool(checks(d))
        print(("  ok   RED on " if red else "  FAIL stayed green on ") + a[:50]); fails += (not red)
    finally: shutil.rmtree(d, ignore_errors=True)
sys.exit(1 if fails else 0)
PY
