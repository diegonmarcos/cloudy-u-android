#!/usr/bin/env bash
# #1547 the runtime-data guard must be RED for every way data gets baked into an APK again (a new JSON asset or raw resource, a new gradle
# asset injection, a new BuildConfig base64 field, mock data in a main source set, a baseline that stopped shrinking, a bad data/fleet manifest)
# and GREEN on a conforming tree. One small fixture repo per case.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-runtime-data-guard.py"
fails=0
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/ac_a/app/src/main/assets" "$r/ac_a/app/src/main/res/raw" "$r/data/fleet/app/com.x.a"
  cat > "$r/1_cicd/src/data/runtime-data-baseline.json" <<'J'
{"seed_allowlist": {"_doc": "x"}, "json_max": 1, "gradle_max": 1, "b64_max": 1, "mock_files": ["shop.json"],
 "json": {"ac_a/app/src/main/assets/old.json": {"tier": 3, "target": "store app/com.x.a/old"}},
 "gradle_assets": {"ac_a/app/build.gradle": 1}, "buildconfig_b64": {"ac_a/app/build.gradle": 1}}
J
  echo '{}' > "$r/ac_a/app/src/main/assets/old.json"
  cat > "$r/ac_a/app/build.gradle" <<'G'
android { sourceSets { main { assets.srcDirs += ['../data'] } } }
android.defaultConfig { buildConfigField "String", "UI_B64", "\"${uiB64}\"" ; buildConfigField "String", "GIT_SHORT_SHA", "\"abc\"" }
G
  cat > "$r/data/fleet/manifest.json" <<'J'
{"schemaVersion": 1, "shared": {"fleet-x": {"tier": 3, "consumers": ["*"]}}, "apps": {"com.x.a": ["defaults"]}}
J
  echo '{"schemaVersion": 1}' > "$r/data/fleet/fleet-x.json"
  echo '{"schemaVersion": 1}' > "$r/data/fleet/app/com.x.a/defaults.json"
  echo "$r"
}
expect() {   # expect <name> <exit code wanted> <root>
  python3 "$GUARD" "$3" > "$OUT" 2>&1; local got=$?
  if [ "$got" = "$2" ]; then echo "  PASS: $1"; else echo "  FAIL: $1 (exit $got, wanted $2)"; sed 's/^/        /' "$OUT" | head -5; fails=$((fails + 1)); fi
  rm -rf "$3"
}
OUT="$(mktemp)"; trap 'rm -f "$OUT"' EXIT
jset() { python3 - "$1" "$2" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); exec(sys.argv[2]); json.dump(d, open(sys.argv[1], "w"))
PY
}
B=1_cicd/src/data/runtime-data-baseline.json
r="$(mk)"; expect "a conforming tree is green" 0 "$r"
r="$(mk)"; echo '{}' > "$r/ac_a/app/src/main/assets/new.json"; expect "D1 a new JSON in src/main/assets is red" 1 "$r"
r="$(mk)"; echo '{}' > "$r/ac_a/app/src/main/res/raw/new.json"; expect "D1 a new JSON in res/raw is red" 1 "$r"
r="$(mk)"; mkdir -p "$r/ac_a/app/src/release/assets/x"; echo '{}' > "$r/ac_a/app/src/release/assets/x/new.json"; expect "D1 a new JSON in a nested assets dir of any source set is red" 1 "$r"
r="$(mk)"; rm "$r/ac_a/app/src/main/assets/old.json"; expect "D1 a baselined JSON that is gone must leave the baseline (red)" 1 "$r"
r="$(mk)"; echo '{}' > "$r/ac_a/app/src/main/assets/new.json"; jset "$r/$B" 'd["seed_allowlist"]["ac_a/app/src/main/assets/new.json"]="tier-4 static reference seed, runtime provider overrides it"'; expect "a declared seed with a reason is green" 0 "$r"
r="$(mk)"; echo '{}' > "$r/ac_a/app/src/main/assets/new.json"; jset "$r/$B" 'd["seed_allowlist"]["ac_a/app/src/main/assets/new.json"]="x"'; expect "a declared seed without a reason is red" 1 "$r"
r="$(mk)"; jset "$r/$B" 'd["json"]["ac_a/app/src/main/assets/old.json"]={"tier":3}'; expect "a baselined JSON without a target provider is red" 1 "$r"
r="$(mk)"; echo 'android { sourceSets { main { assets.srcDirs += ["../more"] } } }' >> "$r/ac_a/app/build.gradle"; expect "D2 a new gradle assets.srcDirs is red" 1 "$r"
r="$(mk)"; printf 'tasks.register("c", Copy) { from("x"); into(layout.buildDirectory.dir("generated/assets/y")) }\n' > "$r/ac_a/new.gradle"; expect "D2 a new gradle Copy into assets in a new file is red" 1 "$r"
r="$(mk)"; printf 'x = 1\n' > "$r/ac_a/app/build.gradle"; printf 'buildConfigField "String", "UI_B64", "\\"${b}\\""\n' >> "$r/ac_a/app/build.gradle"; expect "D2 a gradle injection removed without lowering the baseline is red" 1 "$r"
r="$(mk)"; printf 'android.defaultConfig { buildConfigField "String", "NEW_SECTIONS_B64", "\\"${x}\\"" }\n' >> "$r/ac_a/app/build.gradle"; expect "D3 a new BuildConfig base64 field is red" 1 "$r"
r="$(mk)"; printf 'android.defaultConfig { buildConfigField "String", "TOOLS", "\\"${toolsConfig.bytes.encodeBase64()}\\"" }\n' >> "$r/ac_a/app/build.gradle"; expect "D3 a BuildConfig field whose value is a base64 expression is red" 1 "$r"
r="$(mk)"; printf '// buildConfigField "String", "X_B64", "y"\n' >> "$r/ac_a/app/build.gradle"; expect "a commented-out field is not counted (green)" 0 "$r"
r="$(mk)"; printf 'android.defaultConfig { buildConfigField "String", "BUILD_TIMESTAMP", "\\"1\\"" }\n' >> "$r/ac_a/app/build.gradle"; expect "a plain BuildConfig identity field is green" 0 "$r"
r="$(mk)"; printf 'x = 1\n' > "$r/ac_a/app/build.gradle"; jset "$r/$B" 'd["gradle_assets"]={}; d["buildconfig_b64"]={}; d["gradle_max"]=0; d["b64_max"]=0'; expect "a fully migrated gradle file with a lowered baseline is green" 0 "$r"
r="$(mk)"; jset "$r/$B" 'd["json_max"]=0'; expect "the JSON baseline above its ceiling is red" 1 "$r"
r="$(mk)"; mkdir -p "$r/ac_a/app/src/main/assets/shop"; echo '{}' > "$r/ac_a/app/src/main/assets/shop/shop.json"; jset "$r/$B" 'd["json"]["ac_a/app/src/main/assets/shop/shop.json"]={"tier":"mock","target":"remove"}; d["json_max"]=2'; expect "D4 mock data in a main source set is red even when baselined" 1 "$r"
r="$(mk)"; mkdir -p "$r/ac_a/app/src/debug/assets"; echo '{}' > "$r/ac_a/app/src/debug/assets/shop.json"; jset "$r/$B" 'd["json"]["ac_a/app/src/debug/assets/shop.json"]={"tier":"mock","target":"debug only"}; d["json_max"]=2'; expect "D4 mock data in the debug source set is green" 0 "$r"
r="$(mk)"; jset "$r/data/fleet/manifest.json" 'd["shared"]["fleet-x"]["tier"]=1'; expect "D5 a tier-1 dataset in the public manifest is red" 1 "$r"
r="$(mk)"; jset "$r/data/fleet/manifest.json" 'd["shared"]["personal/wallet"]={"tier":3,"consumers":["*"]}'; expect "D5 a personal/ dataset in the public manifest is red" 1 "$r"
r="$(mk)"; echo '{}' > "$r/data/fleet/fleet-x.json"; expect "D5 a dataset without schemaVersion is red" 1 "$r"
r="$(mk)"; rm "$r/data/fleet/fleet-x.json"; expect "D5 a manifest entry without its file is red" 1 "$r"
r="$(mk)"; echo '{"schemaVersion": 1}' > "$r/data/fleet/orphan.json"; expect "D5 a dataset file the manifest does not list is red" 1 "$r"
r="$(mk)"; mkdir -p "$r/data/fleet/personal"; echo '{"schemaVersion": 1}' > "$r/data/fleet/personal/wallet.json"; expect "D5 a personal dataset file under data/fleet is red" 1 "$r"
r="$(mk)"; rm "$r/data/fleet/manifest.json"; expect "D5 a missing manifest is red" 1 "$r"
echo "-- runtime-data-guard test: $fails failed"
exit $((fails > 0))
