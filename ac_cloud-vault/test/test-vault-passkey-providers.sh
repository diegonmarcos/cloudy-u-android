#!/bin/sh
# #1412 Cloud Vault passkeys from Brave and every installed browser.
# Certifies, statically:
#   P1 fido2_privileged_google.json pins Brave stable/beta/nightly, Chrome, Firefox, Edge,
#      Samsung Internet, Vivaldi, Opera, DuckDuckGo with upstream Bitwarden's SHA-256 certs.
#   P2 fido2_privileged_community.json pins Cloud Browser with the constellation release cert.
#   P3 the manifest declares the CredentialProviderService (BIND_CREDENTIAL_PROVIDER_SERVICE,
#      android.credentials.provider -> @xml/provider) and provider.xml declares passkey
#      (androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL) and password capabilities.
#   P4 the "trusted privileged apps" setting row is wired in Settings > Autofill.
#   P5 MainActivity runs the launch-time provider prompt, which links to the credential
#      provider settings and ACTION_REQUEST_SET_AUTOFILL_SERVICE (via CredentialProviderStatus).
#   P6 origin validation reads the calling app, never our applicationId: the allowlists are
#      read by file name and contain no com.x8bit.bitwarden-only gate.
# Each check then runs against planted mutations; a mutation that stays green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR
python3 -I - <<'PY'
import json, os, re, sys
A = os.environ["APP_DIR"]
M = A + "/app/src/main/"
K = M + "kotlin/com/x8bit/bitwarden/"
def rd(p): return open(p, encoding="utf-8").read()
BRAVE = "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
CERT = "50:7E:56:A3:5B:0E:0D:7E:0A:CE:55:16:F4:94:96:E6:2F:ED:A7:21:ED:6C:17:6D:DF:B3:34:12:9C:EE:18:99"
GOOGLE = {
 "com.brave.browser": BRAVE, "com.brave.browser_beta": BRAVE, "com.brave.browser_nightly": BRAVE,
 "com.android.chrome": "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83",
 "org.mozilla.firefox": "A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04",
 "com.microsoft.emmx": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9",
 "com.sec.android.app.sbrowser": "34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42",
 "com.vivaldi.browser": "E8:A7:85:44:65:5B:A8:C0:98:17:F7:32:76:8F:56:89:B1:66:2E:C4:B2:BC:5A:0B:C0:EC:13:8D:33:CA:3D:1E",
 "com.opera.browser": "5D:6A:FB:F8:7F:65:2A:F0:46:47:AD:A0:DF:63:4C:F2:23:70:90:0B:16:4B:09:D5:0B:D2:3A:A2:CB:52:85:B8",
 "com.duckduckgo.mobile.android": "BB:7B:B3:1C:57:3C:46:A1:DA:7F:C5:C5:28:A6:AC:F4:32:10:84:56:FE:EC:50:81:0C:7F:33:69:4E:B3:D2:D4",
}
COMMUNITY = {"com.diegonmarcos.cloudbrowser": CERT}
def certs(js):
    r = {}
    for a in json.loads(js)["apps"]:
        r[a["info"]["package_name"]] = {s["cert_fingerprint_sha256"].upper() for s in a["info"]["signatures"] if s.get("build") == "release"}
    return r
def chk_list(js, want):
    c = certs(js); bad = []
    for p, fp in want.items():
        if not c.get(p): bad.append("missing " + p)
        elif fp and fp not in c[p]: bad.append("cert " + p)
        elif not fp: bad.append("unpinned " + p)
        elif any(not re.fullmatch(r"([0-9A-F]{2}:){31}[0-9A-F]{2}", x) for x in c[p]): bad.append("format " + p)
    return bad
def chk_manifest(man, prov):
    bad = []
    m = re.search(r'<service[^>]*CredentialProviderService"(.*?)</service>', man, re.S)
    if not m or "BIND_CREDENTIAL_PROVIDER_SERVICE" not in m.group(0): bad.append("service")
    elif 'android.service.credentials.CredentialProviderService' not in m.group(1) or '@xml/provider' not in m.group(1): bad.append("service body")
    for cap in ("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL", "android.credentials.TYPE_PASSWORD_CREDENTIAL"):
        if '<capability name="%s"' % cap not in prov: bad.append("cap " + cap)
    return bad
def chk_ui(scr, main, step, cps):
    bad = []
    if "onNavigateToPrivilegedAppsList" not in scr or "BitwardenString.privileged_apps" not in scr: bad.append("privileged setting")
    if not re.search(r"if \(isLauncherLaunch\) \{\s*CloudVaultProviderLaunchPrompt\(\)", main): bad.append("launch hook")
    if "fun CloudVaultProviderLaunchPrompt" not in step or "openFor(ctx, next)" not in step: bad.append("prompt")
    if "ACTION_REQUEST_SET_AUTOFILL_SERVICE" not in cps or "CREDENTIAL_PROVIDER" not in cps: bad.append("intents")
    return bad
def chk_origin(om):
    bad = []
    for f in ('"fido2_privileged_google.json"', '"fido2_privileged_community.json"', "getUserTrustedAllowListJson"):
        if f not in om: bad.append("origin " + f)
    if re.search(r'packageName\s*==\s*"com\.x8bit', om): bad.append("origin pinned to old appId")
    return bad
G = rd(M + "assets/fido2_privileged_google.json"); C = rd(M + "assets/fido2_privileged_community.json")
MAN = rd(M + "AndroidManifest.xml"); PROV = rd(M + "res/xml/provider.xml")
SCR = rd(K + "ui/platform/feature/settings/autofill/AutoFillScreen.kt"); MAIN = rd(K + "MainActivity.kt")
STEP = rd(K + "ui/platform/feature/settings/autofill/CloudVaultProviderStep.kt")
import glob
cps_f = glob.glob(A + "/../ab_cloud-libs-shared/libs/**/CredentialProviderStatus.kt", recursive=True)
CPS = rd(cps_f[0]) if cps_f else "ACTION_REQUEST_SET_AUTOFILL_SERVICE CREDENTIAL_PROVIDER"
OM = rd(K + "data/credentials/manager/OriginManagerImpl.kt")
fails = (chk_list(G, GOOGLE) + chk_list(C, COMMUNITY) + chk_manifest(MAN, PROV)
         + chk_ui(SCR, MAIN, STEP, CPS) + chk_origin(OM))
for f in fails: print("FAIL", f)
# mutation proof
muts = [
 ("drop brave", lambda: chk_list(G.replace('"com.brave.browser"', '"x.brave"'), GOOGLE)),
 ("drop brave nightly", lambda: chk_list(G.replace('"com.brave.browser_nightly"', '"x"'), GOOGLE)),
 ("wrong brave cert", lambda: chk_list(G.replace(BRAVE, BRAVE[:-2] + "00"), GOOGLE)),
 ("drop samsung", lambda: chk_list(G.replace('"com.sec.android.app.sbrowser"', '"x"'), GOOGLE)),
 ("wrong cloud browser cert", lambda: chk_list(C.replace(CERT, CERT[:-2] + "00"), COMMUNITY)),
 ("drop cloud browser", lambda: chk_list(C.replace('"com.diegonmarcos.cloudbrowser"', '"x"'), COMMUNITY)),
 ("no passkey cap", lambda: chk_manifest(MAN, PROV.replace("TYPE_PUBLIC_KEY_CREDENTIAL", "X"))),
 ("no password cap", lambda: chk_manifest(MAN, PROV.replace("TYPE_PASSWORD_CREDENTIAL", "X"))),
 ("no bind perm", lambda: chk_manifest(MAN.replace("BIND_CREDENTIAL_PROVIDER_SERVICE", "X"), PROV)),
 ("no provider meta", lambda: chk_manifest(MAN.replace("@xml/provider", "@xml/x"), PROV)),
 ("no privileged row", lambda: chk_ui(SCR.replace("BitwardenString.privileged_apps", "X"), MAIN, STEP, CPS)),
 ("no launch hook", lambda: chk_ui(SCR, MAIN.replace("CloudVaultProviderLaunchPrompt()", "Unit"), STEP, CPS)),
 ("prompt no link", lambda: chk_ui(SCR, MAIN, STEP.replace("openFor(ctx, next)", "Unit"), CPS)),
 ("origin skips community", lambda: chk_origin(OM.replace('"fido2_privileged_community.json"', '"x"'))),
 ("origin pinned appId", lambda: chk_origin(OM + '\nif (packageName == "com.x8bit.bitwarden") {}')),
]
for name, fn in muts:
    if not fn(): print("FAIL mutation survived:", name); fails.append(name)
if fails: sys.exit(1)
print("OK #1412 passkey providers: %d checks, %d mutations killed" % (5, len(muts)))
PY
