#!/usr/bin/env bash
# #1693 item 1: Cloud Account serves a SHORT-LIVED Authelia session to the fleet over its accountdata provider, and Drive's
# Cloud rung reads it from there instead of logging in itself. Static (no build, no network). Holds:
#  1. the provider's `session` method sits behind the fleet signature permission (the top gate AND its own re-check), answers
#     through SessionLease.answer, and is refreshed by Cloud Account (SessionProbe), never by the caller.
#  2. the lease is memory only (no prefs/file/keystore) and expires; the cookie is never logged, echoed or put in an error
#     (SessionLease, SessionProbe, the provider branch, AccountClient.session, DriveSessionSource) and Session.toString hides it.
#  3. only Cloud Account's portal login feeds it (both SignInHost hosts override onWebSession -> SessionLease.remember).
#  4. Drive earns no cookie: FleetSession gets its lease from DriveSessionSource (AccountClient.session), there is no
#     WebAuth/onWebSession/CookieManager in Drive, and every IO use goes through FleetSession.fresh()/sync().
# Then each mutation is planted in a scratch copy and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
L=ab_cloud-libs-shared/libs
F=(
  $L/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt
  $L/account/src/main/java/com/diegonmarcos/superapp/profile/SessionLease.kt
  $L/account/src/main/java/com/diegonmarcos/superapp/profile/SessionProbe.kt
  $L/account/src/main/java/com/diegonmarcos/superapp/profile/AccountPages.kt
  $L/account/src/main/java/com/diegonmarcos/superapp/profile/AuthProvidersPage.kt
  $L/fleet-endpoints/src/main/kotlin/com/diegonmarcos/superapp/fleetendpoints/SignInContract.kt
  $L/fleet-endpoints-android/src/main/java/com/diegonmarcos/superapp/fleetendpoints/android/AccountClient.kt
  $L/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/FleetSession.kt
  ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/DriveSessionSource.kt
  ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitSyncCoordinator.kt
  ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt
  ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/MainActivity.kt
)

check() { python3 - "$1" <<'PY'
import sys, re, os, glob
r = sys.argv[1]
def src(p): return open(os.path.join(r, p), encoding="utf-8").read()
def code(p): return "\n".join(l for l in src(p).splitlines() if not l.strip().startswith(("*", "//", "/*")))
L = "ab_cloud-libs-shared/libs/"
AD = code(L+"account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt")
SL = code(L+"account/src/main/java/com/diegonmarcos/superapp/profile/SessionLease.kt")
SP = code(L+"account/src/main/java/com/diegonmarcos/superapp/profile/SessionProbe.kt")
CT = code(L+"fleet-endpoints/src/main/kotlin/com/diegonmarcos/superapp/fleetendpoints/SignInContract.kt")
AC = code(L+"fleet-endpoints-android/src/main/java/com/diegonmarcos/superapp/fleetendpoints/android/AccountClient.kt")
FS = code(L+"git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/FleetSession.kt")
D = "ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/"
DS = code(D+"sync/DriveSessionSource.kt"); GC = code(D+"sync/GitSyncCoordinator.kt"); GR = code(D+"sync/GitReposScreen.kt"); MA = code(D+"MainActivity.kt")
bad = []
def need(c, why):
    if not c: bad.append(why)
# 1 gate
need("caller is not a fleet app" in AD and AD.index("checkCallingPermission(FleetConfig.PERMISSION)") < AD.index("METHOD_SECRET ->"), "the provider's top signature gate must precede every method")
b = AD[AD.index("SignInContract.METHOD_SESSION ->"):] if "SignInContract.METHOD_SESSION ->" in AD else ""
b = b[:b.index('else -> error("unknown method')] if b else ""
need("checkCallingPermission(FleetConfig.PERMISSION) == PackageManager.PERMISSION_GRANTED" in b, "the session branch must re-check the signature permission itself")
need("SessionLease.answer(" in b and "SessionProbe.probe()" in b, "the session branch must answer through SessionLease.answer with Cloud Account's own probe")
need("if (!allowed) error(" in b, "a caller that is not allowed must get an error, not a session")
need("fun answer(provider: String, allowed: Boolean" in SL and "if (!allowed) return" in SL and SL.index("if (!allowed)") < SL.index("keepFresh(probe)"), "answer must refuse before it refreshes or reads the lease")
need("METHOD_SESSION" in AC and "KEY_SESSION_JSON" in AC, "AccountClient must call the session method")
# 2 memory only, expiry, never logged
for name, t in (("SessionLease", SL), ("SessionProbe", SP), ("DriveSessionSource", DS), ("FleetSession", FS)):
    need(not re.search(r"SharedPreferences|getSharedPreferences|openFileOutput|File\(|KeyStore|putString|DataStore|edit\(\)", t), name + " must keep the session in memory only")
for name, t in (("SessionLease", SL), ("SessionProbe", SP), ("DriveSessionSource", DS), ("FleetSession", FS)):
    need(not re.search(r"\bLog\.|println\(|Timber|logcat|DriveDebugLog", t), name + " must not log")
need("expiresAtMs <= clock()" in SL.replace("h.expiresAtMs", "expiresAtMs") and "expiresAtMs <= clock()" in FS.replace("l.expiresAtMs", "expiresAtMs"), "both leases must expire")
need(not re.search(r"Log\.[a-z]\([^)]*(cookie|SECRET)", AC), "AccountClient must not log the cookie")
ts = CT[CT.index("override fun toString()"):].split("\n")[0] if "override fun toString()" in CT else ""
need(ts != "" and "$cookie" not in ts and "cookie=" not in ts, "SignInContract.Session.toString must hide the cookie")
need(re.search(r'if \(url == null\) true|stateUrl', SP) is not None and 'protocol != "https"' in SP, "the probe must send the cookie only over https to the declared portal")
need("c.instanceFollowRedirects = false" in SP, "the probe must not follow a redirect with the cookie")
# 3 only Cloud Account's login feeds it
for p in ("AccountPages.kt", "AuthProvidersPage.kt"):
    need("override fun onWebSession(cookie: String) { SessionLease.remember(cookie) }" in code(L+"account/src/main/java/com/diegonmarcos/superapp/profile/"+p), p + " host must hand the portal session to SessionLease")
# 4 Drive earns no cookie
need("AccountClient.session(app, SignInContract.PROVIDER_AUTHELIA)" in DS and "FleetSession.source" in DS, "Drive's session source must be Cloud Account's session method")
need("DriveSessionSource.install(this)" in MA, "MainActivity must install the session source")
need("FleetSession.fresh()" in GC and "FleetSession.cookie" not in GC, "the coordinator must read the session through fresh()")
need("FleetSession.cookie" not in GR and "FleetSession.sync()" in GR, "the Git page must sync before it uses the session")
for p in glob.glob(os.path.join(r, "ac_cloud-drive/app/src/main/**/*.kt"), recursive=True):
    t = "\n".join(l for l in open(p, encoding="utf-8").read().splitlines() if not l.strip().startswith(("*", "//", "/*")))
    rel = os.path.relpath(p, r)
    need(not re.search(r"onWebSession|CookieManager|WebAuthDialog|FleetSession\.remember\(", t) or rel.endswith("DriveDebugApi.kt"), rel + " earns a session cookie")
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
}

fail=0
out=$(check "$ROOT") || { echo "RED: $out"; fail=1; }
[ $fail = 0 ] && echo "ok  the session method, its lease and Drive's source hold"

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
fresh() { rm -rf "$W/t"; mkdir "$W/t"; for f in "${F[@]}"; do mkdir -p "$W/t/$(dirname "$f")"; cp "$ROOT/$f" "$W/t/$f"; done; mkdir -p "$W/t/ac_cloud-drive/app/src/main/java/x"; }
mut() { # name file old new
  fresh
  python3 - "$W/t/$2" "$3" "$4" <<'PY' || { echo "FAIL  mutation '$1' does not apply"; fail=1; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if a not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
PY
  if check "$W/t" >/dev/null 2>&1; then echo "FAIL  survived: $1"; fail=1; else echo "ok  red: $1"; fi
}
A=$L/account/src/main/java/com/diegonmarcos/superapp/profile
mut "session branch loses its own permission re-check" $A/AccountData.kt "ctx.checkCallingPermission(FleetConfig.PERMISSION) == PackageManager.PERMISSION_GRANTED
                    val sess" "true
                    val sess"
mut "a disallowed caller gets a session" $A/AccountData.kt "if (!allowed) error(\"caller is not a fleet app\")" "if (false) error(\"x\")"
mut "answer refreshes before it checks the caller" $A/SessionLease.kt 'if (!allowed) return SignInContract.noSession(provider, SignInContract.State.UNKNOWN, "caller is not a fleet app")' 'keepFresh(probe)'
mut "the lease is persisted" $A/SessionLease.kt "@Volatile private var held: Held? = null" "@Volatile private var held: Held? = null; fun save(c: android.content.Context) = c.getSharedPreferences(\"s\", 0).edit().putString(\"c\", held?.cookie).apply()"
mut "the cookie is logged" $A/SessionLease.kt "fun forget() { held = null }" "fun forget() { android.util.Log.d(\"x\", held?.cookie.orEmpty()); held = null }"
mut "the probe follows redirects" $A/SessionProbe.kt "c.instanceFollowRedirects = false" "c.instanceFollowRedirects = true"
mut "the probe goes over http" $A/SessionProbe.kt 'u.protocol != "https"' 'u.protocol != "ftp"'
mut "Session.toString prints the cookie" $L/fleet-endpoints/src/main/kotlin/com/diegonmarcos/superapp/fleetendpoints/SignInContract.kt 'override fun toString(): String = "Session(provider=$provider,' 'override fun toString(): String = "Session(cookie=$cookie, provider=$provider,'
mut "Accounts page host stops feeding the lease" $A/AccountPages.kt "override fun onWebSession(cookie: String) { SessionLease.remember(cookie) }" ""
mut "Providers page host stops feeding the lease" $A/AuthProvidersPage.kt "override fun onWebSession(cookie: String) { SessionLease.remember(cookie) }" ""
mut "FleetSession lease never expires" $L/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/FleetSession.kt "if (l.expiresAtMs <= clock())" "if (false)"
mut "Drive stops reading Cloud Account" ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/DriveSessionSource.kt "AccountClient.session(app, SignInContract.PROVIDER_AUTHELIA)" "SignInContract.noSession(\"authelia\", SignInContract.State.UNKNOWN, \"\")"
mut "source never installed" ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/MainActivity.kt "DriveSessionSource.install(this)" "Unit"
mut "coordinator reads the cookie without a sync" ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitSyncCoordinator.kt "FleetSession.fresh()" "FleetSession.cookie"
fresh; echo 'class X { fun f(c: android.webkit.CookieManager) = c.getCookie("x"); fun g(h: Any) { onWebSession("a") } }' > "$W/t/ac_cloud-drive/app/src/main/java/x/X.kt"
if check "$W/t" >/dev/null 2>&1; then echo "FAIL  survived: Drive earns a cookie itself"; fail=1; else echo "ok  red: Drive earns a cookie itself"; fi
[ $fail = 0 ] && echo "test-account-session: all checks passed" || { echo "test-account-session: FAILED"; exit 1; }
