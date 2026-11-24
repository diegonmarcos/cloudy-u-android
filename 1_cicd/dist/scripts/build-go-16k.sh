# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/build-go-16k.sh ───
#!/usr/bin/env bash
# build-go-16k.sh <name> <ldflags> <package> <out> — build a static Go binary whose PT_LOAD segments are all
# aligned to 16384 (#1881). Run from the module root. First the Go linker's own rounding quantum (-R=16384, CGO
# off); if the result is still below 16 KB, redo it with the external linker and -Wl,-z,max-page-size=16384
# (cgo on, netgo + osusergo, -static). Fails when neither path produces a 16 KB aligned, PT_INTERP-free binary.
set -euo pipefail
name="$1"; ld="$2"; pkg="$3"; out="$4"
mkdir -p "$(dirname "$out")"

aligned() { # every LOAD p_align >= 16384 and no INTERP
  readelf -lW "$1" | grep -E 'LOAD|INTERP'
  ! readelf -lW "$1" | grep -q INTERP || return 1
  local a
  for a in $(readelf -lW "$1" | awk '/LOAD/ { print $NF }'); do [ "$((a))" -ge 16384 ] || return 1; done
}

echo "== $name: internal linker, -R=16384"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "$ld -R=16384" -o "$out" "$pkg"
if aligned "$out"; then echo "$name: aligned with -R=16384"; exit 0; fi

echo "== $name: -R=16384 did not align it; external linker"
CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -trimpath -tags netgo,osusergo \
  -ldflags "$ld -linkmode=external -extldflags '-static -Wl,-z,max-page-size=16384'" -o "$out" "$pkg"
aligned "$out" || { echo "$name: still not 16 KB aligned"; exit 1; }
echo "$name: aligned with the external linker"
