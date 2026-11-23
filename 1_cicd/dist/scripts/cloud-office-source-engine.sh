# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-office-source-engine.sh ───
#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-office-source-engine — Cloud Office from source, against  ║
# ║ the engine taken prebuilt out of the pinned released APK         ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Vendored as ac_cloud-office/build.sh (build.json::vendored_engine), and CI
# runs that copy. Every value comes from ac_cloud-office/build.json:
#
#   ./build.sh materialize   fetch upstream.online at its revision, apply patches/,
#                            place the shared modules build.modules names
#   ./build.sh payload       download upstream.engine.source, verify its sha256,
#                            unpack its native libraries and assets
#   ./build.sh configure     configure --enable-androidapp --with-prebuilt-engine
#   ./build.sh build         gradle, then sign with the ONE constellation key and
#                            assert what was built: package id, and native
#                            libraries byte-identical to the released ones
#   ./build.sh all           the four above, in order
#   ./build.sh runtime       install the signed APK on the adb device, open
#                            runtime_check.sample_document and assert it renders
#                            with no R8 casualty in the app's logcat
#   ./build.sh host-deps     install build.host_packages on the build host
#   ./build.sh asset-path    print where `build` leaves the signed APK
#
# NOTHING NATIVE IS COMPILED. patches/0002 declares the prebuilt-engine mode;
# tests/test-no-engine-build-required.sh proves against upstream that no native
# file is touched, and `build` proves on the output that the packaged libraries
# are the released bytes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_JSON="$SCRIPT_DIR/build.json"
# OUTSIDE the repository, deliberately. The materialized upstream is 1.9 GB of
# working tree plus 480 MB of .git, and this checkout is shared by several
# agents: inside it, one build would bury everybody's `git status`.
WORK_DIR="${CLOUD_OFFICE_WORK_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cloud-office}"
DIST_DIR="$WORK_DIR/dist"
ONLINE_DIR="$WORK_DIR/online"
PAYLOAD_DIR="$WORK_DIR/engine"
ENGINE_APK="$WORK_DIR/engine.apk"

log()    { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1"; }
die()    { printf '\033[0;31m[%s] ERROR: %s\033[0m\n' "$(date '+%H:%M:%S')" "$1" >&2; exit 1; }
_json()  { jq -r "$1 // empty" "$BUILD_JSON"; }

command -v jq >/dev/null 2>&1 || die "jq is not on PATH"
[ -f "$BUILD_JSON" ] || die "no build.json at $BUILD_JSON"

ABI="$(_json '.upstream.engine.source.abi')"
[ -n "$ABI" ] || die "build.json::upstream.engine.source.abi is empty"

# ── materialize ───────────────────────────────────────────────────────
# Gerrit serves neither a sha fetch nor a blob filter, so the ref (a branch, or
# an immutable refs/changes/N/M/P patch-set ref once the branch is deleted) is fetched
# shallow and deepened until the revision is in it. The revision is the
# assertion; the ref is only how the objects arrive. An existing clone that
# already holds the revision (the CI cache) is reused without touching Gerrit.
step_materialize() {
    local url ref rev depth
    url="$(_json '.upstream.online.url')"
    ref="$(_json '.upstream.online.ref')"
    rev="$(_json '.upstream.online.revision')"
    [ -n "$url" ] && [ -n "$ref" ] || die "build.json::upstream.online.url/ref is empty"
    [ "${#rev}" -eq 40 ] || die "build.json::upstream.online.revision must be a full 40-character sha, got '$rev'"
    [ "$(_json '.upstream.online.patch_mode')" = "apply" ] \
        || die "build.json::upstream.online.patch_mode is not 'apply' — this engine only applies a patch series"

    mkdir -p "$WORK_DIR"
    if [ ! -d "$ONLINE_DIR/.git" ]; then
        git init -q "$ONLINE_DIR"
        git -C "$ONLINE_DIR" remote add origin "$url"
    fi
    if ! git -C "$ONLINE_DIR" cat-file -e "$rev^{commit}" 2>/dev/null; then
        log "materialize: fetching $ref from $url (shallow)"
        git -C "$ONLINE_DIR" fetch -q --depth 1 origin "$ref" || die "cannot fetch $ref from $url"
        for depth in 50 200; do
            git -C "$ONLINE_DIR" cat-file -e "$rev^{commit}" 2>/dev/null && break
            log "materialize: $rev is not at the tip of $ref, deepening to $depth"
            git -C "$ONLINE_DIR" fetch -q --depth "$depth" origin "$ref" || die "cannot deepen $ref"
        done
        git -C "$ONLINE_DIR" cat-file -e "$rev^{commit}" 2>/dev/null \
            || die "$rev is not within 200 commits of $ref — re-pin upstream.online.revision and re-verify the series"
    else
        log "materialize: $rev already present, Gerrit not contacted"
    fi

    git -C "$ONLINE_DIR" -c advice.detachedHead=false checkout -q -f "$rev"
    git -C "$ONLINE_DIR" clean -q -ffdx

    # In numeric order, stopping at the first failure. Never --skip, never -3:
    # both turn "upstream moved under an edit we own" into a quiet omission.
    local patch
    for patch in "$SCRIPT_DIR"/patches/[0-9][0-9][0-9][0-9]-*.patch; do
        [ -f "$patch" ] || die "no numbered patches in $SCRIPT_DIR/patches"
        log "materialize: applying $(basename "$patch")"
        git -C "$ONLINE_DIR" -c user.name="Cloud Office" -c user.email=noreply@diegonmarcos.com \
            am -q --keep-non-patch "$patch" \
            || { git -C "$ONLINE_DIR" am --abort >/dev/null 2>&1 || true
                 die "$(basename "$patch") does not apply to $rev — rebase the patch or re-pin"; }
    done

    # Shared modules are LINKED, never copied: a copy of the ITextTools AIDL is a
    # second wire contract. The link lives in the scratch tree only.
    local name dir target
    while IFS=$'\t' read -r name dir target; do
        [ -n "$name" ] || continue
        [ -d "$SCRIPT_DIR/$dir" ] || die "build.json::build.modules.$name.dir $dir does not exist"
        mkdir -p "$(dirname "$ONLINE_DIR/$target")"
        ln -sfn "$(cd "$SCRIPT_DIR/$dir" && pwd)" "$ONLINE_DIR/$target"
        log "materialize: linked $name → $target"
    done < <(jq -r '.build.modules | to_entries[] | select(.value|type=="object") | [.key, .value.dir, .value.materialize_to] | @tsv' "$BUILD_JSON")

    # The app's own build.json, LINKED for the same reason: libs:devtools reads the
    # consuming app's declaration by walking up from the gradle root, and that root
    # is in this scratch tree, where no walk ever meets the repository.
    target="$(_json '.build.build_json_to')"
    if [ -n "$target" ]; then
        ln -sfn "$BUILD_JSON" "$ONLINE_DIR/$target"
        log "materialize: linked build.json → $target"
        # #796: the debug-API port slice beside it (cloud-android-mesh-slices-gen.py
        # writes <app>/debug-api.json; libs:devtools walks up from the gradle root
        # for it exactly as it does for build.json, and that walk ends here too).
        [ -f "$SCRIPT_DIR/debug-api.json" ] || die "no debug-api.json beside $BUILD_JSON — run ./build.sh workflow (libs:devtools bakes this app's port from it)"
        ln -sfn "$SCRIPT_DIR/debug-api.json" "$ONLINE_DIR/$(dirname "$target")/debug-api.json"
        log "materialize: linked debug-api.json → $(dirname "$target")/debug-api.json"
    fi

    # The fleet launcher icon. The Android res dir only exists in this fetched
    # tree, so the ONE generator overlays its files here (build.json::
    # launcher_icon.overlay) and removes upstream's hand-made launcher art.
    overlay="$(_json '.launcher_icon.overlay')"
    if [ -n "$overlay" ]; then
        python3 "$SCRIPT_DIR/../1_cicd/src/scripts/cloud-android-launcher-icons.py" \
            --overlay "$(basename "$SCRIPT_DIR")" "$ONLINE_DIR/$overlay" \
            || die "launcher icon overlay failed"
        log "materialize: launcher icon generated into $overlay"
    fi
}

# ── payload ───────────────────────────────────────────────────────────
# The released APK is the engine. Its sha256 is the whole supply-chain claim, so
# a mismatch stops the build, and so does a native library count other than the
# declared one: an app that dlopens something absent is not shippable.
step_payload() {
    local url sha size actual
    url="$(_json '.upstream.engine.source.url')"
    sha="$(_json '.upstream.engine.source.sha256')"
    size="$(_json '.upstream.engine.source.size')"
    [ -n "$url" ] && [ -n "$sha" ] && [ -n "$size" ] || die "build.json::upstream.engine.source url/sha256/size is incomplete"

    mkdir -p "$WORK_DIR"
    if [ ! -f "$ENGINE_APK" ] || [ "$(sha256sum "$ENGINE_APK" | cut -d' ' -f1)" != "$sha" ]; then
        log "payload: downloading $url"
        curl -fSL --retry 3 --retry-delay 5 -o "$ENGINE_APK" "$url" || die "cannot download $url"
    fi
    actual="$(sha256sum "$ENGINE_APK" | cut -d' ' -f1)"
    [ "$actual" = "$sha" ] && [ "$(stat -c %s "$ENGINE_APK")" = "$size" ] \
        || die "engine APK does not match the pin: sha256 $actual, $(stat -c %s "$ENGINE_APK") bytes; expected $sha, $size bytes"
    log "payload: sha256 verified ($sha)"

    rm -rf "$PAYLOAD_DIR"
    python3 - "$ENGINE_APK" "$PAYLOAD_DIR" "$BUILD_JSON" <<'PY'
import json, os, sys, zipfile
apk, out, bj = sys.argv[1], sys.argv[2], sys.argv[3]
engine = json.load(open(bj))["upstream"]["engine"]
libs = engine["native_libs"]["from_apk_dir"].rstrip("/") + "/"
assets = engine["assets"]["from_apk_dir"].rstrip("/") + "/"
exclude = tuple(e.rstrip("/") + "/" for e in engine["assets"]["exclude"])
count = 0
with zipfile.ZipFile(apk) as z:
    for info in z.infolist():
        name = info.filename
        if info.is_dir():
            continue
        if name.startswith(libs) and name.endswith(".so"):
            count += 1
        elif not name.startswith(assets) or name.startswith(exclude):
            continue
        dest = os.path.join(out, name)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(info) as src, open(dest, "wb") as dst:
            dst.write(src.read())
expected = engine["native_libs"]["expect_count"]
if count != expected:
    sys.exit(f"payload: {count} native libraries under {libs}, build.json expects {expected}")
print(f"payload: {count} native libraries and the assets outside {list(exclude)} unpacked")
PY
}

# ── configure ─────────────────────────────────────────────────────────
step_configure() {
    [ -d "$ONLINE_DIR/.git" ] || die "run materialize first"
    [ -d "$PAYLOAD_DIR/lib/$ABI" ] || die "run payload first"
    local flags=()
    while IFS= read -r flag; do flags+=("$flag"); done \
        < <(jq -r '.upstream.online.configure | to_entries[] | select(.key|startswith("--")) | "\(.key)=\(.value)"' "$BUILD_JSON")
    [ "${#flags[@]}" -gt 0 ] || die "build.json::upstream.online.configure declares no flags"
    log "configure: --enable-androidapp --with-android-abi=$ABI --with-prebuilt-engine ${flags[*]}"
    ( cd "$ONLINE_DIR" && ./autogen.sh && ./configure --enable-androidapp --with-android-abi="$ABI" \
        --with-prebuilt-engine="$PAYLOAD_DIR" "${flags[@]}" ) || die "configure failed"
}

# ── signing: the ONE shared constellation key, or no build ────────────
_resolve_signing() {
    if [ -n "${GITHUB_ACTIONS:-}" ] && [ -n "${ANDROID_KEYSTORE_FILE:-}" ] \
       && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
        return 0
    fi
    local vault keystore secrets
    vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
    keystore="$vault/$(_json '.signing.vault_keystore')"
    secrets="$vault/$(_json '.signing.vault_secrets')"
    [ -f "$keystore" ] || die "signing: the shared constellation keystore is missing at $keystore — no other key is allowed"
    command -v sops >/dev/null 2>&1 || die "signing: sops is not on PATH"
    ANDROID_KEYSTORE_FILE="$keystore"
    ANDROID_KEYSTORE_PASSWORD="$(sops --config /dev/null -d --extract '["keystore_password"]' "$secrets" 2>/dev/null || true)"
    ANDROID_KEY_PASSWORD="$(sops --config /dev/null -d --extract '["key_password"]' "$secrets" 2>/dev/null || true)"
    ANDROID_KEY_ALIAS="$(sops --config /dev/null -d --extract '["key_alias"]' "$secrets" 2>/dev/null || true)"
    [ -n "$ANDROID_KEYSTORE_PASSWORD" ] && [ -n "$ANDROID_KEY_ALIAS" ] \
        || die "signing: cannot decrypt $secrets (SOPS_AGE_KEY missing?)"
    export ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD ANDROID_KEY_ALIAS
}

# ── build ─────────────────────────────────────────────────────────────
step_build() {
    [ -f "$ONLINE_DIR/android/app/appSettings.gradle" ] || die "run configure first"
    local task asset build_tools apk out app_id
    task="$(_json '.build.gradle_task')"
    asset="$(_json '.release.gh_release.asset_name')"
    app_id="$(_json '.android.application_id')"
    [ -n "$task" ] && [ -n "$asset" ] && [ -n "$app_id" ] || die "build.json::build.gradle_task, release.gh_release.asset_name or android.application_id is empty"

    # The SDK packages are INSTALLED, not left to AGP to fetch on demand: an
    # auto-download depends on accepted licences and on the platform being the
    # one AGP guesses, and when it does not happen the failure is a compileSdk
    # error hours into the run. sdkmanager is not on PATH on every image, so it
    # is resolved from the SDK this build will use.
    local sdkmanager packages
    sdkmanager="$(command -v sdkmanager || true)"
    [ -n "$sdkmanager" ] || sdkmanager="${ANDROID_HOME:-}/cmdline-tools/latest/bin/sdkmanager"
    [ -x "$sdkmanager" ] || die "sdkmanager not found (PATH, or \$ANDROID_HOME/cmdline-tools/latest/bin)"
    mapfile -t packages < <(jq -r '.build.sdk_packages[]' "$BUILD_JSON")
    [ "${#packages[@]}" -gt 0 ] || die "build.json::build.sdk_packages is empty"
    # THE LICENCE ACCEPTANCES ARE A FILE, AND SDKMANAGER IS NOT IN A PIPELINE.
    #
    # It used to be `yes | sdkmanager`, and under `set -o pipefail` that made the
    # PRODUCER'S status the step's: run 35034695984 failed with "yes: standard
    # output: Broken pipe" although every package was already installed on the
    # image. Feeding a fixed number of lines instead does not fix it, it only
    # makes it rare — sdkmanager can still close the read end before the write
    # lands, and then the producer takes EPIPE exactly as `yes` did. Measured,
    # not reasoned about: the fixed-feed version flaked on the second local run
    # of tests/test-sdkmanager-status-is-its-own.sh.
    #
    # With the acceptances on disk there is no second process in the command at
    # all, so there is nothing whose failure could be mistaken for sdkmanager's.
    # One "y" per package: sdkmanager asks at most once per licence and there are
    # never more licences than packages. An extra prompt reads end of file,
    # declines, and fails loudly below rather than hanging.
    #
    # sdkmanager's output is KEPT, because "Failed to find package" goes to its
    # stdout — sending that to /dev/null made a real failure and the spurious one
    # above produce byte-identical job logs.
    local sdk_log="$WORK_DIR/sdkmanager.log" sdk_accepts="$WORK_DIR/sdkmanager.accepts"
    printf 'y\n%.0s' "${packages[@]}" > "$sdk_accepts"
    log "build: sdkmanager ${packages[*]}"
    if ! "$sdkmanager" "${packages[@]}" <"$sdk_accepts" >"$sdk_log" 2>&1; then
        cat "$sdk_log" >&2
        die "sdkmanager could not install ${packages[*]} — see its output above"
    fi

    # #830: the build commit is baked by an init script of ours (upstream's app
    # module is not ours to edit); git's fallback must read THIS repository.
    local init_script
    init_script="$SCRIPT_DIR/$(_json '.build.gradle_init_script')"
    [ -f "$init_script" ] || die "build.json::build.gradle_init_script does not name a file beside build.json"
    log "build: gradle $task (init script $(basename "$init_script"))"
    ( cd "$ONLINE_DIR/android" && CLOUD_OFFICE_REPO_DIR="$SCRIPT_DIR" \
        ./gradlew --no-daemon --stacktrace -I "$init_script" "$task" ) || die "gradle $task failed"

    apk="$(find "$ONLINE_DIR/android/build/app/outputs/apk/release" -name "*$ABI*.apk" | head -1)"
    [ -f "$apk" ] || die "no $ABI release APK under android/build/app/outputs/apk/release"
    mkdir -p "$DIST_DIR"
    out="$DIST_DIR/$asset"
    cp "$apk" "$out"

    build_tools="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/* | sort -V | tail -1)"
    _resolve_signing
    "$build_tools/zipalign" -f -p 4 "$out" "$out.aligned" && mv -f "$out.aligned" "$out"
    "$build_tools/apksigner" sign --ks "$ANDROID_KEYSTORE_FILE" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
        --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" "$out" \
        || die "signing with the shared constellation key failed"
    rm -f "$out.idsig"
    "$build_tools/apksigner" verify "$out" >/dev/null || die "$asset is not validly signed"

    # What was built, asserted on the output rather than inferred from the
    # inputs: our package id, and native libraries that are the released bytes.
    "$build_tools/aapt2" dump badging "$out" | grep -q "^package: name='$app_id'" \
        || die "$asset does not carry package $app_id"
    python3 - "$ENGINE_APK" "$out" "$(_json '.upstream.engine.native_libs.from_apk_dir')" \
        "$(_json '.upstream.engine.native_libs.expected_extra // [] | join(" ")')" <<'PY' || die "native libraries differ from the released ones"
import hashlib, sys, zipfile
released, built, libs = sys.argv[1], sys.argv[2], sys.argv[3].rstrip("/") + "/"
extra = set(sys.argv[4].split()) if len(sys.argv) > 4 and sys.argv[4] else set()
def digests(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: hashlib.sha256(z.read(i)).hexdigest()
                for i in z.infolist() if i.filename.startswith(libs) and i.filename.endswith(".so")}
a, b = digests(released), digests(built)
# The invariant is that our patch series does NOT relink the engine: every
# native library the released APK carries must still be present in the built
# APK with the identical bytes. A library the built APK carries that the
# released one lacks is NOT a relink — it is a new native library from a
# library dependency (the shared ml-l-image-mlkit AAR ships ML Kit's OCR
# pipeline), and it is allowed only if build.json names it beforehand in
# upstream.engine.native_libs.expected_extra. Anything else new is a failure.
missing  = [k for k in a if k not in b]
relinked = [k for k in a if k in b and a[k] != b[k]]
unexpected = [k for k in b if k not in a and k not in extra]
if not a or missing or relinked or unexpected:
    sys.exit(f"build: native libraries differ from the released APK: "
             + repr(sorted(missing + relinked + unexpected)))
print(f"build: {len(a)} released native libraries byte-identical; "
      f"{len(b) - len(a)} declared extra(s) present")
PY
    log "build: $out ($(stat -c %s "$out") bytes)"
}

# ── runtime ───────────────────────────────────────────────────────────
# The release APK is R8-optimised, and the prebuilt engine reaches Java by name
# over JNI while cool.html reaches it through @JavascriptInterface. A keep rule
# that is missing fails only when the app runs, so this runs it: install the
# signed asset `build` left, open build.json::runtime_check.sample_document in
# LOActivity, and judge from the app's own logcat and a screenshot. Needs a
# booted device on adb (the workflow's emulator step); evidence lands in
# $CLOUD_OFFICE_RUNTIME_OUT.
step_runtime() {
    local asset out
    asset="$(step_asset_path)"
    [ -f "$asset" ] || die "runtime: no $asset — run build first"
    command -v adb >/dev/null 2>&1 || die "runtime: adb is not on PATH"
    [ -n "$(_json '.runtime_check.activity')" ] || die "build.json::runtime_check is missing"
    out="${CLOUD_OFFICE_RUNTIME_OUT:-$WORK_DIR/runtime}"
    rm -rf "$out" && mkdir -p "$out"
    python3 -u - "$BUILD_JSON" "$SCRIPT_DIR" "$asset" "$out" "$ABI" <<'PY' || die "runtime: the release APK did not open the sample document cleanly — evidence in $out"
import json, os, re, struct, subprocess, sys, time
bj, app_dir, apk, out, abi = sys.argv[1:6]
cfg = json.load(open(bj))
rc, pkg = cfg["runtime_check"], cfg["android"]["application_id"]

def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True)
    if check and r.returncode != 0:
        sys.exit("runtime: adb %s: %s" % (" ".join(args), (r.stderr or r.stdout).decode(errors="replace").strip()))
    return r.stdout

def sh(cmd, check=True):
    return adb("shell", cmd, check=check).decode(errors="replace").strip()

abis = sh("getprop ro.product.cpu.abilist")
print("runtime: device ABIs", abis)
if abi not in abis.split(","):
    sys.exit("runtime: this image cannot run %s (abilist %s) — pick a runtime_check.emulator image "
             "with ARM translation; this says nothing about R8" % (abi, abis))

adb("logcat", "-G", "16M", check=False)
adb("logcat", "-c")
log_path = os.path.join(out, "logcat.txt")
logcat = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=open(log_path, "wb"), stderr=subprocess.DEVNULL)

# A freshly booted emulator is starved of CPU for its first minute and the system
# launcher can ANR in that window; its "isn't responding" dialog then stays over
# the app for the whole run, so the screenshot shows the sample under a scrim
# (ship run 37518868324: the page rendered, 0.0% of the screen was its colour).
# Dialogs say nothing about this APK: hide them, and the app's own crashes are
# still caught below by its fatal log lines and its process going away.
sh("settings put global hide_error_dialogs 1", check=False)
sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS", check=False)

print("runtime:", adb("install", "-r", "-g", apk).decode(errors="replace").strip())
sh("appops set %s MANAGE_EXTERNAL_STORAGE allow" % pkg)
sample = os.path.join(app_dir, rc["sample_document"])
dev = rc["device_dir"].rstrip("/") + "/" + os.path.basename(sample)
adb("push", sample, dev)
# A file pushed over FUSE gets its MediaStore row on its own; wait for it
# rather than assume it, and say so if it never comes.
row_query = "content query --uri %s --projection _id --where \"_display_name='%s'\"" % (
    rc["media_collection"], os.path.basename(sample))
for _ in range(30):
    row = re.search(r"_id=(\d+)", sh(row_query, check=False))
    if row:
        break
    time.sleep(1)
else:
    sys.exit("runtime: %s never got a row in %s" % (dev, rc["media_collection"]))
uri = "%s/%s" % (rc["media_collection"], row.group(1))
print("runtime: opening", uri)
started = sh("am start -W -n %s/%s -a android.intent.action.VIEW -d %s -t %s"
             % (pkg, rc["activity"], uri, rc["sample_mime"]))
print(started)
if "Error" in started:
    sys.exit("runtime: am start refused the activity")

markers, fatal = rc["loaded_markers"], rc["fatal_patterns"]
start_re = re.compile(r"Start proc (\d+):%s(?::[\w.]+)?/" % re.escape(pkg))
pid_re = re.compile(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s")

def scan():
    lines = open(log_path, errors="replace").read().splitlines()
    pids = set(start_re.findall("\n".join(lines))) | set(sh("pidof %s" % pkg, check=False).split())
    own = [l for l in lines if (m := pid_re.match(l)) and m.group(1) in pids]
    bad = [l for l in own if any(f in l for f in fatal)]
    bad += [l for l in lines if ">>> %s <<<" % pkg in l or "ANR in %s" % pkg in l]
    return own, bad, [m for m in markers if any(m in l for l in own)]

def magenta_fraction():
    # Raw screencap: width, height, format (1 = RGBA_8888) and, from API 28, a
    # colour-space word, then the pixels.
    raw = adb("exec-out", "screencap")
    w, h, fmt = struct.unpack_from("<III", raw)
    hdr = len(raw) - w * h * 4
    if fmt != 1 or hdr not in (12, 16):
        sys.exit("runtime: unexpected screencap format %d / header %d bytes" % (fmt, hdr))
    px, (r0, g0, b0), tol = raw[hdr:], rc["render"]["color"], rc["render"]["tolerance"]
    hits = sum(1 for r, g, b in zip(px[0::4], px[1::4], px[2::4])
               if abs(r - r0) <= tol and abs(g - g0) <= tol and abs(b - b0) <= tol)
    return hits / (w * h)

deadline, failure, frac = time.time() + rc["load_timeout_s"], None, 0.0
while True:
    time.sleep(10)
    own, bad, seen = scan()
    if bad:
        failure = "fatal line(s) from the app"
        break
    if markers[-1] in seen:
        frac = magenta_fraction()
        if frac >= rc["render"]["min_fraction"]:
            time.sleep(5)
            own, bad, seen = scan()
            failure = "fatal line(s) from the app after render" if bad else None
            break
    if not sh("pidof %s" % pkg, check=False):
        failure = "the app's process is gone"
        break
    if time.time() > deadline:
        failure = "no render within %ds" % rc["load_timeout_s"]
        break

open(os.path.join(out, "screen.png"), "wb").write(adb("exec-out", "screencap", "-p", check=False))
logcat.terminate()
print("runtime: markers seen: %s" % (", ".join(seen) or "none"))
print("runtime: page colour on %.1f%% of the screen (need %.1f%%)" % (frac * 100, rc["render"]["min_fraction"] * 100))
for l in bad[:40]:
    print("runtime: FATAL  " + l)
if failure:
    print("\n".join(own[-80:]))
    sys.exit("runtime: " + failure)
print("runtime: the release APK opened %s and core rendered it" % os.path.basename(sample))
PY
}

# ── host-deps / asset-path: what the workflow needs from this engine ──
step_host_deps() {
    mapfile -t packages < <(jq -r '.build.host_packages[]' "$BUILD_JSON")
    [ "${#packages[@]}" -gt 0 ] || die "build.json::build.host_packages is empty"
    sudo apt-get update -y >/dev/null && sudo apt-get install -y "${packages[@]}"
}

step_asset_path() { printf '%s/%s\n' "$DIST_DIR" "$(_json '.release.gh_release.asset_name')"; }

case "${1:-help}" in
    host-deps)   step_host_deps ;;
    asset-path)  step_asset_path ;;
    materialize) step_materialize ;;
    payload)     step_payload ;;
    configure)   step_configure ;;
    build)       step_build ;;
    runtime)     step_runtime ;;
    all)         step_materialize; step_payload; step_configure; step_build ;;
    *)           sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//; /^set -euo/d' ;;
esac
