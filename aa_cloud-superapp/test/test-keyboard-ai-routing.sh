#!/usr/bin/env bash
# The keyboard's LLM path is ONE registry (build.json::keyboard_ai, baked into
# BuildConfig.AI_ROUTING_B64) consumed by Text Enhancements, Grammar "ai" mode
# and the ENHANCE toolbar key. This is the static proof that the wiring is
# complete WITHOUT a gradle build (this runner cannot build):
#
#   T1  keyboard_ai is well-formed: every provider's default_model is in its own
#       models list, default_provider / default_style resolve, styles carry prompts
#   T2  libs/keyboard/build.gradle bakes the block (AI_ROUTING_B64)
#   T3  ENHANCE is wired end-to-end: ToolbarKey enum, KeyCode, all three
#       KeyboardIconsSet maps, InputLogic dispatch, default first-row toolbar list
#   T4  every R.string the new screens/engines reference exists in strings.xml,
#       and the icon + per-provider title strings exist for each registry provider
#   T5  settings surface: nav destinations, MainSettingsScreen entries, container
#       registration, Grammar mode "ai" in both engine and screen
#   T7  pricing: every model entry is {id, name, open?, params_b?, quant?,
#       trained_for?, note?, prompt?, completion?}; a provider with catalog_url has
#       pricing_as_of + baked prices; and, when the catalog is reachable, every id
#       exists there and every baked price is a sane number within x$DRIFT_FACTOR of the live
#       one (fatal); 'open' and a re-price beyond 1 % are DRIFT: a warn: line while
#       pricing_as_of is inside DRIFT_MAX_AGE_DAYS, fatal once it is older (the daily
#       refresh in the "Test -> AI model registry" workflow rewrites them)
#   T9  model catalogues (ai-registries.json::catalogues, e.g. Cloud Search's model page): one row per
#       provider, an offline snapshot on every priced row, and, live, every curated id still listed by
#       the OpenRouter catalogue (models / embeddings / images / videos) that prices its section
#   T8  the AI Model Routing table: slugs non-empty and unique, every price renders
#       #.#### dollars per million -- the provider's own unit, unscaled, with a
#       published per-token price pinned to the exact cell it must produce so no
#       stray factor can creep back onto that path -- size sorts numerically
#       and not lexically, a row missing size/quant/category renders the unknown
#       marker instead of a blank, ONE LINE PER MODEL (one no-wrap Cell per column,
#       one Row per model, header and rows declaring the same eight columns in the
#       same order over one shared scroll state, each column wide enough for its
#       longest value in the registry), and routing still resolves by stored id so
#       the re-sort cannot move which model Enhance or Grammar calls. Online it also
#       re-reads OpenRouter for the quant and trained_for values baked into
#       build.json — that is the refresh procedure for the columns with no runtime
#       refresh (build.json keyboard_ai._doc_refresh)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$APP/../ab_cloud-libs-shared"
K="$LIBS/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
# Same, but the needle is a LITERAL: the Kotlin below is full of (){}?.$ that a regex would eat.
hasf() { grep -qF -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }

echo "== keyboard AI routing: registry, ENHANCE key, settings =="

# T1 registry
if python3 - "$LIBS/build.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
p = d["providers"]; s = d["styles"]
assert d["default_provider"] in p, "default_provider not a provider"
assert d["default_style"] in s, "default_style not a style"
for pid, pv in p.items():
    assert pv["default_model"] in [m["id"] for m in pv["models"]], f"{pid}: default_model not in models"
    assert pv["url"].startswith("http"), f"{pid}: url"
    assert isinstance(pv["needs_token"], bool), f"{pid}: needs_token"
for sid, sv in s.items():
    assert sv["prompt"].strip() and sv["label"].strip(), f"{sid}: empty"
assert "grammar" in s, "Grammar 'ai' mode needs the 'grammar' style"
assert int(d["timeout_ms"]) <= 60000, "keyboard cap must stay far below the 180 s bridge timeout"
EOF
then ok "T1 keyboard_ai registry consistent"; else bad "T1 keyboard_ai registry"; fi

# T2 gradle bake
has "$LIBS/libs/keyboard/build.gradle" 'AI_ROUTING_B64' "T2 build.gradle bakes AI_ROUTING_B64"
has "$LIBS/libs/keyboard/build.gradle" 'keyboard_ai' "T2 build.gradle reads keyboard_ai"

# T3 ENHANCE wiring
has "$J/latin/utils/ToolbarUtils.kt" '^    ENHANCE' "T3 ToolbarKey.ENHANCE declared"
has "$J/latin/utils/ToolbarUtils.kt" 'ENHANCE -> KeyCode.ENHANCE' "T3 ToolbarKey.ENHANCE -> KeyCode.ENHANCE"
has "$J/keyboard/internal/keyboard_parser/floris/KeyCode.kt" 'const val ENHANCE' "T3 KeyCode.ENHANCE"
n=$(grep -c 'ToolbarKey.ENHANCE -> R.drawable.ic_toolbar_enhance' "$J/keyboard/internal/KeyboardIconsSet.kt")
[ "$n" = 3 ] && ok "T3 icon mapped in all 3 KeyboardIconsSet maps" || bad "T3 icon maps: $n/3"
has "$J/latin/inputlogic/InputLogic.java" 'case KeyCode.ENHANCE' "T3 InputLogic dispatches ENHANCE"
has "$J/latin/inputlogic/InputLogic.java" 'TextEnhancer.enhance' "T3 ENHANCE calls TextEnhancer"
row1=$(awk '/val default = listOf\(/{f=1} f{print} f && /^    \)/{exit}' "$J/latin/utils/ToolbarUtils.kt")
case "$row1" in
  *ENHANCE*TRANSLATE*|*TRANSLATE*ENHANCE*) ok "T3 ENHANCE and TRANSLATE in default first-row toolbar list" ;;
  *) bad "T3 ENHANCE not in defaultToolbarPref first row" ;;
esac
[ -f "$K/res/drawable/ic_toolbar_enhance.xml" ] && ok "T3 drawable exists" || bad "T3 ic_toolbar_enhance.xml missing"

# T4 strings + per-provider titles
for f in "$J/latin/AiRouter.kt" "$J/latin/TextEnhancer.kt" "$J/settings/screens/AiRoutingScreen.kt" \
         "$J/settings/screens/TextEnhanceScreen.kt" "$J/settings/screens/MainSettingsScreen.kt" "$J/settings/screens/GrammarCheckScreen.kt"; do
  grep -o 'R\.string\.[a-z_0-9]*' "$f"
done | sort -u | sed 's/R.string.//' | while read -r s; do
  grep -q "name=\"$s\"" "$K/res/values/strings.xml" || echo "$s"
done > /tmp/kb-ai-missing-strings.$$
[ -s /tmp/kb-ai-missing-strings.$$ ] && bad "T4 missing strings: $(tr '\n' ' ' < /tmp/kb-ai-missing-strings.$$)" || ok "T4 every referenced R.string exists"
rm -f /tmp/kb-ai-missing-strings.$$
for pid in $(python3 -c "import json,sys;print(' '.join(json.load(open(sys.argv[1]))['keyboard_ai']['providers']))" "$LIBS/build.json"); do
  has "$K/res/values/strings.xml" "name=\"ai_token_${pid}_title\"" "T4 provider '$pid' has its key title"
  has "$K/res/values/strings.xml" "name=\"ai_model_${pid}_title\"" "T4 provider '$pid' has its model title"
done
has "$K/res/values/strings.xml" 'name="enhance" tools:keep' "T4 toolbar key label 'enhance' kept for getStringResourceOrName"

# T5 settings surface
has "$J/settings/SettingsNavHost.kt" 'const val AiRouting' "T5 nav destination AiRouting"
has "$J/settings/SettingsNavHost.kt" 'const val TextEnhance' "T5 nav destination TextEnhance"
has "$J/settings/SettingsNavHost.kt" 'AiRoutingScreen(onClickBack' "T5 AiRoutingScreen routed"
has "$J/settings/SettingsNavHost.kt" 'TextEnhanceScreen(onClickBack' "T5 TextEnhanceScreen routed"
has "$J/settings/screens/MainSettingsScreen.kt" 'settings_screen_ai_routing' "T5 main menu: AI Model Routing"
has "$J/settings/screens/MainSettingsScreen.kt" 'settings_screen_enhance' "T5 main menu: Text Enhancements"
# order: Text Enhancements before Grammar; AI Model Routing after Voice transcript
awk '/settings_screen_enhance\)/{e=NR} /settings_screen_grammar\)/{g=NR} /settings_screen_voice_transcript\)/{v=NR} /settings_screen_ai_routing\)/{a=NR}
     END{exit !(e && g && v && a && e<g && v<a)}' "$J/settings/screens/MainSettingsScreen.kt" \
  && ok "T5 menu order: Enhance<Grammar, Voice<AI Routing" || bad "T5 menu order"
has "$J/settings/SettingsContainer.kt" 'createAiRoutingSettings(context)' "T5 container registers AI routing settings"
has "$J/settings/SettingsContainer.kt" 'createTextEnhanceSettings(context)' "T5 container registers enhance settings"
has "$J/latin/GrammarChecker.kt" '"ai" -> TextEnhancer.run' "T5 Grammar mode 'ai' routes through TextEnhancer"
has "$J/settings/screens/GrammarCheckScreen.kt" 'grammar_mode_ai) to "ai"' "T5 Grammar screen offers 'ai'"

# T7 pricing: registry shape, then live catalog cross-check (skipped, not failed, when offline)
if python3 - "$LIBS/build.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
assert int(d["catalog_ttl_ms"]) >= 3600000, "catalog_ttl_ms: re-fetching a 700 KB catalog more than hourly is waste"
for pid, pv in d["providers"].items():
    for m in pv["models"]:
        assert set(m) <= {"id", "name", "open", "params_b", "quant", "trained_for", "note", "prompt", "completion"}, f"{pid}/{m.get('id')}: unknown key"
        assert m["id"].strip(), f"{pid}: empty model id"
        assert ("prompt" in m) == ("completion" in m), f"{pid}/{m['id']}: prompt without completion or vice versa"
    if "catalog_url" in pv:
        assert pv["catalog_url"].startswith("https://"), f"{pid}: catalog_url must be https"
        assert pv.get("pricing_as_of", "").count("-") == 2, f"{pid}: pricing_as_of YYYY-MM-DD required with catalog_url"
        assert all("prompt" in m for m in pv["models"]), f"{pid}: every catalog model needs baked prices (offline fallback)"
        assert sum(1 for m in pv["models"] if m.get("open")) >= 5, f"{pid}: fewer than 5 open-weight models"
EOF
then ok "T7 model entries well-formed, catalog providers carry baked prices"; else bad "T7 pricing shape"; fi
ROOT="$(cd "$APP/.." && pwd)"
REGISTRIES="$APP/test/ai-registries.json"
CATALOG=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['keyboard_ai']['providers']['openrouter']['catalog_url'])" "$LIBS/build.json")

# ── WHICH ASSERTIONS MAY VETO A PUBLISH ───────────────────────────────────
# Everything above this line is about code in this repository, and stays fatal
# wherever it runs. Everything below reads OpenRouter's live catalogue, so its
# verdict is authored by a company that has never heard of this fleet.
#
# Those two must not share a gate. On 2026-09-10 they did, and it cost the
# owner's phone every APK for a day and a half: z-ai/glm-5.3-flash doubled in
# price overnight, T7 correctly noticed, the shell-tester step went red, and
# the SuperApp's launcher, Inboxes screen and notification fixes — none of
# which have anything to do with a price — were stranded in the repository.
# The publish gate that caught it was right; what was wrong was letting a
# third party's pricing page reach it at all.
#
# CLOUD_RELEASE_GATE is set by cloud-android-test-engine.sh when these testers
# run as the gate on a publish. It silences THESE lines only, and says so on
# stdout; run this script by hand, or from the scheduled "Test → AI model
# registry" workflow, and the live blocks are fatal exactly as before. That
# scheduled run is the one that catches a price the owner is being charged.
live_checks_are_advisory() { [ -n "${CLOUD_RELEASE_GATE:-}" ]; }

# ── WHAT A DRIFT IS ALLOWED TO DO ─────────────────────────────────────────
# OpenRouter re-prices models by the hour (deepseek-v4-flash-0731's input went 0.0047 -> 0.0131 ->
# 0.0137 $/M inside one day) and re-ranks categories without notice. A baked value is an OFFLINE
# FALLBACK - AiRouter.refreshPricing reads the live price on the phone - so a value that is merely a
# few days old is not a defect, and a check that turned red on every re-price was red more days than
# green and so told nobody anything. What stays fatal: an id OpenRouter no longer lists; a baked
# price that is not a sane number (negative, NaN); a price off by more than DRIFT_FACTOR in either
# direction (a unit or scaling bug - per token vs per million - not a re-price); and ANY drift once
# the registry's pricing_as_of is older than DRIFT_MAX_AGE_DAYS, which means the scheduled refresh
# (cloud-android-ai-registry-refresh.py, run by the "Test -> AI model registry" workflow every day)
# has stopped landing. A drift inside the window prints a `warn:` line and the refresh rewrites it.
export DRIFT_MAX_AGE_DAYS="${DRIFT_MAX_AGE_DAYS:-7}"
export DRIFT_FACTOR="${DRIFT_FACTOR:-10}"

if live_checks_are_advisory; then
  echo "  skip: T7 live catalog — release gate does not veto a publish on a third party's live state; the scheduled AI model registry monitor enforces this"
elif curl -sS --max-time 30 -o /tmp/kb-ai-catalog.$$ "$CATALOG" 2>/dev/null; then
  if python3 - "$ROOT" "$REGISTRIES" /tmp/kb-ai-catalog.$$ <<'EOF'
import datetime, json, math, os, sys
root, manifest, catalog = sys.argv[1], sys.argv[2], sys.argv[3]
max_age, factor = int(os.environ["DRIFT_MAX_AGE_DAYS"]), float(os.environ["DRIFT_FACTOR"])
cat = {m["id"]: m for m in json.load(open(catalog))["data"]}
bad, warn = [], []
# Every registry in the manifest, not just the keyboard's: two apps bake their own copy of this
# table, and checking one of them is how a stale row survives untouched in the other.
for r in json.load(open(manifest))["registries"]:
    pv = json.load(open(os.path.join(root, r["path"])))[r["key"]]["providers"]["openrouter"]
    age = (datetime.date.today() - datetime.date.fromisoformat(pv["pricing_as_of"])).days
    stale = age > max_age
    drift = bad if stale else warn
    for m in pv["models"]:
        c = cat.get(m["id"])
        if not c: bad.append(f"{r['label']} {m['id']}: not in catalog"); continue
        if bool(m.get("open")) != bool(c.get("hugging_face_id")): drift.append(f"{r['label']} {m['id']}: open={m.get('open', False)} but hugging_face_id={c.get('hugging_face_id')!r}")
        for k in ("prompt", "completion"):
            baked = m[k]
            if not isinstance(baked, (int, float)) or math.isnan(baked) or baked < 0:
                bad.append(f"{r['label']} {m['id']}: baked {k} {baked!r} is not a price"); continue
            # The catalogue publishes DOLLARS PER TOKEN, as a string; the registry is authored in
            # DOLLARS PER MILLION TOKENS. This *1e6 is the ONLY conversion on this path and it is
            # the same one AiRouter.refreshPricing applies to the live price, so a factor that
            # crept in on either side shows up here as a hundred-fold or million-fold disagreement
            # rather than as a plausible-looking number on the settings screen - and THAT stays fatal.
            live = float(c["pricing"][k]) * 1e6
            if live < 0: continue  # "varies" (a router): nothing to compare
            if baked > 0 and live > 0 and max(live / baked, baked / live) > factor:
                bad.append(f"{r['label']} {m['id']}: {k} baked {baked} vs live {live:.4f} $/M - off by more than x{factor:g}, a unit error, not a re-price")
            elif abs(live - baked) > 0.01 * max(live, baked):
                drift.append(f"{r['label']} {m['id']}: {k} baked {baked} vs live {live:.4f} $/M")
    if stale and any(x.startswith(r["label"] + " ") for x in bad):
        bad.append(f"{r['label']}: pricing_as_of {pv['pricing_as_of']} is {age} days old (window {max_age}) - the scheduled refresh has stopped landing")
for w in warn: print(f"  warn: T7 drift inside the {max_age}-day window (the scheduled refresh rewrites it): {w}")
assert not bad, "\n    ".join(bad)
EOF
  then ok "T7 live catalog: every registry id listed, every baked price a sane number within x$DRIFT_FACTOR, drift only inside the $DRIFT_MAX_AGE_DAYS-day window"; else bad "T7 live catalog cross-check"; fi
else
  echo "  skip: T7 live catalog unreachable ($CATALOG) — offline, baked prices unverified"
fi
rm -f /tmp/kb-ai-catalog.$$

# T8 the routing table: data shape, rendering, sort, and the routing guarantee
if python3 - "$LIBS/build.json" <<'EOF'
import json, re, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
# OpenRouter's own twelve categories. Not a taxonomy of ours: GET /api/v1/models?category=<c>
# rejects anything else with a 400 that lists exactly these.
CATEGORIES = {"programming", "roleplay", "marketing", "marketing/seo", "technology", "science",
              "translation", "legal", "finance", "health", "trivia", "academia"}
BITS = {"fp4": 4, "int4": 4, "mxfp4": 4, "nvfp4": 4, "fp6": 6, "fp8": 8, "int8": 8, "fp16": 16, "bf16": 16, "fp32": 32}
INF = float("inf")
def size_key(m): return m.get("params_b", INF)
def quant_key(m): return min((BITS.get(q, INF) for q in m.get("quant", [])), default=INF)

for pid, pv in d["providers"].items():
    ms = pv["models"]
    ids = [m["id"] for m in ms]
    names = [m["name"] for m in ms]
    assert all(i.strip() for i in ids), f"{pid}: a model row has an empty slug"
    assert len(set(ids)) == len(ids), f"{pid}: duplicate slug {sorted(i for i in ids if ids.count(i) > 1)}"
    assert all(n.strip() for n in names), f"{pid}: a model row has an empty short name"
    assert len(set(names)) == len(names), f"{pid}: duplicate short name {sorted(n for n in names if names.count(n) > 1)}"

    # Every price cell is US dollars per million tokens at four decimals, printed UNCHANGED from
    # the registry (AiRoutingScreen.usdPerMillionTokens). Four decimals is not a style choice: at
    # two, 0.065 / 0.07 / 0.075 $/M all render "0.07"; at three, 0.0399 and 0.04 $/M both render
    # "0.040" — each time the column hides a real price difference. A real cost that renders as
    # zero would read as free, so that is checked too.
    seen = {}
    for m in ms:
        for k in ("prompt", "completion"):
            if k not in m: continue
            cell = "%.4f" % m[k]
            assert re.fullmatch(r"\d+\.\d{4}", cell), f"{pid}/{m['id']}: {k} renders {cell!r}, not #.####"
            assert m[k] == 0 or float(cell) > 0, \
                f"{pid}/{m['id']}: {k}={m[k]} $/M renders as {cell} — the column is lying about a real cost"
            if cell in seen and seen[cell] != m[k]:
                raise AssertionError(f"{pid}: {m[k]} and {seen[cell]} $/M both render {cell} — the column hides a real price difference")
            seen[cell] = m[k]

    for m in ms:
        # params_b is only ever the number the id itself publishes. OpenRouter exposes no
        # parameter field at all, so anything else in here was invented.
        want = re.search(r"(?<![A-Za-z0-9])(\d+)b(?![A-Za-z0-9])", m["id"])
        if want:
            assert m.get("params_b") == int(want.group(1)), \
                f"{pid}/{m['id']}: id says {want.group(1)}B but params_b={m.get('params_b')}"
        else:
            assert "params_b" not in m, \
                f"{pid}/{m['id']}: params_b={m.get('params_b')} but the id publishes no size — that is unknown, not a guess"
        for q in m.get("quant", []):
            assert q in BITS, f"{pid}/{m['id']}: quantisation '{q}' has no known bit width, so it cannot be sorted"
        for c in m.get("trained_for", []):
            assert c in CATEGORIES, f"{pid}/{m['id']}: '{c}' is not one of OpenRouter's twelve categories"

    # Size sorts as a NUMBER: 8B before 70B before 235B. To prove that, the rows must contain a
    # pair the two orders disagree on (8 < 70 numerically, "70" < "8" lexically) — without such a
    # pair the comparison below would pass on a string sort too, and assert nothing.
    sized = [m for m in ms if "params_b" in m]
    if len(sized) > 1:
        assert any(a["params_b"] < b["params_b"] and str(a["params_b"]) > str(b["params_b"])
                   for a in sized for b in sized), \
            f"{pid}: no two sizes order differently as numbers and as strings — the next check would prove nothing"
        # Nothing more is asserted here. Once that pair exists, "sorted numerically differs from
        # sorted lexically" is true by construction — size is the primary key and the pair
        # disagrees on it — so asserting it would be a tautology. What is left to prove is that
        # the SCREEN sorts on a number, and that is the sizeKey/compareBy check further down.
    # No stability assertion here on purpose. Name is the last sort key and names are already
    # asserted unique above, so (size, quant, name) is a strict total order and the rendered order
    # cannot depend on sortedWith being stable. An assert that two rows never tie on all three
    # keys would be unfalsifiable given that uniqueness — green forever, testing nothing.

# Each unknown marker needs a row that reaches it, or the branch rendering it is dead code and
# the "missing renders as the marker" guarantee is never actually exercised.
orm = d["providers"]["openrouter"]["models"]
assert any("params_b" not in m for m in orm), "every model has params_b — the unknown size marker is dead code"
assert any(not m.get("quant") for m in orm), "every model has quant — the unknown quantisation marker is dead code"
assert any(not m.get("trained_for") for m in orm), "every model has trained_for — the unknown category marker is dead code"
assert all(m.get("note", "").strip() for m in orm), "a model has an empty observation — write one or drop the key"

# Routing resolves a stored id. These are the values a fresh install lands on; sorting the table
# is presentation and must not move either of them.
assert d["default_provider"] == "openrouter", "default_provider moved — routing changed"
assert d["providers"]["openrouter"]["default_model"] == "google/gemini-2.5-flash", "openrouter default_model moved — routing changed"
assert d["providers"]["cloud"]["default_model"] == "claude-sonnet-4-6", "cloud default_model moved — routing changed"
# An id that shipped before is an id some phone has stored in its prefs. Dropping one silently
# falls that user back to the default model with no warning.
for old in ["google/gemini-2.5-flash", "anthropic/claude-haiku-4.5", "openai/gpt-4.1-mini",
            "deepseek/deepseek-v4-flash-0731", "meta-llama/llama-3.3-70b-instruct",
            "qwen/qwen3-235b-a22b-2507", "mistralai/mistral-small-3.2-24b-instruct",
            "google/gemma-3-27b-it", "z-ai/glm-5.3-flash", "moonshotai/kimi-k2-0905"]:
    assert old in [m["id"] for m in orm], f"{old} was removed — every phone that had it selected silently re-routes"
EOF
then ok "T8 table data: unique slugs, #.#### dollars per million, numeric sort, unknown markers reachable, routing pinned"; else bad "T8 table data"; fi

# T8 rendering, read off the Kotlin (this runner cannot build, so the render is proved by its source)
S="$J/settings/screens/AiRoutingScreen.kt"
hasf "$S" 'String.format(Locale.US, "%.${PRICE_DECIMALS}f", v)' "T8 prices render in a fixed locale, with no scaling in the formatter"

# T8 the WHOLE conversion, provider wire format -> rendered cell, pinned to exact strings.
#
# A price crosses two scalings between OpenRouter and the screen: refreshPricing multiplies the
# published per-TOKEN figure to reach per-MILLION, and the formatter prints it. Neither is checked
# by looking at the other, so this block reads BOTH factors out of the Kotlin and renders four
# prices whose published value is public knowledge. If a stray hundred reappears anywhere on that
# path -- the defect this replaced, where the column silently printed cents -- claude-haiku-4.5
# stops reading 1.000 and the exact string below fails.
if python3 - "$J/latin/AiRouter.kt" "$S" <<'EOF'
import re, sys
from decimal import Decimal, ROUND_HALF_UP

router = open(sys.argv[1], encoding="utf-8").read()
screen = open(sys.argv[2], encoding="utf-8").read()

# The scale refreshPricing applies to the provider's per-token price, taken from the source.
ingest = re.search(r'getString\("prompt"\)\.toDouble\(\) \* ([0-9.e_]+)', router)
assert ingest, "refreshPricing no longer scales the catalog's per-token price -- find where it moved"
scale = float(ingest.group(1).replace("_", ""))
assert scale == 1e6, f"per-token price scaled by {scale:g}, expected 1e6 to reach per-million"

# The formatter's decimals, and the proof it does NOT scale: its value argument must be bare 'v'.
dec = re.search(r"private val PRICE_DECIMALS = (\d+)|private const val PRICE_DECIMALS = (\d+)", screen)
assert dec, "PRICE_DECIMALS is gone -- the column's precision is no longer declared in one place"
decimals = int(dec.group(1) or dec.group(2))
fmt = re.search(r'private fun usdPerMillionTokens\(v: Double\) = (.+)$', screen, re.M)
assert fmt, "the price formatter changed shape -- re-read it before trusting this check"
body = fmt.group(1).strip()
assert body.endswith(", v)"), \
    f"the formatter no longer prints its argument as given ({body!r}) -- a scaling crept back into the cell"

def cell(per_token_string):
    """Exactly what the table renders: ingest scaling, then Java's HALF_UP %.Nf on the double."""
    usd_per_million = float(per_token_string) * scale
    return str(Decimal(usd_per_million).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP))

# id, the price string OpenRouter's catalog publishes (USD per token), the cell the table must show.
# Chosen because these four are quoted per million in public price lists as $1, $5, $0.40 and
# $0.05 -- so the rendered cell is checkable against the vendor's own page by eye.
KNOWN = [
    ("anthropic/claude-haiku-4.5",       "prompt",     "0.000001",   "1.0000"),
    ("anthropic/claude-haiku-4.5",       "completion", "0.000005",   "5.0000"),
    ("openai/gpt-4.1-mini",              "prompt",     "0.0000004",  "0.4000"),
    ("meta-llama/llama-3.1-8b-instruct", "prompt",     "0.00000005", "0.0500"),
]
wrong = [f"{i} {k}: catalog {v} $/token renders {cell(v)!r}, must be {want!r}"
         for i, k, v, want in KNOWN if cell(v) != want]
assert not wrong, "\n    ".join(wrong)
EOF
then ok "T8 a published per-token price renders the exact dollars-per-million cell"; else bad "T8 per-token price to rendered cell"; fi
hasf "$S" 'compareBy<AiRouter.Model>({ sizeKey(it) }, { quantKey(it) }, { it.name })' "T8 sort is size, then quantisation, then name"
hasf "$S" 'private fun sizeKey(m: AiRouter.Model) = m.paramsB ?: Int.MAX_VALUE' "T8 models with no published size sort last"
hasf "$S" 'm.paramsB?.let { "${it}B" } ?: unknown' "T8 missing size renders the unknown marker"
hasf "$S" 'm.quant.joinToString("/").ifEmpty { unknown }' "T8 missing quantisation renders the unknown marker"
hasf "$S" 'm.trainedFor.joinToString(", ").ifEmpty { unknown }' "T8 missing category renders the unknown marker"
hasf "$S" 'm.note ?: unknown' "T8 missing observation renders the unknown marker"
hasf "$S" 'val unknown = stringResource(R.string.ai_table_unknown)' "T8 the unknown marker is one string resource"
# ONE LINE PER MODEL. Nine assertions, because two lines per model is what this table was and the
# way back is a single wrapped cell or a single column added to the rows and not to the header.
if python3 - "$S" "$LIBS/build.json" "$K/res/values/strings.xml" <<'EOF'
import json, re, sys, xml.etree.ElementTree as ET
src = open(sys.argv[1]).read()
reg = json.load(open(sys.argv[2]))["keyboard_ai"]
strings = {e.get("name"): "".join(e.itertext()) for e in ET.parse(sys.argv[3]).getroot()}

# Every cell goes through Cell(), and Cell is where the no-wrap guarantee lives. One place, so a new
# column cannot be added with the wrapping left on by accident.
assert re.search(r"private fun Cell\(.*?\n\s+Text\(text, modifier, color = color, style = style, "
                 r"textAlign = align, maxLines = 1, softWrap = false\)", src, re.S), \
    "Cell() no longer renders exactly one unwrapped line — every cell in the table inherits this"

body = src[src.index("private fun AiPricingTable("):]
body = body[:body.index("\n@Composable")]
assert "Text(" not in body.replace("Text(setting.title", "").replace("Text(it,", "").replace("Text(note,", ""), \
    "a table cell is a bare Text( instead of a Cell( — it does not carry the no-wrap guarantee"

# Exactly two Rows in the table: the header and the one the model loop emits. A third Row is a
# second line per model, which is the layout this replaced.
rows = re.findall(r"Row\((.*?)\) \{", body)
assert len(rows) == 2, f"{len(rows)} Rows in the table, expected 2 (header + one per model): {rows}"
assert all(r == "Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(scroll)" for r in rows), \
    f"a table Row is not the shared-scroll strip: {rows}"
assert body.count("forEach") == 1, "more than one pass over the models — one row per model means one loop"

# weight() cannot appear inside a horizontalScroll: the scroller measures its child with an infinite
# width, so a weighted cell resolves to zero. The old pinned name column was weight(1f).
assert "weight(" not in body, "weight() inside the scrolling row measures to zero width"

# Header and rows must declare the SAME columns in the SAME order, or every cell sits under the
# wrong heading. This is the check a column added to one and not the other fails.
head, rowcells = body.split("sortedWith(byModelSize)")
cols = lambda t: re.findall(r"Cell\([^\n]*?,\s*(col\w+),", t)
assert cols(head) == cols(rowcells), f"header columns {cols(head)} != row columns {cols(rowcells)}"
order = cols(head)
assert len(order) == 8, f"{len(order)} columns, expected 8"

# Each column is wide enough for its longest value. Roboto at the table's size averages well under
# 7 dp per character, so this catches a value that outgrows its column by a wide margin -- the case
# where softWrap = false turns into a silent clip -- rather than measuring text to the pixel.
DP_PER_CHAR = 7.0
width = {m[1]: (m[2], float(m[3])) for m in
         re.finditer(r"private val (col\w+) = Modifier\.(width|widthIn\(min = )\(?(\d+(?:\.\d+)?)\.dp", src)}
width = {n: (kind, dp) for n, (kind, dp) in width.items()}
assert set(width) == set(order), f"declared widths {sorted(width)} != columns used {sorted(order)}"

ms = [m for pv in reg["providers"].values() for m in pv["models"]]
# Positional, in the order the header declares them. A ninth column fails the length check above
# and lands the author here, which is the point.
longest = [
    ("colName",    max(m["name"] + (strings["ai_model_open_suffix"] if m.get("open") else "") for m in ms)),
    ("colPrice",   max(("%.4f" % m[k] for m in ms for k in ("prompt", "completion") if k in m), key=len)),
    ("colPrice",   ""),
    ("colSize",    max((f'{m["params_b"]}B' for m in ms if "params_b" in m), key=len)),
    ("colQuant",   max(("/".join(m.get("quant", [])) for m in ms), key=len)),
    ("colTrained", max((", ".join(m.get("trained_for", [])) for m in ms), key=len)),
    ("colNote",    max((m.get("note", "") for m in ms), key=len)),
    ("colSlug",    max((m["id"] for m in ms), key=len)),
]
assert [c for c, _ in longest] == order, f"this check is paired to {[c for c, _ in longest]}, table renders {order}"
heads = ["ai_pricing_col_" + n for n in
         ("model", "in", "out", "size", "quant", "trained", "note", "slug")]
over = []
for (col, val), h in zip(longest, heads):
    kind, dp = width[col]
    if kind != "width": continue          # widthIn is a floor: it grows instead of clipping
    for text in (val, strings[h]):
        if len(text) * DP_PER_CHAR > dp:
            over.append(f"{col} is {dp:.0f} dp but must hold {len(text)} chars ({text!r})")
assert not over, "\n    ".join(over)
EOF
then ok "T8 one row per model: one no-wrap Cell everywhere, 2 Rows, header and rows same 8 columns, each wide enough"
else bad "T8 one row per model"; fi

# The model code is the string you copy when something needs the exact identifier, so its column is
# a floor and not a fixed width: last in the row, nothing to its right, and it can never be cut.
hasf "$S" 'private val colSlug = Modifier.widthIn(min = 280.dp)' "T8 the model code column grows rather than clips"
# Header and rows must share ONE scroll state, or the columns slide out from under their headings.
n=$(grep -cF 'rememberScrollState()' "$S")
[ "$n" = 1 ] && ok "T8 header and rows share one scroll state" || bad "T8 $n scroll states — rows would scroll apart from the header"
n=$(grep -cF 'Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(scroll))' "$S")
[ "$n" = 2 ] && ok "T8 one scrolling strip for the header and one carrying the whole model row" || bad "T8 $n horizontal strips, expected 2"
# ONE horizontal scroller per row and nothing scrollable inside it. A scroller nested on the same
# axis is the gesture the inner one steals; the settings list below is vertical and does not fight.
n=$(grep -cF 'horizontalScroll(' "$S")
[ "$n" = 2 ] && ok "T8 no scroller nested inside the row — nothing contests a sideways drag" || bad "T8 $n horizontalScroll calls, expected 2"
grep -qF 'LazyRow(' "$S" && bad "T8 LazyRow in the table — nest that in the settings list and the scrolls fight" \
  || ok "T8 the strip is a horizontalScroll, perpendicular to the settings list's vertical scroll"
# Colours come from the theme. A literal here is wrong under the dark and Samsung-black themes.
grep -qE 'Color\(0x|Color\.(White|Black|Gray|Red|Blue|Green)' "$S" \
  && bad "T8 a colour literal in the table — the dark theme supplies its own" \
  || ok "T8 every colour in the table comes from the theme"
# Table and picker must agree, and both sort by the same presentation comparator.
n=$(grep -cF 'sortedWith(byModelSize)' "$S")
[ "$n" = 2 ] && ok "T8 table and model picker use the same order" || bad "T8 sortedWith(byModelSize) used $n times, expected 2 (table + picker)"
hasf "$S" 'context.labelFor(it) to it.id' "T8 the picker's stored value is the id, not the row position"

# Sorting is presentation. Had it leaked into AiRouter, the picker would follow the table.
grep -qE 'sortedWith|sortedBy|sorted\(' "$J/latin/AiRouter.kt" \
  && bad "T8 AiRouter sorts models — the call path must not depend on row order" \
  || ok "T8 AiRouter never sorts: it resolves the stored id, not a row position"
hasf "$J/latin/AiRouter.kt" 'providers.firstOrNull { it.id == id }' "T8 provider resolved by id"
hasf "$J/latin/AiRouter.kt" 'PREF_AI_MODEL_PREFIX + p.id, p.defaultModel' "T8 Enhance/Grammar resolve the stored model id, falling back to default_model"
# Translate does not read this table at all — it goes through libs:translate's registered client.
# If that ever changes, this table's order becomes a routing input and T8 has to be rewritten.
grep -rqF 'AiRouter' "$LIBS/libs/translate/src/main/java" \
  && bad "T8 libs:translate now references AiRouter — Translate would start depending on this table" \
  || ok "T8 Translate routes through TranslateEngines.client, untouched by the model table"

# T8 live: the quantisation and trained_for values baked into build.json, re-read from OpenRouter.
# This is the refresh procedure for the two columns that have no runtime refresh on a phone.
#
# Reachability is probed FIRST and on its own line, because "the catalogue did not answer" and "the
# catalogue answered and disagrees with us" are opposite results and only one of them is allowed to
# be quiet. They used to share a single `if curl … && python3 …` whose else-branch was an `echo`, so
# a real drift printed its own traceback and the suite still ended `0 failed` and exited 0 — the
# check ran, found the truth, and reported green. An unreachable catalogue skips; a catalogue that
# contradicts the registry fails the build.
if live_checks_are_advisory; then
  echo "  skip: T8 live OpenRouter cross-check — release gate does not veto a publish on a third party's live state; the scheduled AI model registry monitor enforces this"
elif ! curl -sS --max-time 15 -o /dev/null "$CATALOG" 2>/dev/null; then
  echo "  skip: T8 live OpenRouter cross-check unreachable ($CATALOG) — quant/trained_for unverified"
elif python3 - "$ROOT" "$REGISTRIES" <<'EOF'
import collections, datetime, json, os, sys, urllib.request
root, manifest = sys.argv[1], sys.argv[2]
max_age = int(os.environ["DRIFT_MAX_AGE_DAYS"])
def get(u):
    with urllib.request.urlopen(u, timeout=40) as r: return json.load(r)
ranked = collections.defaultdict(set)
for c in ("programming", "roleplay", "marketing", "marketing/seo", "technology", "science",
          "translation", "legal", "finance", "health", "trivia", "academia"):
    for m in get("https://openrouter.ai/api/v1/models?category=" + c.replace("/", "%2F"))["data"]:
        ranked[m["id"]].add(c)
# Both registries list the same model ids, so the per-model endpoint call is fetched once and
# reused — checking the second copy costs no extra requests.
endpoints = {}
fatal, warn = [], []
for r in json.load(open(manifest))["registries"]:
    pv = json.load(open(os.path.join(root, r["path"])))[r["key"]]["providers"]["openrouter"]
    age = (datetime.date.today() - datetime.date.fromisoformat(pv["pricing_as_of"])).days
    drift = fatal if age > max_age else warn
    before = len(fatal)
    for m in pv["models"]:
        live_cats = ranked.get(m["id"], set())
        if set(m.get("trained_for", [])) != live_cats:
            drift.append(f"{r['label']} {m['id']}: trained_for {sorted(m.get('trained_for', []))} vs live {sorted(live_cats)}")
        if m["id"] not in endpoints:
            endpoints[m["id"]] = get(f"https://openrouter.ai/api/v1/models/{m['id']}/endpoints")["data"]["endpoints"]
        live_q = sorted({e.get("quantization") for e in endpoints[m["id"]] if e.get("quantization")} - {"unknown"})
        if sorted(m.get("quant", [])) != live_q:
            drift.append(f"{r['label']} {m['id']}: quant {sorted(m.get('quant', []))} vs live {live_q}")
    if len(fatal) > before:
        fatal.append(f"{r['label']}: pricing_as_of {pv['pricing_as_of']} is {age} days old (window {max_age}) - the scheduled refresh has stopped landing")
for w in warn: print(f"  warn: T8 drift inside the {max_age}-day window (the scheduled refresh rewrites it): {w}")
assert not fatal, "\n    ".join(fatal)
EOF
then ok "T8 live: baked quantisation and trained_for match OpenRouter, or drift only inside the $DRIFT_MAX_AGE_DAYS-day window"
else bad "T8 live: quant/trained_for drifted and the registry is older than the window (each listed above) — run 1_cicd/src/scripts/cloud-android-ai-registry-refresh.py and commit"; fi

# T9 model catalogues (ai-registries.json::catalogues): curated selections an app browses, priced live
# on the phone. The shape is static and fatal; whether every id is still listed is OpenRouter's state,
# so it follows the T7/T8 rule above (advisory inside a release gate, fatal on the schedule).
if python3 - "$ROOT" "$REGISTRIES" <<'EOF'
import json, os, sys
root, manifest = sys.argv[1], sys.argv[2]
for c in json.load(open(manifest)).get("catalogues", []):
    d = json.load(open(os.path.join(root, c["path"])))
    assert all(u.startswith("https://") for u in d["urls"].values()), f"{c['label']}: a catalogue url is not https"
    for g in d["groups"]:
        for s in g["sections"]:
            others = [r["provider"].lower() for r in s["rows"] if not r["id"].startswith("anthropic/")]
            assert len(others) == len(set(others)), f"{c['label']} {s['id']}: one row per provider"
            for r in s["rows"]:
                assert "/" in r["id"] and r["provider"].strip(), f"{c['label']} {s['id']}: bad row {r}"
                assert s["catalog"] == "none" or "snapshot" in r, f"{c['label']} {s['id']} {r['id']}: no offline snapshot"
EOF
then ok "T9 model catalogues well-formed: one row per provider, every priced row carries its snapshot"; else bad "T9 model catalogue shape"; fi

if live_checks_are_advisory; then
  echo "  skip: T9 live catalogue ids — release gate does not veto a publish on a third party's live state; the scheduled AI model registry monitor enforces this"
elif ! curl -sS --max-time 15 -o /dev/null "$CATALOG" 2>/dev/null; then
  echo "  skip: T9 live catalogue ids unreachable ($CATALOG) — curated ids unverified"
elif python3 - "$ROOT" "$REGISTRIES" <<'EOF'
import json, os, sys, urllib.request
root, manifest = sys.argv[1], sys.argv[2]
def ids(u):
    with urllib.request.urlopen(u, timeout=40) as r: return {m["id"] for m in json.load(r)["data"]}
gone = []
for c in json.load(open(manifest)).get("catalogues", []):
    d = json.load(open(os.path.join(root, c["path"])))
    listed = {k: ids(d["urls"][k]) for k in ("chat", "embeddings", "images", "videos")}
    for g in d["groups"]:
        for s in g["sections"]:
            where = {"chat": listed["chat"], "embeddings": listed["embeddings"], "images": listed["images"] | listed["videos"],
                     "videos": listed["videos"], "none": set()}[s["catalog"]]
            gone += [f"{c['label']} {s['id']} {r['id']}: not in OpenRouter's {s['catalog']} catalogue" for r in s["rows"] if r["id"] not in where]
assert not gone, "\n    ".join(gone)
EOF
then ok "T9 live: every curated catalogue id is still listed by the OpenRouter catalogue that prices it"
else bad "T9 live: a curated model is gone from OpenRouter (each listed above) — pick that provider's next model in the catalogue the line names"; fi

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
