#!/usr/bin/env bash
# Tester: "turn Play Protect off" is a DECLARED Perms row with a MEASURED check
# (#632).
#
# WHY IT IS A ROW AT ALL. The fleet sideloads every app (#571 WE ARE THE STORE),
# and GmsCore's package verifier warns on, delays, or silently flags a sideloaded
# APK — a plausible contributor to #200 (Collabora installs silently with no
# error) and #163 (install stuck in downloading). It belongs in the setup
# checklist as a first-class item, not as advice in a paragraph.
#
# THE TWO WAYS A ROW LIKE THIS GOES WRONG, and both are what is asserted here:
#
#   1. APPENDED IN KOTLIN. Every other row on that page comes from a
#      declaration; a row hand-added in the fragment renders differently, is
#      invisible to the wizard, and is the #170/#102/#405 second-list defect.
#   2. A REMEMBERED FLAG instead of a read. Play Protect is DEVICE state — GMS
#      can re-arm it behind us and an uninstall does not reset it — so a stored
#      "done" is green on a phone that is scanning again (#622/#452).
#
# And one honesty requirement: Settings.Global.getInt needs a default, and the
# default is the stock-GMS value, so an ABSENT key reads exactly like a consented
# device. The row must be able to say it cannot verify instead of showing a tick
# it cannot justify.
#
# Nothing here touches a device: every assertion is over declared data or over
# source, which is all this container can honestly read.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }

FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt"
GRADLE="$APP/app/build.gradle"
# The engine that does the reading, resolved through build.json::modules like
# every other cross-module path in these testers.
shzdir="$(jq -r '.modules["libs:shizuku-adb-debug-tools"].dir // empty' "$BJ")"
[ -n "$shzdir" ] || { echo "ERROR: build.json::modules[libs:shizuku-adb-debug-tools].dir is not declared" >&2; exit 2; }
PV="$APP/app/src/main/java/com/diegonmarcos/superapp/system/PackageVerifier.kt"   # #1682 P3 step 4e: moved from the owner lib to the SuperApp (it writes through the broker client)
for f in "$FRAG" "$GRADLE" "$PV"; do
    [ -f "$f" ] || { echo "ERROR: not found: $f" >&2; exit 2; }
done

# ── T1 — the row is DECLARED, with a label and a remedy ──────────────────────
row="$(jq -c '[.ui.permissions.device[]? | select(.key == "play_protect_off")] | first // empty' "$BJ")"
if [ -n "$row" ]; then
    ok "T1 build.json::ui.permissions.device[] declares the play_protect_off row"
else
    bad "T1 no play_protect_off row in build.json::ui.permissions.device[] — the item is not in the checklist, or it was appended in Kotlin instead (#632 mutation 1)"
fi
if [ -n "$row" ] \
   && [ -n "$(printf '%s' "$row" | jq -r '.label // ""')" ] \
   && [ -n "$(printf '%s' "$row" | jq -r '.remedy // ""')" ]; then
    ok "T1b the declared row carries both a label and the remedy text"
else
    bad "T1b the declared row is missing its label or its remedy — the page would draw a row that cannot say what to do (#632)"
fi

# ── T2 — the page renders it FROM the declaration ────────────────────────────
if grep -F 'UI_PERMISSIONS_DEVICE_B64' "$GRADLE" >/dev/null \
   && grep -F 'buildJson.ui.permissions?.device' "$GRADLE" >/dev/null; then
    ok "T2 the declaration is baked into BuildConfig beside the other three perms lists"
else
    bad "T2 build.gradle does not bake ui.permissions.device[] — the declaration cannot reach the page, so any row shown is hardcoded (#632 mutation 1)"
fi
if grep -F 'UI_PERMISSIONS_DEVICE_B64' "$FRAG" >/dev/null \
   && grep -E '\+ roles \+ device' "$FRAG" >/dev/null; then
    ok "T2b the Perms page builds its rows from the declaration and joins them to the page items"
else
    bad "T2b the Perms page no longer reads the declared device rows — a row that is not read is a row that got appended in Kotlin (#632 mutation 1)"
fi
# And it must not ALSO carry a hand-written Play Protect page item: two rows for
# one fact is the defect the declaration exists to prevent.
if [ "$(grep -c 'PageItem("Play Protect' "$FRAG")" -eq 0 ]; then
    ok "T2c no hardcoded Play Protect page item sits beside the declared one"
else
    bad "T2c a hand-written Play Protect PageItem is back — the row is stated twice and the two can disagree (#632 mutation 1)"
fi

# ── T3 — the check is a REAL system read ────────────────────────────────────
if grep -F 'PackageVerifier.readable(ctx)' "$FRAG" >/dev/null \
   && grep -F 'PackageVerifier.state(ctx)' "$FRAG" >/dev/null; then
    ok "T3 the row's state comes from PackageVerifier, which reads Settings.Global"
else
    bad "T3 the row's state no longer comes from a system read — a remembered flag is green on a phone GMS has re-armed (#632 mutation 2)"
fi
if grep -F 'Settings.Global.getString(ctx.contentResolver, key)' "$PV" >/dev/null \
   && grep -F 'Settings.Global.getInt(ctx.contentResolver, key, def)' "$PV" >/dev/null; then
    ok "T3b PackageVerifier reads the keys off Settings.Global, both for the value and for presence"
else
    bad "T3b PackageVerifier stopped reading Settings.Global — whatever the row shows is no longer measured (#632 mutation 2)"
fi
# No preference store anywhere near this row's measurement.
if ! grep -E 'private fun deviceState' -A 14 "$FRAG" \
     | grep -E 'SharedPreferences|ConfigsPrefs|getBoolean\(' >/dev/null; then
    ok "T3c the row consults no stored flag"
else
    bad "T3c a preference read appeared in the measurement — that is the remembered value the rule forbids (#622/#452, #632 mutation 2)"
fi

# ── T4 — it can say it cannot verify, and that is not a tick ────────────────
if grep -F 'cannot verify on this device' "$FRAG" >/dev/null \
   && grep -E 'null to "— cannot verify' "$FRAG" >/dev/null; then
    ok "T4 an unverifiable row reports cannot-verify rather than a granted state"
else
    bad "T4 the row has no cannot-verify answer — an absent key reads like a consented device, so a tick there is fabricated (#632)"
fi
if grep -E 'fun readable\(ctx: Context\): Boolean' "$PV" >/dev/null; then
    ok "T4b PackageVerifier.readable exists, so absent and stock can be told apart"
else
    bad "T4b PackageVerifier.readable is gone — getInt's default is the stock value, so nothing can distinguish it from a real read (#632)"
fi

# (T5 — the #622 wizard's second reading of this row — is gone with the wizard, #778:
#  Account ▸ Cloud Constellation Setup and its step list were deleted, so the Perms row
#  is the one place Play Protect is measured.)

# ── T6 — the action takes the user to the toggle and always says something ──
if grep -F 'com.google.android.gms.security.settings.VerifyAppsSettingsActivity' "$FRAG" >/dev/null; then
    ok "T6 the row deep-links to the Play Protect settings activity"
else
    bad "T6 no deep link to Play Protect settings — the row states a remedy with no way to reach it (#616 pattern, #632)"
fi
if grep -E 'fun openDeviceRemedy' -A 40 "$FRAG" | grep -F 'Nothing on this device answers' >/dev/null; then
    ok "T6b when nothing resolves, the action says so instead of doing nothing quietly"
else
    bad "T6b the remedy action can end silently — indistinguishable from a dead button (silence guard, #632)"
fi
# The app must not claim it flipped it: no write path in this row.
if ! grep -E 'fun openDeviceRemedy' -A 40 "$FRAG" | grep -F 'setScanning' >/dev/null; then
    ok "T6c the row does not pretend to flip Play Protect itself"
else
    bad "T6c the declared row calls setScanning — it needs WRITE_SECURE_SETTINGS, so on an unpaired phone it would fail while looking like a toggle (#632)"
fi

echo
echo "perms-play-protect: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
