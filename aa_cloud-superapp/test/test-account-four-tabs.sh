#!/usr/bin/env bash
# Tester (#1693, replaces the #778 / Cloud Account redesign checks of the same name, which moved to
# ac_cloud-account/test/test-account-four-tabs.sh with libs:account): the SuperApp holds NO Account page.
#   N1 libs:account is linked nowhere in this app: not in build.json::modules or any module list, not in
#      app/build.gradle, and no source here names its classes (profile.*, settings.ConfigsPrefs/AccountVault,
#      AccountHost / AccountData / AccountModel / VaultCockpit / ProfilePrefs / ProfileSync / AccountDebugApi).
#   N2 the Account entry is ONE link: AccountHandoff opens Cloud Account when installed, otherwise offers its
#      install and says so (never a crash, never an embedded page).
#   N3 what the app needs from Cloud Account it reads over Cloud Account's provider (AccountClient), and the
#      Import configs shortcut opens Cloud Account's Import page.
#   N4 the four-tab assertions live with Cloud Account.
# Every rule is mutation-proved; each mutation is checked to have APPLIED.
set -uo pipefail
APP="${SA_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
BJ="$APP/build.json"; GR="$APP/app/build.gradle"; SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
HO="$SRC/apps/AccountHandoff.kt"; SH="$SRC/ShellActivity.kt"
for f in "$BJ" "$GR" "$HO" "$SH"; do [ -f "$f" ] || { echo "FAIL: $f missing - this tester is unrun, not passing"; exit 1; }; done
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

FORBID='superapp\.profile\.|superapp\.settings\.(ConfigsPrefs|AccountVault|MailSignatures)|\b(AccountHost|AccountData|AccountModel|VaultCockpit|ProfilePrefs|ProfileSync|AccountDebugApi|ConnectWays|AccountConnectPage)\b'
n1() {   # $1 = build.json, $2 = app/build.gradle, $3 = source dir
    jq -e '(.modules | has("libs:account") | not) and ([.. | strings | select(. == "libs:account")] | length == 0)' "$1" >/dev/null \
        || { echo "build.json still declares libs:account"; return 1; }
    grep -qF "project(':libs:account')" <<<"$(grep -vE '^\s*//' "$2")" && { echo "app/build.gradle still links libs:account"; return 1; }
    local hit f; hit=""
    while IFS= read -r f; do grep -qE "$FORBID" <<<"$(grep -vE '^\s*(//|\*|/\*)' "$f")" && { hit=$f; break; }; done < <(grep -rlE "$FORBID" --include='*.kt' "$3")
    [ -n "$hit" ] && { echo "$hit names a libs:account class"; return 1; }
    [ -e "$3/settings/ImportConfigsFragment.kt" ] && { echo "the Import page is back in the SuperApp"; return 1; }
    return 0
}
echo "== N1: no libs:account in the SuperApp =="
msg=$(n1 "$BJ" "$GR" "$SRC") && ok "N1: libs:account is neither declared, linked nor named here" || bad "N1: $msg"
echo "-- N1-mutation --"
jq '.modules["libs:account"] = {"dir": "../ab_cloud-libs-shared/libs/account"}' "$BJ" > "$TMP/bj.json"
n1 "$TMP/bj.json" "$GR" "$SRC" >/dev/null && bad "N1-mutation: libs:account declared again was NOT caught" || ok "N1-mutation: libs:account declared again -> RED"
{ cat "$GR"; echo "dependencies { implementation project(':libs:account') }"; } > "$TMP/b.gradle"
n1 "$BJ" "$TMP/b.gradle" "$SRC" >/dev/null && bad "N1-mutation: libs:account linked again was NOT caught" || ok "N1-mutation: libs:account linked again -> RED"
mkdir -p "$TMP/src/x"; cp -r "$SRC/." "$TMP/src/"; echo 'val m = com.diegonmarcos.superapp.profile.AccountModel.get(ctx)' > "$TMP/src/x/Back.kt"
n1 "$BJ" "$GR" "$TMP/src" >/dev/null && bad "N1-mutation: a libs:account class named again was NOT caught" || ok "N1-mutation: a libs:account class named again -> RED"

n2() {   # $1 = AccountHandoff.kt
    local c; c=$(grep -vE '^\s*(//|\*)' "$1")
    grep -qF 'fun installed(ctx: Context): Boolean = AccountClient.installed(ctx)' <<<"$c" || { echo "the hand-off does not ask whether Cloud Account is installed"; return 1; }
    grep -qF 'if (installed) OpenCloudAccount() else InstallCloudAccount()' <<<"$c" || { echo "the page is not 'open it, or offer its install'"; return 1; }
    grep -qF 'AppInstall.start(ctx, PKG, LABEL).message' <<<"$c" || { echo "a missing Cloud Account is not offered for install with a sentence"; return 1; }
    grep -qF '}.getOrDefault(false)' <<<"$c" || { echo "opening Cloud Account can throw"; return 1; }
    return 0
}
echo "== N2: the Account entry is one link to Cloud Account =="
msg=$(n2 "$HO") && ok "N2: Account opens Cloud Account, or offers its install, and never throws" || bad "N2: $msg"
sed 's/if (installed) OpenCloudAccount() else InstallCloudAccount()/OpenCloudAccount()/' "$HO" > "$TMP/h.kt"
cmp -s "$HO" "$TMP/h.kt" && bad "N2-mutation did not apply" || { n2 "$TMP/h.kt" >/dev/null && bad "N2-mutation: no install offer was NOT caught" || ok "N2-mutation: missing-app state dropped -> RED"; }

n3() {   # $1 = ShellActivity.kt, $2 = source dir
    grep -qF 'AccountHandoff.openAt(this, "connect", "import-configs")' "$1" || { echo "Import configs does not open Cloud Account's Import page"; return 1; }
    for f in cloud/FleetBearerProvider.kt notificationcenter/AiBadgeService.kt devcontrol/XrayExport.kt; do
        grep -qF 'AccountClient.secret(ctx, "fleet.bearer")' "$2/$f" || { echo "$f does not read the bearer from Cloud Account"; return 1; }
    done
    grep -qF 'AccountClient.profile(ctx)' "$2/launcher/HomeDrawerFragment.kt" || { echo "the drawer badge does not read Cloud Account's profile"; return 1; }
    grep -qF 'AccountClient.meshDeviceAddresses(ctx)' "$2/network/mesh/AndroidMeshPort.kt" || { echo "the Cloud-preset guard does not ask Cloud Account for the picked device"; return 1; }
    return 0
}
echo "== N3: Cloud Account's data comes over its provider =="
msg=$(n3 "$SH" "$SRC") && ok "N3: bearer, profile, picked device and Import configs all go to Cloud Account" || bad "N3: $msg"
sed 's/AccountHandoff.openAt(this, "connect", "import-configs")/Unit/' "$SH" > "$TMP/s.kt"
n3 "$TMP/s.kt" "$SRC" >/dev/null && bad "N3-mutation: Import configs unlinked was NOT caught" || ok "N3-mutation: Import configs unlinked -> RED"

echo "== N4: the four-tab assertions live with Cloud Account =="
[ -f "$APP/../ac_cloud-account/test/test-account-four-tabs.sh" ] && ok "N4: ac_cloud-account/test/test-account-four-tabs.sh holds them" || bad "N4: the Account page assertions are gone"

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
