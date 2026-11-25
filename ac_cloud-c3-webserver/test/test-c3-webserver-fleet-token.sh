#!/bin/sh
# test-c3-webserver-fleet-token.sh — #1835 the fleet bearer token is OWNED by Cloud C3 Webserver.
#   G1 source guard: no code reads the fleet token from the SuperApp as PRIMARY. The mesh listener's JNI read targets this app's own
#      provider; the only SuperApp token reads left are the declared FALLBACK / MIGRATION / wake sites (allowlist below).
#   G2 the owner door: provider declared signature-guarded, vault Keystore-encrypted, devtools FleetToken reads the owner first.
#   P1 parity, nothing removed: the webserver Configs pages (server, device, engines, links) + the new Fleet token page, and every row
#      of the SuperApp About "Dev Control HTTP API" / "Curl shortcuts" sections, with Token now the read-only mirror.
# Reads other apps' sources, so it is kept out of test-c3-webserver.sh (like about-mirror).
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
python3 - "$APP" "$ROOT" <<'PY'
import json, os, re, sys
app, root = sys.argv[1:3]
rd = lambda p: open(os.path.join(root, p), encoding="utf-8").read()
errors = []
kdir = "ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver"
def need(cond, msg):
    if not cond: errors.append(msg)

# G1 ---------------------------------------------------------------------------------------------------------------------------
bridge = rd("ac_cloud-c3-webserver/core/src/bridge.rs")
m = re.search(r"fn read_token\(\).*?\n}\n", bridge, re.S)
need(m is not None, "G1 bridge.rs has no read_token")
if m:
    body = m.group(0)
    need("bridge_package" not in body and "superapp" not in body.lower().replace("the superapp is not read here", ""), "G1 read_token still reads the SuperApp")
    need("owner_token_target" in body and "getPackageName" in body, "G1 read_token does not target this app's own provider")
need("fleettoken" in rd("ac_cloud-c3-webserver/build.json"), "G1 build.json::mesh.token_authority_suffix is not the owner's authority")
need("the token the SuperApp mints" not in rd("ac_cloud-c3-webserver/server/src/meshlogs.rs"), "G1 meshlogs.rs still documents the SuperApp as the source")
ALLOW = {  # files that may name the SuperApp's token provider, and why
    "ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetToken.kt": "fallback (after the owner)",
    "ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetPeers.kt": "wake, row discarded",
    kdir + "/FleetTokenAndroid.kt": "one-time migration",
}
pat = re.compile(r"superapp\.fleet/token|\$AUTHORITY_PKG\.fleet|AUTHORITY_URI|\.fleet/token")
for dp, dn, fn in os.walk(root):
    dn[:] = [d for d in dn if d not in (".git", "build", "target", "node_modules", "z_archive", "test", "androidTest")]
    for f in fn:
        if not f.endswith((".kt", ".java", ".rs")): continue
        p = os.path.join(dp, f); rel = os.path.relpath(p, root)
        if rel in ALLOW: continue
        if pat.search(open(p, encoding="utf-8", errors="ignore").read()):
            errors.append("G1 %s reads a fleet token from the SuperApp (not in the fallback allowlist)" % rel)
ft = rd("ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetToken.kt")
g = re.search(r"fun get\(ctx: Context\): String \{(.*?)\n    \}", ft, re.S)
need(g is not None and g.group(1).find("owner(app)") != -1 and g.group(1).find("owner(app)") < g.group(1).find("adopt(app)"), "G1 FleetToken.get does not read the owner before the SuperApp fallback")
need('OWNER_PKG = "com.diegonmarcos.cloudwebserver"' in ft, "G1 FleetToken.OWNER_PKG is not the webserver")
dcs = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt")
need("FleetToken.get(ctx)" in dcs, "G1 the SuperApp's DevControlServer does not read the owner's token")

# G2 ---------------------------------------------------------------------------------------------------------------------------
man = rd("ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main/AndroidManifest.xml")
pm = re.search(r'<provider\s+android:name="\.FleetTokenOwnerProvider".*?/>', man, re.S)
need(pm is not None and "${applicationId}.fleettoken" in pm.group(0) and "CONSTELLATION_DATA" in pm.group(0), "G2 the owner provider is not declared signature-guarded on <pkg>.fleettoken")
vault = rd(kdir + "/FleetTokenAndroid.kt")
need("AndroidKeyStore" in vault and "AES/GCM/NoPadding" in vault, "G2 the vault is not Keystore AES-GCM")
need("Log." not in vault + rd(kdir + "/FleetTokenVault.kt") + rd(kdir + "/ui/FleetTokenPage.kt"), "G2 the fleet token code logs")
page = rd(kdir + "/ui/FleetTokenPage.kt")
need("createConfirmDeviceCredentialIntent" in page, "G2 Reveal / Copy are not behind the device credential")
for label in ("Paste", "Generate", "Rotate", "Reveal", "Copy", "Clear"):
    need('Act("%s"' % label in page, "G2 the Fleet token page has no %s" % label)
need("Save to vault" not in page, "G2 the page invents a vault path (no declared phone-to-vault path exists)")

# P1 ---------------------------------------------------------------------------------------------------------------------------
bj = json.loads(rd("ac_cloud-c3-webserver/build.json"))
cfg = [s for s in bj["ui"]["sections"] if s["id"] == "configs"][0]["pages"]
ids = [p["id"] for p in cfg]
for k in ("server", "device", "engines", "links", "fleet_token"):
    need(k in ids, "P1 webserver Configs lost / lacks the %r page" % k)
need('PAGE_FLEET_TOKEN = "fleet_token"' in rd(kdir + "/Decl.kt"), "P1 Decl.PAGE_FLEET_TOKEN missing")
frag = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt")
a = frag.index('infoSection("Dev Control HTTP API")'); b = frag.index('infoSection("Curl shortcuts")'); c = frag.index("infoSection(", b + 10)
for label in ("Endpoint", "Status", "Bound to", "Bind scope", "Token", "API enabled"):
    need(re.search(r'(row|toggle)\("%s"' % re.escape(label), frag[a:b]), "P1 SuperApp About lost the %r row" % label)
for label in ("Docs", "Logcat", "Trace", "Crashes", "Bundle", "Download logs", "Send logs → cloud", "Info", "State", "Haptic", "Update", "Restart", "Tracker"):
    need(re.search(r'row\("%s"' % re.escape(label), frag[b:c]), "P1 SuperApp About lost the Curl shortcut %r" % label)
need("read-only mirror, managed in Cloud C3 Webserver" in frag[a:b] and 'button("Open in Cloud C3 Webserver")' in frag[a:b], "P1 the About Token row is not the read-only mirror with its webserver link")
need('row("Token",    prefs.token)' not in frag, "P1 the About Token row still shows the full token")
need("c3_open_fleet_token" in rd(kdir + "/MainActivity.kt") and "c3_open_fleet_token" in frag, "P1 the webserver does not open the Fleet token page from the About link")

# V1 vault delivery (declared by name; Cloud Account's App credentials is the vault-to-phone path; foreground only)
ft = bj.get("fleet_token", {})
need(ft.get("name") == "SUPERAPP_FLEET_TOKEN" and ft.get("vault", {}).get("key") == "SUPERAPP_FLEET_TOKEN" and ft.get("owner") == "ac_cloud-c3-webserver", "V1 build.json::fleet_token does not declare the secret by name")
dl = ft.get("delivery", {})
contract = rd("ab_cloud-libs-shared/libs/fleet-endpoints/src/main/kotlin/com/diegonmarcos/superapp/fleetendpoints/AppCredentialsContract.kt")
need('const val METHOD = "%s"' % dl.get("method") in contract, "V1 delivery.method is not AppCredentialsContract.METHOD")
need('Entry("%s"' % dl.get("credential") in contract and "PKG_WEBSERVER" in contract, "V1 the credential is not in the App credentials catalogue for the webserver")
need('"com.diegonmarcos.cloudwebserver" to listOf("%s")' % dl.get("grant") in rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt"), "V1 Cloud Account does not seed the grant to the webserver")
andr = rd(kdir + "/FleetTokenAndroid.kt")
need(dl.get("authority") in andr and '"%s"' % dl.get("method") in andr and dl.get("credential") in andr, "V1 the webserver's delivery call does not match the declaration")
need("deliver" not in re.search(r"class FleetTokenOwnerProvider.*", andr, re.S).group(0), "V1 the peer-facing provider must not wake Cloud Account")
need("fleet_token_vault" in rd("fleet-private-data/fleet-config.json"), "V1 the store is not declared in fleet-config.json")
need("Fetch from vault" in page and "not in vault" in page, "V1 the page lacks Fetch from vault / the 'not in vault' mark")

if errors:
    print("\n".join("  FAIL " + e for e in errors)); sys.exit(1)
print("  ok  fleet token: owned by the webserver; SuperApp is fallback / mirror only; nothing removed")
PY
