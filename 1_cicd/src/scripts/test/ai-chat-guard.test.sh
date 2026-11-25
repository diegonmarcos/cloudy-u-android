#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ ai-chat-guard.test — prove the guard FAILS, and prove it can     ║
# ║ pass, so a green result means something                          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard only ever watched succeeding is indistinguishable from `exit 0`.
# cloud-android-ai-chat-guard.py holds the fleet to ONE AI chat UI (libs:ai). This file builds
# a small fleet around the REAL policy (1_cicd/src/data/ai-chat-guard.json, so the exemptions
# are the ones CI runs), requires GREEN for a clean fleet and for each legitimate shape (a host
# wrapper around libs:ai's page, a messenger the policy exempts, a test that names the roles),
# then draws a second chat in an app six ways and requires RED each time, empties the owner two
# ways and requires RED, and removes every app and requires the guard to refuse to answer (2).
# Finally it runs the guard on the real tree.
#
# Nothing outside coreutils + python3.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-ai-chat-guard.py"
POLICY="$ROOT/1_cicd/src/data/ai-chat-guard.json"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK:?}"' EXIT

OWNER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["owner"])' "$POLICY")"
AIDIR="src/main/kotlin/com/diegonmarcos/superapp/ai"

# The fleet the policy expects to be green: the owner holds the chat (stubs carrying exactly the
# needles the policy names), a host app wraps it, a messenger is exempt, everything else is clean.
build_fixture() {
  local t="$1"
  python3 - "$POLICY" "$t" <<'PY'
import json, os, sys
policy, t = sys.argv[1], sys.argv[2]
cfg = json.load(open(policy))
for need in cfg["owner_must_hold"]:
    p = os.path.join(t, need["repo_file"]) if need.get("repo_file") else os.path.join(t, cfg["owner"], need["file"])
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write("\n".join(need["needles"]) + "\n")
PY
  mkdir -p "$t/ac_cloud-demo/app/src/main/java/demo"
  cat > "$t/ac_cloud-demo/app/src/main/java/demo/AssistantPages.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
// A host: it hands libs:ai's page its theme and engines. A wrapper named like a page is fine here.
@Composable
fun AssistantPage() {
    AiChatPage(state, theme)
}
EOF
  cat > "$t/ac_cloud-demo/app/src/main/java/demo/Plain.kt" <<'EOF'
package demo
fun subpageKind(k: String) = when (k) { "assistant" -> 1; "listing" -> 2; else -> 0 }
EOF
  mkdir -p "$t/ac_cloud-chat/app/src/main/java/mm"
  cat > "$t/ac_cloud-chat/app/src/main/java/mm/Thread.kt" <<'EOF'
package mm
import androidx.compose.runtime.Composable
@Composable
fun ChatScreen(m: Msg) { if (m.role == "user") Unit }
EOF
  mkdir -p "$t/ac_cloud-demo/app/src/test/java/demo"
  cat > "$t/ac_cloud-demo/app/src/test/java/demo/ShellTest.kt" <<'EOF'
package demo
class ShellTest { fun posts() = "http://gw/v1/chat/completions" + "X-Agent-Mode" ; val r = m.role == "assistant" }
EOF
}

# expect <exit code> <label> <fixture dir>
expect() {
  local want="$1" label="$2" dir="$3" out got
  out="$(python3 "$GUARD" "$dir" --policy "$POLICY" 2>&1)"; got=$?
  if [ "$got" -eq "$want" ]; then ok "$label (exit $got)"; else fail "$label: expected exit $want, got $got: $out"; fi
  LAST="$out"
}

# mutate <name>: a fresh clean fixture at $WORK/<name>
mutate() {
  local name="$1"
  rm -rf "${WORK:?}/${name:?}"; mkdir -p "${WORK:?}/${name:?}"; build_fixture "$WORK/$name"
}

contains() { [ "${1#*"$2"}" != "$1" ]; }

# ── GREEN: the clean fleet, and the shapes that must stay legal ──────────────
mutate clean
expect 0 "a clean fleet is green (host wrapper, exempt messenger, a test naming the roles)" "$WORK/clean"

# ── RED: a second chat UI in an app ──────────────────────────────────────────
mutate page
cat > "$WORK/page/ac_cloud-demo/app/src/main/java/demo/ChatPage.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun ChatPage() {}
EOF
expect 1 "an app declaring its own ChatPage is red" "$WORK/page"
contains "$LAST" "chat-composable" && ok "  ... naming the rule" || fail "  ... the message does not name the rule: $LAST"
contains "$LAST" "libs:ai" && ok "  ... and teaching the owner" || fail "  ... the message does not teach where the chat lives: $LAST"

mutate bubble
cat > "$WORK/bubble/ac_cloud-demo/app/src/main/java/demo/Bubbles.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun Row(msg: Msg) { if (msg.role == "assistant") Avatar() else Mine() }
EOF
expect 1 "a composable branching on role == \"assistant\" is red" "$WORK/bubble"

mutate whenroles
cat > "$WORK/whenroles/ac_cloud-demo/app/src/main/java/demo/Bubbles.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun Row(msg: Msg) {
    when (msg.kind) {
        "user" -> Mine()
        "assistant" -> Avatar()
    }
}
EOF
expect 1 "a composable whose when has user and assistant branches is red" "$WORK/whenroles"

mutate endpoint
cat > "$WORK/endpoint/ac_cloud-demo/app/src/main/java/demo/Talk.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun Talk() { post("https://openrouter.ai/api/v1/chat/completions") }
EOF
expect 1 "a composable file naming the model endpoint is red" "$WORK/endpoint"

mutate modeheader
cat > "$WORK/modeheader/ac_cloud-demo/app/src/main/java/demo/Talk.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun Talk() { headers["X-Agent-Mode"] = "goose" }
EOF
expect 1 "a composable file naming the gateway's mode header is red" "$WORK/modeheader"

mutate panel
mkdir -p "$WORK/panel/ab_cloud-libs-shared/libs/other/src/main/kotlin"
cat > "$WORK/panel/ab_cloud-libs-shared/libs/other/src/main/kotlin/Panel.kt" <<'EOF'
import androidx.compose.runtime.Composable
@Composable
private fun AssistantPanel() {}
EOF
expect 1 "a second chat panel in a shared lib is red" "$WORK/panel"

mutate hostbubble
cat > "$WORK/hostbubble/ac_cloud-demo/app/src/main/java/demo/AssistantPages.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun AssistantPage() {
    AiChatPage(state, theme)
    if (m.role == "user") Own()
}
EOF
expect 1 "a host that also draws bubbles is red (the wrapper waiver does not cover them)" "$WORK/hostbubble"

# ── RED: the owner stops holding the chat ────────────────────────────────────
mutate ownerfile
rm "$WORK/ownerfile/$OWNER/$AIDIR/AiPanel.kt"
expect 1 "an owner that lost a file is red" "$WORK/ownerfile"

mutate ownerneedle
python3 - "$WORK/ownerneedle/$OWNER/$AIDIR/AiPage.kt" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().replace("fun AiChatPage(", "fun SomethingElse(")
open(p, "w").write(s)
PY
expect 1 "an owner whose page is no longer there is red" "$WORK/ownerneedle"

# #1566 the contract is the materialised fleet-private copy at the repo root: lost or emptied, the owner no longer holds the chat
mutate nocontract
rm "$WORK/nocontract/fleet-private-data/ai.json"
expect 1 "a fleet without the materialised AI contract is red" "$WORK/nocontract"

mutate emptycontract
: > "$WORK/emptycontract/fleet-private-data/ai.json"
expect 1 "a materialised AI contract without its gateway is red" "$WORK/emptycontract"

mutate noowner
rm -rf "${WORK:?}/noowner/${OWNER:?}"
expect 1 "no owner tree at all is red" "$WORK/noowner"

# ── 2: nothing to scan is not a verdict ──────────────────────────────────────
mutate empty
rm -rf "${WORK:?}/empty/ac_cloud-demo" "${WORK:?}/empty/ac_cloud-chat"
# the owner tree lives under ab_cloud-libs-shared, whose only Kotlin is the owner's: skipped, so nothing is scanned
expect 2 "a fleet with no Kotlin outside the owner is refused, not passed" "$WORK/empty"

# ── the real tree ────────────────────────────────────────────────────────────
expect 0 "the real fleet is green" "$ROOT"

if [ "$FAILURES" -eq 0 ]; then
  echo "ai-chat-guard.test: all assertions passed"
else
  echo "ai-chat-guard.test: $FAILURES assertion(s) FAILED"
  exit 1
fi
