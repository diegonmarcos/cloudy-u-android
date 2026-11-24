#!/usr/bin/env bash
# Build the firestack netstack aar that libs:firewall consumes.
#
# This lives WITH the module, not inside one consumer, because libs:firewall has
# two of them: aa_cloud-superapp (which compiles it into Cloud-SuperApp.apk) and
# ab_cloud-libs-shared/lib-apks (which ships it as Cloud-Lib-Firewall.apk). The
# build step used to exist only in the superapp's build.sh, so lib-apks could
# never produce the aar — which is why the dependency could not be declared in
# libs/firewall/build.gradle without breaking ship-cloud-libs. One script, both
# callers, no duplicated Go/gomobile logic to drift.
#
# ENV-AGNOSTIC BY DESIGN: it assumes it is ALREADY running wherever ANDROID_HOME
# and an NDK are visible. Each caller wraps it in its own devShell
# (`in_nix bash build-firestack.sh`), because the superapp resolves that through
# its flake while lib-apks runs under BYPASS_NIX=1 with the SDK the CI action
# installed. Baking either assumption in here would break the other.
#
# Idempotent: exits 0 immediately if the aar is already present.
#
# Usage:  build-firestack.sh [--force]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # libs/firewall
SHARED_ROOT="$(cd "$HERE/../.." && pwd)"                       # ab_cloud-libs-shared
CFG="$SHARED_ROOT/build.json"
SRC="$HERE/firestack"                                          # vendored source AND aar home

log()    { printf '  \033[36m•\033[0m %s\n' "$*"; }
errlog() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; }

command -v jq >/dev/null 2>&1 || { errlog "firestack: jq is required"; exit 1; }
[ -f "$CFG" ] || { errlog "firestack: no $CFG"; exit 1; }

cfgv() { jq -r "$1 // empty" "$CFG"; }

AAROUT="$(cfgv '.firestack.build.aar_out')"
[ -n "$AAROUT" ] || { errlog "firestack: build.json has no .firestack.build.aar_out"; exit 1; }
AAR="$SRC/$AAROUT"

if [ "${1:-}" != "--force" ] && [ -f "$AAR" ]; then
  log "firestack: aar already present ($AAR) — skipping"
  exit 0
fi

GOVER="$(cfgv '.firestack.build.go_version')"
GOSHA="$(cfgv '.firestack.build.go_sha256_linux_amd64')"
TARGET="$(cfgv '.firestack.build.make_target')"
AARBUILT="$(cfgv '.firestack.build.aar_built')"
API="$(cfgv '.firestack.build.android_api')"
TAGS="$(cfgv '.firestack.build.gomobile_tags')"
# go-patch-overlay is a build tool, not a module dependency, so go.mod cannot
# pin it. REQUIRED here, not tolerated as empty: this script is what seeds the
# module cache, and it can only seed a version it was told. Falling through to
# the Makefile's own default would leave the tool unseeded and the build would
# die on it under GOPROXY=off, twenty minutes later and nowhere near the cause.
GPOV="$(cfgv '.firestack.build.go_patch_overlay_version')"
[ -n "$GPOV" ] || { errlog "firestack: build.json has no .firestack.build.go_patch_overlay_version"; exit 1; }
VARIANT="${SUPERAPP_VARIANT:-}"
GT="$(jq -r --arg v "$VARIANT" \
      '.firestack.build.gomobile_targets[$v] // .firestack.build.gomobile_targets[""] // empty' "$CFG")"

# Vendored tree: no .git, so go.mod is the marker that the source is usable.
# Testing for .git here would be false forever and re-clone over committed code.
[ -f "$SRC/go.mod" ] || { errlog "firestack: vendored source missing at $SRC (expected go.mod)"; exit 1; }

# The pinned Go tarball + sha in build.json are linux/amd64 (the x86 CI runner).
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) : ;;
  *) errlog "firestack: pinned Go is linux-amd64 only (host: $(uname -s)-$(uname -m)). Build on the x86 runner, or add this arch's tarball+sha to build.json::firestack.build."; exit 1 ;;
esac

[ -n "${ANDROID_HOME:-}" ] || { errlog "firestack: ANDROID_HOME unset — call this inside the devShell / after setup-android"; exit 1; }

CACHE="${FIRESTACK_CACHE:-$SHARED_ROOT/.cache}"
GODIR="$CACHE/golang"
export GOPATH="$CACHE/gopath" GOBIN="$CACHE/gopath/bin" GOTOOLCHAIN=local
# GOMODCACHE is named explicitly rather than left to default under GOPATH so CI
# can persist exactly this one directory across runs. gomobile also passes
# GOMODCACHE down to the `go` commands it spawns, so the cache seeded below is
# the same one the bind reads.
export GOMODCACHE="$CACHE/gomodcache"
mkdir -p "$GODIR" "$GOPATH" "$GOMODCACHE"

TARBALL="go${GOVER}.linux-amd64.tar.gz"
if [ ! -x "$GODIR/go/bin/go" ]; then
  log "firestack: downloading Go $GOVER"
  curl -fLso "$GODIR/$TARBALL" "https://go.dev/dl/$TARBALL"
  echo "$GOSHA  $GODIR/$TARBALL" | sha256sum -c -
  rm -rf "$GODIR/go"; tar -C "$GODIR" -xzf "$GODIR/$TARBALL"
fi
export PATH="$GODIR/go/bin:$GOBIN:$PATH"

# gomobile finds the NDK via ANDROID_NDK_HOME; take the newest present rather
# than pinning a version that the SDK action may stop installing.
NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
[ -n "$NDK" ] || { errlog "firestack: no NDK under $ANDROID_HOME/ndk"; exit 1; }
export ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK"

# SEED, then BUILD OFFLINE. These two steps are the whole reason this build is
# reproducible, and they must stay in this order.
#
# Dependency resolution happens HERE, once, and only against the committed
# go.mod/go.sum: `go mod download` verifies every module against the checksums
# in go.sum and refuses anything that does not match. Nothing is resolved
# afterwards. The build itself then runs with GOPROXY=off, so it can only use
# what these pins delivered — a dependency the pins do not cover fails loudly
# with "module lookup disabled by GOPROXY=off" instead of being silently
# fetched from the network mid-build.
#
# Why this matters: `gomobile bind` shells out to `go mod tidy` in a throwaway
# module it synthesises per ABI (x/mobile cmd/gomobile/bind_androidapp.go, the
# goModTidyAt call after writeGoMod). That temp module has no go.sum, so tidy
# re-derives the ENTIRE dependency graph on every build. Three APK publishes
# were lost in one day to sum.golang.org returning a stream error partway
# through that, each time on a different module. Seeding first is what stops
# the aar depending on a third-party service being healthy.
# NOT `go mod download all`: the `all` pattern walks the test dependencies of
# dependencies, and to record them it WRITES new hashes into go.sum — a build
# step quietly editing the pins it is supposed to be constrained by, which is
# the same class of bug as the tidy. Bare `go mod download` covers what this
# module builds, verifies it against the committed go.sum, and leaves both files
# byte-identical.
log "firestack: seeding module cache from committed pins (go.mod/go.sum)"
( cd "$SRC" && go mod download ) || {
  errlog "firestack: seeding failed — the committed go.mod/go.sum do not cover this build."
  errlog "firestack: fix the pins in a commit; do NOT relax verification to get past this."
  exit 1
}

# The seed above covers the modules go.mod REQUIRES. It cannot cover a build
# tool go.mod deliberately does not require, and the Makefile installs exactly
# one of those: `go install github.com/felixge/go-patch-overlay@$(GOPATCHOVERLAY_VERSION)`
# in the $(GOMOBILE) recipe. That install was therefore the one piece of
# resolution still happening INSIDE the GOPROXY=off build, where it could only
# fail — "module lookup disabled by GOPROXY=off", exit 2, no aar, no APK. It was
# invisible while the tool was `@latest` on a runner whose module cache happened
# to carry it; pinning the version did not put it in the cache, it only made the
# gap deterministic. Seeded here, with the proxy still on, by the same rule as
# every other module: resolve once, then resolve nothing.
# SEED THE BINARIES, NOT JUST THE MODULES — AND INTO THE GOBIN THE MAKEFILE NAMES.
#
# Seeding the module cache was necessary and not sufficient, and runs
# 34462765453 / 34464514365 are what proved it. The $(GOMOBILE) recipe installs
# BOTH build tools into its own GOBIN ($SRC/bin), and that recipe runs INSIDE the
# GOPROXY=off build. `go install pkg@version` does not stop at the module cache:
# before it builds anything it asks the proxy whether that module is DEPRECATED,
# and with the proxy off the only answer available is
#
#     loading deprecation for github.com/felixge/go-patch-overlay:
#     module lookup disabled by GOPROXY=off
#
# The seed above put the module in the cache and then installed the binary into
# the DEFAULT GOBIN, which is not the directory the Makefile looks in — so the
# recipe ran anyway and died on the deprecation lookup, with the aar unbuilt and
# the owner's phone left on an APK from 2026-09-09.
#
# The invariant is unchanged and is now actually enforced: resolve once, here,
# with the proxy up; resolve NOTHING inside the offline build. The way to get
# there is to leave the recipe with nothing left to do. make rebuilds
# $(GOMOBILE) only when bin/gomobile is missing or older than go.mod, so seeding
# both binaries into $SRC/bin means the target is already up to date and the
# recipe never executes — no `go install` runs under GOPROXY=off because no
# command runs there at all.
#
# gomobile itself carries no @version (it resolves through go.mod), which is why
# its install was the one surviving the offline build; it is seeded here anyway
# so that bin/gomobile EXISTS, which is the whole mechanism.
GOBIN_MAKEFILE="$SRC/bin"

log "firestack: seeding pinned build tool go-patch-overlay@$GPOV into $GOBIN_MAKEFILE"
( cd "$SRC" && GOBIN="$GOBIN_MAKEFILE" go install "github.com/felixge/go-patch-overlay@$GPOV" ) || {
  errlog "firestack: could not seed go-patch-overlay@$GPOV — check .firestack.build.go_patch_overlay_version in build.json"
  exit 1
}

log "firestack: seeding gomobile at the version go.mod pins"
( cd "$SRC" && GOBIN="$GOBIN_MAKEFILE" go install golang.org/x/mobile/cmd/gomobile ) || {
  errlog "firestack: could not seed gomobile — the committed go.mod/go.sum do not cover golang.org/x/mobile."
  exit 1
}

# Part of the same recipe, so it belongs to the same seed: gomobile init writes
# the NDK toolchain gomobile bind later expects. Left to the recipe it would
# never run, because the recipe is precisely what we are arranging to skip.
log "firestack: gomobile init (NDK toolchain), while the proxy is still up"
( cd "$SRC" && PATH="$GOBIN_MAKEFILE:$PATH" "$GOBIN_MAKEFILE/gomobile" init ) || {
  errlog "firestack: gomobile init failed — the offline build would fail later, at the bind, with no useful message."
  exit 1
}

# The skip above is a TIMESTAMP argument, so assert the thing it rests on rather
# than trusting it. If bin/gomobile is absent the recipe runs, hits the proxy and
# dies twenty lines later quoting GOPROXY; saying so here names the real cause.
[ -x "$GOBIN_MAKEFILE/gomobile" ] || {
  errlog "firestack: $GOBIN_MAKEFILE/gomobile is missing after seeding — the Makefile would rebuild it inside the offline build and fail on 'module lookup disabled by GOPROXY=off'."
  exit 1
}

# SEED WHAT GOMOBILE RESOLVES FOR ITSELF, NOT JUST WHAT THIS MODULE BUILDS.
#
# Everything above seeds the modules THIS module builds. `gomobile bind` does
# not build the aar from this module's dependency graph, and it resolves twice
# more on its own — both times INSIDE the GOPROXY=off build:
#
#   1. getModuleVersions (x/mobile cmd/gomobile/bind.go:229-243) runs
#      `go list -m -json -tags=<tags> all` in THIS directory to learn the
#      versions it should write into the throwaway module it synthesises per
#      ABI. `go list -m all` walks the whole module GRAPH — it needs the go.mod
#      of every module in it, not only the ones this module builds — so a cache
#      seeded by bare `go mod download` does not satisfy it. Measured on a cache
#      seeded exactly that way: 80 lookups, every one refused.
#
#   2. goModTidyAt (cmd/gomobile/bind_androidapp.go:383) then runs `go mod tidy`
#      inside that throwaway module, whose go.mod is a FLAT require list. MVS
#      re-runs there without this module's graph pruning and can select HIGHER
#      versions than we pin — measured: testify v1.9.0 in the flat list pulls
#      stretchr/objx v0.5.2, where this module's own graph never goes past objx
#      v0.1.0. A version we do not pin is by definition not in a cache seeded
#      from our pins.
#
# When (1) fails, gomobile does not say so. bind.go:240-243 turns the error into
# `return nil, nil`, and writeGoMod (bind.go:318-325) treats that nil as "no
# module info" and writes a ZERO-BYTE go.mod. The build then dies in (2) with
#
#     go: error reading go.mod: missing module declaration
#
# which names neither GOPROXY nor the module that was missing, and points at a
# file gomobile itself had just emptied. That is the failure that broke every
# Cloud SuperApp publish from 2026-09-09 17:14 onward: the offline seeding was
# right in intent and incomplete in fact, and the incompleteness was invisible.
#
# So resolve both HERE, with the proxy still up, by the same rule as every other
# module: resolve once, then resolve nothing. Neither step may touch the
# committed pins — asserted below rather than assumed.
PINS_BEFORE="$(sha256sum "$SRC/go.mod" "$SRC/go.sum")"
GOMOBILE_GOARCH="${GT##*/}"

MODLIST="$(mktemp)"
REPLICA="$(mktemp -d)"
trap 'rm -rf "$REPLICA" "$MODLIST"' EXIT

log "firestack: seeding the module graph gomobile's own 'go list -m all' walks (abi=$GT)"
( cd "$SRC" && GOOS=android GOARCH="$GOMOBILE_GOARCH" go list -m -tags="$TAGS" all ) >"$MODLIST" || {
  errlog "firestack: could not resolve the module graph gomobile needs — the committed pins do not cover it."
  errlog "firestack: fix the pins in a commit; do NOT relax GOPROXY to get past this."
  exit 1
}

# The replica stands in for the module gomobile will synthesise: same module
# name, same flat require list, same local replace for this module, importing
# the same packages the bind exports. Tidying it with the proxy UP pulls that
# closure into GOMODCACHE, so the real tidy inside the offline build finds
# everything already there. It is a THROWAWAY: the go.sum this tidy writes is
# its own, in a temp directory, and is discarded with it.
mapfile -t BINDPKGS < <(jq -r '.firestack.build.bind_packages[]? // empty' "$CFG")
[ "${#BINDPKGS[@]}" -gt 0 ] || {
  errlog "firestack: build.json has no .firestack.build.bind_packages — cannot pre-resolve what gomobile binds"
  exit 1
}

{
  echo "module gobind"
  echo
  echo "go ${GOVER%.*}"
  echo
  # `go list -m all` prints "path version"; the main module prints its path
  # alone, which is why a bare NF==1 line becomes the local replace rather than
  # a require. A replaced module prints "path version => ..." — the => field is
  # skipped so the replacement, not the placeholder, is what gets seeded.
  awk -v src="$SRC" '
    NF == 1     { printf "replace %s => %s\n", $1, src; next }
    $2 !~ /^=>/ { printf "require %s %s\n", $1, $2 }
  ' "$MODLIST"
} >"$REPLICA/go.mod"

{
  echo "package gobindseed"
  echo
  printf 'import _ "%s"\n' "${BINDPKGS[@]}"
} >"$REPLICA/seed.go"

log "firestack: seeding the closure gomobile's throwaway-module tidy resolves"
( cd "$REPLICA" && GOOS=android GOARCH="$GOMOBILE_GOARCH" CGO_ENABLED=1 go mod tidy ) || {
  errlog "firestack: could not pre-resolve what gomobile's tidy needs."
  errlog "firestack: if .firestack.build.bind_packages no longer matches the Makefile's bind targets, fix it there."
  exit 1
}

[ "$PINS_BEFORE" = "$(sha256sum "$SRC/go.mod" "$SRC/go.sum")" ] || {
  errlog "firestack: seeding REWROTE the committed go.mod/go.sum — the step that enforces the pins just edited them."
  exit 1
}

# THE CHECK THIS BUILD DID NOT HAVE.
#
# Run gomobile's own resolution in the state the bind will run in. If it cannot
# resolve here it will not resolve there either — but here it is a named,
# fatal error instead of an empty go.mod that gomobile hands to tidy.
#
# Empty output is a FAILURE, not a pass: `go list` exiting 0 with nothing to say
# is precisely the shape bind.go mistakes for success, so testing the exit
# status alone would reproduce gomobile's own bug inside the guard against it.
log "firestack: verifying gomobile can resolve with the proxy off (pre-flight)"
if ! offline_list="$( cd "$SRC" && GOPROXY=off GOOS=android GOARCH="$GOMOBILE_GOARCH" go list -m -tags="$TAGS" all 2>&1 )"; then
  errlog "firestack: gomobile's 'go list -m all' cannot resolve offline. gomobile would SWALLOW this"
  errlog "firestack: (bind.go:240-243), write an empty go.mod, and fail in tidy with 'missing module declaration'."
  printf '         %s\n' "$(printf '%s\n' "$offline_list" | head -5)" >&2
  exit 1
fi
[ -n "$offline_list" ] || {
  errlog "firestack: gomobile's 'go list -m all' resolved offline but returned NOTHING — gomobile would read that"
  errlog "firestack: as 'no module info' and write a zero-byte go.mod. Refusing to build an aar nobody can use."
  exit 1
}

log "firestack: building netstack aar — $(go version); ndk=$(basename "$NDK"); abi=$GT (slow)"
make -C "$SRC" clean || true
# Override firestack's Makefile ANDROID23 so gomobile builds ONE ABI, not all
# four: the default is ~4x the time and blows the CI job limit.
# GOPROXY=off: the build resolves nothing. See the seed step above.
GOPROXY=off make -C "$SRC" "$TARGET" \
  ANDROID23="-androidapi $API -target=$GT -tags=$TAGS -work" \
  ${GPOV:+GOPATCHOVERLAY_VERSION="$GPOV"}
cp "$SRC/$AARBUILT" "$AAR"

# Smoke-test: a truncated or empty aar resolves in gradle and fails at dex time,
# a long way from the cause.
[ -f "$AAR" ] || { errlog "firestack: aar not produced: $AAR"; exit 1; }
# An aar that is present but hollow is the failure mode worth catching, so this
# check must never be the thing that goes quiet. It used to `log` and continue
# when unzip was missing, which turns the one assertion standing between a
# broken aar and a published APK into a no-op on any host without unzip — and
# unzip is NOT installed everywhere this runs. python3 is, so the listing has a
# second source; if neither exists the build stops rather than shipping an
# unverified aar.
aar_entries() {
  if command -v unzip >/dev/null 2>&1; then
    unzip -l "$1"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys,zipfile; print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$1"
  else
    return 127
  fi
}

if ! entries="$(aar_entries "$AAR")"; then
  errlog "firestack: cannot inspect $AAR — neither unzip nor python3 is available."
  errlog "firestack: refusing to publish an aar whose contents were never checked."
  exit 1
fi
printf '%s' "$entries" | grep -q "classes.jar"         || { errlog "firestack: aar has no classes.jar"; exit 1; }
printf '%s' "$entries" | grep -q "AndroidManifest.xml" || { errlog "firestack: aar has no AndroidManifest.xml"; exit 1; }

# #1881 the aar now carries the mesh engine (com.celzero.firestack.mesh.Mesh) next to
# libwg-go, so it is the ONE Go binary of two VPN paths. Two things must hold before it can
# be published: the mesh facade is really bound, and every libgojni.so is linked for 16 KB pages
# (PT_LOAD p_align >= 16384, the same rule page-size-guard.json enforces on the APK, with no
# exemption). Checked here so a lost -Wl,-z,max-page-size flag turns THIS workflow red, not a phone.
python3 -I - "$AAR" <<'PYEOF' || { errlog "firestack: mesh/16 KB check failed on $AAR"; exit 1; }
import io, struct, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
cj = zipfile.ZipFile(io.BytesIO(z.read("classes.jar")))
if "com/celzero/firestack/mesh/Mesh.class" not in cj.namelist():
    sys.exit("aar has no com/celzero/firestack/mesh/Mesh.class (mesh package not bound)")
sos = [n for n in z.namelist() if n.endswith("/libgojni.so")]
if not sos:
    sys.exit("aar has no libgojni.so")
for n in sos:
    d = z.read(n)
    if d[:4] != b"\x7fELF" or d[4] != 2:
        sys.exit("%s: not a 64-bit ELF" % n)
    phoff, = struct.unpack_from("<Q", d, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
    for i in range(phnum):
        t, _f, _o, _v, _p, _fs, _ms, align = struct.unpack_from("<IIQQQQQQ", d, phoff + i * phentsize)
        if t == 1 and align < 16384:
            sys.exit("%s: PT_LOAD p_align %d < 16384" % (n, align))
    print("firestack: %s PT_LOAD p_align >= 16384" % n)
PYEOF

log "firestack: → $AAR ($(du -h "$AAR" 2>/dev/null | cut -f1))"
