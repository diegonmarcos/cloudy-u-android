#!/usr/bin/env bash
# Tester (#877): the Cloud Mesh page - declaration <-> code, density-only sizing, controls wired to the
# engine, no View navigation. Static (no device, no gradle); the behaviour itself is MeshModelTest,
# MeshStoreTest and MeshPageComposeTest. Planted mutations (T9) prove each rule can fail.
#
#   T1  ui.mesh_page <-> code, both ways: every declared page has a composable branch, a page file and its
#       strings; every status row has a drawing; every control names an engine call the store dispatches OR
#       an `unsupported` reason; no dispatch entry is orphaned; labels exist in values AND values-es
#   T2  density-only sizing: no dp / sp / Dp / TextUnit literal in network/mesh except MeshDensity.kt, and
#       no hex colour outside MeshWidgets.kt
#   T3  controls act through the EXISTING engine paths: the store's handlers reach the port, the port
#       reaches WgState.backend / WireGuardPrefs / AccountMesh / FleetDns, and no second tunnel path exists
#   T4  silence guard: a control the engine cannot honour is drawn disabled WITH its reason; no empty click
#   T5  no View navigation: no TabLayout/ViewPager/BottomNavigationView/View builders; the strip is PageTabs
#       and is handed no colour or size
#   T6  polling is lifecycle-bound: RESUMED only, cancelled not slept, no Handler/Timer/Worker
#   T7  every existing route into the page still resolves to it; the hosting fragment is Compose
#   T8  the private key never reaches an export, a copy, a QR or a prefilled box
#   T9  planted mutations of each of the above are caught
#   T11 (#1120) the Start-up switches act (none declared unsupported), the supervisor is started, Disconnect records the intent
#   T10 (#1074) three tabs - Status / Profile / Logs - declared in build.json, every section in exactly one,
#       the profile table before the controls; the guard (previous_controls): no control the page had
#       before the reorganisation is undeclared or unreachable from a tab
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, os, re, shutil, sys, tempfile

ROOT = sys.argv[1]
MESH = "app/src/main/java/com/diegonmarcos/superapp/network/mesh"
FILES = [MESH, "app/src/main/java/com/diegonmarcos/superapp/network/WireGuardFragment.kt",
         "app/src/main/java/com/diegonmarcos/superapp/network/WireGuardPrefs.kt",
         "app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt",
         "app/src/main/res/values/strings.xml", "app/src/main/res/values-es/strings.xml",
         "app/build.gradle", "build.json"]
COMPOSE = "../1_cicd/src/data/compose-migration.json"

def read(root, rel):
    try: return open(os.path.join(root, rel), encoding="utf-8").read()
    except OSError: return ""

def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return "\n".join(re.sub(r"//.*$", "", l) for l in t.splitlines())

def kt_files(root):
    d = os.path.join(root, MESH)
    return {f: code(open(os.path.join(d, f), encoding="utf-8").read()) for f in sorted(os.listdir(d)) if f.endswith(".kt")} if os.path.isdir(d) else {}

def strings(root, loc):
    t = read(root, "app/src/main/res/%s/strings.xml" % loc)
    return set(re.findall(r'<string name="([^"]+)"', t))

def check(root):
    """Every problem found in the tree at [root]; empty = the page holds."""
    P = []
    try: decl = json.loads(read(root, "build.json"))["ui"]["mesh_page"]
    except Exception as e: return ["T1 build.json::ui.mesh_page unreadable: %s" % e]
    ks = kt_files(root)
    screen = ks.get("MeshScreen.kt", "")
    store = ks.get("MeshStore.kt", "")
    port = ks.get("MeshPort.kt", "")
    impl = ks.get("AndroidMeshPort.kt", "")
    en, es = strings(root, "values"), strings(root, "values-es")
    pages = decl.get("pages", [])
    controls = [c for p in pages for c in p.get("controls", [])]

    # ── T1 ──
    if decl.get("default_page") not in [p["id"] for p in pages]: P.append("T1 default_page is not a declared page")
    for p in pages:
        pid = p["id"]
        if not re.search(r'"%s"\s*->\s*Mesh\w+Page\(' % re.escape(pid), screen): P.append("T1 declared page %s has no branch in MeshScreen" % pid)
        for loc, have in (("values", en), ("values-es", es)):
            if "mesh_page_" + pid not in have: P.append("T1 page %s has no mesh_page_%s in %s" % (pid, pid, loc))
        if not ("MeshTags.page(\"%s\")" % pid in "".join(ks.values())): P.append("T1 page %s is never tagged mesh:page:%s" % (pid, pid))
    for b in re.findall(r'"(\w+)"\s*->\s*Mesh\w+Page\(', screen):
        if b not in [p["id"] for p in pages]: P.append("T1 MeshScreen draws page %s that is not declared" % b)
    rows = next((p.get("rows", []) for p in pages if p["id"] == "status"), [])
    sp = ks.get("MeshStatusPage.kt", "")
    for r in rows:
        if not re.search(r'"%s"\s*->' % re.escape(r), sp): P.append("T1 status row %s has no drawing" % r)
    for b in re.findall(r'^\s*"(\w+)"\s*->', sp, flags=re.M):
        if b not in rows: P.append("T1 MeshStatusPage draws row %s that is not declared" % b)
    handlers = set(re.findall(r'^\s*"([\w.]+)"\s+to\s', store, flags=re.M))
    used = set()
    for c in controls:
        e, u = c.get("engine", ""), c.get("unsupported", "")
        if not e and not u: P.append("T1 control %s declares neither an engine call nor an unsupported reason" % c["id"])
        if e.startswith("read.") and not u: P.append("T1 control %s is read-only on the engine (%s) but declares no unsupported reason for its write" % (c["id"], e))
        if e:
            used.add(e)
            if e not in handlers: P.append("T1 control %s names engine call %s that MeshStore does not dispatch" % (c["id"], e))
        for loc, have in (("values", en), ("values-es", es)):
            if "mesh_ctl_" + c["id"] not in have: P.append("T1 control %s has no mesh_ctl_%s in %s" % (c["id"], c["id"], loc))
            if u and "mesh_why_" + c["id"] not in have: P.append("T1 unsupported control %s has no mesh_why_%s in %s" % (c["id"], c["id"], loc))
            if c.get("group") and "mesh_group_" + c["group"] not in have: P.append("T1 group %s has no string in %s" % (c["group"], loc))
            for o in c.get("choices", []):
                if "mesh_opt_" + o not in have: P.append("T1 option %s has no mesh_opt_%s in %s" % (o, o, loc))
    for h in sorted(handlers - used): P.append("T1 MeshStore dispatches %s, which no control declares" % h)
    for pid in [p["id"] for p in pages]:
        if not any(re.match(r"Mesh%sPage\.kt$" % pid.capitalize(), f) for f in ks): P.append("T1 page %s has no Mesh%sPage.kt" % (pid, pid.capitalize()))
    if "UI_MESH_PAGE_B64" not in read(root, "app/build.gradle") or "buildJson.ui.mesh_page" not in read(root, "app/build.gradle"):
        P.append("T1 app/build.gradle does not bake ui.mesh_page as UI_MESH_PAGE_B64")

    # ── T2 ──
    for f, t in ks.items():
        if f == "MeshDensity.kt": continue
        for m in re.finditer(r"(?<![\w.])\d+(?:\.\d+)?f?\s*\.\s*(?:dp|sp)\b|\b(?:Dp|TextUnit)\s*\(|\bfontSize\s*=\s*\d|\bpadding\(\s*\d|\bsize\(\s*\d", t):
            P.append("T2 %s holds a size literal %r - sizes come from MeshDensity" % (f, m.group(0)))
        if f != "MeshWidgets.kt" and re.search(r"Color\(\s*0x", t): P.append("T2 %s holds a hex colour - colours are kit palette roles" % f)
        for m in re.finditer(r"MeshDensity\.(S\d+|GLYPH|TAP|QR|COLUMN)\b", t):
            if not re.search(r"const val %s\b" % m.group(1), ks.get("MeshDensity.kt", "")): P.append("T2 %s uses undefined MeshDensity.%s" % (f, m.group(1)))
    dens = ks.get("MeshDensity.kt", "")
    for need in ("const val SCALE", "const val TEXT_SCALE", "const val T_BODY", "const val S4", "fun dp(", "fun sp("):
        if need not in dens: P.append("T2 MeshDensity lacks %s" % need)

    # ── T3 ──
    for h in sorted(handlers):
        line = next((l for l in store.splitlines() if re.match(r'\s*"%s"\s+to\s' % re.escape(h), l)), "")
        block = store[store.index(line):][:600] if line else ""
        nxt = re.search(r'\n\s*"[\w.]+"\s+to\s', block[len(line):])
        block = block[: len(line) + (nxt.start() if nxt else 600)]
        if not re.search(r"port\.|host\.|view\[|diffFor|qrFor|journal|connectFlow|confirmCloud|perform\(", block): P.append("T3 handler %s reaches neither the port, the host nor view state" % h)
    for m in re.findall(r"fun (\w+)\(", port):
        if not re.search(r"override fun %s\(" % m, impl) and m not in ("requestConsent",):
            if "interface MeshHost" not in port.split("fun %s(" % m)[0][-4000:]: P.append("T3 port method %s is not implemented by AndroidMeshPort" % m)
    for need in ("WgState.backend", "backend.setState(WgState.tunnel, Tunnel.State.UP", "toTunnelConfig()", "AccountMesh.applyMesh", "FleetDns.", "prefs.savePeers"):
        if need not in impl.replace("\n", " ") and need.replace("backend.setState", "backend.setState") not in impl: P.append("T3 AndroidMeshPort never uses %s" % need)
    if "WgState.backend" not in impl or "private val backend get() = WgState.backend" not in impl: P.append("T3 the tunnel is not driven through WgState.backend")
    for f, t in ks.items():
        if re.search(r"GoBackend|VpnService\b|Runtime\.getRuntime|ProcessBuilder|wg-quick|libwg|\bINetBackend\b|bindService", t): P.append("T3 %s opens a second tunnel path" % f)
    if "toWgConfig()" in "".join(ks.values()): P.append("T3 the page brings a tunnel up with the literal form config instead of toTunnelConfig()")

    # ── T4 ──
    cp = ks.get("MeshControlsPage.kt", "")
    if not re.search(r'c\.unsupported', cp) or 'declLabel("why"' not in cp: P.append("T4 the controls page does not draw an unsupported control's reason")
    if not re.search(r"enabled\s*=\s*usable", cp): P.append("T4 controls are not disabled when unusable")
    if "Block.ENGINE_MISSING" not in cp and "mesh_why_engine" not in cp: P.append("T4 an engine-bound control does not say the engine is missing")
    if "mesh_need_key" not in cp: P.append("T4 a key-bound control does not say the key is missing")
    for f, t in ks.items():
        if re.search(r"MButton\([^\n]*,\s*\{\s*\}\s*[,)]", t) or re.search(r"onClick\s*=\s*\{\s*\}", t): P.append("T4 %s has an empty click handler" % f)
    for c in controls:
        if c.get("unsupported") and c.get("kind") == "action": P.append("T4 action %s is unsupported - an action has no disabled state to explain" % c["id"])

    # ── T5 ──
    for f, t in list(ks.items()) + [("WireGuardFragment.kt", code(read(root, "app/src/main/java/com/diegonmarcos/superapp/network/WireGuardFragment.kt")))]:
        if re.search(r"TabLayout|ViewPager|BottomNavigationView|android\.widget\.|LinearLayout|\bTextView\b|setContentView|LayoutInflater|findViewById|PageTabsView|AlertDialog\.Builder|MaterialAlertDialogBuilder", t): P.append("T5 %s uses View UI or View navigation" % f)
    call = re.search(r"PageTabs\((.*?)\)\s*\n", screen, flags=re.S)
    if not call: P.append("T5 MeshScreen does not draw the PageTabs strip")
    elif re.search(r"Color|\.dp|\.sp|MeshDensity|textSize|fontSize|background", call.group(1)): P.append("T5 PageTabs is handed a colour or size - the lib owns its look")
    if "import com.diegonmarcos.superapp.bottomnav.PageTabs" not in screen: P.append("T5 the strip is not libs:bottomnav's PageTabs")

    # ── T6 ──
    tick = screen[screen.find("fun MeshTicker"):] if "fun MeshTicker" in screen else ""
    if "Lifecycle.State.RESUMED" not in tick or "LaunchedEffect(resumed)" not in tick: P.append("T6 the ticker is not bound to the RESUMED lifecycle state")
    if "isActive" not in tick or "delay(" not in tick: P.append("T6 the ticker is not a cancellable delay loop")
    for f, t in ks.items():
        if re.search(r"\bHandler\b|\bTimer\b|scheduleAtFixedRate|WorkManager|AlarmManager|Executors\.newScheduled|while\s*\(\s*true\s*\)", t) and f != "MeshStore.kt": P.append("T6 %s polls outside the lifecycle-bound ticker" % f)
    if re.search(r"newScheduled|scheduleAtFixedRate|Timer\(", store): P.append("T6 MeshStore schedules its own polling")
    if "fun shouldPoll(resumed: Boolean): Boolean = resumed" not in store: P.append("T6 shouldPoll is not 'only while resumed'")

    # ── T7 ──
    spg = code(read(root, "app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"))
    if not re.search(r'sectionId == "config" && pageId == "wg"\s*->\s*WireGuardFragment\.newInstance\(\)', spg): P.append("T7 config/wg no longer opens WireGuardFragment")
    if not re.search(r'sectionId == "wg"\s*&& pageId == "config"\s*->\s*WireGuardFragment\.newInstance\(\)', spg): P.append("T7 wg/config no longer opens WireGuardFragment")
    if not re.search(r'sectionId == "wg"\s*&& pageId == "status"\s*->\s*WireGuardFragment\.newInstance\("status"\)', spg): P.append("T7 the hidden wg/status page does not render the Status tab")
    frag = code(read(root, "app/src/main/java/com/diegonmarcos/superapp/network/WireGuardFragment.kt"))
    if "class WireGuardFragment : KitComposeFragment()" not in frag: P.append("T7 WireGuardFragment is not a KitComposeFragment")
    if "MeshScreen(" not in frag: P.append("T7 WireGuardFragment does not host MeshScreen")
    try:
        bj = json.loads(read(root, "build.json"))["ui"]["sections"]
        wg = next(s for s in bj if s["id"] == "wg")
        ids = [p["id"] for p in wg["pages"]]
        if ids != ["config", "status"] or not wg["pages"][1].get("hidden"): P.append("T7 section wg pages changed: %r" % ids)
    except Exception as e: P.append("T7 cannot read sections: %s" % e)
    P += t8(root)

    # ── T10 ──
    tabs = decl.get("tabs", [])
    ids = [p["id"] for p in pages]
    if [x["id"] for x in tabs] != ["status", "profile", "logs"]: P.append("T10 the tabs are not Status, Profile, Logs: %r" % [x["id"] for x in tabs])
    if decl.get("default_tab") not in [x["id"] for x in tabs]: P.append("T10 default_tab is not a declared tab")
    placed = [s for x in tabs for s in x.get("sections", [])]
    for s in ids:
        if placed.count(s) != 1: P.append("T10 section %s sits in %d tabs, not exactly one" % (s, placed.count(s)))
    for s in placed:
        if s not in ids: P.append("T10 a tab names undeclared section %s" % s)
    prof = next((x.get("sections", []) for x in tabs if x["id"] == "profile"), [])
    if "profiles" not in prof or "controls" not in prof or prof.index("profiles") > prof.index("controls"): P.append("T10 the Profile tab does not put the profile table before the controls")
    for x in tabs:
        for loc, have in (("values", en), ("values-es", es)):
            if "mesh_tab_" + x["id"] not in have: P.append("T10 tab %s has no mesh_tab_%s in %s" % (x["id"], x["id"], loc))
    reach = {c["id"] for p in pages if p["id"] in placed for c in p.get("controls", [])}
    prev = decl.get("previous_controls", [])
    # #1153 the Fleet topology show/hide switch was removed on purpose (the topology is always shown): 36 remain
    if len(prev) < 36: P.append("T10 the guard lists %d controls, fewer than the 36 the page ships with" % len(prev))
    for c in prev:
        if c not in {x["id"] for x in controls}: P.append("T10 control %s was removed (it is in previous_controls)" % c)
        elif c not in reach: P.append("T10 control %s is declared but no tab reaches it" % c)
    for c in controls:
        if c["id"] not in prev and not c["id"].startswith(("cf_", "vpn_", "status_", "reach_", "log_level", "section_", "log_export", "dns_fleet_link")): P.append("T10 control %s is new and neither Cloudflare nor the #1120 Status VPN row nor a #1928 addition (section Copy / Export, Log export, DNS link): list it in previous_controls only if it is old" % c["id"])
    cf = decl.get("cloudflare", {})
    if "waiting_on" not in cf.get("one", {}) or "team" not in cf.get("one", {}): P.append("T10 cloudflare.one lacks team / waiting_on")

    # ── T11 (#1120) the Start-up switches act, and the auto-reconnect is started ──
    for c in controls:
        if c.get("group") == "start" and c.get("unsupported"): P.append("T11 start-up control %s is declared unsupported: it would be drawn greyed out for good" % c["id"])
    engines = {c.get("engine") for c in controls}
    for h in ("startup.boot", "startup.wifi", "startup.mobile", "lockdown.set", "alwayson.set", "state.connect", "state.disconnect"):
        if h not in engines: P.append("T11 no control dispatches %s" % h)
    if "MeshSupervisorRunner.start(this)" not in read(root, "app/src/main/java/com/diegonmarcos/superapp/App.kt"): P.append("T11 App.onCreate does not start the mesh supervisor")
    if "MeshIntent.setWanted(ctx, false)" not in code(read(root, MESH + "/AndroidMeshPort.kt")): P.append("T11 Disconnect does not record the owner's intent")
    return P

def t8(root):
    k = []
    frag = code(read(root, "app/src/main/java/com/diegonmarcos/superapp/network/WireGuardFragment.kt"))
    exp = frag[frag.find("private fun exportProfilesTo"):frag.find("private fun deleteExisting")]
    if "interfacePrivateKey" in exp or "privateKey" in exp.lower(): k.append("T8 exportProfilesTo touches the private key")
    if "no private key included" not in exp: k.append("T8 the profile export does not say it withheld the key")
    ks = kt_files(root)
    w = ks.get("MeshWidgets.kt", "")
    if 'if (secret) "" else value' not in w or "PasswordVisualTransformation" not in w: k.append("T8 a secret field is prefilled or not masked")
    if "presharedKey" in ks.get("MeshModel.kt", "").split("fun peerBlock")[1].split("}")[0].replace("p.presharedKey.isNotBlank()", "").replace("PresharedKey withheld", ""): k.append("T8 a peer copy carries the pre-shared key")
    if "interfacePrivateKey" in "".join(v for f, v in ks.items() if f != "AndroidMeshPort.kt"): k.append("T8 a page file reads the private key")
    impl = ks.get("AndroidMeshPort.kt", "")
    if re.search(r"fun generateKey\(\)[^\n]*\{[^}]*privateKey\.toBase64", impl) or "prefs.generateInterfaceKeyPair()" not in impl: k.append("T8 generateKey does not return only the public half")
    if "interfacePrivateKey" in impl.replace("prefs.interfacePrivateKey.isNotBlank()", "").replace("val priv = prefs.interfacePrivateKey", "").replace("val key = prefs.interfacePrivateKey", "").replace("prefs.interfacePrivateKey = t", ""): k.append("T8 AndroidMeshPort reads the private key beyond the splice")
    return k

def report(label, problems, want_clean=True):
    global fails, passes
    if want_clean:
        if problems: fails += 1; print("  FAIL: %s" % label); [print("      " + p) for p in problems]
        else: passes += 1; print("  PASS: %s" % label)

passes = fails = 0
probs = check(ROOT)
for tag, label in [("T1", "declaration <-> code, both directions, and the strings in values + values-es"),
                   ("T2", "density-only sizing: every dp/sp comes from MeshDensity"),
                   ("T3", "controls act through the existing engine paths; no second tunnel path"),
                   ("T4", "silence guard: an unsupported control is disabled WITH its reason; no empty click"),
                   ("T5", "no View navigation; the strip is PageTabs with no colour or size passed"),
                   ("T6", "polling is lifecycle-bound (RESUMED only), never in the background"),
                   ("T7", "every existing route still opens the page; the fragment is Compose"),
                   ("T10", "three tabs over the sections; no control the page had before is lost")]:
    report(label, [p for p in probs if p.startswith(tag)])
other = [p for p in probs if not re.match(r"T(?:[1-7]|10) ", p)]
report("no stray findings", other)

report("the private key never reaches an export, a copy, a QR or a prefilled box", [p for p in probs if p.startswith("T8")])

# ── ratchet moved ──
cm = json.load(open(os.path.join(ROOT, COMPOSE), encoding="utf-8")) if os.path.exists(os.path.join(ROOT, COMPOSE)) else {"units": []}
unit = next((u for u in cm["units"] if u["id"] == "aa_cloud-superapp"), {})
done = " ".join(unit.get("done", []))
report("compose-migration.json records the move (WireGuardFragment in `done`)", [] if "WireGuardFragment" in done else ["WireGuardFragment is not listed under done"])

# ── T9: planted mutations ──
print("== T9: planted mutations ==")
def mutate(name, want, edit):
    global passes, fails
    d = tempfile.mkdtemp(prefix="meshmut")
    try:
        for rel in FILES:
            src = os.path.join(ROOT, rel)
            dst = os.path.join(d, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if os.path.isdir(src): shutil.copytree(src, dst)
            elif os.path.exists(src): shutil.copy(src, dst)
        edit(d)
        got = check(d)
        if any(p.startswith(want) for p in got): passes += 1; print("  PASS: %s -> caught (%s)" % (name, want))
        else: fails += 1; print("  FAIL: %s was NOT caught as %s: %r" % (name, want, got[:3]))
    finally: shutil.rmtree(d, ignore_errors=True)

def sub(rel, old, new, count=1):
    def f(d):
        p = os.path.join(d, rel); t = open(p, encoding="utf-8").read()
        assert old in t, "mutation anchor %r missing in %s" % (old, rel)
        open(p, "w", encoding="utf-8").write(t.replace(old, new, count))
    return f
def bj(edit):
    def f(d):
        p = os.path.join(d, "build.json"); j = json.load(open(p, encoding="utf-8")); edit(j["ui"]["mesh_page"])
        json.dump(j, open(p, "w", encoding="utf-8"))
    return f
M = MESH
mutate("M1 a dp literal in a page", "T2", sub(M + "/MeshStatusPage.kt", "modifier.pageBody(embedded)", "modifier.pageBody(embedded).padding(12.dp)"))
mutate("M2 an sp literal in a widget", "T2", sub(M + "/MeshWidgets.kt", "fontSize = MeshDensity.sp(MeshDensity.T_META),", "fontSize = 13.sp,"))
mutate("M3 a hex colour in a page", "T2", sub(M + "/MeshRoutesPage.kt", "MText(r.cidr,", "MText(androidx.compose.ui.graphics.Color(0xFF00FF00).toString(),"))
mutate("M4 a declared page loses its branch", "T1", sub(M + "/MeshScreen.kt", '"routes" -> MeshRoutesPage(store, embedded = true)', '"routes" -> Unit'))
mutate("M5 a control names an engine call nobody dispatches", "T1", bj(lambda m: next(c for p in m["pages"] for c in p["controls"] if c["id"] == "mtu").update(engine="prefs.nope")))
mutate("M6 a control loses its engine call (and declares no unsupported reason)", "T1", bj(lambda m: next(c for p in m["pages"] for c in p["controls"] if c["id"] == "kill_switch").pop("engine")))
mutate("M7 a declared page has no Spanish label", "T1", sub("app/src/main/res/values-es/strings.xml", 'name="mesh_page_routes"', 'name="mesh_page_routes_x"'))
mutate("M8 a status row has no drawing", "T1", bj(lambda m: next(p for p in m["pages"] if p["id"] == "status")["rows"].append("jitter")))
mutate("M9 a dispatch entry no control declares", "T1", sub(M + "/MeshStore.kt", '"log.clear" to', '"log.explode" to'))
mutate("M10 a TabLayout sneaks in", "T5", sub(M + "/MeshScreen.kt", "Column(modifier.fillMaxSize()", "com.google.android.material.tabs.TabLayout(null); Column(modifier.fillMaxSize()"))
mutate("M11 the strip is handed a colour", "T5", sub(M + "/MeshScreen.kt", "PageTabs(tabs, store.tab, onSelect = { store.tab = it.id }, underTopChrome = false, flushTop = true)", "PageTabs(tabs, store.tab, onSelect = { store.tab = it.id }, underTopChrome = false, flushTop = true, modifier = Modifier.background(Color.Red))"))
mutate("M12 a second tunnel path (own GoBackend)", "T3", sub(M + "/AndroidMeshPort.kt", "private val backend get() = WgState.backend(ctx)", "private val backend get() = com.wireguard.android.backend.GoBackend(ctx, null)"))
mutate("M13 connect brings the tunnel up without the fleet DNS", "T3", sub(M + "/AndroidMeshPort.kt", "val cfg = prefs.toTunnelConfig()\n        val r = backend", "val cfg = prefs.toWgConfig()\n        val r = backend"))
mutate("M14 the ticker polls in the background", "T6", sub(M + "/MeshScreen.kt", "LaunchedEffect(resumed)", "LaunchedEffect(Unit)"))
mutate("M15 a scheduler appears", "T6", sub(M + "/MeshLogPage.kt", "fun MeshLogPage", "val t = java.util.Timer()\n@Composable\nfun MeshLogPage"))
mutate("M16 an unsupported control is drawn without its reason", "T4", sub(M + "/MeshControlsPage.kt", 'declLabel("why", c.id, c.unsupported)', 'c.unsupported'))
mutate("M17 an empty click handler", "T4", sub(M + "/MeshStatusPage.kt", "{ store.run(\"state.reconnect\") }", "{ }"))
mutate("M18 the hidden status route stops rendering the Status tab", "T7", sub("app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt", 'WireGuardFragment.newInstance("status")', "C3MeshFragment.newInstance()"))
mutate("M19 the profile export reads the key", "T8", sub("app/src/main/java/com/diegonmarcos/superapp/network/WireGuardFragment.kt", "val written = mutableListOf<String>()", "val written = mutableListOf<String>(); prefs.interfacePrivateKey"))
mutate("M21 a control is dropped from the declaration", "T10", bj(lambda m: next(p for p in m["pages"] if p["id"] == "controls")["controls"].pop(0)))
mutate("M22 a section is left without a tab", "T10", bj(lambda m: next(x for x in m["tabs"] if x["id"] == "logs").update(sections=[])))
mutate("M23 the controls move above the profile table", "T10", bj(lambda m: next(x for x in m["tabs"] if x["id"] == "profile").update(sections=["controls", "profiles"])))
mutate("M24 a control is declared but no tab reaches it", "T10", bj(lambda m: next(x for x in m["tabs"] if x["id"] == "status").update(sections=["status", "routes"])))
mutate("M25 a start-up control is declared unsupported again", "T11", bj(lambda m: next(c for p in m["pages"] for c in p["controls"] if c["id"] == "auto_boot").update(unsupported="no")))
mutate("M26 Disconnect stops recording the intent", "T11", sub(M + "/AndroidMeshPort.kt", "MeshIntent.setWanted(ctx, false) // #1120 Disconnect", "// #1120 Disconnect"))
mutate("M20 a secret field is prefilled", "T8", sub(M + "/MeshWidgets.kt", 'if (secret) "" else value', "value", -1))

print("\n== RESULT: %d passed, %d failed ==" % (passes, fails))
sys.exit(1 if fails else 0)
PY
