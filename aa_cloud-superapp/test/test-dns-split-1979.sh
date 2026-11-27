#!/usr/bin/env bash
# #1979 Split DNS while the Cloud VPN is up: public pages never depend on the mesh resolver (the gcp-proxy hub).
# Static tester (the behaviour is asserted by FleetDnsSplit1979Test, run by the ship's JVM unit tests); every check has a mutation leg.
#   test-dns-split-1979.sh [ROOT]   check ROOT (default: the repo)     test-dns-split-1979.sh --mutants   apply each mutant to a copy, each must go RED
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
REL=(aa_cloud-superapp/build.json
  aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/network/FleetDns.kt
  ab_cloud-libs-shared/libs/sysdns/src/main/java/com/diegonmarcos/cloudlib/sysdns/FleetDnsBridge.kt
  aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/network/FleetDnsSplit1979Test.kt)

run() { # $1 = root; prints FAIL lines, returns 0 when green
  local R="$1" fails=0
  local BJ="$R/${REL[0]}" DNS="$R/${REL[1]}" BR="$R/${REL[2]}" T="$R/${REL[3]}"
  for f in "$BJ" "$DNS" "$BR" "$T"; do [ -f "$f" ] || { echo "FAIL: missing $f"; return 1; }; done
  bad() { echo "FAIL: $1"; fails=$((fails+1)); }
  local out; out="$(python3 - "$BJ" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))['ui']['dns']
pr = []
z = d.get('mesh_zones', [])
if 'mesh.diegonmarcos.com' not in z: pr.append('mesh.diegonmarcos.com is not a mesh zone')
if 'diegonmarcos.com' in z: pr.append('the public zone diegonmarcos.com is a mesh zone (every public page goes to the hub)')
pn = d.get('private_names', [])
if not pn or not all(n.startswith('c3-') for n in pn): pr.append('private_names empty or not c3-*: %r' % pn)
pu = d.get('public_resolvers', [])
if not {'1.1.1.1', '1.0.0.1'} <= set(pu): pr.append('public_resolvers lacks 1.1.1.1 / 1.0.0.1: %r' % pu)
if any(a.startswith(('10.', 'fd')) for a in pu): pr.append('a public resolver is a mesh address')
t = d.get('mesh_timeout_ms')
if not isinstance(t, int) or not 0 < t <= 800: pr.append('mesh_timeout_ms %r is not within 1..800' % t)
print('; '.join(pr) or 'OK')
PY
)"
  [ "$out" = OK ] || bad "build.json::ui.dns: $out"
  grep -qF 'if (d.publicResolvers.isNotEmpty()) return own.filter { it !in fleet }.ifEmpty { d.publicResolvers }' "$DNS" || bad "queryPlan does not send a public name to the public resolvers"
  grep -qF '(d.meshZones + d.privateNames).any' "$DNS" || bad "isMeshName ignores the declared private names"
  grep -qF 'timeoutMs = decl.meshTimeoutMs' "$DNS" || bad "the mesh route does not carry the declared timeout"
  grep -qF 'up && fleet.isNotEmpty() && decl.publicResolvers.isNotEmpty() ->' "$DNS" || bad "bridgeRoutes asks Android's resolver (the hub) for a public name"
  grep -qF 'if (r.timeoutMs > 0) 1 else TRIES_PER_ROUTE' "$BR" || bad "a mesh route is retried instead of asked once"
  grep -qF 's.soTimeout = routeTimeout.get()' "$BR" || bad "the route timeout is not applied to the socket"
  for n in publicPagesGoToThePublicResolversAndNeverToTheMesh meshAndPrivateNamesGoToTheMeshResolver \
           withTheMeshResolverStalledPublicNamesStillResolveWithinASecond aStalledMeshNameIsAskedOnceAndReportedUnreachable; do
    grep -qF "fun $n()" "$T" || bad "test $n is gone"
  done
  if grep -nE '"(1\.1\.1\.1|1\.0\.0\.1|c3-pvt\.diegonmarcos\.com|mesh\.diegonmarcos\.com)"' "$DNS" "$BR" >/dev/null; then bad "a declared address or name is a Kotlin literal"; fi
  [ $fails -eq 0 ]
}

if [ "${1:-}" = "--mutants" ]; then
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  fresh() { rm -rf "$TMP/r"; for f in "${REL[@]}"; do mkdir -p "$TMP/r/$(dirname "$f")"; cp "$REPO/$f" "$TMP/r/$f"; done; }
  fresh; run "$TMP/r" >/dev/null || { echo "BASELINE RED"; run "$TMP/r"; exit 1; }
  echo "baseline green"
  survived=0
  mut() { # name file sed-expr
    fresh; sed -i "$3" "$TMP/r/$2"
    if cmp -s "$TMP/r/$2" "$REPO/$2"; then echo "MUTANT DID NOT APPLY: $1"; survived=$((survived+1)); return; fi
    if run "$TMP/r" >/dev/null; then echo "SURVIVED: $1"; survived=$((survived+1)); else echo "KILLED: $1"; fi
  }
  B="${REL[0]}"; D="${REL[1]}"; W="${REL[2]}"; T="${REL[3]}"
  mut public-zone-is-mesh      "$B" 's#"mesh_zones": \["app", "db", "mesh.diegonmarcos.com"\]#"mesh_zones": ["app", "db", "mesh.diegonmarcos.com", "diegonmarcos.com"]#'
  mut no-private-names         "$B" 's#"private_names": \["c3-pvt.diegonmarcos.com"\]#"private_names": []#'
  mut public-resolver-is-mesh  "$B" 's#"public_resolvers": \["1.1.1.1", "1.0.0.1"\]#"public_resolvers": ["10.0.0.1"]#'
  mut slow-mesh-timeout        "$B" 's#"mesh_timeout_ms": 800#"mesh_timeout_ms": 2500#'
  mut public-name-asks-fleet   "$D" 's#if (d.publicResolvers.isNotEmpty()) return#if (false) return#'
  mut private-names-ignored    "$D" 's#(d.meshZones + d.privateNames).any#(d.meshZones).any#'
  mut no-mesh-timeout          "$D" 's#timeoutMs = decl.meshTimeoutMs#timeoutMs = 0#'
  mut android-resolver-first   "$D" 's#up \&\& fleet.isNotEmpty() \&\& decl.publicResolvers.isNotEmpty() ->#false ->#'
  mut mesh-retried             "$W" 's#if (r.timeoutMs > 0) 1 else TRIES_PER_ROUTE#TRIES_PER_ROUTE#'
  mut timeout-not-applied      "$W" 's#s.soTimeout = routeTimeout.get()#s.soTimeout = timeoutMs#'
  mut test-dropped             "$T" 's#fun aStalledMeshNameIsAskedOnceAndReportedUnreachable()#fun renamed()#'
  mut literal-resolver         "$D" 's#^object FleetDns {#object FleetDns {\n    private const val X = "1.1.1.1"#'
  [ $survived -eq 0 ] && { echo "ALL MUTANTS KILLED"; exit 0; } || { echo "$survived mutant(s) survived"; exit 1; }
fi

ROOT="${1:-$REPO}"
if run "$ROOT"; then echo "OK: split DNS declared and wired"; else exit 1; fi
