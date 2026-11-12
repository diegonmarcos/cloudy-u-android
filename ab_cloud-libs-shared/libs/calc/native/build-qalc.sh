#!/usr/bin/env bash
# build-qalc.sh — build Cloud-Lib-Calc's native engine for ONE target.
#
#   build-qalc.sh host     the CI host: builds qalc_core + qalc_golden against the
#                          pinned libqalculate, runs golden.tsv, proves the golden
#                          runner fails on a planted wrong row, then runs
#                          libqalculate's own `make check` with the same flags.
#   build-qalc.sh <abi>    an ABI from data/qalc-native.json::build.abis: cross-
#                          compiles with the declared NDK and links
#                          $QALC_OUT/<abi>/libqalc.so, then checks its ELF header,
#                          NEEDED entries, exported JNI_OnLoad and page alignment.
#
# Everything — versions, URLs, checksums, configure flags, NDK, API level, ABIs,
# the soname — is read from data/qalc-native.json. Nothing here names a version.
# Work tree: $QALC_WORK (default ab_cloud-libs-shared/.cache/qalc, gitignored).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # libs/calc/native
MOD="$(cd "$HERE/.." && pwd)"                          # libs/calc
CFG="$MOD/data/qalc-native.json"
WORK="${QALC_WORK:-$(cd "$MOD/../.." && pwd)/.cache/qalc}"
OUT="${QALC_OUT:-$WORK/out}"
TARGET="${1:?usage: build-qalc.sh host|<abi>}"
JOBS="$(nproc 2>/dev/null || echo 2)"

log() { printf '  \033[36m•\033[0m %s\n' "$*" >&2; }
die() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }
cfg() { jq -er "$1" "$CFG"; }

command -v jq >/dev/null || die "jq is required"
command -v sha256sum >/dev/null || die "sha256sum is required"

QALC_VERSION="$(cfg '.build.sources.libqalculate.version')"
PREFIX="$WORK/prefix/$TARGET"
BUILD="$WORK/build/$TARGET"
mkdir -p "$WORK/src" "$PREFIX" "$BUILD"

# ── toolchain ────────────────────────────────────────────────────────────────
if [ "$TARGET" = host ]; then
    KIND=host
    export CC="${CC:-gcc}" CXX="${CXX:-g++}"
    HOST_ARGS=()
else
    KIND=android
    TRIPLE="$(jq -er --arg a "$TARGET" '.build.abis[$a].triple' "$CFG")" || die "$TARGET is not in build.abis"
    NDK_VERSION="$(cfg '.build.ndk')"
    API="$(cfg '.build.api')"
    NDK="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$NDK_VERSION}"
    TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    [ -x "$TC/bin/clang" ] || die "NDK $NDK_VERSION not found at $NDK (install ndk;$NDK_VERSION)"
    export CC="$TC/bin/${TRIPLE}${API}-clang" CXX="$TC/bin/${TRIPLE}${API}-clang++"
    export AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" NM="$TC/bin/llvm-nm"
    READELF="$TC/bin/llvm-readelf"
    HOST_ARGS=(--host="$TRIPLE")
fi
# The host gcc (15+ on ubuntu-26.04) defaults to C23, where gmp 6.3.0's configure probe
# ("long long reliability test 1": an empty-parens declaration called with arguments)
# fails and gmp reports "no working compiler". Pin the C dialect gmp 6.3.0 was written for.
CSTD=""; [ "$KIND" = host ] && CSTD=" -std=gnu17"
export CFLAGS="-O2 -fPIC$CSTD" CXXFLAGS="-O2 -fPIC" CPPFLAGS="-I$PREFIX/include" LDFLAGS="-L$PREFIX/lib"
# Only this prefix's .pc files: a host libxml2 must never satisfy a cross build.
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

# ── fetch once, refuse on a checksum mismatch ────────────────────────────────
fetch() {
    local name="$1" url sum file
    url="$(cfg ".build.sources.\"$name\".url")"
    sum="$(cfg ".build.sources.\"$name\".sha256")"
    file="$WORK/src/$(basename "$url")"
    if [ ! -f "$file" ] || [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$sum" ]; then
        log "fetch $name: $url"
        curl -fsSL --retry 3 -o "$file.part" "$url" || die "download failed: $url"
        mv "$file.part" "$file"
    fi
    local got
    got="$(sha256sum "$file" | cut -d' ' -f1)"
    [ "$got" = "$sum" ] || die "$name: sha256 $got, pinned $sum — refusing $url"
    printf '%s' "$file"
}

# ── one dependency: configure, make, install into $PREFIX ────────────────────
build_dep() {
    local name="$1" tarball dir
    local stamp="$BUILD/.$name.done"
    [ -f "$stamp" ] && { log "$name: already built for $TARGET"; return; }
    tarball="$(fetch "$name")"
    dir="$BUILD/$name"
    rm -rf "$dir"; mkdir -p "$dir"
    tar -xf "$tarball" -C "$dir" --strip-components=1
    local patch
    for patch in $(jq -r --arg n "$name" '.build.sources[$n].patches // [] | .[]' "$CFG"); do
        log "$name: patch $patch"
        patch -d "$dir" -p1 --forward --quiet < "$MOD/$patch" || die "$name: $patch does not apply to $(basename "$tarball")"
    done
    local args=()
    mapfile -t args < <(jq -r --arg n "$name" --arg k "$KIND" \
        '.build.sources[$n] | (.configure // []) + (.[$k] // []) | .[]' "$CFG")
    args=("${args[@]//\{prefix\}/$PREFIX}")
    log "$name ($TARGET): configure ${args[*]}"
    (cd "$dir" && ./configure --prefix="$PREFIX" "${HOST_ARGS[@]}" "${args[@]}" > "$BUILD/$name.configure.log" 2>&1) \
        || { tail -n 60 "$BUILD/$name.configure.log"; tail -n 80 "$dir/config.log" 2>/dev/null; die "$name: configure failed"; }
    if [ "$name" = libqalculate ] && [ "$KIND" = android ]; then
        # The library and the definitions it compiles in; no qalc, docs or translations.
        (cd "$dir" && make -j"$JOBS" -C data && make -j"$JOBS" -C libqalculate && make -C libqalculate install) \
            > "$BUILD/$name.make.log" 2>&1 || { tail -n 80 "$BUILD/$name.make.log"; die "$name: make failed"; }
    else
        (cd "$dir" && make -j"$JOBS" && make install) > "$BUILD/$name.make.log" 2>&1 \
            || { tail -n 80 "$BUILD/$name.make.log"; die "$name: make failed"; }
    fi
    touch "$stamp"
}

for dep in $(jq -r '.build.order[]' "$CFG"); do build_dep "$dep"; done

LIBS=(-lqalculate -lxml2 -lmpfr -lgmp -lm)
DEFS=(-DQALC_VERSION="\"$QALC_VERSION\"")

if [ "$KIND" = host ]; then
    # ── the golden table, against the same core the .so carries ──────────────
    BIN="$BUILD/qalc_golden"
    "$CXX" -std=c++17 -O2 "${DEFS[@]}" -I"$PREFIX/include" "$HERE/qalc_core.cc" "$HERE/qalc_golden.cc" \
        -L"$PREFIX/lib" "${LIBS[@]}" -lpthread -o "$BIN"
    scratch="$(mktemp -d)"
    log "golden: $HERE/golden.tsv"
    "$BIN" "$HERE/golden.tsv" "$scratch/user" || die "golden calculations failed (rows above)"

    # The runner must be able to fail: a copy with one expectation made wrong
    # has to go red, or a green above proves nothing.
    sed 's/^standard\t-\t2+2\t4$/standard\t-\t2+2\t5/' "$HERE/golden.tsv" > "$scratch/planted.tsv"
    cmp -s "$HERE/golden.tsv" "$scratch/planted.tsv" && die "MUT: the planted row did not change the table — the 2+2 row moved"
    if "$BIN" "$scratch/planted.tsv" "$scratch/user2" > "$scratch/planted.log" 2>&1; then
        die "MUT: qalc_golden passed a table that says 2+2 = 5"
    fi
    grep -F 'FAIL  standard | 2+2 -> "4" want "5"' "$scratch/planted.log" > /dev/null \
        || { cat "$scratch/planted.log"; die "MUT: the planted row failed for the wrong reason"; }
    log "MUT: a planted wrong row goes red (2+2 = 5 refused)"

    # libqalculate's own suite (tests/*.batch through qalc), with OUR flags.
    log "libqalculate make check"
    (cd "$BUILD/libqalculate" && make check) > "$BUILD/libqalculate.check.log" 2>&1 \
        || { tail -n 120 "$BUILD/libqalculate.check.log"; die "libqalculate's own tests failed with these configure flags"; }
    # Not vacuous: automake's harness must have PASSED the unittest runner, and the runner
    # must have walked every tests/*.batch file. "Nothing to be done for 'check'" in a
    # subdirectory is also green, so green alone proves nothing.
    grep -q '^PASS: unittest' "$BUILD/libqalculate.check.log" \
        || { tail -n 60 "$BUILD/libqalculate.check.log"; die "make check did not PASS the unittest runner — the upstream suite did not run"; }
    batches=$(find "$BUILD/libqalculate/tests" -maxdepth 1 -name '*.batch' | wc -l)
    ran=$(grep -c '^Running unit tests from' "$BUILD/libqalculate/src/unittest.log" || true)
    [ "$batches" -gt 0 ] && [ "$ran" -eq "$batches" ] \
        || die "libqalculate's unittest walked $ran of $batches tests/*.batch files"
    log "libqalculate make check: unittest PASS, $ran/$batches batch files"
    rm -rf "$scratch"
    log "host: golden + upstream tests green"
    exit 0
fi

# ── the Android library ───────────────────────────────────────────────────────
SONAME="$(cfg '.build.soname')"
PAGE="$(cfg '.build.max_page_size')"
mkdir -p "$OUT/$TARGET"
SO="$OUT/$TARGET/$SONAME"
"$CXX" -shared -std=c++17 -O2 -fPIC -fvisibility=hidden "${DEFS[@]}" -I"$PREFIX/include" \
    "$HERE/qalc_core.cc" "$HERE/qalc_jni.cc" -L"$PREFIX/lib" "${LIBS[@]}" \
    -static-libstdc++ -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--no-undefined \
    -Wl,-z,max-page-size="$PAGE" -Wl,-soname,"$SONAME" -o "$SO"
"$STRIP" --strip-unneeded "$SO"

# ── what the loader on a phone will check, checked here first ────────────────
want_machine="$(jq -er --arg a "$TARGET" '.build.abis[$a].machine' "$CFG")"
# Each tool's output is captured before it is searched: `producer | grep -q`
# under pipefail reads an early match as a failure (#634).
header="$("$READELF" -h "$SO")"; segments="$("$READELF" -l "$SO")"
dynamic="$("$READELF" -d "$SO")"; exported="$("$NM" -D --defined-only "$SO")"
machine="$(sed -n 's/^ *Machine: *//p' <<< "$header")"
[ "$machine" = "$want_machine" ] || die "$SO is a $machine binary, $TARGET needs $want_machine"
! grep -q 'INTERP' <<< "$segments" || die "$SO requests a program interpreter — it must be a plain shared library"
mapfile -t needed < <(sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' <<< "$dynamic")
for n in "${needed[@]}"; do
    jq -e --arg n "$n" '.build.allowed_needed | index($n)' "$CFG" > /dev/null \
        || die "$SO needs $n, which is not a platform library (allowed: $(jq -c .build.allowed_needed "$CFG"))"
done
grep -qw JNI_OnLoad <<< "$exported" || die "$SO does not export JNI_OnLoad — RegisterNatives would never run"
loads=0
while read -r kind _ _ _ _ _ _ align; do
    [ "$kind" = LOAD ] || continue
    loads=$((loads + 1))
    [ $((align)) -ge "$PAGE" ] || die "$SO has a LOAD segment aligned to $align, below $PAGE"
done <<< "$(sed 's/ R E \| RW \| R / X /' <<< "$segments")"
[ "$loads" -gt 0 ] || die "no LOAD segment read from $SO — the alignment check checked nothing"
log "$TARGET: $SO — $(stat -c%s "$SO") bytes, $machine, NEEDED ${needed[*]}"
