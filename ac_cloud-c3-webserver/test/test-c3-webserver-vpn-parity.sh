#!/usr/bin/env bash
# #1845 Home: the VPN table, the Markdown export, the truthful Summary, and the mesh listener on every mesh address. Static: no build, no device.
#  V  Home draws a VPN table AFTER the Server table, read from the engine's INetBackend (VpnReader over libs:net AidlBackend), never from the loopback proxy;
#     every row the SuperApp VPN page draws per mesh is there (tunnel, handshakes, interface, route, mesh DNS, Android DNS, hub IP, private name, path, fallbacks, always-on)
#  M  build.json::vpn.meshes equals the SuperApp's ui.mesh_page.meshes (one declaration, two readers)
#  E  Export all to Md and Copy all sit at the bottom of Home (after the VPN table), built by HomeReport from the same models
#  S  the Summary rows come from HomeModel.summaryRows and name the cause; the Home rows kept (rule 16)
#  L  the mesh listener binds every mesh address (mesh_addrs / plan_binds, a socket per address, re-read), reports them, and the status lists them
#  C  the SuperApp VPN page carries Export to Md beside Copy all / Export all (declared, drawn, dispatched, translated, tested)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
python3 - "$APP" <<'PY'
import json, os, sys
APP = sys.argv[1]
def rd(p, base=None):
    try: return open(os.path.join(base or APP, p), encoding="utf-8").read()
    except OSError: return ""
SA = os.path.join(APP, "..", "aa_cloud-superapp")
UI = "src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/"
KT = "src-tauri/gen/android/app/src/test/java/com/diegonmarcos/cloudwebserver/"
def check(src):
    bad = []
    home, status, reader, vmodel, report, model = (src[k] for k in ("home", "status", "reader", "vmodel", "report", "model"))
    # V
    h = home
    if not (0 <= h.find("ServerTable(data)") < h.find("VpnTable(data")): bad.append("V1 the VPN table is not drawn after the Server table")
    if "AidlBackend" not in reader or "getState(" not in reader or "getStatistics(" not in reader: bad.append("V2 VpnReader does not read the engine's INetBackend (AidlBackend)")
    if "127.0.0.1" in reader or "Api." in reader: bad.append("V3 VpnReader reads through the loopback server: a proxy, not the engine")
    if 'implementation(project(":libs:net"))' not in src["gradle"] or '"libs:net"' not in src["build"]: bad.append("V4 libs:net is not linked and declared")
    for k in ['"Tunnel"', '"Handshakes"', '"Android DNS"', '"Active path"', '"Fallbacks"', '"Always-on"', '$l interface', '$l route', '$l mesh DNS', '$l hub IP', '$l private name']:
        if k not in vmodel: bad.append("V5 the VPN table lacks the row %s" % k)
    if "from)" not in reader or "bind(" not in reader: bad.append("V6 the hub probe is not bound to the phone's address in that mesh")
    # M
    b = json.loads(src["build"]); sa = json.loads(src["sabuild"])
    keys = ("id", "label", "cidr", "hub", "resolver", "name", "service_url")
    mine = [{k: m[k] for k in keys} for m in b.get("vpn", {}).get("meshes", [])]
    theirs = [{k: m[k] for k in keys} for m in sa["ui"]["mesh_page"]["meshes"]]
    if not mine or mine != theirs: bad.append("M1 build.json::vpn.meshes differs from the SuperApp's ui.mesh_page.meshes")
    if "vpn" not in src["power"]: bad.append("M2 the status does not carry the vpn declaration to Kotlin")
    # E
    if not (0 <= h.find("VpnTable(data") < h.find('"Export all to Md"') and h.find("VpnTable(data") < h.find('"Copy all"')): bad.append("E1 Export all to Md / Copy all are not at the bottom, after the VPN table")
    if "shareMarkdown(" not in h or "HomeReport.markdown(" not in h: bad.append("E2 the export does not use HomeReport")
    for sec in ('"Summary"', '"Pages"', '"API"', '"SSHD"', '"ADB Shell"', '"Server"', '"VPN ('):
        if sec not in report: bad.append("E3 the Markdown lacks the section %s" % sec)
    # S
    if "fun summaryRows(" not in model or "attentionWhy" not in status or "r.cause" not in status: bad.append("S1 the Summary does not name the cause on each row")
    if "fun sshdCause(" not in model or "ON means a process answers" not in model: bad.append("S2 SSHD ON with 0 doors up is not reconciled")
    # L
    ml = src["meshlogs"]
    for lit in ("pub fn mesh_addrs(", "pub fn plan_binds(", "pub fn bound_addrs(", "pub fn live_binds(", "TcpListener::bind((addr, m.port))", "fn every_mesh_address_of_the_phone_is_wanted_not_just_the_first", "fn one_socket_per_address_answers_each_with_the_bearer_gate_first"):
        if lit not in ml: bad.append("L1 meshlogs.rs lacks %s" % lit)
    if "pick_bind(" in ml.split("pub fn start")[1].split("#[cfg(test)]")[0]: bad.append("L2 the listener goes back to binding one address")
    if "check_bind()" not in ml.split("pub fn start")[1]: bad.append("L3 the bind guard no longer runs first")
    if '"addr"' not in src["power"] or "bound_addrs()" not in src["power"]: bad.append("L4 the status ports do not list every bound address")
    if "shown_binds()" not in src["apis"]: bad.append("L5 the Mesh Endpoints page lists one address")
    # C
    sab = src["sabuild"]
    if '"status_export_md"' not in sab or '"export.md"' not in sab: bad.append("C1 the SuperApp VPN page declares no Export to Md")
    if '"status_export_md"' not in src["sastatus"] or '"export.md"' not in src["sastore"]: bad.append("C2 Export to Md is not drawn / dispatched")
    if "mesh_ctl_status_export_md" not in src["sastr"] or "mesh_ctl_status_export_md" not in src["sastres"]: bad.append("C3 Export to Md has no label in both languages")
    if "export to Md" not in src["satest"]: bad.append("C4 no test for Export to Md")
    return bad
def load():
    return dict(
        home=rd(UI + "HomePage.kt"), status=rd(UI + "StatusPage.kt"), reader=rd(UI + "VpnReader.kt"), vmodel=rd(UI + "VpnModel.kt"), report=rd(UI + "HomeReport.kt"), model=rd(UI + "HomeModel.kt"),
        gradle=rd("src-tauri/gen/android/app/build.gradle.kts"), build=rd("build.json"), sabuild=rd("build.json", SA), power=rd("server/src/power.rs"), meshlogs=rd("server/src/meshlogs.rs"), apis=rd("server/src/apis.rs"),
        sastatus=rd("app/src/main/java/com/diegonmarcos/superapp/network/mesh/MeshStatusPage.kt", SA), sastore=rd("app/src/main/java/com/diegonmarcos/superapp/network/mesh/MeshStore.kt", SA),
        sastr=rd("app/src/main/res/values/strings.xml", SA), sastres=rd("app/src/main/res/values-es/strings.xml", SA), satest=rd("app/src/test/java/com/diegonmarcos/superapp/network/mesh/MeshStoreTest.kt", SA),
    )
src = load()
bad = check(src)
for x in bad: print("  FAIL:", x)
if bad: sys.exit(1)
print("  PASS: the VPN table, the Markdown export, the Summary causes, the listener on every mesh address and the SuperApp Export to Md")
def mut(name, key, a, b):
    s = dict(src); s[key] = s[key].replace(a, b, 1)
    if s[key] == src[key]: print("  FAIL: mutation does not apply:", name); return False
    if not check(s): print("  FAIL: mutation not caught:", name); return False
    print("  PASS: mutation turns red:", name); return True
ok = all([
    mut("VPN table before Server", "home", "VpnTable(data, vpn)", "ServerTableX(data); VpnTable"),
    mut("VPN read through a proxy", "reader", "AidlBackend(ctx", 'Api.get("127.0.0.1"); AidlBackend(ctx'),
    mut("one mesh row lost", "vmodel", '$l hub IP', '$l hub'),
    mut("listener back to one address", "meshlogs", "for addr in add {", "for addr in add.into_iter().take(1) { let _ = pick_bind(m.bind, &[], |_| true);"),
    mut("Export to Md not dispatched", "sastore", '"export.md"', '"export.mdx"'),
    mut("mesh list drifted", "build", '"Mesh-2"', '"Mesh-Two"'),
    mut("Export moved above the VPN table", "home", 'SectionBand("vpn", "VPN")', '"Export all to Md" ; "Copy all"; SectionBand("vpn", "VPN")'),
])
sys.exit(0 if ok else 1)
PY
