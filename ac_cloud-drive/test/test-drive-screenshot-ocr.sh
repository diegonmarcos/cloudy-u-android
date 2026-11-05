#!/usr/bin/env bash
# #1507 Screenshot OCR agent: the declaration, the rename, the sidecar contract, the battery rules.
#   S1 the agent is declared in assets/agents.json as "Screenshot OCR" and the title string says so (no old "Screenshots" name)
#   S2 libs:ocr is a declared, linked module and owns table detection, Markdown, JSON, naming and the sha256 skip
#   S3 the agent writes the pair through SidecarWriter and skips on SidecarIdempotency (md first, json last)
#   S4 battery: no PeriodicWorkRequest, a charging one-shot backfill, a content-URI watch, backoff floor >= 30 s, no sleep, delay or timer loops
#   S5 the agent row is dense KitStoreRow, with no sp literal and no fontSize number in agents/
#   S6 local ML only: no http(s) URL and no network client in agents/ or libs/ocr
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
D="$ROOT/ac_cloud-drive"; A="$D/app/src/main/java/com/diegonmarcos/clouddrive/agents"; L="$ROOT/ab_cloud-libs-shared/libs/ocr/src/main/java/com/diegonmarcos/superapp/ocr"
fails=0; ok(){ echo "  PASS  $1"; }; bad(){ echo "  FAIL  $1"; fails=$((fails+1)); }
grep -q '"name": "Screenshot OCR"' "$D/app/src/main/assets/agents.json" && grep -q '<string name="agent_shots_title">Screenshot OCR</string>' "$D/app/src/main/res/values/strings.xml" && ok "S1 agent declared and titled Screenshot OCR" || bad "S1 agent not declared as Screenshot OCR"
grep -q '>Screenshots<' "$D/app/src/main/res/values/strings.xml" && bad "S1 old name still in strings" || ok "S1 no old name"
grep -q '"libs:ocr"' "$D/build.json" && grep -q "project(':libs:ocr')" "$D/app/build.gradle" && ok "S2 libs:ocr declared and linked" || bad "S2 libs:ocr not wired"
for f in TableDetector MarkdownFormatter SidecarJson SidecarNaming SidecarIdempotency; do [ -f "$L/$f.kt" ] || bad "S2 $f.kt missing"; done; ok "S2 the lib files exist"
grep -q 'SidecarIdempotency.isCurrent' "$A/SidecarWriter.kt" && grep -q 'SidecarWriter.write' "$A/ScreenshotAgent.kt" && ok "S3 the pass writes the pair and skips current ones" || bad "S3 pass does not use the sidecar rules"
awk '/atomic\(SidecarNaming.md/{m=NR} /atomic\(SidecarNaming.json/{j=NR} END{exit !(m && j && m<j)}' "$A/SidecarWriter.kt" && ok "S3 md written before json" || bad "S3 json before md"
W="$A/ScreenshotAgentWorker.kt"
grep -q 'PeriodicWorkRequestBuilder' "$W" && bad "S4 a periodic request is back" || ok "S4 no periodic request"
grep -q 'setRequiresCharging(true)' "$W" && grep -q 'addContentUriTrigger' "$W" && ok "S4 charging backfill and content-URI watch" || bad "S4 backfill or watch missing"
n=$(grep -o 'BACKOFF_SECONDS = [0-9]*' "$W" | grep -o '[0-9]*$'); [ "${n:-0}" -ge 30 ] && ok "S4 backoff floor ${n}s" || bad "S4 backoff below 30 s"
grep -nE 'Thread\.sleep|delay\(|postDelayed|Timer\(' "$A"/*.kt | grep -v '^.*//' >/dev/null && bad "S4 a polling loop in agents/" || ok "S4 no polling loop"
grep -q 'KitStoreRowLine' "$A/ScreenshotAgentPage.kt" && grep -q 'KitStoreQuick' "$A/ScreenshotAgentPage.kt" && ok "S5 the agent row is a KitStoreRow" || bad "S5 no KitStoreRow"
grep -nE '[0-9]\.sp\b|fontSize *= *[0-9]' "$A"/*.kt >/dev/null && bad "S5 sp literal in agents/" || ok "S5 no sp literal"
grep -rnE 'https?://|HttpURLConnection|OkHttp' "$A" "$L" | grep -v '^\S*:[0-9]*:\s*\*\|//' >/dev/null && bad "S6 a network reference" || ok "S6 no network reference"
[ $fails -eq 0 ] && echo "test-drive-screenshot-ocr: all checks passed" || { echo "test-drive-screenshot-ocr: $fails FAILED"; exit 1; }
