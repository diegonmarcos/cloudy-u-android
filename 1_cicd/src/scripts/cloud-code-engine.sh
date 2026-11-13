#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-code-engine — Cloud Code from the vendored Acode (#562)    ║
# ║                                                                  ║
# ║ Acode is vendored at the app root (ac_cloud-code/, no .git).     ║
# ║ This engine builds the Android APK from that committed source    ║
# ║ the way upstream's own nightly does (utils/scripts/build.sh,     ║
# ║ "paid dev apk fdroid"), plus the three things that make it OURS: ║
# ║ the launcher name from build.json, the MyTerminal target         ║
# ║ resolved from the fleet, and the shared-constellation signature. ║
# ║                                                                  ║
# ║ Vendored as ac_cloud-code/build.sh (build.json::vendored_engine) ║
# ║ and CI runs THAT copy (parity is enforced by the generator).     ║
# ║                                                                  ║
# ║ Commands:                                                        ║
# ║   materialize-fork <key>  no-op — the tree is VENDORED (no .git) ║
# ║   build-fork <key>        npm → cordova setup → rspack → cordova ║
# ║                           build → shared-key sign → asserts      ║
# ║   publish-fork <key>      oras push dist APK → ghcr image        ║
# ║   gh-release-fork <key>   attach dist APK + sha256 to rolling    ║
# ║                           GitHub Release                          ║
# ║   asset-path              print dist APK path                    ║
# ║   clean                   rm -rf dist/                           ║
# ║   help                    this message                            ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ROOT="$SCRIPT_DIR"
BUILD_JSON="$SCRIPT_DIR/build.json"
DIST_DIR="$SCRIPT_DIR/dist"

log()    { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf '\033[0;31m[%s] ERROR: %s\033[0m\n' "$(date '+%H:%M:%S')" "$1" >&2; }
die()    { errlog "$1"; exit 1; }
_json()  { jq -r "$1 // empty" "$BUILD_JSON" 2>/dev/null || printf ''; }

command -v jq >/dev/null 2>&1 || die "jq is not on PATH"
[ -f "$BUILD_JSON" ] || die "no build.json at $BUILD_JSON"

# No fallbacks: every value below is the app's declaration, and a default here
# would be a second declaration that drifts.
APP_NAME="$(_json '.name')"
DISPLAY_NAME="$(_json '.display_name')"
APP_ID="$(_json '.android.application_id')"
ASSET="$(_json '.release.artifact.release')"
IMG="$(_json '.release.ghcr.image')"
VARIANT="$(_json '.upstream.flavor.variant')"
FDROID="$(_json '.upstream.flavor.fdroid')"
for _v in APP_NAME DISPLAY_NAME APP_ID ASSET IMG VARIANT FDROID; do
  [ -n "${!_v}" ] || die "build.json is missing the value behind \$$_v"
done
unset _v

# ── signing-key resolver (build.json::signing → vault → env) ──────────
# The ONE shared Cloud-constellation key. No fallback: every APK this fleet
# signs must be installable over every other (the updater keys off signature).
_resolve_signing() {
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  ks_rel="$(_json '.signing.vault_keystore')"
  sec_rel="$(_json '.signing.vault_secrets')"
  [ -n "$ks_rel" ] && [ -n "$sec_rel" ] \
    || die "FATAL signing: .signing.vault_keystore/.vault_secrets empty in build.json — ALL constellation apps sign with the ONE shared key"
  vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
  ks="$vault/$ks_rel"
  [ -f "$ks" ] || die "FATAL signing: the ONE shared constellation keystore is missing at $ks (set VAULT_DIR). NO fallback key allowed."
  command -v sops >/dev/null 2>&1 || die "FATAL signing: sops not on PATH; cannot decrypt the shared key."
  store_pw="$(sops --config /dev/null -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops --config /dev/null -d --extract '["key_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops --config /dev/null -d --extract '["key_alias"]' "$vault/$sec_rel" 2>/dev/null || true)"
  [ -n "$store_pw" ] && [ -n "$alias_" ] \
    || die "FATAL signing: cannot decrypt $sec_rel (need SOPS_AGE_KEY). Refusing any other key."
  export ANDROID_KEYSTORE_FILE="$ks" ANDROID_KEYSTORE_PASSWORD="$store_pw" \
         ANDROID_KEY_PASSWORD="$key_pw" ANDROID_KEY_ALIAS="$alias_"
  log "signing: ONE shared constellation key (alias $alias_)"
}

# Re-sign whatever gradle produced (debug/unsigned) with the shared key and
# prove the result. Mirrors cloud-chat-fork-engine.sh::_enforce_signature.
_enforce_signature() {
  local apk="$1" bt zipalign apksigner
  [ -f "$apk" ] || die "sign-enforce: missing APK $apk"
  _resolve_signing
  [ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set"
  bt="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  zipalign="$bt/zipalign"; apksigner="$bt/apksigner"
  [ -x "$apksigner" ] || die "sign-enforce: apksigner missing (bt=$bt)"
  "$zipalign" -f -p 4 "$apk" "${apk}.aln" 2>/dev/null && mv -f "${apk}.aln" "$apk" || rm -f "${apk}.aln"
  "$apksigner" sign --ks "$ANDROID_KEYSTORE_FILE" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
    "$apk" || die "sign-enforce: re-sign with shared key failed for $apk"
  rm -f "${apk}.idsig"
  "$apksigner" verify "$apk" >/dev/null 2>&1 \
    || die "sign-enforce: FATAL $(basename "$apk") not validly signed after shared-key re-sign"
  log "sign-enforce: OK $(basename "$apk") signed by the ONE shared constellation key"
}

# Prove the built APK really is OUR application id (a de-clouding regression
# guard: upstream appId must never leak back in).
_assert_apk_identity() {
  local apk="$1"
  local bt dump
  [ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set"
  bt="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  dump="$("$bt/aapt" dump badging "$apk" 2>/dev/null | grep -E "^package: name=" || true)"
  echo "$dump" | grep -q "name='$APP_ID'" \
    || die "identity: FATAL $(basename "$apk") has package $(echo "$dump" | head -1) — expected $APP_ID"
  log "identity: OK $(basename "$apk") is $APP_ID"
}

# Prove the MERGED manifest of the built APK asks for the permissions the Perms page lists. A special/app-op
# permission (PACKAGE_USAGE_STATS, MANAGE_EXTERNAL_STORAGE) that is not declared is invisible to its Settings page
# ("Usage access" never lists the app), so a missing declaration reads on the phone as a dead Perms row.
# The list is data: build.json::release.artifact_assert.required_permissions.
_assert_apk_permissions() {
  local apk="$1" bt dump p
  [ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set"
  bt="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  dump="$("$bt/aapt" dump permissions "$apk" 2>/dev/null || true)"
  [ -n "$dump" ] || die "permissions: FATAL aapt could not read the merged manifest of $(basename "$apk")"
  for p in $(jq -r '.release.artifact_assert.required_permissions[]' "$BUILD_JSON"); do
    echo "$dump" | grep -q "name='$p'" || die "permissions: FATAL merged manifest of $(basename "$apk") does not declare $p"
  done
  log "permissions: OK merged manifest declares every required permission"
}

# Prove the built APK carries the web bundle AND this app's own layer.
#
# gradle wraps whatever www/ holds, including nothing, in a structurally
# perfect APK; apksigner signs it; aapt confirms the id. None of that looks
# INSIDE the zip. Floors and markers are data: build.json::release.artifact_assert.
_assert_apk_payload() {
  local apk="$1"
  [ -f "$apk" ] || die "payload: missing APK $apk"
  command -v python3 >/dev/null 2>&1 || die "payload: python3 not on PATH — an unread APK must never be called proven"
  python3 - "$apk" "$BUILD_JSON" <<'PY' || die "payload: FATAL the APK does not carry a bootable cloud-code bundle — see the lines above"
import json, sys, zipfile

apk, build_json = sys.argv[1], sys.argv[2]
cfg = json.load(open(build_json))['release']['artifact_assert']
zf = zipfile.ZipFile(apk)
sizes = {i.filename: i.file_size for i in zf.infolist()}
bad = []

entry = cfg['web_entry']
if sizes.get(entry, -1) < cfg['web_entry_min_bytes']:
    bad.append('%s is %s bytes, floor is %d' % (entry, sizes.get(entry, 'ABSENT'), cfg['web_entry_min_bytes']))

chunks = {n: s for n, s in sizes.items() if n.startswith(cfg['js_prefix']) and n.endswith('.js')}
total = sum(chunks.values())
if len(chunks) < cfg['js_min_chunks']:
    bad.append('%s holds %d .js files, floor is %d' % (cfg['js_prefix'], len(chunks), cfg['js_min_chunks']))
if total < cfg['js_min_total_bytes']:
    bad.append('%s holds %d bytes of JS, floor is %d' % (cfg['js_prefix'], total, cfg['js_min_total_bytes']))

main = cfg['main_js']
if main not in sizes:
    bad.append('%s is ABSENT' % main)
else:
    text = zf.read(main).decode('utf-8', 'replace')
    for marker in cfg['main_js_must_contain']:
        if marker not in text:
            bad.append('%s does not contain %r — the cloud-code layer is not in the shipped bundle' % (main, marker))

for line in bad:
    print('  payload FAIL  %s' % line)
if bad:
    raise SystemExit(1)
print('  payload OK    %s %d B · %d js files / %d B · %s carries %s'
      % (entry, sizes[entry], len(chunks), total, main, ', '.join(cfg['main_js_must_contain'])))
PY
  log "payload: OK $(basename "$apk") carries the web bundle and the cloud-code layer"
}

# ── materialize-fork: the source is VENDORED, nothing to clone ────────
step_materialize_fork() {
  log "materialize-fork: no-op — Acode is vendored at the app root (no .git); revision pinned in build.json::upstream"
}

# ── build-fork ────────────────────────────────────────────────────────
step_build_fork() {
  cd "$APP_ROOT"
  [ -f package-lock.json ] && [ -f config.xml ] || die "no package-lock.json/config.xml at the app root — this is not the Acode tree"
  [ -f codemirror-lsp-client/dist/index.js ] || die "codemirror-lsp-client/dist missing — the inlined submodule is incomplete"

  # Upstream's build.sh and its Cordova hooks talk to each other through
  # $TMPDIR/fdroid.bool (post-process.js pins targetSdk 28 on it). Written
  # BEFORE setup, because `cordova platform add` already runs the hooks.
  # #1708: that hook pins targetSdk 28 only when the flag is true. This build targets build.json::android.target_sdk
  # (>= 35, silent Store updates) and runs the terminal's proot, loader and axs from nativeLibraryDir (W^X safe), so the
  # flag it reads is "true" only for a declared target_sdk of 28 or less.
  local tmp="${TMPDIR:-/tmp}" tsdk hookflag
  tsdk="$(_json '.android.target_sdk')"
  hookflag=false; [ "${tsdk:-0}" -le 28 ] && hookflag=true
  printf '%s\n' "$hookflag" > "$tmp/fdroid.bool"
  log "build-fork: flavour $VARIANT, fdroid=$FDROID (upstream's own axes, build.json::upstream.flavor), targetSdk=$tsdk"

  log "build-fork: npm ci (lock is committed; proot pruned from it with the plugin)"
  npm ci --no-audit --no-fund
  export PATH="$APP_ROOT/node_modules/.bin:$PATH"   # the pinned cordova, never a global one

  # setup.js skips AdMob only while config.xml's widget id is upstream's PAID
  # id — which is why the widget id is never rewritten (build-extras.gradle
  # moves the applicationId instead).
  log "build-fork: upstream setup (cordova platform add android + every src/plugins/*)"
  node utils/setup.js

  local p
  for p in $(jq -r '.upstream.flavor.removed_plugins[]' "$BUILD_JSON"); do
    if [ -d "plugins/$p" ]; then
      log "build-fork: flavour removes $p"
      cordova plugin remove "$p"
    fi
  done

  log "build-fork: upstream config (dev, $VARIANT)"
  node utils/config.js d "$VARIANT"

  log "build-fork: launcher name ← build.json::display_name ($DISPLAY_NAME)"
  python3 - "$DISPLAY_NAME" <<'PY'
import re, sys
name = sys.argv[1]
text = open('config.xml').read()
new, n = re.subn(r'<name>[^<]*</name>', '<name>%s</name>' % name, text, count=1)
if n != 1:
    raise SystemExit('config.xml has no <name> element')
open('config.xml', 'w').write(new)
PY

  log "build-fork: resolve nav targets from the fleet (src/cloud/targets.gen.json)"
  python3 tools/resolve-targets.py "$APP_ROOT" --out src/cloud/targets.gen.json --print

  log "build-fork: web bundle (rspack, development — upstream's 'd' mode)"
  rspack --mode development

  # #1708 the terminal's native tools ship as lib*.so under jniLibs and run from applicationInfo.nativeLibraryDir
  # (the only place an app at targetSdk >= 29 may exec from). Fetched here, hash-pinned, never committed
  # (licence-boundaries: no *.so / *.rootfs in the tree).
  if [ "${tsdk:-0}" -ge 29 ]; then
    log "build-fork: native terminal tools -> platforms/android/app/src/main/jniLibs (tools/fetch-native-tools.py)"
    python3 tools/fetch-native-tools.py "$APP_ROOT" "$APP_ROOT/platforms/android/app/src/main/jniLibs"
  fi

  log "build-fork: cordova build android (debug)"
  # An EMPTY build config, explicitly: Cordova reads ./build.json by default,
  # and ./build.json here is the fleet declaration, not Cordova signing config.
  printf '{}\n' > "$tmp/cloud-code-cordova-build.json"
  cordova build android --buildConfig="$tmp/cloud-code-cordova-build.json" -- --packageType=apk

  local apk="$APP_ROOT/platforms/android/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$apk" ] || die "build-fork: no APK at $apk"

  mkdir -p "$DIST_DIR"
  _enforce_signature "$apk"
  _assert_apk_identity "$apk"
  _assert_apk_permissions "$apk"
  _assert_apk_payload "$apk"
  cp "$apk" "$DIST_DIR/$ASSET"
  log "build-fork: OK $DIST_DIR/$ASSET"
}

# ── publish-fork: oras push to GHCR ───────────────────────────────────
step_publish_fork() {
  local ref media
  ref="$(_json '.release.ghcr.registry')/$(_json '.release.ghcr.namespace')/$IMG"
  media="$(_json '.release.ghcr.media_type')"
  [ -n "$ref" ] || die "publish-fork: .release.ghcr.registry/namespace empty"
  [ -f "$DIST_DIR/$ASSET" ] || die "publish-fork: $DIST_DIR/$ASSET missing — run build-fork first"
  command -v oras >/dev/null 2>&1 || die "publish-fork: oras not on PATH"
  local creds=()
  [ -n "${GH_TOKEN:-}" ] && creds=(--username diegonmarcos --password "$GH_TOKEN")
  ( cd "$DIST_DIR" && oras push "${creds[@]}" "$ref" "$ASSET:$media" --artifact-type "$media" ) \
    || die "publish-fork: oras push failed"
  log "publish-fork: OK $ref ← $ASSET"
}

# ── gh-release-fork: rolling GitHub Release ───────────────────────────
step_gh_release_fork() {
  local tag aname
  tag="$(_json '.release.gh_release.rolling_tag')"
  [ -n "$tag" ] || tag="latest"
  [ -f "$DIST_DIR/$ASSET" ] || die "gh-release-fork: $DIST_DIR/$ASSET missing — run build-fork first"
  command -v gh >/dev/null 2>&1 || die "gh-release-fork: gh not on PATH"
  ( cd "$DIST_DIR" && sha256sum "$ASSET" | awk '{print $1}' > "$ASSET.sha256" )
  if ! gh release view "$tag" >/dev/null 2>&1; then
    # --latest: this IS the rolling head every /releases/latest/download/ URL
    # resolves through. Unflagged, gh recomputes the pointer by recency, and
    # cloud-android-release-latest-audit.sh fails the call as a hijacker.
    gh release create "$tag" --title "$tag" --generate-notes --latest >/dev/null 2>&1 || true
  fi
  gh release upload "$tag" "$DIST_DIR/$ASSET" "$DIST_DIR/$ASSET.sha256" --clobber \
    || die "gh-release-fork: upload failed"
  # Re-pin: a per-tag release created since may hold the pointer; take it back.
  gh release edit "$tag" --latest >/dev/null 2>&1 || true
  log "gh-release-fork: OK $ASSET + .sha256 on release $tag"
}

step_asset_path() { printf '%s\n' "$DIST_DIR/$ASSET"; }

step_clean() { rm -rf "$DIST_DIR"; }

usage() { sed -n '5,24p' "$0" | sed 's/^# \?//'; }

CMD="${1:-help}"
case "$CMD" in
  materialize-fork) step_materialize_fork "$@" ;;
  build-fork)       step_build_fork "$@" ;;
  publish-fork)     step_publish_fork "$@" ;;
  gh-release-fork)  step_gh_release_fork "$@" ;;
  asset-path)       step_asset_path ;;
  clean)            step_clean ;;
  help|--help|-h)   usage ;;
  *) die "unknown command: $CMD (see ./build.sh help)" ;;
esac
