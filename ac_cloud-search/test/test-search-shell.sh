#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #779 Cloud Search — the declaration and the code that renders it agree,   ║
# ║ the network and the AI token each have one door, and no sample data ships   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   S1  core/…/tax/Lohnsteuer2026.kt is exactly what core/tools/pap2kt.py makes of the BMF's
#       core/pap/Lohnsteuer2026.xml: the payroll tax cannot be hand-edited or drift from the
#       ministry's PAP it claims to be.
#   S2  the subpage kinds build.json::search.subpages declares and the kinds SearchShell's
#       `when (kind)` dispatches are the same set, both ways; every vertical's subpage is declared.
#   S3  every declared icon (vertical, engine, calculator) has a branch in IconCatalog — a misspelt
#       name would draw the fallback — and every declared colour name (engine accent, vertical
#       chart_color) is a colors.xml entry (#797).
#   S4  every enabled api source names a parser Parsers.parse dispatches, every declared calculator
#       is a Calculators.run branch, every declared series names a Series.parse branch (#797), and
#       no dispatch has a branch nothing declares.
#   S5  one network door: only core/Engine.kt (UrlHttp) opens a connection; no app or other core
#       source names java.net, HttpURLConnection, OkHttp or a socket, and the app declares INTERNET.
#   S6  one token door: only data/Account.kt calls revealAiKey; it and data/SearchAi.kt never log,
#       and Account.kt writes nothing (no SharedPreferences, no file) — the fleet Account is the
#       only store of the OpenRouter token.
#   S7  /api/<group>/verticals, query, calc, analysis and feed are documented and answered under
#       build.json::ui.debug_api.group, and no op is named `state` (GET /api/state's key).
#   S8  no sample data ships: no placeholder image host, lorem ipsum or `mock` identifier in app or
#       core sources, and no Kotlin colour literal (colours are res/values/colors.xml).
#   S9  (#797) the Phosphor icon set is one list: app/tools/phosphor.json, the generated
#       res/drawable/ph_*.xml (each carrying the generator's header) and every R.drawable.ph_* the
#       Kotlin names agree, both ways.
#   S10 (#797, #868, #1161) Cloud Search draws its OWN glass theme and pages, so the fleet kit (libs:ui-kit)
#       is not linked or imported; its chrome is the fleet's shared nav pattern: libs:bottomnav is linked
#       (NavDecl), Glass.kt draws no BottomNav/SubNav, SearchShell draws NavBarBottomHost (libs:ui-nav-bars),
#       NavBarTopFirstRowScaffold (libs:ui-nav-bars, Profile on the fleet Account launcher) and the
#       strips with NavBarTopSecondRow (libs:ui-nav-bars) from the NavDecl build.json::ui bakes, the app
#       bakes UI_BOTTOM_NAV/UI_SECTIONS_B64/UI_DEFAULT_SECTION/UI_STYLE (style fleet: the search-html variant is gone).
#       #1197: the island is exactly News, Research, CGC, Search (id chat), Media, Shopping, Brokers; Media's, Shopping's and
#       Brokers' pages are search.verticals (less the assistant), each exactly once, with its subpages one strip lower (Brokers
#       and CGC are horizontal_two_rows, every page in a group); Search is the assistant vertical (AI Chat), Engines, Apps,
#       Messages, Code; Research is Reports, Models and Agents (with its four pages); News is the fleet news then the declared
#       feed pages; and every section icon is a branch of the libs:ui-icons catalog.
#   S15 (#1197) the Engines page and the News feed pages are declared real data: the major engines are https URLs with {q}, the news
#       pages name declared rss/atom feeds, the media sources are enabled keyless https APIs that need a search term and state
#       their terms, and the shell opens Engines and the feed pages.
#   S14 (#1184) the code indexes and the news are DATA and honest: search.cgc.indexes are the cgc and mecgc sections, each
#       with Semantic and Graph RAG pages, each naming a service ac_cloud-account's fleet_endpoints declares (the private
#       one mesh_only, no public address, behind the Account sign-in; no repository in both indexes); search.research names
#       a declared service; app/build.gradle bakes the endpoints; the shell dispatches Research and the code sections; both
#       modes call the service's two tools; Search is a fleet-nsc.json app with its manifest pointing at the generated
#       network security config; no fleet host is spelled in the new sources; and no new page nests a vertical scroller.
#   S11 (#803) the app icon is the mockup's central nav icon: res/drawable/ic_launcher_foreground.xml
#       and ic_launcher_monochrome.xml are exactly what app/tools/phosphor2vd.py makes of
#       phosphor.json::launcher (the Phosphor glyph, the .ai-nav-icon gradient from colors.xml), and
#       both adaptive icons declare the foreground and the monochrome layer.
#   S12 (#803) the Search tab is ONE page, as the owner's renderUnifiedSearchChat: exactly one
#       vertical holds the `assistant` subpage and it declares no other (so no sub-nav, no second
#       page); AssistantPage draws it through libs:ai's AiChatPage (#823, shared with
#       Cloud Browser, Cloud Drive and Cloud Tools Small LLMs) with every declared engine, each filled by
#       Templates.fill and opened through SearchAi's opener = Browser.open; AiChatPage itself draws the engine boxes (Welcome, a
#       box per engine) and the chat (Conversation + Composer); and SearchShellTest keeps the UI test
#       that asserts both parts inside that one page.
#   S13 (#903) Things is coarse, once, dense and honest: the manifest holds ACCESS_COARSE_LOCATION and
#       neither the fine nor the background permission; ThingsPage asks for it once (the asked flag is
#       set before the request, behind a check of that flag); Locator.kt stores and logs nothing; the
#       Things pages inflate no minimum height; and the declared default radius is the owner's 20 km.
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# Reads ac_cloud-search and, for S12, the one lib it hosts its Search page from
# (ab_cloud-libs-shared/libs/ai). python3 + grep.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
LIB="$(cd "$APP/../ab_cloud-libs-shared/libs/ai" && pwd)"
K='app/src/main/java/com/diegonmarcos/cloudsearch'
C='core/src/main/kotlin/com/diegonmarcos/cloudsearch/core'
for f in build.json app/src/main/AndroidManifest.xml core/pap/Lohnsteuer2026.xml core/tools/pap2kt.py \
         "$C/tax/Lohnsteuer2026.kt" "$C/Listing.kt" "$C/Calculators.kt" "$C/Engine.kt" "$C/Market.kt" \
         "$K/ui/SearchShell.kt" "$K/ui/IconCatalog.kt" "$K/data/Account.kt" "$K/debugapi/SearchDebugApi.kt"; do
    [ -f "$APP/$f" ] || { echo "ERROR missing source: $APP/$f — this tester is unrun, not passing"; exit 1; }
done

export ACCT_JSON="$APP/../ac_cloud-account/build.json"
export NSC_JSON="$APP/../1_cicd/src/data/fleet-nsc.json"
CHECK="$(mktemp)"
trap 'rm -f "$CHECK"; rm -rf "${WORK:-}"' EXIT
cat > "$CHECK" <<'PY'
import glob, json, os, re, subprocess, sys

app = sys.argv[1]
splib = sys.argv[2]
bad = []
src = os.path.join(app, "app", "src", "main", "java", "com", "diegonmarcos", "cloudsearch")
core = os.path.join(app, "core", "src", "main", "kotlin", "com", "diegonmarcos", "cloudsearch", "core")

def code(p):
    """The file with comment lines dropped: prose may NAME what code must not do."""
    return "\n".join(l for l in open(p, encoding="utf-8").read().split("\n")
                     if not re.match(r"\s*(\*|//|/\*)", l))

kts = sorted(glob.glob(os.path.join(src, "**", "*.kt"), recursive=True))
cks = sorted(glob.glob(os.path.join(core, "**", "*.kt"), recursive=True))
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
S = bj["search"]

# S1
gen = subprocess.run([sys.executable, os.path.join(app, "core", "tools", "pap2kt.py"), os.path.join(app, "core", "pap", "Lohnsteuer2026.xml")],
                     capture_output=True, text=True)
committed = open(os.path.join(core, "tax", "Lohnsteuer2026.kt"), encoding="utf-8").read()
if gen.returncode != 0:
    bad.append("S1 pap2kt.py failed: %s" % gen.stderr.strip()[-200:])
elif gen.stdout.rstrip("\n") != committed.rstrip("\n"):
    bad.append("S1 tax/Lohnsteuer2026.kt differs from what pap2kt.py makes of core/pap/Lohnsteuer2026.xml — regenerate it, never edit it")

# S2
shell = code(os.path.join(src, "ui", "SearchShell.kt"))
m = re.search(r"when \(kind\) \{(.*?)\n        \}", shell, re.S)
dispatched = set(re.findall(r'^\s*"(\w+)" ->', m.group(1), re.M)) if m else set()
if not dispatched:
    bad.append("S2 SearchShell has no `when (kind)` this tester can read")
declared = {p["kind"] for p in S["subpages"]}
for k in sorted(declared - dispatched):
    bad.append("S2 subpage kind %s is declared but no page draws it" % k)
for k in sorted(dispatched - declared):
    bad.append("S2 SearchShell draws kind %s, which no subpage declares — dead page" % k)
sub_ids = {p["id"] for p in S["subpages"]}
for v in S["verticals"]:
    for p in v["subpages"]:
        if p not in sub_ids:
            bad.append("S2 vertical %s names subpage %s, which is not declared" % (v["id"], p))

# S3
ic = code(os.path.join(src, "ui", "IconCatalog.kt"))
icons = set(re.findall(r'^\s*"([\w-]+)" ->', ic, re.M))
declared_icons = [("vertical", v["id"], v.get("icon")) for v in S["verticals"]]
declared_icons += [("engine", e["id"], e.get("icon")) for e in S["engines"]]
declared_icons += [("calculator", k, c.get("icon")) for k, c in S["calculators"].items() if not k.startswith("_")]
for kind, ident, icon in declared_icons:
    if icon not in icons:
        bad.append("S3 %s %s icon %r has no IconCatalog branch — it would draw the fallback" % (kind, ident, icon))
# #1160 the island draws its glyphs from the fleet's catalog (libs:ui-icons), not from this app's IconCatalog
uic_path = sys.argv[3]
uic_names = set(re.findall(r'"([\w-]+)" to ', code(uic_path))) if os.path.isfile(uic_path) else set()
if not uic_names:
    bad.append("S3 the libs:ui-icons catalog (%s) holds no names — this tester is unrun, not passing" % uic_path)
for x in (bj.get("ui") or {}).get("sections") or []:
    if x.get("icon") not in uic_names:
        bad.append("S3 ui.sections %s icon %r is not in the libs:ui-icons catalog — the island would draw the fallback" % (x.get("id"), x.get("icon")))
colors = set(re.findall(r'<color name="([\w]+)"', open(os.path.join(app, "app", "src", "main", "res", "values", "colors.xml"), encoding="utf-8").read()))
declared_colors = [("engine", e["id"], e.get("accent")) for e in S["engines"]]
declared_colors += [("vertical", v["id"], v.get("chart_color")) for v in S["verticals"] if v.get("analysis", "none") != "none"]
for kind, ident, name in declared_colors:
    if name not in colors:
        bad.append("S3 %s %s colour %r is not in colors.xml — it would draw the accent instead" % (kind, ident, name))

# S4
def branches(path, fn):
    t = code(path)
    m = re.search(r"fun %s\(.*?when \((\w+)\) \{(.*?)\n        else ->" % fn, t, re.S)
    return set(re.findall(r'^\s*"([\w-]+)" ->', m.group(2), re.M)) if m else set()
parsers = branches(os.path.join(core, "Listing.kt"), "parse")
used = {s["parser"] for s in S["sources"].values() if s.get("kind") == "api" and s.get("enabled") and s.get("parser")}
if not parsers:
    bad.append("S4 Parsers.parse has no dispatch this tester can read")
for p in sorted(used - parsers):
    bad.append("S4 a source uses parser %s, which Parsers.parse does not have" % p)
for p in sorted(parsers - used):
    bad.append("S4 Parsers.parse has parser %s, which no enabled source uses — dead parser" % p)
calcs = branches(os.path.join(core, "Calculators.kt"), "run")
declared_calcs = {k for k in S["calculators"] if not k.startswith("_")}
for c in sorted(declared_calcs - calcs):
    bad.append("S4 calculator %s is declared but Calculators.run does not compute it" % c)
for c in sorted(calcs - declared_calcs):
    bad.append("S4 Calculators.run computes %s, which no calculator declares — dead branch" % c)
sparsers = branches(os.path.join(core, "Market.kt"), "parse")
sused = {x["parser"] for k, x in S.get("series", {}).items() if not k.startswith("_")}
if not sparsers:
    bad.append("S4 Series.parse has no dispatch this tester can read")
for p in sorted(sused - sparsers):
    bad.append("S4 a series uses parser %s, which Series.parse does not have" % p)
for p in sorted(sparsers - sused):
    bad.append("S4 Series.parse has parser %s, which no series uses — dead parser" % p)

# S5
net = re.compile(r"java\.net\.|HttpURLConnection|okhttp3|\bSocket\(|\bURL\(")
for p in kts + cks:
    hit = net.search(code(p))
    if hit and os.path.relpath(p, core) != "Engine.kt":
        bad.append("S5 %s uses the network (%s) — core/Engine.kt UrlHttp is the only door" % (os.path.basename(p), hit.group(0)))
manifest = open(os.path.join(app, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
if "android.permission.INTERNET" not in manifest:
    bad.append("S5 the manifest does not declare INTERNET — no source could be read")

# S6
holders = sorted(os.path.relpath(p, src) for p in kts if "revealAiKey" in code(p))
if holders != ["data/Account.kt"]:
    bad.append("S6 revealAiKey must be called by data/Account.kt alone, found in %s" % holders)
for rel in ("data/Account.kt", "data/SearchAi.kt"):
    t = code(os.path.join(src, rel))
    if re.search(r"\bLog\.[a-z]\(|println\(|printStackTrace", t):
        bad.append("S6 %s logs — the token must never reach logcat" % rel)
acct = code(os.path.join(src, "data", "Account.kt"))
if re.search(r"SharedPreferences|getSharedPreferences|putString|writeText|FileOutputStream", acct):
    bad.append("S6 data/Account.kt stores something — the fleet Account is the token's only store")

# S7
api = code(os.path.join(src, "debugapi", "SearchDebugApi.kt"))
if "BuildConfig.DEBUG_API_GROUP" not in api:
    bad.append("S7 SearchDebugApi does not register under build.json::ui.debug_api.group")
for op in ("verticals", "query", "calc", "analysis", "feed", "things", "agents", "runs", "reports", "report", "cloud"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, api) or not re.search(r'"%s" ->' % op, api):
        bad.append("S7 /api/<group>/%s is not both documented and answered" % op)
if re.search(r'"state"', api):
    bad.append("S7 SearchDebugApi names an op state — that key is GET /api/state's (update-ack guard)")
if not bj.get("ui", {}).get("debug_api", {}).get("group"):
    bad.append("S7 build.json::ui.debug_api.group is missing")

# S8
mock = re.compile(r"placehold\.co|picsum\.photos|lorem ipsum|\bmock\w*", re.I)
for p in kts + cks:
    hit = mock.search(code(p))
    if hit:
        bad.append("S8 %s carries sample data (%s) — the shipped app shows real sources only" % (os.path.basename(p), hit.group(0)))
    if re.search(r"Color\(0x", code(p)):
        bad.append("S8 %s names a colour literal — colours are res/values/colors.xml" % os.path.basename(p))

# S9
ph = json.load(open(os.path.join(app, "app", "tools", "phosphor.json"), encoding="utf-8"))
listed = {"ph_" + n.replace("-", "_") for n in ph["regular"]} | {"ph_" + n.replace("-", "_") + "_fill" for n in ph["fill"]}
draw = os.path.join(app, "app", "src", "main", "res", "drawable")
on_disk = {f[:-4] for f in os.listdir(draw) if f.startswith("ph_") and f.endswith(".xml")}
for d in sorted(listed - on_disk):
    bad.append("S9 phosphor.json lists %s but res/drawable has no %s.xml — run app/tools/phosphor2vd.py" % (d, d))
for d in sorted(on_disk - listed):
    bad.append("S9 res/drawable/%s.xml is not in phosphor.json — an icon nothing regenerates" % d)
for d in sorted(on_disk):
    if "GENERATED by app/tools/phosphor2vd.py" not in open(os.path.join(draw, d + ".xml"), encoding="utf-8").read():
        bad.append("S9 res/drawable/%s.xml was not written by phosphor2vd.py — hand-drawn icons drift from the set" % d)
named = set()
for p in kts:
    named |= set(re.findall(r"R\.drawable\.(ph_\w+)", code(p)))
for d in sorted(named - on_disk):
    bad.append("S9 Kotlin draws R.drawable.%s, which phosphor.json does not generate" % d)

# S10 (#868: the app's chrome is its own glass theme and top bar, but its bottom nav and sub-page strips are the fleet's)
deps = bj["modules"]["app"]["depends_on"]
if "libs:ui-kit" in deps or "libs:ui-kit" in bj["modules"]:
    bad.append("S10 build.json links libs:ui-kit — Cloud Search draws its own chrome from the owner's mockup")
if "libs:bottomnav" not in deps or "libs:bottomnav" not in bj["modules"]:
    bad.append("S10 build.json does not link libs:bottomnav — the bottom nav and sub-page strips are the fleet's")
for p in kts:
    hit = re.search(r"import com\.diegonmarcos\.superapp\.uikit\.", code(p))
    if hit:
        bad.append("S10 %s imports the fleet uikit — the app's own Glass.kt draws its chrome" % os.path.basename(p))
glass = code(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsearch/ui/Glass.kt"))
if re.search(r"fun\s+(BottomNav|SubNav)\b", glass):
    bad.append("S10 Glass.kt draws its own BottomNav/SubNav — the bar is libs:bottomnav's BottomNavIsland, the strip its PageTabs")
# #1161 the bar is libs:ui-nav-bars's NavBarBottomHost (the shared pattern, no variant), fed by the baked NavDecl.
if not re.search(r"NavBarBottomHost\(\s*entries = NAV\.islandEntries", shell):
    bad.append("S10 SearchShell does not draw NavBarBottomHost from the baked NavDecl (NAV.islandEntries)")
if re.search(r"\b(BottomNavIsland|SearchHtmlIsland)\s*\(", shell):
    bad.append("S10 SearchShell also draws BottomNavIsland / SearchHtmlIsland - one bar, the shared NavBarBottomHost")
if not re.search(r"NavBarTopFirstRowScaffold\(", shell) or "rememberAccountLauncher()" not in shell:
    bad.append("S10 SearchShell does not draw the shared first row (NavBarTopFirstRowScaffold) with Profile on the fleet Account launcher")
if "NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION, BuildConfig.UI_STYLE)" not in shell:
    bad.append("S10 SearchShell's NAV is not NavDecl.fromBuildConfig of the four baked fields")
if (bj.get("ui") or {}).get("style") not in (None, "", "fleet"):
    bad.append("S10 build.json ui.style is not fleet - Cloud Search wears the shared nav pattern (#1161), no variant")
vpages = code(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsearch/ui/VerticalPages.kt"))
if not re.search(r"NavBarTopSecondRow\(\s*pages = NAV\.section\(state\.sectionOf\(v\.id\)\)\?\.page\(v\.id\)", vpages):
    bad.append("S10 the vertical pages do not draw their sub-pages with NavBarTopSecondRow from the vertical's page in the NavDecl")
if not re.search(r"NavSectionLayout\(\s*section = decl,\s*selectedId = state\.vertical", shell) or not re.search(r"in state\.verticalSections -> VerticalsSection\(state, state\.section, v\)", shell):
    bad.append("S10 SearchShell does not draw Media's, Shopping's and Brokers' verticals with NavSectionLayout from NAV.section(section)")
# #913b Chat holds Search and the fleet searches that were the Cloud page: its strip is NavBarTopSecondRow, its pages dispatch to CloudPage.
if not re.search(r"NavBarTopSecondRow\(\s*pages = NAV\.section\(\"chat\"\)", shell) or "else CloudPage(state, state.chatPage)" not in shell:
    bad.append("S10 SearchShell does not draw Chat's strip (Search + Apps, Messages, Code) with NavBarTopSecondRow and CloudPage")
cloudp = code(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsearch/ui/CloudPages.kt"))
for kind, fn in (("messages", "MessagesPage"), ("code", "CodePage")):
    if not re.search(r'"%s" -> %s\(state, cloud\)' % (kind, fn), cloudp):
        bad.append("S10 CloudPage does not open %s (%s) - a moved Cloud page is unreachable" % (kind, fn))
if not re.search(r"else -> AppsPage\(state, cloud\)", cloudp):
    bad.append("S10 CloudPage does not open apps (AppsPage) - a moved Cloud page is unreachable")
# #937 + #913b a query from outside lands on Chat > Search whatever page Chat was on.
ask = re.search(r"fun ask\(q: String, scope: CoroutineScope\) \{\s*openSection\(\"chat\"\)\s*chatPage = chatVertical", shell)
if not ask:
    bad.append("S10 SearchState.ask does not open Chat on Search (openSection(\"chat\"); chatPage = chatVertical)")
gradle = open(os.path.join(app, "app", "build.gradle"), encoding="utf-8").read()
for field in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64", "UI_DEFAULT_SECTION", "UI_STYLE"):
    if field not in gradle:
        bad.append("S10 app/build.gradle does not bake %s" % field)
# #1197 the declaration: seven island sections, News, Research, CGC, Search (id chat), Media, Shopping, Brokers. Every vertical but the
# assistant is a page of exactly one of Media, Shopping, Brokers (each with its subpages one strip lower); Search is AI Chat (the assistant
# vertical), Engines, Apps, Messages and Code; Research is Reports, Models and Agents (Agents, Runs, Templates, Settings one strip lower);
# News is the fleet news then the declared feed pages; CGC is Cloud CGC and Me CGC, one horizontal_two_rows group each.
ui, vs = bj.get("ui") or {}, bj["search"]["verticals"]
secs = {x.get("id"): x for x in ui.get("sections") or []}
# #1472 the island is exactly the six tabs; News, Shopping, Brokers (and chat, media) stay declared as parked sections, off the island
if ui.get("bottom_nav") != ["scrapers", "research", "cgc", "image", "video", "models3d"]:
    bad.append("S10 ui.bottom_nav is not scrapers, research, cgc, image, video, models3d")
if [x.get("id") for x in ui.get("sections") or []][:6] != ui.get("bottom_nav"):
    bad.append("S10 ui.sections do not start with the island's items, in order")
if [secs.get(i, {}).get("label") for i in ui.get("bottom_nav") or []] != ["Scrappers", "Research", "CGC Search", "Image", "Videos", "3D Models"]:
    bad.append("S10 the island's labels are not Scrappers, Research, CGC Search, Image, Videos, 3D Models")
if {"news", "shopping", "brokers"} & set(ui.get("bottom_nav") or []):
    bad.append("S10 News, Shopping or Brokers is on the island")
samples = bj.get("sample_pages", {}).get("pages") or {}
for sid, ids in (("scrapers", ["sitefiles", "apify", "cloudflare", "selfhosted"]), ("models3d", ["models3d"])):
    if [q.get("id") for q in (secs.get(sid) or {}).get("pages") or []] != ids:
        bad.append("S10 ui.sections[%s] pages are not %s" % (sid, ", ".join(ids)))
    for i in ids:
        rows = (samples.get(i) or {}).get("rows") or []
        if not rows or not all(r.get("title") and r.get("meta") and r.get("badge") for r in rows):
            bad.append("S10 build.json::sample_pages[%s] has no complete title/meta/badge rows" % i)
if "SAMPLE_PAGES_B64" not in gradle:
    bad.append("S10 app/build.gradle does not bake SAMPLE_PAGES_B64")
labels = {q["id"]: q["label"] for q in bj["search"]["subpages"]}
chat_v = [v for v in vs if any(kinds_.get(x) == "assistant" for x in v["subpages"])] if (kinds_ := {sp["id"]: sp["kind"] for sp in S["subpages"]}) else []
rest_v = [v for v in vs if v not in chat_v]
VSEC = ("image", "video", "media", "shopping", "brokers")
vert_pages = [q for sid in VSEC for q in (secs.get(sid) or {}).get("pages") or []]
if sorted(q.get("id") for q in vert_pages) != sorted(v["id"] for v in rest_v):
    bad.append("S10 ui.sections[image, video, media, shopping, brokers] pages are not search.verticals (less the assistant), each exactly once")
for sid in VSEC:
    if not (secs.get(sid) or {}).get("pages"):
        bad.append("S10 ui.sections[%s] has no vertical" % sid)
byid = {v["id"]: v for v in rest_v}
for q in vert_pages:
    v = byid.get(q.get("id"))
    if v is None:
        continue
    # a page's label is its own (Shopping's things vertical is Compare, Brokers' jobs is Jobs); a vertical with subpages says which
    if len(v["subpages"]) > 1:
        # #1199 a nav page id is unique in the app: "<vertical>-<subpage>" (jobs-listing), never the bare shared subpage id
        if [z.get("id") for z in q.get("pages") or []] != ["%s-%s" % (v["id"], x) for x in v["subpages"]]:
            bad.append("S10 ui.sections page %s subpages differ from search.verticals[%s]" % (q.get("id"), v["id"]))
        for z in q.get("pages") or []:
            if z.get("label") != labels.get(z.get("id")[len(v["id"]) + 1:]):
                bad.append("S10 ui.sections page %s/%s label differs from search.subpages" % (q.get("id"), z.get("id")))
    elif q.get("pages"):
        bad.append("S10 ui.sections page %s declares a second strip for a vertical with one subpage" % q.get("id"))
want_v = {"image": ["image"], "video": ["video"], "media": ["movies", "books"], "shopping": ["groceries", "electronics", "clothing", "things"],
          "brokers": ["jobs", "house", "commercial", "business", "suppliers", "services"]}
for sid, ids in want_v.items():
    if [q.get("id") for q in (secs.get(sid) or {}).get("pages") or []] != ids:
        bad.append("S10 ui.sections[%s] pages are not %s" % (sid, ", ".join(ids)))
# Brokers (and CGC) are two-row sections: every page in exactly one group
for sid, groups in (("brokers", [("job_placement", ["jobs"]), ("real_estate", ["house", "commercial"]), ("business_group", ["business", "suppliers", "services"])]),
                    ("cgc", [("cloud_cgc", ["cloud_semantic", "cloud_graph"]), ("me_cgc", ["me_semantic", "me_graph"])])):
    sec_ = secs.get(sid) or {}
    if sec_.get("nav_mode") != "horizontal_two_rows":
        bad.append("S10 ui.sections[%s] is not horizontal_two_rows" % sid)
    if [(g.get("id"), g.get("pages")) for g in sec_.get("groups") or []] != groups:
        bad.append("S10 ui.sections[%s] groups are not %s" % (sid, [g[0] for g in groups]))
chat = secs.get("chat") or {}
cpages = [q.get("id") for q in chat.get("pages") or []]
if len(chat_v) != 1 or cpages[:1] != [chat_v[0]["id"]] or chat.get("icon") != chat_v[0].get("icon"):
    bad.append("S10 ui.sections[chat] does not open on the assistant vertical (its id and icon)")
if cpages[1:] != ["engines", "apps", "messages", "code"]:
    bad.append("S10 ui.sections[chat] pages after AI Chat are not engines, apps, messages, code")
rpages = (secs.get("research") or {}).get("pages") or []
if [q.get("id") for q in rpages] != ["reports", "models", "agenthub"]:
    bad.append("S10 ui.sections[research] pages are not reports, models, agenthub")
if [z.get("id") for q in rpages if q.get("id") == "agenthub" for z in q.get("pages") or []] != ["agents", "runs", "templates", "settings"]:
    bad.append("S10 ui.sections[research] agenthub pages are not agents, runs, templates, settings")
if [q.get("id") for q in (secs.get("news") or {}).get("pages") or []] != ["news"] + [n["id"] for n in S.get("news_pages") or []]:
    bad.append("S10 ui.sections[news] pages are not the fleet news then search.news_pages, in order")
if ui.get("default_section") != "scrapers":
    bad.append("S10 ui.default_section is not scrapers, the first tab")

# S14 (#1184, #1197) the code indexes and the news: data, declared services, honest, no host in the sources
acct = json.load(open(os.environ["ACCT_JSON"], encoding="utf-8"))["fleet_endpoints"]["services"]
fsvc = {x["id"]: x for x in acct}
cg, rs = S.get("cgc") or {}, S.get("research") or {}
ixs = cg.get("indexes") or []
if [x.get("id") for x in ixs] != ["cgc", "mecgc"]:
    bad.append("S14 search.cgc.indexes are not the cgc and mecgc indexes")
cgc_pages = {q.get("id") for q in (secs.get("cgc") or {}).get("pages") or []}
for x in ixs:
    if {x.get("semantic_page"), x.get("graph_page")} - cgc_pages or x.get("semantic_page") == x.get("graph_page"):
        bad.append("S14 search.cgc index %s names semantic_page / graph_page that ui.sections[cgc] does not hold" % x.get("id"))
for x in ixs:
    sv = fsvc.get(x.get("service"))
    if sv is None:
        bad.append("S14 search.cgc index %s names service %s, which fleet_endpoints does not declare" % (x.get("id"), x.get("service")))
    elif x.get("needs_account") and (sv.get("policy") != "mesh_only" or sv.get("public_domain")):
        bad.append("S14 the private index %s is not mesh_only without a public address in fleet_endpoints" % x.get("id"))
    if x.get("id") == "mecgc" and not x.get("needs_account"):
        bad.append("S14 the private index mecgc does not wait for the Cloud Account sign-in")
if len(ixs) == 2 and set(ixs[0].get("repos") or []) & set(ixs[1].get("repos") or []):
    bad.append("S14 a repository is in both the public and the private index")
if (rs.get("news") or {}).get("service") not in fsvc:
    bad.append("S14 search.research.news.service is not a fleet_endpoints service")
if "FLEET_ENDPOINTS_B64" not in gradle:
    bad.append("S14 app/build.gradle does not bake FLEET_ENDPOINTS_B64")
if not all(re.search(r'"%s" -> %s\(state\)' % (k, f), shell) for k, f in (("news", "NewsSection"), ("research", "ResearchSection"), ("cgc", "CgcSection"))):
    bad.append("S14 SearchShell does not dispatch News, Research and the CGC section")
fcalls = code(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsearch/data/FleetCalls.kt"))
if "Cgc.SEARCH_TOOL" not in fcalls or "Cgc.GRAPH_TOOL" not in fcalls:
    bad.append("S14 FleetCalls does not call both tools (Semantic and Graph RAG)")
if "if (ix.needsAccount && token == null)" not in fcalls:
    bad.append("S14 FleetCalls does not hold the private index behind the Account sign-in")
nsc = json.load(open(os.environ["NSC_JSON"], encoding="utf-8"))["apps"]
mine = [a for a in nsc if a["nsc"].startswith("ac_cloud-search/")]
if not mine or not {"news", "cgc-pub-mcp", "cgc-pvt-mcp"} <= set(mine[0]["services"]):
    bad.append("S14 fleet-nsc.json does not list Cloud Search with news, cgc-pub-mcp and cgc-pvt-mcp")
if "android:networkSecurityConfig=\"@xml/network_security_config\"" not in open(os.path.join(app, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read():
    bad.append("S14 the manifest does not point at the generated network security config")
for rel in ("core/Cgc.kt", "core/Endpoints.kt", "core/Research.kt", "app/data/FleetCalls.kt", "app/ui/CgcPages.kt", "app/ui/ResearchPages.kt"):
    path = os.path.join(core, rel[5:]) if rel.startswith("core/") else os.path.join(src, rel[4:])
    t = code(path) if os.path.isfile(path) else ""
    if not t:
        bad.append("S14 %s is missing" % rel)
    elif re.search(r"[a-z0-9-]+\.(mesh\.)?diegonmarcos\.com", t):
        bad.append("S14 %s spells a fleet host: the address comes from fleet_endpoints" % rel)
    elif rel.startswith("app/ui/") and re.search(r"verticalScroll\(|rememberScrollState\(\)\)\.verticalScroll", t):
        bad.append("S14 %s nests a verticalScroll in a page that scrolls" % rel)
    elif rel.startswith("app/ui/") and "LazyColumn(" not in t:
        bad.append("S14 %s has no LazyColumn: the page must be the one scroller the shared collapse reads" % rel)

# S15 (#1197) Search > Engines and the News feed pages are declared data the shell draws, honest and real: every major engine is an
# https URL with {q}; every news page names declared feeds and the feed formats are the two the parser reads; the media sources are
# real keyless APIs (https, a parser, a search term required) and say so in their terms; the shell opens Engines and the feed pages.
engines_major = S.get("engines_major") or []
if len(engines_major) < 6:
    bad.append("S15 search.engines_major declares fewer than six engines")
for e in engines_major:
    if not str(e.get("url", "")).startswith("https://") or "{q}" not in e.get("url", ""):
        bad.append("S15 major engine %s has no https url with {q}" % e.get("id"))
for must in ("google", "bing", "duckduckgo", "brave", "qwant", "kagi", "perplexity"):
    if must not in [e.get("id") for e in engines_major]:
        bad.append("S15 search.engines_major does not declare %s" % must)
for n in S.get("news_pages") or []:
    for f in n.get("feeds") or []:
        if f not in S["feeds"]:
            bad.append("S15 news page %s names feed %s, which search.feeds does not declare" % (n.get("id"), f))
for k, f in S["feeds"].items():
    if not k.startswith("_") and f.get("format", "rss") not in ("rss", "atom"):
        bad.append("S15 feed %s has format %s, which is neither rss nor atom" % (k, f.get("format")))
for v in vs:
    if v["id"] in ("image", "video", "movies", "books"):
        for sid in v["sources"]:
            src_ = S["sources"][sid]
            if src_.get("kind") != "api" or not src_.get("enabled") or not str(src_.get("url", "")).startswith("https://") or not src_.get("query_required") or not src_.get("terms"):
                bad.append("S15 media source %s is not an enabled keyless https api that needs a search term and states its terms" % sid)
if 'state.chatPage == "engines") EnginesPage(state)' not in shell:
    bad.append("S15 SearchShell does not open the Engines page")
if "if (page == null) NewsPage(state) else FeedPage(state, page)" not in code(os.path.join(src, "ui", "FeedPages.kt")):
    bad.append("S15 NewsSection does not draw the fleet news and the declared feed pages")

# S11
res = os.path.join(app, "app", "src", "main", "res")
try:
    import importlib.util
    spec_ = importlib.util.spec_from_file_location("phosphor2vd", os.path.join(app, "app", "tools", "phosphor2vd.py"))
    gen_ = importlib.util.module_from_spec(spec_)
    spec_.loader.exec_module(gen_)
    want_files = gen_.launcher_files(res, ph)
except Exception as e:  # a missing glyph, colour or key is a red, not a crash
    want_files = {}
    bad.append("S11 phosphor2vd.py cannot make the launcher icon from phosphor.json::launcher: %r" % e)
for rel, text in sorted(want_files.items()):
    p = os.path.join(res, rel)
    if not os.path.isfile(p) or open(p, encoding="utf-8").read() != text:
        bad.append("S11 res/%s is not what phosphor2vd.py makes of phosphor.json::launcher — run `python3 app/tools/phosphor2vd.py --launcher`, never edit it" % rel)
for icon in ("ic_launcher.xml", "ic_launcher_round.xml"):
    x = open(os.path.join(res, "mipmap-anydpi-v26", icon), encoding="utf-8").read()
    for layer in ('<foreground android:drawable="@drawable/ic_launcher_foreground"', '<monochrome android:drawable="@drawable/ic_launcher_monochrome"'):
        if layer not in x:
            bad.append("S11 mipmap-anydpi-v26/%s lacks %s" % (icon, layer.split()[0][1:]))

# S12
kinds = {sp["id"]: sp["kind"] for sp in S["subpages"]}
holders = [v for v in S["verticals"] if any(kinds.get(x) == "assistant" for x in v["subpages"])]
if len(holders) != 1:
    bad.append("S12 %d verticals hold the assistant subpage — the Search tab is exactly one" % len(holders))
for v in holders:
    if len(v["subpages"]) != 1:
        bad.append("S12 vertical %s declares subpages %s — the Search tab is ONE page (engines + chat), not a split" % (v["id"], v["subpages"]))
ap = code(os.path.join(src, "ui", "AssistantPages.kt"))
lp = code(os.path.join(splib, "src", "main", "kotlin", "com", "diegonmarcos", "superapp", "ai", "AiPage.kt"))
host = code(os.path.join(src, "data", "SearchAi.kt"))
def body(fn, text):
    m = re.search(r"fun %s\(.*?\n\}" % fn, text, re.S)
    return m.group(0) if m else ""
page, shared, welcome = body("AssistantPage", ap), body("AiChatPage", lp), body("Welcome", lp)
if "AiChatPage(" not in page:
    bad.append("S12 AssistantPage does not draw libs:ai's AiChatPage")
if not re.search(r"engines = state\.cfg\.engines\.map", page) or "Templates.fill(" not in page:
    bad.append("S12 AssistantPage does not hand the page every declared engine, filled by Templates.fill")
for call in ("Welcome(", "Conversation(", "Composer("):
    if call not in shared:
        bad.append("S12 AiChatPage does not draw %s) — both halves of the Search tab live in the one page" % call[:-1])
if not re.search(r"engines\.forEachIndexed", welcome) or "host::openUrl" not in welcome:
    bad.append("S12 Welcome does not draw a box per declared engine opening its results through the host")
if not re.search(r"opener = \{ Browser\.open\(s\.app, it\) \}", host):
    bad.append("S12 SearchAi does not open a result in cloud-browser (Browser.open)")
ui_test = open(os.path.join(app, "app", "src", "test", "java", "com", "diegonmarcos", "cloudsearch", "SearchShellTest.kt"), encoding="utf-8").read()
if not re.search(r"@Test fun theSearchTabIsOnePageWithTheEnginesAndTheChat\(\)", ui_test) \
        or 'hasAnyAncestor(hasTestTag(Tags.page("assistant")))' not in ui_test:
    bad.append("S12 SearchShellTest lost theSearchTabIsOnePageWithTheEnginesAndTheChat, the UI test of the one page")

# S13
mf = open(os.path.join(app, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
if "android.permission.ACCESS_COARSE_LOCATION" not in mf:
    bad.append("S13 the manifest does not declare ACCESS_COARSE_LOCATION — Things could not place the search")
for fine in ("ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION"):
    if fine in mf:
        bad.append("S13 the manifest declares %s — Things needs a coarse fix, once, in the foreground" % fine)
things_ui = code(os.path.join(src, "ui", "ThingsPage.kt"))
if not re.search(r"!prefs\.locationAsked[^\n]*\n\s*prefs\.locationAsked = true\n\s*ask\.launch\(Manifest\.permission\.ACCESS_COARSE_LOCATION\)", things_ui):
    bad.append("S13 ThingsPage does not ask the location permission once (flag checked, set, then the request)")
loc = code(os.path.join(src, "data", "Locator.kt"))
if re.search(r"SharedPreferences|putString|putFloat|putLong|writeText|\bLog\.[a-z]\(|println\(", loc):
    bad.append("S13 data/Locator.kt stores or logs the fix — it only centres the search")
if "ACCESS_FINE" in loc:
    bad.append("S13 data/Locator.kt names the fine permission")
for p in (os.path.join(src, "ui", "ThingsPage.kt"),):
    if re.search(r"minHeight|heightIn\(|defaultMinSize|sizeIn\(|requiredHeightIn", code(p)):
        bad.append("S13 %s inflates a minimum height — the table is data-dense" % os.path.basename(p))
if S["things"]["default_radius_km"] != 20:
    bad.append("S13 build.json::search.things.default_radius_km is %s — the owner's default search area is 20 km" % S["things"]["default_radius_km"])

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── S1-S15 against the tree ──"
UIC="$APP/../ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"
if python3 "$CHECK" "$APP" "$LIB" "$UIC"; then echo "  PASS  S1-S15"; else FAILURES=$((FAILURES + 1)); fi

# ── mutations: each must go red, for the right reason ─────────────────────────
WORK="$(mktemp -d)"
mutate() {  # name, file (relative to the app), python expression over s, expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    cp -r "$APP/build.json" "$APP/app" "$APP/core" "$copy/"
    cp -r "$LIB" "$copy/ai"
    if ! python3 - "$copy/$rel" "$expr" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
t = eval(expr)
if t == s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(t)
PY
    then echo "  VOID  MUT $name: the edit did not land — the mutation targets text that moved"; FAILURES=$((FAILURES + 1)); return; fi
    local out
    out="$(python3 "$CHECK" "$copy" "$copy/ai" "$UIC" 2>&1)"
    if [ $? -eq 0 ]; then
        echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then
        echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else
        echo "  PASS  MUT $name"
    fi
}
J="$K"
mutate pap-hand-edited "$C/tax/Lohnsteuer2026.kt" 's.replace("GFB = bd(12348)", "GFB = bd(12000)")' "S1 tax/Lohnsteuer2026.kt differs"
mutate pap-xml-changed core/pap/Lohnsteuer2026.xml 's.replace("BigDecimal.valueOf(20350)", "BigDecimal.valueOf(20000)")' "S1 tax/Lohnsteuer2026.kt differs"
mutate kind-without-page "$J/ui/SearchShell.kt" 's.replace("\"feed\" -> FeedPage(v)", "")' "S2 subpage kind feed is declared"
mutate dead-page "$J/ui/SearchShell.kt" 's.replace("\"assistant\" -> AssistantPage(v)", "\"assistant\" -> AssistantPage(v)\n            \"atlas\" -> AssistantPage(v)")' "S2 SearchShell draws kind atlas"
mutate subpage-undeclared build.json 's.replace("\"listing\",\n          \"analysis\",", "\"listing\",\n          \"analysiz\",")' "names subpage analysiz"
mutate icon-misspelt build.json 's.replace("\"icon\": \"groceries\"", "\"icon\": \"grocerys\"")' "S3 vertical groceries icon"
mutate engine-icon-misspelt build.json 's.replace("\"icon\": \"bird\"", "\"icon\": \"birb\"")' "S3 engine duckduckgo icon"
mutate calc-icon-misspelt build.json 's.replace("\"icon\": \"wallet\"", "\"icon\": \"walet\"")' "S3 calculator max_rent icon"
mutate accent-missing build.json 's.replace("\"accent\": \"engine_brave\"", "\"accent\": \"engine_bravo\"")' "S3 engine brave colour"
mutate chart-colour-missing app/src/main/res/values/colors.xml 's.replace("<color name=\"chart_jobs\">", "<color name=\"chart_job\">")' "S3 vertical jobs colour"
mutate phosphor-unlisted app/tools/phosphor.json 's.replace("\"warning\", ", "")' "S9 res/drawable/ph_warning.xml is not in phosphor.json"
mutate phosphor-listed-missing app/tools/phosphor.json 's.replace("\"wallet\",", "\"wallet\", \"rocket\",")' "S9 phosphor.json lists ph_rocket"
mutate hand-drawn-icon app/src/main/res/drawable/ph_x.xml 's.replace("GENERATED by app/tools/phosphor2vd.py", "drawn by hand")' "S9 res/drawable/ph_x.xml was not written"
mutate icon-not-generated "$J/ui/SearchShell.kt" 's.replace("R.drawable.ph_caret_down", "R.drawable.ph_caret_up")' "S9 Kotlin draws R.drawable.ph_caret_up"
mutate fleet-nav-unlinked build.json 's.replace("\"libs:text-tools\",\n        \"libs:bottomnav\",", "\"libs:text-tools\",")' "S10 build.json does not link libs:bottomnav"
mutate fleet-kit-linked build.json 's.replace("\"libs:text-tools\",\n        \"libs:bottomnav\",", "\"libs:text-tools\",\n        \"libs:bottomnav\",\n        \"libs:ui-kit\",")' "S10 build.json links libs:ui-kit"
mutate fleet-kit-imported "$J/ui/SearchTheme.kt" 's.replace("import com.diegonmarcos.cloudsearch.R\n", "import com.diegonmarcos.cloudsearch.R\nimport com.diegonmarcos.superapp.uikit.KitCard\n")' "S10 SearchTheme.kt imports the fleet uikit"
mutate own-nav-back "$J/ui/Glass.kt" 's + "\n@androidx.compose.runtime.Composable\nfun BottomNav() {}\n"' "S10 Glass.kt draws its own BottomNav/SubNav"
mutate island-dropped "$J/ui/SearchShell.kt" 's.replace("NavBarBottomHost(\n                    entries = NAV.islandEntries", "NavRail(\n                    entries = NAV.islandEntries")' "S10 SearchShell does not draw NavBarBottomHost"
mutate fleet-island-back "$J/ui/SearchShell.kt" 's + "\nfun again() { BottomNavIsland(entries = e, selectedId = null, onSelect = {}) }\n"' "S10 SearchShell also draws BottomNavIsland"
mutate style-undeclared build.json 's.replace("\"default_section\": \"scrapers\"", "\"style\": \"search-html\",\n    \"default_section\": \"scrapers\"")' "S10 build.json ui.style is not fleet"
mutate style-not-baked app/build.gradle 's.replace("\"UI_STYLE\"", "\"UI_STYL\"")' "S10 app/build.gradle does not bake UI_STYLE"
mutate strip-dropped "$J/ui/VerticalPages.kt" 's.replace("NavBarTopSecondRow(\n                pages = NAV.section(state.sectionOf", "SubNav(\n                pages = NAV.section(state.sectionOf")' "S10 the vertical pages do not draw their sub-pages with NavBarTopSecondRow"
mutate web-strip-dropped "$J/ui/SearchShell.kt" 's.replace("NavSectionLayout(\n        section = decl,\n        selectedId = state.vertical", "SubNav(\n        section = decl,\n        selectedId = state.vertical")' "S10 SearchShell does not draw Media's, Shopping's and Brokers'"
mutate chat-strip-dropped "$J/ui/SearchShell.kt" 's.replace("NavBarTopSecondRow(\n                pages = NAV.section(\"chat\")", "SubNav(\n                pages = NAV.section(\"chat\")")' "S10 SearchShell does not draw Chat's strip"
mutate cloud-page-unreachable "$J/ui/SearchShell.kt" 's.replace("else CloudPage(state, state.chatPage)", "else Unit")' "S10 SearchShell does not draw Chat's strip"
mutate messages-unreachable "$J/ui/CloudPages.kt" 's.replace("\"messages\" -> MessagesPage(state, cloud)", "\"messagez\" -> MessagesPage(state, cloud)")' "S10 CloudPage does not open messages"
mutate ask-lands-elsewhere "$J/ui/SearchShell.kt" 's.replace("openSection(\"chat\")\n        chatPage = chatVertical", "openSection(\"chat\")")' "S10 SearchState.ask does not open Chat on Search"
mutate vertical-in-both build.json 's.replace("\"id\": \"books\",\n            \"label\": \"Books\",\n            \"icon\": \"menu_book\"\n          }", "\"id\": \"books\",\n            \"label\": \"Books\",\n            \"icon\": \"menu_book\"\n          },\n          {\n            \"id\": \"house\",\n            \"label\": \"House\"\n          }")' "S10 ui.sections[image, video, media, shopping, brokers] pages are not search.verticals"
mutate chat-section-dropped build.json 's.replace("\"id\": \"chat\",\n        \"label\": \"Search\"", "\"id\": \"chats\",\n        \"label\": \"Search\"")' "S10 ui.sections[chat] does not open on the assistant vertical"
mutate section-icon-misspelt build.json 's.replace("\"icon\": \"reports\"", "\"icon\": \"reportz\"", 1)' "S3 ui.sections research icon"
mutate agents-page-dropped build.json 's.replace("\"id\": \"runs\",", "\"id\": \"run\",", 1)' "S10 ui.sections[research] agenthub pages are not agents, runs, templates, settings"
mutate cloud-pages-reordered build.json 's.replace("\"id\": \"apps\",\n            \"label\": \"Apps\"", "\"id\": \"appz\",\n            \"label\": \"Apps\"")' "S10 ui.sections[chat] pages after AI Chat"
mutate agents-debug-op-dropped "$J/debugapi/SearchDebugApi.kt" 's.replace("\"runs\" -> runs(", "\"runz\" -> runs(")' "S7 /api/<group>/runs"
mutate nav-not-baked app/build.gradle 's.replace("\"UI_SECTIONS_B64\"", "\"UI_SECTIONS\"")' "S10 app/build.gradle does not bake UI_SECTIONS_B64"
mutate section-reordered build.json 's.replace("\"bottom_nav\": [\n      \"scrapers\",\n      \"research\",", "\"bottom_nav\": [\n      \"research\",\n      \"scrapers\",")' "S10 ui.bottom_nav is not scrapers, research, cgc, image, video, models3d"
mutate news-on-island build.json 's.replace("\"bottom_nav\": [\n      \"scrapers\",", "\"bottom_nav\": [\n      \"news\",\n      \"scrapers\",")' "S10 News, Shopping or Brokers is on the island"
mutate section-pages-drift build.json 's.replace("\"id\": \"jobs-calculators\",\n                \"label\": \"Calculators\"", "\"id\": \"jobs-calculators\",\n                \"label\": \"Calculatorz\"")' "S10 ui.sections page jobs/jobs-calculators label"
mutate parser-missing "$C/Listing.kt" 's.replace("\"open_prices\" -> openPrices(body, source)", "")' "S4 a source uses parser open_prices"
mutate dead-parser "$C/Listing.kt" 's.replace("\"ba\" -> ba(body, source)", "\"ba\" -> ba(body, source)\n        \"immo\" -> ba(body, source)")' "S4 Parsers.parse has parser immo"
mutate series-parser-missing "$C/Market.kt" 's.replace("\"jsonstat\" -> jsonStat(body)", "")' "S4 a series uses parser jsonstat"
mutate dead-series-parser "$C/Market.kt" 's.replace("\"sdmx_json\" -> sdmxJson(body)", "\"sdmx_json\" -> sdmxJson(body)\n        \"csv\" -> sdmxJson(body)")' "S4 Series.parse has parser csv"
mutate debug-feed-dropped "$J/debugapi/SearchDebugApi.kt" 's.replace("\"feed\" -> feed(Services.get(app), q).toString()", "")' "S7 /api/<group>/feed"
mutate calc-missing "$C/Calculators.kt" 's.replace("\"max_rent\" -> Result(", "\"max_rentx\" -> Result(")' "S4 calculator max_rent is declared"
mutate app-online "$J/ui/VerticalPages.kt" 's + "\nprivate val u = java.net.URL(\"https://example.org\")\n"' "S5 VerticalPages.kt uses the network"
mutate core-online "$C/Chat.kt" 's + "\nprivate fun leak() = java.net.Socket(\"x\", 1)\n"' "S5 Chat.kt uses the network"
mutate no-internet app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.INTERNET\" />", "")' "S5 the manifest does not declare INTERNET"
mutate token-second-door "$J/data/SearchAi.kt" 's + "\nprivate fun peek(c: com.diegonmarcos.superapp.texttools.TextToolsClient) = c.revealAiKey(\"openrouter\")\n"' "S6 revealAiKey must be called by data/Account.kt alone"
mutate token-logged "$J/data/Account.kt" 's.replace("val r = c.revealAiKey(provider)", "val r = c.revealAiKey(provider)\n        android.util.Log.d(\"acct\", r.text.orEmpty())")' "S6 data/Account.kt logs"
mutate token-stored "$J/data/Account.kt" 's.replace("val r = c.revealAiKey(provider)", "val r = c.revealAiKey(provider)\n        ctx.getSharedPreferences(\"x\", 0).edit().putString(\"t\", r.text).apply()")' "S6 data/Account.kt stores something"
mutate debug-op-dropped "$J/debugapi/SearchDebugApi.kt" 's.replace("\"calc\" -> calc(Services.get(app), q).toString()", "")' "S7 /api/<group>/calc"
mutate debug-op-state "$J/debugapi/SearchDebugApi.kt" 's.replace("\"verticals\" -> verticals(", "\"state\" -> verticals(")' "S7 SearchDebugApi names an op state"
mutate mock-shipped "$J/ui/VerticalPages.kt" 's + "\nprivate val mockData = listOf(\"Modern Loft in Downtown\")\n"' "S8 VerticalPages.kt carries sample data"
mutate placeholder-image "$C/Listing.kt" 's + "\nprivate const val IMG = \"https://placehold.co/400x200\"\n"' "S8 Listing.kt carries sample data"
mutate colour-literal "$J/ui/SearchTheme.kt" 's + "\nprivate val x = androidx.compose.ui.graphics.Color(0xFF000000)\n"' "S8 SearchTheme.kt names a colour literal"

mutate launcher-hand-edited app/src/main/res/drawable/ic_launcher_foreground.xml 's.replace("#FFFF416C", "#FFFF0000")' "S11 res/drawable/ic_launcher_foreground.xml is not what"
mutate launcher-icon-changed app/tools/phosphor.json 's.replace("\"icon\": \"shooting-star\"", "\"icon\": \"robot\"")' "S11 res/drawable/ic_launcher_foreground.xml is not what"
mutate launcher-colour-moved app/src/main/res/values/colors.xml 's.replace("<color name=\"ai_nav_2\">#FF8A2387", "<color name=\"ai_nav_2\">#FF8A2388")' "S11 res/drawable/ic_launcher_foreground.xml is not what"
mutate launcher-glyph-missing app/tools/phosphor.json 's.replace("\"icon\": \"shooting-star\"", "\"icon\": \"comet\"")' "S11 phosphor2vd.py cannot make the launcher icon"
mutate monochrome-dropped app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml 's.replace("    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n", "")' "S11 mipmap-anydpi-v26/ic_launcher_round.xml lacks monochrome"
mutate search-split build.json 's.replace("\"subpages\": [\n          \"assistant\"\n        ]", "\"subpages\": [\n          \"assistant\",\n          \"listing\"\n        ]")' "S12 vertical search declares subpages"
mutate assistant-twice build.json 's.replace("\"subpages\": [\n          \"listing\"\n        ],\n        \"sources\": [\n          \"open-prices\"", "\"subpages\": [\n          \"assistant\"\n        ],\n        \"sources\": [\n          \"open-prices\"")' "S12 2 verticals hold the assistant subpage"
SP=ai/src/main/kotlin/com/diegonmarcos/superapp/ai
mutate chat-moved-out "$SP/AiPage.kt" 's.replace("        Composer(\n            chat, theme", "        NoBar(\n            chat, theme")' "S12 AiChatPage does not draw Composer"
mutate engines-moved-out "$SP/AiPage.kt" 's.replace("if (chat.session.messages.isEmpty() && !chat.sending) Welcome(", "if (chat.session.messages.isEmpty() && !chat.sending) Unit; if (false) Wx(")' "S12 AiChatPage does not draw Welcome"
mutate engine-boxes-dropped "$SP/AiPage.kt" 's.replace("engines.forEachIndexed", "emptyList<AiEngine>().forEachIndexed")' "S12 Welcome does not draw a box per declared engine"
mutate page-not-shared "$J/ui/AssistantPages.kt" 's.replace("    AiChatPage(\n", "    OwnPage(\n")' "S12 AssistantPage does not draw libs:ai"
mutate engines-not-handed "$J/ui/AssistantPages.kt" 's.replace("engines = state.cfg.engines.map", "engines = emptyList<SearchConfig.Engine>().map")' "S12 AssistantPage does not hand the page every declared engine"
mutate result-not-in-browser "$J/data/SearchAi.kt" 's.replace("opener = { Browser.open(s.app, it) }", "opener = { println(it) }")' "S12 SearchAi does not open"
mutate one-page-test-dropped app/src/test/java/com/diegonmarcos/cloudsearch/SearchShellTest.kt 's.replace("fun theSearchTabIsOnePageWithTheEnginesAndTheChat()", "fun theSearchTabComposes()")' "S12 SearchShellTest lost"

mutate fine-location app/src/main/AndroidManifest.xml 's.replace("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")' "S13 the manifest does not declare ACCESS_COARSE_LOCATION"
mutate background-location app/src/main/AndroidManifest.xml 's.replace("<uses-feature", "<uses-permission android:name=\"android.permission.ACCESS_BACKGROUND_LOCATION\" />\n    <uses-feature")' "S13 the manifest declares ACCESS_BACKGROUND_LOCATION"
mutate asked-every-time "$J/ui/ThingsPage.kt" 's.replace("            prefs.locationAsked = true\n            ask.launch(Manifest", "            ask.launch(Manifest")' "S13 ThingsPage does not ask the location permission once"
mutate fix-stored "$J/data/Locator.kt" 's.replace("fun fix(ctx: Context): Fix? =", "fun leak(ctx: Context) = ctx.getSharedPreferences(\"x\", 0).edit().putString(\"fix\", \"\").apply()\n\n    fun fix(ctx: Context): Fix? =")' "S13 data/Locator.kt stores or logs the fix"
mutate fix-logged "$J/data/Locator.kt" 's.replace("private const val WAIT_MS", "private fun dbg(f: Fix) = android.util.Log.d(\"loc\", f.toString())\n    private const val WAIT_MS")' "S13 data/Locator.kt stores or logs the fix"
mutate height-inflated "$J/ui/ThingsPage.kt" 's.replace("private val KM_COL", "private val Tall = androidx.compose.ui.Modifier.heightIn(min = 120.dp)\nprivate val KM_COL")' "S13 ThingsPage.kt inflates a minimum height"
mutate radius-default-moved build.json 's.replace("\"default_radius_km\": 20", "\"default_radius_km\": 25")' "S13 build.json::search.things.default_radius_km is 25"
mutate things-without-renderer "$J/ui/SearchShell.kt" 's.replace("\"things\" -> ThingsPage(v)", "")' "S2 subpage kind things is declared"
mutate things-debug-op-dropped "$J/debugapi/SearchDebugApi.kt" 's.replace("\"things\" -> things(Services.get(app), q).toString()", "")' "S7 /api/<group>/things"

mutate cgc-service-undeclared build.json 's.replace("\"service\": \"cgc-pvt-mcp\"", "\"service\": \"cgc-ghost-mcp\"")' "S14 search.cgc index mecgc names service cgc-ghost-mcp"
mutate private-index-opened build.json 's.replace("\"needs_account\": true", "\"needs_account\": false")' "S14 the private index mecgc does not wait"
mutate repo-in-both-indexes build.json 's.replace("\"cloud-me-pvt-data\",\n            \"cloud-me-pvt-my-ai\"", "\"cloud-data\",\n            \"cloud-u-android\"")' "S14 a repository is in both"
mutate news-service-ghost build.json 's.replace("\"service\": \"news\"", "\"service\": \"newz\"")' "S14 search.research.news.service is not a fleet_endpoints service"
mutate cgc-section-undispatched "$J/ui/SearchShell.kt" 's.replace("\"cgc\" -> CgcSection(state)", "")' "S14 SearchShell does not dispatch News, Research and the CGC section"
mutate graph-tool-dropped "$J/data/FleetCalls.kt" 's.replace("Cgc.GRAPH_TOOL", "\"x\"")' "S14 FleetCalls does not call both tools"
mutate private-gate-dropped "$J/data/FleetCalls.kt" 's.replace("if (ix.needsAccount && token == null)", "if (false)")' "S14 FleetCalls does not hold the private index behind the Account sign-in"
mutate nsc-not-wired app/src/main/AndroidManifest.xml 's.replace("android:networkSecurityConfig=\"@xml/network_security_config\"", "")' "S14 the manifest does not point at the generated network security config"
mutate host-spelled "$J/data/FleetCalls.kt" 's + "\nval h = \"http://cloud-cgc-pvt-mcp.mesh.diegonmarcos.com:3107\"\n"' "spells a fleet host"
mutate nested-scroller "$J/ui/CgcPages.kt" 's + "\nval n = Modifier.verticalScroll(rememberScrollState())\n"' "S14 app/ui/CgcPages.kt nests a verticalScroll"
mutate engines-page-dropped "$J/ui/SearchShell.kt" 's.replace("state.chatPage == \"engines\") EnginesPage(state)", "state.chatPage == \"engines\") Unit")' "S15 SearchShell does not open the Engines page"
mutate engine-without-query build.json 's.replace("https://kagi.com/search?q={q}", "https://kagi.com/search")' "S15 major engine kagi has no https url with {q}"
mutate news-feed-undeclared build.json 's.replace("\"gn-world\",\n          \"gn-business\"", "\"gn-worldz\",\n          \"gn-business\"")' "S15 news page gnews names feed gn-worldz"
mutate media-source-paid build.json 's.replace("\"query_required\": true,\n        \"url\": \"https://api.openverse.org", "\"query_required\": false,\n        \"url\": \"https://api.openverse.org")' "S15 media source openverse-images"
mutate feed-format-unknown build.json 's.replace("\"format\": \"atom\"", "\"format\": \"json\"", 1)' "has format json, which is neither rss nor atom"

echo "── S1-S15 + mutations: $FAILURES failure(s) ──"
[ "$FAILURES" -eq 0 ]
