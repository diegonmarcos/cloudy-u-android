#!/usr/bin/env bash
# #860/#866 Store download — a failed lookup NAMES THE RESOLVER THAT WAS ASKED.
#
# MEASURED 2026-10-06: "could not download lib-calc: release → DNS: cannot
# resolve github.com (active resolver: unknown) | ghcr → … (active resolver:
# unknown) | mesh → DNS: cannot resolve git-proxy-api.mesh.diegonmarcos.com (active resolver:
# unknown)" on a phone whose DNS page resolved github.com through the system
# resolver under "Mirror Android", mesh down. The downloader (plain
# HttpURLConnection = Android's system resolver, DIRECT) is what the preset
# promises; the hook that names it only knew the routes StoreDns had WALKED,
# and nothing was walked when the system lookup went straight to the socket.
#
#   R1  the three-rung ladder stays: mesh, release, ghcr — mesh first (#1093, like every fleet endpoint), the public legs after it
#   R2  StoreDns names the resolver that was asked, never null (the "unknown"
#       wording stays reserved for a host without the hook). #874: that
#       resolver is the fleet DNS bridge, and a miss lists the routes it tried
#   R3  the mesh leg reports "mesh down" from the tunnel's own state instead of
#       a DNS failure on the declared name, and SuperApp hands it that state
#   R4  the public legs resolve through the ONE bridge of the process (#874,
#       test-store-download-via-bridge.sh) — no OkHttp Dns, no DoH, no second
#       resolver in the lib's download path
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
FLEET="$LIBS/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
SRC="$LIBS/updater/src/main/java/com/diegonmarcos/superapp/updater/source"
STOREDNS="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreDns.kt"
MAIN="$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt"
MAIN_FLEETDNS="$APP/app/src/main/java/com/diegonmarcos/superapp/network/FleetDns.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$FLEET" "$SRC/ApkSource.kt" "$SRC/DownloadFailure.kt" "$STOREDNS" "$MAIN"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== R1: the ladder is mesh → release → ghcr, mesh first =="
grep -q 'listOf(MeshMirrorSource, ReleaseSource, GhcrSource)' "$FLEET" \
  && ok "Fleet.sources = mesh, release, ghcr" || bad "Fleet's source list is not mesh, release, ghcr"
grep -q 'declined += "${source.name} → ${com.diegonmarcos.superapp.updater.source.DownloadFailure.describe(t)}"' "$FLEET" \
  && ok "each declined rung is named with DownloadFailure.describe" || bad "a declined rung no longer carries its classified reason"
grep -q '"DNS: cannot resolve ${host(t) ?: "the download host"} (active resolver: ${resolver?.takeIf { it.isNotBlank() } ?: "unknown"})"' "$SRC/DownloadFailure.kt" \
  && ok "the DNS wording keeps the host and the active resolver" || bad "DownloadFailure's DNS wording changed"

echo "== R2: StoreDns names the resolver that was asked =="
hook="$(awk '/val named: \(String\?\) -> String\? = \{/,/^        \}/' "$STOREDNS")"
printf '%s\n' "$hook" | grep -q '?: runCatching { resolverLabel() }.getOrDefault("bridge")' \
  && ok "the hook falls back to the resolver's own name (bridge 127.0.0.1:<port>)" \
  || bad "the hook does not name the resolver: a lookup that walked nothing reads as unknown again"
printf '%s\n' "$hook" | grep -q '?: net$' \
  && bad "the hook still ends on the bare network summary, which is null without an active network" \
  || ok "the hook never yields null for a wired host"
printf '%s\n' "$hook" | grep -q 'relay.failureFor(host)?.let { "resolvers tried: $it" }' \
  && ok "a miss still lists the routes tried" || bad "the routes tried are no longer named"
grep -q 'fun mirror(preset: String? = null): List<Route>' "$LIBS/sysdns/src/main/java/com/diegonmarcos/cloudlib/sysdns/FleetDnsBridge.kt" \
  && grep -q 'FleetDnsBridge.mirror(label)' "$MAIN_FLEETDNS" \
  && ok "Mirror's routes are Android's resolver, labelled with the preset" \
  || bad "Mirror's plan no longer names the Android system resolver with the preset"

echo "== R3: the mesh leg says 'mesh down' from the tunnel's state =="
grep -q '@Volatile var meshUp: (Context) -> Boolean? = { null }' "$SRC/ApkSource.kt" \
  && ok "MeshMirror.meshUp hook, unknown by default" || bad "MeshMirror has no meshUp hook"
grep -q 'val up: Boolean? = try { meshUp(ctx) } catch (_: Exception) { null }' "$SRC/ApkSource.kt" \
  && grep -q 'if (up == false)' "$SRC/ApkSource.kt" \
  && grep -q 'throw java.io.IOException("mesh down: ' "$SRC/ApkSource.kt" \
  && ok "a down mesh fails the leg as 'mesh down' before any lookup" \
  || bad "the mesh leg still resolves git-proxy-api.mesh.diegonmarcos.com with the mesh down"
grep -q 'MeshMirror.meshUp = { c -> com.diegonmarcos.superapp.network.FleetDns.meshUp(c) }' "$MAIN" \
  && ok "SuperApp hands FleetDns.meshUp to the mesh leg" || bad "SuperApp does not wire MeshMirror.meshUp"
grep -q 'StoreDns.start(dnsCtx)' "$MAIN" \
  && ok "SuperApp still starts StoreDns (the resolver-naming hook)" || bad "StoreDns.start is gone from App.onCreate"

echo "== R4: the public legs resolve through the one bridge, with no resolver of the lib's own =="
grep -q 'URL(current).openConnection() as HttpURLConnection' "$SRC/Download.kt" \
  && ok "Download opens plain HttpURLConnections (the selector routes them)" || bad "Download no longer uses HttpURLConnection"
grep -rqE 'okhttp|OkHttp|dns-over-https|DnsResolver|127\.0\.0\.1:2053' "$SRC" "$FLEET" \
  && bad "a custom resolver crept into the lib's download path" || ok "no custom Dns / DoH / port literal in the download path"
grep -q 'bridge = { h -> try { resolve(h) }' "$STOREDNS" \
  && ok "StoreDns's first rung is the host's bridge (resolve hook)" || bad "StoreDns does not resolve through the bridge"

echo "== RESULT(#860/#866 store download resolver): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
