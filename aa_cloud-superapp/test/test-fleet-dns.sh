#!/usr/bin/env bash
# #740 Configs ▸ Setup ▸ Network ▸ DNS — the fleet resolver, chosen once and
# applied at the SuperApp's VPN.
#
#   D1  the page: config/dns sits right after Cloud Mesh in Setup ▸ Network and
#       SectionPages routes it to DnsFragment
#   D2  the declaration (build.json::ui.dns) is complete: a usable default, one
#       Mirror, public presets with a primary AND a fallback list, a Private-only
#       that can take no fallback, a Private-with-fallbacks whose choices are
#       declared presets, a reason on every unavailable preset, mesh zones that
#       hold the mesh test name and not the public one
#   D3  ONE application point: every path that brings the tunnel UP hands it
#       toTunnelConfig() (the fleet choice), and that is built from
#       FleetDns.vpnServers — no connect path may bypass it with toWgConfig()
#   D4  data-driven: the declaration is baked (UI_DNS_B64) and read, and no
#       resolver address from it is written in any Kotlin source
#   D5  the behaviour guards live in FleetDnsTest and none of them was dropped
#   D6  #751 the choice holds without the mesh: ui.dns.mesh_down declares the
#       peerless tunnel; the engine (Cloud-Lib-Net-Wg, owner of the one VPN
#       slot) hands the slot to it when one of its own tunnels goes down,
#       through two AIDL calls appended after every older one; the DNS page and
#       the launcher's start push it, only for an explicit choice, and neither
#       takes the slot from the firewall
#   D7  #794 a chosen preset is never silently not in effect: FleetDns.verdict
#       holds the promise against the DNS Android really hands out; missing VPN
#       consent for the engine raises the fleet alert that opens the page,
#       whose one tap launches the engine's consent FOR A RESULT and then
#       syncs and re-checks; the same check runs at the launcher's start and on
#       an engine update/reinstall. The page and /api/net/dns/overview share
#       ONE collector (DnsOverview), which asks every member for /api/net/dns,
#       /api/sysdns/state (the terminals' SystemDnsBridge counters) and, for a
#       bridge holder, its crash reports (#791). Every D7 rule is
#       mutation-proved: each mutant is checked to have APPLIED, then to FAIL.
#
# WHAT each preset puts on the VPN (Private-only has no fallback, split DNS for
# mesh names, fail-loud without a fleet resolver) is asserted by
# app/src/test/.../network/FleetDnsTest.kt on the declaration this build bakes.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
PAGES="$SRC/launcher/SectionPages.kt"
DNS="$SRC/network/FleetDns.kt"
FRAG="$SRC/network/DnsFragment.kt"
WGP="$SRC/network/WireGuardPrefs.kt"
GRADLE="$APP/app/build.gradle"
UT="$APP/app/src/test/java/com/diegonmarcos/superapp/network/FleetDnsTest.kt"
APPKT="$SRC/App.kt"
LIBS="$APP/../ab_cloud-libs-shared/libs"
ENGINE="$LIBS/net-wg/src/main/java/com/diegonmarcos/superapp/netwg/NetBackendService.kt"
AIDL="$LIBS/net/src/main/aidl/com/diegonmarcos/superapp/net/INetBackend.aidl"
CLIENT="$LIBS/net/src/main/java/com/diegonmarcos/superapp/net/AidlBackend.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BJ" "$PAGES" "$DNS" "$FRAG" "$WGP" "$GRADLE" "$UT" "$APPKT" "$ENGINE" "$AIDL" "$CLIENT"; do
  [ -f "$f" ] || { echo "ERROR: missing $f — a check over nothing passes" >&2; exit 2; }
done
command -v python3 >/dev/null || { echo "ERROR: python3 missing" >&2; exit 2; }

echo "== D1: the page =="
out="$(python3 - "$BJ" <<'EOF'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
mesh = [p['id'] for p in pages if p.get('group') == 'Setup' and p.get('subgroup') == 'Network' and not p.get('hidden')]
dns = next((p for p in pages if p['id'] == 'dns'), {})
print('MESH', ','.join(mesh))
print('LABEL', dns.get('label'), dns.get('hidden', False))
EOF
)"
echo "$out" | sed 's/^/    /'
echo "$out" | grep -qE '^MESH firewall,dns,(webserver,)?wg,' && ok "DNS is the tile right after Firewall, before Cloud Mesh, in Setup ▸ Network" \
  || bad "config/dns is not declared second (after firewall, before wg) in Setup ▸ Network"
echo "$out" | grep -qx 'LABEL DNS False' && ok "the page reads DNS and is a visible tile" \
  || bad "config/dns is missing, hidden or not labelled DNS"
grep -qE 'pageId == "dns" +-> com\.diegonmarcos\.superapp\.network\.DnsFragment\.newInstance\(\)' "$PAGES" \
  && ok "SectionPages routes config/dns to DnsFragment" \
  || bad "no SectionPages branch opens DnsFragment for config/dns — the tile would open the placeholder"

echo "== D2: the declaration =="
out="$(python3 - "$BJ" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))['ui'].get('dns')
if not d: print('no ui.dns'); sys.exit()
ps = {p['id']: p for p in d.get('presets', [])}
pr = []
if len(ps) != len(d.get('presets', [])): pr.append('duplicate preset id')
df = ps.get(d.get('default_preset'))
if not df or df.get('available', True) is False: pr.append('default_preset %r is not a usable preset' % d.get('default_preset'))
for p in ps.values():
    if p.get('kind') not in ('mirror', 'public', 'private', 'warp'): pr.append('%s: unknown kind %r' % (p['id'], p.get('kind')))
    if p.get('available', True) is False and not str(p.get('unavailable_reason', '')).strip():
        pr.append('%s is unavailable without a reason' % p['id'])
    if p.get('kind') == 'public' and (not p.get('servers') or not p.get('fallback')):
        pr.append('%s: a public preset needs servers AND fallback' % p['id'])
if [p['id'] for p in ps.values() if p.get('kind') == 'mirror'].__len__() != 1: pr.append('not exactly one mirror preset')
priv = [p for p in ps.values() if p.get('kind') == 'private']
only = [p for p in priv if not p.get('fallback_allowed')]
fb   = [p for p in priv if p.get('fallback_allowed')]
if len(only) != 1: pr.append('expected ONE private preset with no fallback, got %d' % len(only))
for p in only:
    if p.get('fallback_choices') or p.get('default_fallbacks') or p.get('servers') or p.get('fallback'):
        pr.append('%s is private-only yet declares fallbacks or servers of its own' % p['id'])
if len(fb) != 1: pr.append('expected ONE private preset with fallbacks, got %d' % len(fb))
for p in fb:
    ch = p.get('fallback_choices', [])
    if not ch: pr.append('%s offers no fallback choices' % p['id'])
    for c in ch:
        if c not in ps or ps[c].get('kind') not in ('public', 'warp'):
            pr.append('%s: fallback choice %r is not a declared public/warp preset' % (p['id'], c))
    for c in p.get('default_fallbacks', []):
        if c not in ch: pr.append('%s: default fallback %r is not one of its choices' % (p['id'], c))
z = d.get('mesh_zones', [])
inzone = lambda n: any(n == x or n.endswith('.' + x) for x in z)
tn = d.get('test_names', {})
if not z: pr.append('no mesh_zones')
if not inzone(tn.get('mesh', '')): pr.append('test_names.mesh %r is in no mesh zone' % tn.get('mesh'))
if not tn.get('public') or inzone(tn['public']): pr.append('test_names.public %r is missing or a mesh name' % tn.get('public'))
modes = [m['id'] for m in d.get('android_private_dns', {}).get('modes', [])]
if modes != ['off', 'opportunistic', 'hostname']: pr.append('android_private_dns.modes = %r, not Android\'s three' % modes)
req = d.get('android_private_dns', {}).get('private_presets_require')
if req not in modes: pr.append('android_private_dns.private_presets_require = %r is not a declared mode (#794: Automatic Private DNS pre-empts the fleet resolver)' % req)
print('; '.join(pr) or 'OK')
EOF
)"
[ "$out" = OK ] && ok "ui.dns: usable default, one mirror, public primary+fallback, private-only takes none, fallbacks name declared presets, mesh zones hold the mesh test name" \
  || bad "ui.dns: $out"

echo "== D3: one application point =="
n_fleet=$(grep -rnE 'toTunnelConfig\(\)' "$SRC" --include=*.kt | grep -v 'fun toTunnelConfig' | grep -c .)
# toWgConfig() is the literal form; its ONE legitimate caller is the .conf export
# (exportLauncher in WireGuardFragment). Any other caller is a path that would
# bring the tunnel up — or hand it on — without the fleet's DNS.
callers=$(grep -rnF 'toWgConfig()' "$SRC" --include=*.kt | grep -v 'fun toWgConfig' || true)
export_ok=$(awk '/val exportLauncher/{f=1} /val vpnConsentLauncher/{f=0} f && /toWgConfig\(\)/{print FILENAME":"FNR}' "$SRC/network/WireGuardFragment.kt")
others=$(printf '%s\n' "$callers" | grep . | grep -v "^${export_ok}:" || true)
[ -n "$export_ok" ] && [ -z "$others" ] && ok "the .conf export is the only toWgConfig() caller — no path brings the tunnel up without the fleet DNS" \
  || { bad "toWgConfig() is called outside the export:"; printf '%s\n' "$others" | sed 's/^/      /'; }
[ "$n_fleet" -ge 4 ] && ok "$n_fleet connect paths hand the tunnel toTunnelConfig() (Mesh page, network popup, device controls, DNS page re-apply)" \
  || bad "only $n_fleet connect path(s) use toTunnelConfig() — expected the Mesh page, the network popup, the device controls and the DNS page"
grep -qF 'buildConfig(FleetDns.vpnServers(app, interfaceDns).joinToString(", "))' "$WGP" \
  && ok "toTunnelConfig() takes its DNS from FleetDns.vpnServers with the Cloud Mesh field as the fleet resolver" \
  || bad "toTunnelConfig() does not build its DNS from FleetDns.vpnServers"

echo "== D4: data-driven =="
grep -qF 'buildConfigField "String", "UI_DNS_B64", "\"${uiDnsB64}\""' "$GRADLE" && grep -qF 'stripDocs(buildJson.ui.dns)' "$GRADLE" \
  && ok "app/build.gradle bakes build.json::ui.dns as UI_DNS_B64" || bad "ui.dns is not baked into BuildConfig"
grep -qF 'BuildConfig.UI_DNS_B64' "$DNS" && ok "FleetDns reads the baked declaration" || bad "FleetDns does not read UI_DNS_B64"
lits="$(python3 - "$BJ" "$SRC" <<'EOF'
import json, sys, pathlib
d = json.load(open(sys.argv[1]))['ui']['dns']
addrs = {a for p in d['presets'] for k in ('servers', 'fallback') for a in p.get(k, [])}
addrs |= {h for p in d['presets'] for h in p.get('tls_hostnames', [])}
addrs |= set(d.get('android_private_dns', {}).get('hostname_suggestions', []))
hits = []
for f in pathlib.Path(sys.argv[2]).rglob('*.kt'):
    t = f.read_text(errors='replace')
    hits += ['%s: %s' % (f.name, a) for a in sorted(addrs) if '"%s"' % a in t]
print(len(addrs)); print('\n'.join(hits))
EOF
)"
n_addr=$(echo "$lits" | head -1); hits=$(echo "$lits" | tail -n +2 | grep . || true)
[ "$n_addr" -ge 6 ] && ok "checked $n_addr declared resolver addresses / hostnames" || bad "only $n_addr declared addresses — the literal check walks nothing"
[ -z "$hits" ] && ok "no declared resolver address is a string literal in Kotlin" \
  || { bad "resolver literals in Kotlin:"; echo "$hits" | sed 's/^/      /'; }

echo "== D5: the behaviour guards exist =="
for t in eachPublicPresetIsItsServersThenItsFallback mirrorPutsOnlyTheFleetResolversOnTheVpn \
         privateOnlyIsTheFleetResolverAndNothingElse privateWithFallbacksIsFleetFirstThenTheChosenFallbackInOrder \
         anUnavailablePresetIsNeverChosenNorUsedAsAFallback aPrivatePresetWithNoFleetResolverFailsLoudly \
         meshNamesGoToTheFleetResolverWhileTheMeshIsUp; do
  grep -qE "@Test fun $t\(\)" "$UT" && ok "FleetDnsTest.$t" || bad "FleetDnsTest.$t is gone"
done

echo "== D6: #751 the choice holds without the mesh =="
out="$(python3 - "$BJ" <<'EOF'
import json, sys, ipaddress
md = json.load(open(sys.argv[1]))['ui']['dns'].get('mesh_down') or {}
pr = []
n = md.get('tunnel_name', '')
if not n or len(n) > 15: pr.append('tunnel_name %r is empty or longer than an interface name' % n)
a = md.get('addresses', [])
if not a: pr.append('no addresses')
for x in a:
    try:
        net = ipaddress.ip_network(x, strict=True)
        if net.prefixlen != net.max_prefixlen: pr.append('%s is not a host prefix: it would route a subnet into a tunnel with no peer' % x)
    except ValueError as e: pr.append('%s: %s' % (x, e))
print('; '.join(pr) or 'OK')
EOF
)"
[ "$out" = OK ] && ok "ui.dns.mesh_down: a tunnel name and host-prefix addresses (the tunnel routes no subnet)" \
  || bad "ui.dns.mesh_down: $out"
awk '/isLockdownEnabled\(\);/{l=NR} /String setIdleTunnel\(/{s=NR} /String getIdleStatus\(\);/{g=NR} END{exit !(l && s>l && g>s)}' "$AIDL" \
  && ok "the AIDL appends setIdleTunnel/getIdleStatus after every older method (an older engine keeps its transaction codes)" \
  || bad "setIdleTunnel/getIdleStatus are missing or not the last methods of INetBackend.aidl"
grep -qF 'if (wasUp && want == Tunnel.State.DOWN && tunnel.getName() != idleName) reconcileIdle(leaving = tunnel.getName())' "$ENGINE" \
  && grep -qF 'if (wasUp && result != Tunnel.State.UP.name) reconcileIdle()' "$ENGINE" \
  && ok "the engine hands the slot to the mesh-down tunnel when a tunnel of its own leaves it (as one switch)" \
  || bad "NetBackendService.setState no longer hands the slot over on a real DOWN"
GOB="$LIBS/net-wg/src/main/java/com/wireguard/android/backend/GoBackend.java"
grep -qF 'if (!switching) try {' "$GOB" && grep -qF 'switching = true;' "$GOB" \
  && ok "GoBackend keeps its VpnService across a switch (a stop there races the next establish)" \
  || bad "GoBackend stops the VpnService mid-switch again — a re-sync from upstream dropped the #751 fix?"
grep -qF 'if (raiseNow || wgQuickConfig.isNullOrBlank()) reconcileIdle()' "$ENGINE" \
  && ok "an explicit push raises it only on the client's word (or releases it)" \
  || bad "setIdleTunnel raises without the client's word — it would revoke the firewall"
grep -qF 'it.setIdleTunnel(tunnelName, config?.toWgQuickString().orEmpty(), raiseNow)' "$CLIENT" \
  && ok "AidlBackend forwards setIdleTunnel" || bad "AidlBackend does not forward setIdleTunnel"
grep -qF 'meshDownConfig(decl, meshDownServers(decl, p.preset, p.fallbacks, fleet), fleet, self, sink)' "$DNS" \
  && grep -qF 'if (!p.chosen) null' "$DNS" \
  && ok "syncMeshDown builds the tunnel from meshDownServers, and only for an explicit choice" \
  || bad "syncMeshDown does not build from meshDownServers, or applies the unchosen default"
grep -qF 'FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))' "$FRAG" \
  && grep -qE 'if \(Prefs\(ctx\)\.chosen\) runCatching \{ syncMeshDown\(ctx, raiseNow\) \}' "$DNS" \
  && ok "every DNS page change pushes the mesh-down form (syncAndCheck → syncMeshDown), never over the firewall" \
  || bad "the DNS page does not push the mesh-down form (or ignores the firewall)"
grep -qF 'raiseNow = !com.diegonmarcos.superapp.firewall.FirewallController.isEnabled(this)' "$APPKT" \
  && ok "the launcher's start hands it back to an engine a reboot emptied" \
  || bad "App.onCreate no longer re-hands the mesh-down DNS"
for t in withoutTheMeshEachPresetKeepsWhatItCanStillReach theMeshDownTunnelRoutesNothingButAnUnreachableFleetResolver; do
  grep -qE "@Test fun $t\(\)" "$UT" && ok "FleetDnsTest.$t" || bad "FleetDnsTest.$t is gone"
done

echo "== D7: #794 the preset is in effect, or the page says so in red =="
OV="$SRC/network/DnsOverview.kt"
OVT="$APP/app/src/test/java/com/diegonmarcos/superapp/network/DnsOverviewTest.kt"
BRIDGE="$LIBS/sysdns/src/bridge/java/com/diegonmarcos/cloudlib/sysdns/SystemDnsBridge.java"
SYST="$LIBS/sysdns/src/test/java/com/diegonmarcos/cloudlib/sysdns/SysDnsTest.kt"
TX="$APP/../ac_cloud-termux/app/src/main/java/com/termux"
NX="$APP/../ac_cloud-nix-on-droid/app/src/main/java/com/termux/app"
for f in "$OV" "$OVT" "$BRIDGE" "$SYST" "$TX/app/TerminalDebugApi.java" "$TX/cloud/CloudDnsBridge.java" "$NX/TerminalDebugApi.java" "$NX/TermuxApplication.java"; do
  [ -f "$f" ] || { echo "ERROR: missing $f — a check over nothing passes" >&2; exit 2; }
done
# d7 role=path ...: every rule over code only (whole-line comments dropped). Exit 0 = all hold;
# 1 = a rule broke (each named on stdout).
d7() {
  python3 - "$@" <<'PY'
import json, re, sys
f = dict(a.split('=', 1) for a in sys.argv[1:])
def code(role):
    out = []
    for l in open(f[role], encoding='utf-8'):
        t = l.strip()
        if t.startswith(('//', '*', '/*')): continue
        out.append(l)
    return ''.join(out)
src = {r: code(r) for r in f if r != 'bj'}
pr = []
def need(role, text, why, n=1):
    if src[role].count(text) < n: pr.append('%s: %s' % (role, why))
need('dns', 'if (v.needsConsent) FleetAlerts.raise(', 'missing consent no longer raises the fleet alert')
need('dns', 'deepLink = "page:config/dns"', 'the alert no longer opens the DNS page')
need('dns', 'else FleetAlerts.withdraw(ctx, CONSENT_ALERT)', 'a good verdict no longer takes the alert back')
need('dns', 'idle.contains(BackendException.Reason.VPN_NOT_AUTHORIZED.name)', 'the verdict no longer recognises missing VPN consent')
need('dns', 'android.onVpn && actual.toSet() == want.toSet() ->', '"in effect" no longer needs the promised servers ON the VPN')
need('dns', 'android.privateDnsActive == true &&', 'the verdict no longer sees Android Private DNS in use pre-empting a private preset (#794)')
need('dns', 'privatePresetsRequire = apd.optString("private_presets_require")', 'the required Private DNS mode is not read from the declaration')
need('frag', 'registerForActivityResult(ActivityResultContracts.StartActivityForResult())', 'the consent is not launched for a result')
need('frag', 'WgState.backend(c).consentIntent()?.let { consentLauncher.launch(it) }', 'the one tap no longer launches the engine consent for a result')
need('frag', 'FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))', 'consent / a preset change no longer syncs and re-checks', 2)
need('frag', 'needsConsent = v.needsConsent', 'the consent state no longer follows the verdict')
need('frag', 'if (m.needsConsent) {', 'the consent button is no longer shown only while consent is missing')
need('frag', 'statusOk = v.ok', 'the verdict no longer reaches the status plate')
need('frag', 'plate = if (m.statusOk) Color(0x33000000) else Color(0x55DC2626)', 'a preset not in effect is no longer red')
need('app', 'FleetDns.syncAndCheck(this,', 'the launcher start no longer checks the choice took')
need('app', 'android.content.Intent.ACTION_PACKAGE_REPLACED', 'an engine update no longer re-checks')
need('app', 'com.diegonmarcos.superapp.net.AidlBackend.ENGINE_PKG', 'the package receiver is not about the engine')
need('app', 'DnsOverview.register(this)', '/api/net/dns/overview is not registered')
need('ov', 'AppDebugServer.route("net"', 'the overview is not served under /api/net')
need('ov', 'op == "dns/overview"', 'the overview op is not dns/overview')
for path in ('"net/dns"', '"sysdns/state"', '"diagnostics/crashes"'):
    need('ov', path, 'the overview no longer asks each member ' + path)
# #ipc every installed fleet peer, asked over the fleet IPC layer (StoreMesh.ask), not a loopback sweep
need('ov', 'FleetPeers.list(ctx).filter { it != ctx.packageName }', 'the overview no longer asks every fleet peer')
need('ov', 'StoreMesh.ask(ctx, pkg, port, op)', 'the overview no longer asks members over the fleet IPC layer')
need('bridge', 'public String stateJson()', 'the bridge has no state')
need('bridge', 'asked();', 'a UDP or TCP query is not counted', 2)
need('bridge', 'answered.incrementAndGet()', 'answers are not counted')
need('bridge', 'servfail.incrementAndGet()', 'SERVFAILs are not counted')
need('bridge', 'error("sysdns: an answer failed', 'a failed answer (#791 class) is not recorded')
need('txapi', '"state".equals(op) ? CloudDnsBridge.state() : null', 'cld.termux does not serve its bridge state')
need('txbridge', 'if (bridge != null) return bridge.stateJson();', 'cld.termux reports no live bridge state')
need('nxapi', '"state".equals(op) ? TermuxApplication.dnsBridgeState() : null', 'cld.termux.nix does not serve its bridge state')
need('nxapp', 'if (dnsBridge != null) return dnsBridge.stateJson();', 'cld.termux.nix reports no live bridge state')
for t in ('aChosenPresetTheEngineCannotStartForWantOfConsentIsRedAndAsksForIt', 'aPresetIsInEffectOnlyWhenAndroidResolvesWithExactlyItsServers',
          'onlyMissingConsentAsksForConsent', 'mirrorAndAnUnchosenDefaultWithoutTheMeshAreAndroidsOwnAndFine',
          'strictPrivateDnsBypassesAnyPresetAndSaysSo', 'aPrivatePresetWithNoFleetResolverIsRedNotAndroidsOwn'):
    need('ut', '@Test fun %s()' % t, 'FleetDnsTest.%s is gone' % t)
for t in ('everyMemberIsListedWithItsPathAndTheAddressesAtItsEnd', 'whatAMemberGetsWrongIsFlagged', 'eachDeclaredSelfResolverIsFlaggedOnItsApp',
          'theCrashCountReadsOnlyReportsThroughTheBridge', 'everyKnownServerIsListedOnceWithEveryRoleItPlays',
          'theAnsweringServerIsTheFirstReachableOneAndroidIsHanded'):
    need('ovt', '@Test fun %s()' % t, 'DnsOverviewTest.%s is gone' % t)
need('syst', 'fun theStateCountsEveryQueryByHowItEndedAndKeepsTheLastError()', 'SysDnsTest no longer runs the bridge state')
sr = json.load(open(f['bj']))['ui']['dns'].get('self_resolvers') or []
if not sr: pr.append('bj: ui.dns.self_resolvers is empty')
for r in sr:
    if not all(str(r.get(k, '')).strip() for k in ('pkg', 'what', 'how')): pr.append('bj: a self_resolvers entry lacks pkg/what/how: %r' % r)
print('\n'.join(pr))
sys.exit(1 if pr else 0)
PY
}
ROLES=(dns="$DNS" frag="$FRAG" app="$APPKT" ov="$OV" bridge="$BRIDGE" txapi="$TX/app/TerminalDebugApi.java"
       txbridge="$TX/cloud/CloudDnsBridge.java" nxapi="$NX/TerminalDebugApi.java" nxapp="$NX/TermuxApplication.java"
       ut="$UT" ovt="$OVT" syst="$SYST" bj="$BJ")
if out="$(d7 "${ROLES[@]}")"; then
  ok "#794 verdict, alert, one-tap consent for a result, start/engine-update re-check, overview route and members, bridge state in both terminals, tests"
else
  bad "#794:"; printf '%s\n' "$out" | sed 's/^/      /'
fi
# Mutants: role | python expression over the file text s → the mutated text.
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
while IFS='|' read -r role expr; do
  [ -n "$role" ] || continue
  src=""; for r in "${ROLES[@]}"; do [ "${r%%=*}" = "$role" ] && src="${r#*=}"; done
  mut="$TMP/$role.$RANDOM"
  python3 -c "import sys; s=open(sys.argv[1], encoding='utf-8').read(); open(sys.argv[2], 'w', encoding='utf-8').write($expr)" "$src" "$mut"
  if cmp -s "$src" "$mut"; then bad "D7-mutation did not apply — $role: ${expr:0:80}"; continue; fi
  args=(); for r in "${ROLES[@]}"; do [ "${r%%=*}" = "$role" ] && args+=("$role=$mut") || args+=("$r"); done
  if d7 "${args[@]}" >/dev/null; then bad "D7-mutation NOT caught — $role: ${expr:0:80}"; else ok "D7-mutation caught — $role: ${expr:0:70}"; fi
done <<'MUTANTS'
dns|s.replace('if (v.needsConsent) FleetAlerts.raise(', 'if (false) FleetAlerts.raise(')
dns|s.replace('android.onVpn && actual.toSet() == want.toSet() ->', 'actual.toSet() == want.toSet() ->')
dns|s.replace('idle.contains(BackendException.Reason.VPN_NOT_AUTHORIZED.name)', 'idle.contains("NOPE")')
frag|s.replace('consentLauncher.launch(it)', 'startActivity(it)')
frag|s.replace('FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))', 'FleetDns.syncMeshDown(ctx, raiseNow = !FirewallController.isEnabled(ctx))', 1)
frag|s.replace('statusOk = v.ok', 'statusOk = true')
app|s.replace('addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)', '')
app|s.replace('DnsOverview.register(this)', 'toString()')
ov|s.replace('"sysdns/state"', '"sysdns/nothing"')
ov|s.replace('"diagnostics/crashes"', '"diagnostics/none"')
ov|s.replace('StoreMesh.ask(ctx, pkg, port, op)', 'StoreMesh.get(port, op)')
bridge|s.replace('            asked();\n', '', 1)
bridge|s.replace('error("sysdns: an answer failed', 'log.line("sysdns: an answer failed')
txapi|s.replace('CloudDnsBridge.state()', 'null')
nxapp|s.replace('if (dnsBridge != null) return dnsBridge.stateJson();', '')
ut|s.replace('@Test fun onlyMissingConsentAsksForConsent()', 'fun onlyMissingConsentAsksForConsent()')
bj|s.replace('"how": "plain DNS over UDP', '"why": "plain DNS over UDP')
MUTANTS

echo "== RESULT(#740/#751 fleet dns): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
