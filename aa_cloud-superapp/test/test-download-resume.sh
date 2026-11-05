#!/usr/bin/env bash
# Tester: the constellation download path — resume, stall watchdog, and the
# four states a user must be able to tell apart.
#
# THE FAILURE THIS EXISTS TO KEEP FIXED. Installing "Cloud Office"
# (ghcr.io/diegonmarcos/cloud-office, ONE 278,215,660-byte layer — eight times
# the next largest app in the fleet) sat on "downloading" forever: no error, no
# progress. Three things combined to produce that, all of them invisible at the
# 25 MB every other app ships at:
#
#   1. No resume. Both download loops opened their target at byte zero, so
#      every interruption — screen lock, cell handover, the process going to
#      cached — threw away everything already fetched. A 265 MB transfer on a
#      phone WILL be interrupted; one that restarts each time never finishes.
#   2. A swallowing catch. ReleaseSource wrapped the whole download in
#      `runCatching { … }.getOrNull()`, so a timeout produced no log and no
#      state change; control fell through to GHCR, which republished
#      Downloading(0, …) and began the same 265 MB again.
#   3. No terminal state. Fleet.install threw and told UpdateProgress nothing,
#      so the progress row kept rendering its last Downloading frame forever.
#
# Static assertions + a LIVE check that the two CDNs actually honour the Range
# requests the resume logic depends on.
#
# Usage: ./test-download-resume.sh   (live section needs network)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # → ~/git/cloud-u-android
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }
# CODE ONLY. This file documents the constructs it removed, by name, in the
# comments explaining why they were removed — so a plain grep for a banned
# construct matches the very comment that proves it is gone. Strip comment
# lines first, and the assertion means what it says.
code()  { sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' "$ROOT/$1" 2>/dev/null; }
hasnt_code() { code "$1" | grep -qF "$2" && bad "$3 ($1)" || ok "$3"; }

LIB="ab_cloud-libs-shared/libs"
UPD="$LIB/updater/src/main/java/com/diegonmarcos/superapp/updater"
DL="$UPD/source/Download.kt"
SRC="$UPD/source/ApkSource.kt"
GHCR="$UPD/source/GhcrClient.kt"
FLEET="$UPD/Fleet.kt"
PROG="$UPD/UpdateProgress.kt"
OVL="$UPD/UpdateOverlayFragment.kt"
PAGE="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
# #785 the page's progress line is StoreStages.progress — the one derivation the
# bar and /api/store/progress share — so the unknown-size rule lives there.
STAGES="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreStages.kt"
CWORK="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationWorker.kt"
PREFS="$UPD/AutoUpdatePrefs.kt"

echo "== T1: ONE download loop, and it resumes with Range =="
[ -f "$ROOT/$DL" ] && ok "Download.kt exists" || bad "Download.kt missing"
has "$DL" 'setRequestProperty("Range", "bytes=$resumeFrom-")' "resume sends an HTTP Range request"
has "$DL" 'val append = code == 206'                          "206 appends; anything else restarts"
has "$DL" 'Content-Range'                                     "206 total comes from Content-Range, not the remainder length"
# The .part must SURVIVE a failure — deleting it is what made every retry
# restart from zero, and it is the single line this whole fix turns on.
hasnt_code "$DL" 'finally {'                                  "no finally-delete of the partial (that is what is being resumed)"
has "$DL" 'fun discard(target: File)'                         "an explicit discard for KNOWN-BAD bytes only"

echo "== T2: no second download loop crept back =="
# Exactly one file may contain the 64 kB copy loop. Three copies is how the
# guarantees drifted apart last time (only one chased redirects, only two
# throttled progress, none resumed). ApkIntegrity.kt hashes a file; StoreDns.kt's
# (now DnsLadder.kt's) pipe() relays the bytes of a local CONNECT tunnel between two sockets — neither
# downloads anything, so neither is a second download engine.
LOOPS=$(grep -rlF 'ByteArray(64 * 1024)' "$ROOT/$LIB/updater/src" "$ROOT/$LIB/appstore/src" 2>/dev/null \
        | grep -v 'ApkIntegrity.kt\|StoreDns.kt\|DnsLadder.kt\|RangeFetch.kt\|DownloadCache.kt' | wc -l)
# #1517 The Store's download engine (appstore dl/RangeFetch.kt) is the one loop of the engine path, with validators (If-Range, ETag);
# (DownloadCache.kt only hashes a finished file.) Download.kt stays the loop of the external-app / blob path until that moves onto the engine. Never a third.
[ -f "$ROOT/$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/dl/RangeFetch.kt" ] || bad "the engine's RangeFetch.kt is missing"
[ "$LOOPS" -eq 1 ] && ok "exactly one streaming copy loop outside the engine (Download.kt)" \
                   || bad "found $LOOPS streaming copy loops — expected 1"
hasnt_code "$SRC" 'target.outputStream()'                     "ReleaseSource no longer opens its own output stream"
has "$SRC" 'Download.toFile('                                 "ReleaseSource downloads through the shared engine"
has "$GHCR" 'Download.toFile('                                "GhcrClient.blob downloads through the shared engine"

echo "== T3: a stalled transfer FAILS, and says how far it got =="
has "$DL" 'class Stalled'                                     "a stall is its own failure type"
has "$DL" 'const val STALL_MS'                                "the watchdog window is a named constant"
has "$DL" 'no new data for '                                  "the stall message names the silence"
# A READ timeout, never a call timeout: a whole-transfer deadline would kill a
# big file on a healthy connection purely for being big.
has "$DL" 'readTimeout = READ_MS'                             "bounded by a read timeout"
hasnt_code "$DL" 'callTimeout'                                "no whole-call timeout (that would cap the transfer by size)"

echo "== T4: the swallowing catch is gone, and failures reach the user =="
# `.getOrNull()` is the swallow, not `runCatching` — the phrase still appears
# in the comment that records why it was removed.
hasnt_code "$SRC" '.getOrNull()'                              "ReleaseSource no longer swallows Throwable via runCatching{}.getOrNull()"
has "$SRC" 'catch (c: java.util.concurrent.CancellationException)' "a cancel is rethrown, not treated as a source failure"
has "$FLEET" 'UpdateProgress.State.Failed(' "Fleet publishes a terminal Failed state"
has "$FLEET" 'declined.joinToString'                          "the failure names which source declined and why"

echo "== T5: the four states are four different pictures =="
has "$PROG" 'data class Waiting'                              "waiting-on-a-constraint is its own state"
has "$PROG" 'is State.Waiting -> true'                        "a deferral never throws a full-screen overlay"
has "$PREFS" 'fun deferredReason'                             "one place owns 'why is the automatic pass held back'"
has "$CWORK" 'UpdateProgress.State.Waiting(why)'              "the auto pass SAYS it deferred instead of returning silently"
# Unknown total must read as unknown on BOTH surfaces — a determinate bar
# pinned at 0% while bytes flow is the exact ambiguity being removed.
has "$STAGES" 'total size unknown'                            "Store progress line says so when the length is unknown"
has "$OVL"  'total size unknown'                              "overlay says so when the length is unknown"
has "$STAGES" 'if (state.total > 0) state.percent else -1'    "Store progress line only carries a percentage it actually has"

echo "== T6: only ONE metered policy, not one per worker =="
# The CALL, not the constant name — the constant is also named in comments.
COPIES=$(grep -rlF 'cm.getNetworkCapabilities(it)' "$ROOT/$LIB/updater/src" "$ROOT/$LIB/appstore/src" 2>/dev/null | wc -l)
[ "$COPIES" -eq 1 ] && ok "one isMetered implementation (AutoUpdatePrefs)" \
                    || bad "found $COPIES metered checks — they disagreed once already"

echo "== T7: LIVE — the CDNs honour the Range requests resume depends on =="
# THIS PROBE REPORTS. IT DOES NOT GATE. Everything above is a static assertion
# about OUR code and stays fatal; this one asks a live question of GitHub's
# release CDN, and the fleet's ruling on that is settled: a third party's live
# behaviour must never veto the owner's APK. It had already cost one publish as
# a pricing page, and a Go build wired the same way cost a day and a half of
# APKs on 2026-09-09. The GHCR branch below was always a SKIP; the asymmetry
# with this branch was the bug, not a decision.
#
# It cost another publish before being fixed: on 2026-09-10 this failed with
# "release CDN did not honour Range (got: nothing)" and blocked the SuperApp APK
# (run 34473051246). The CDN was fine. Cloud-Sheets.apk is simply no longer an
# asset on the `latest` release, so the URL 404s — a rotted hardcoded URL
# reporting itself as a CDN regression, in a step that could stop the phone
# getting an APK.
#
# THE HARDCODED URL IS GONE, and it did not need the API call the old note here
# feared. Both values are already DERIVED next door: regen.sh builds
# data/constellation-fleet.json from each app's build.json::release, so
# .release_url and .ghcr_page are this app's own declaration, one file away and
# with no network. That is what rotted on 2026-09-10 — the URL named
# Cloud-Sheets.apk long after the shelf stopped carrying it — and what would
# have rotted again on the ac_cloud-sheets -> ac_cloud-office rename.
# The fleet id is derived with regen.sh's own rule (basename sans ac_cloud-),
# so the directory and the id cannot drift apart.
OFFICE_DIR="ac_cloud-office"           # the fleet's largest artefact, by an order of magnitude
OFFICE_ID="${OFFICE_DIR#ac_cloud-}"
FLEET_JSON="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"
REL=""; GHCR_REPO=""
# FAIL CLOSED, AND ON OUR OWN DATA. The probe below stays non-fatal because it
# asks a THIRD PARTY a live question. Deriving the URL does not: the fleet
# manifest is this repo's own generated file, so an empty answer here means our
# id rule or our manifest moved, and reporting SKIP for that would be the same
# silence that let a rotted URL sit here since 2026-09-10. jq absent is the same
# class of lie, so it is named rather than assumed.
if ! command -v jq >/dev/null 2>&1; then
  bad "jq unavailable — cannot derive the probe URL from $FLEET_JSON, so T7 asserted nothing"
elif [ ! -f "$FLEET_JSON" ]; then
  bad "$FLEET_JSON missing — cannot derive the probe URL"
else
  REL=$(jq -r --arg id "$OFFICE_ID" '.apps[] | select(.id == $id) | .release_url // ""' "$FLEET_JSON")
  GHCR_REPO=$(jq -r --arg id "$OFFICE_ID" '.apps[] | select(.id == $id) | ((.namespace // "") + "/" + (.image // ""))' "$FLEET_JSON")
  # TEST THE TWO VALUES SEPARATELY. The first version of this check globbed the
  # pair CONCATENATED — case "$REL:$GHCR_REPO" in ... *:/* ) — and `*:/*`
  # matches the "https://" inside the release URL's own scheme, so a perfectly
  # good pair took the failure branch and reddened the SuperApp's publish. A
  # guard whose pattern matches its own input is not a guard.
  if [ -z "$REL" ]; then
    bad "fleet entry '$OFFICE_ID' has no release_url in $FLEET_JSON — $OFFICE_DIR was renamed or regen.sh changed its id rule, and this probe would have reported on nothing"
    REL=""
  elif [ -z "${GHCR_REPO%%/*}" ] || [ -z "${GHCR_REPO#*/}" ]; then
    bad "fleet entry '$OFFICE_ID' has no namespace/image pair in $FLEET_JSON (got '$GHCR_REPO') — the GHCR half of this probe would have asked about nothing"
    REL=""
  fi
fi
if [ -n "$REL" ] && command -v curl >/dev/null; then
  HEADS=$(curl -sIL --max-time 45 -H 'Range: bytes=1000-1099' "$REL" | tr -d '\r')
  CR=$(printf '%s' "$HEADS" | grep -i '^content-range:' | tail -1)
  LAST=$(printf '%s' "$HEADS" | grep -i '^HTTP/' | tail -1)
  case "$CR" in
    *bytes\ 1000-1099/*) ok "release CDN: 206 with $CR" ;;
    *)
      case "$LAST" in
        *404*) echo "  SKIP: $REL is not an asset on the latest release ($LAST) — the URL has rotted, so this says nothing about the CDN" ;;
        *)     echo "  SKIP: release CDN Range unconfirmed ($LAST, content-range: ${CR:-none}) — reported, not fatal" ;;
      esac ;;
  esac
  TOK=$(curl -s --max-time 20 "https://ghcr.io/token?service=ghcr.io&scope=repository:${GHCR_REPO}:pull" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -n "$TOK" ]; then
    DIG=$(curl -s --max-time 20 -H "Authorization: Bearer $TOK" \
          -H "Accept: application/vnd.oci.image.manifest.v1+json" \
          "https://ghcr.io/v2/${GHCR_REPO}/manifests/latest" \
          | sed -n 's/.*"digest":"\(sha256:[a-f0-9]*\)".*/\1/p' | tail -1)
    # A ranged GET, not HEAD: the blob endpoint answers 307 to a presigned URL
    # and that redirect does not answer HEAD with a Content-Range.
    GR=$(curl -sL --max-time 45 -D - -o /dev/null \
         -H "Authorization: Bearer $TOK" -H 'Range: bytes=1000-1099' \
         "https://ghcr.io/v2/${GHCR_REPO}/blobs/$DIG" \
         | tr -d '\r' | grep -i '^content-range:' | tail -1)
    case "$GR" in
      *bytes\ 1000-1099/*) ok "GHCR blob: 206 with $GR" ;;
      *)                   echo "  SKIP: GHCR blob Range unconfirmed (got: ${GR:-nothing})" ;;
    esac
  else echo "  SKIP: no anonymous GHCR token (offline or rate-limited)"; fi
else echo "  SKIP: curl unavailable or no derivable URL"; fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
