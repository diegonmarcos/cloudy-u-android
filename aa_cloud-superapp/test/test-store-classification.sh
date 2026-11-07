#!/usr/bin/env bash
# #563 — both Store tabs group by the CENTRAL classification, Cloud ▸ Apps gets
# the All Apps section Phone ▸ Apps has, and the store's carry-over features
# (#496 #334 #140 #88 #274/#99) survived the move.
#
# NO SECOND MAPPING TABLE. #170, #102 and #405 each deleted a second copy of a
# package→group mapping. So T1 derives every section title and folder label
# from build.json (ui.phone_sections / ui.phone_folders) and fails if ANY of
# them is written into the store library or into the host adapter — the only
# way a shelf name may reach the store is by being read from the taxonomy.
#
# The Kotlin classification itself (what StoreShelves.of RETURNS for cloud-drive
# and WhatsApp) is asserted on the resolved value by the Robolectric test
# app/src/test/.../apps/StoreShelvesTest.kt; T3 here only pins the DATA the
# brief's two examples rest on, so a build.json edit that moves them is caught
# before a build.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
STORE="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"

python3 - "$APP/build.json" "$STORE" "$KT" <<'PY'
import json, os, re, sys
build_path, store_dir, kt_dir = sys.argv[1:]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())

ui = json.loads(read(build_path))["ui"]
sections = [s for s in ui["phone_sections"] if isinstance(s, dict)]
folders = [f for f in ui["phone_folders"] if isinstance(f, dict)]
store_files = {f: code(os.path.join(store_dir, f)) for f in os.listdir(store_dir) if f.endswith(".kt")}
cloud = store_files.get("StoreCloudFragment.kt", "")
phone = store_files.get("StorePhoneFragment.kt", "")
shelves_kt = code(os.path.join(kt_dir, "../../../../../../../../ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/apps/StoreShelves.kt"))
if not (cloud and phone and shelves_kt): print("FATAL: store sources missing"); sys.exit(2)

print("== T1: the store holds NO taxonomy of its own ==")
names = sorted({s["title"] for s in sections} | {f["label"] for f in folders}
               | {f["label"].lstrip("_-+.=@>*") for f in folders if f["label"][:1] in "_-+.=@>*"} - {""})
literals = []
for fname, text in list(store_files.items()) + [("StoreShelves.kt", shelves_kt)]:
    for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', text):
        if lit in names: literals.append("%s: %r" % (fname, lit))
if not literals: ok("none of the %d taxonomy names is written in the store or its adapter" % len(names))
else: bad("a taxonomy name is hardcoded (a second mapping): " + "; ".join(literals[:6]))
leak = [f for f, t in store_files.items() if re.search(r"phone_folders|phone_sections|match_keywords", t)]
if not leak: ok("the store library reads no taxonomy data of its own")
else: bad("the store library reads taxonomy data directly: %s" % leak)

print("== T2: both tabs group through ONE host classifier, and it is the central one ==")
# Cloud > Apps is NOT grouped by the launcher classifier any more: its categories are the
# SuperApp's Cloud > Apps tile groups (fleet app_categories, test-store-cloud-categories.sh).
if "AppStoreHost.classify(" not in cloud and 'fleetObjects("app_categories")' in cloud:
    ok("StoreCloudFragment groups by the derived app_categories, not by a second classifier")
else: bad("StoreCloudFragment must read app_categories and must not call AppStoreHost.classify")
if "AppStoreHost.classify(" in phone: ok("StorePhoneFragment groups by AppStoreHost.classify")
else: bad("StorePhoneFragment does not ask the host classifier — it is grouping by something else")
app_kt = code(os.path.join(kt_dir, "App.kt"))
if re.search(r"\bclassify\s*=\s*com\.diegonmarcos\.superapp\.apps\.StoreShelves::of\b", app_kt):
    ok("the host wires classify to StoreShelves.of")
else: bad("App.kt does not wire AppStoreHost.classify — both tabs would render flat")
for needle, what in (("PhoneTaxonomy.folderIdOf(", "folder comes from PhoneTaxonomy"),
                     ("PhoneSections.loadFromBuildConfig()", "section comes from ui.phone_sections"),
                     ("folder.label.startsWith(it.prefix)", "folder joins its section by the All Apps prefix rule")):
    if needle in shelves_kt: ok(what)
    else: bad("StoreShelves: " + what + " — missing " + needle)

print("== T3: the brief's two examples, on the data ==")
def section_of(folder):
    return next((s["title"] for s in sections if folder["label"].startswith(s["prefix"])), None)
drive = next((e for e in ui["external_apps"] if e.get("id") == "cloud-drive"), None)
f = next((x for x in folders if drive and x["id"] == drive.get("folder")), None)
if f and "Data Apps" in (section_of(f) or ""): ok("cloud-drive -> %s / %s" % (section_of(f), f["label"]))
else: bad("cloud-drive does not classify into a Data Apps section (folder=%s)" % (f and f["label"]))
wa = [x for x in sorted(folders, key=lambda x: (x.get("order", "99"), x["id"]))
      if "pkg:com.whatsapp" in [k.lower() for k in x.get("match_keywords", [])]]
if wa and "Inboxes & AI" in (section_of(wa[0]) or "") and wa[0]["label"].endswith("Chat"):
    ok("WhatsApp -> %s / %s" % (section_of(wa[0]), wa[0]["label"]))
else: bad("WhatsApp is not Tools · Inboxes & AI / Chat (got %s)" % (wa and wa[0]["label"]))

print("== T4: Cloud ▸ Apps has Phone ▸ Apps' All Apps, mirrored over ONE package set ==")
grouped = code(os.path.join(kt_dir, "launcher/GroupedTilesFragment.kt"))
suite = code(os.path.join(kt_dir, "apps/SuitePhoneAppsFragment.kt"))
phone_frag = code(os.path.join(kt_dir, "apps/PhoneAppsFragment.kt"))
phone_ui = code(os.path.join(kt_dir, "apps/PhoneAppsUi.kt"))
cloud_call = re.search(r"allApps\(ctx,\s*only\s*=\s*Sections\.constellationPackages\(ctx\.packageName\)\)", grouped)
# The below-the-fold block is produced only for the cloud page: the call sits right after the
# `sectionId != "cloud"` gate of its produceState.
if cloud_call and 'sectionId != "cloud"' in grouped[max(0, cloud_call.start() - 200):cloud_call.start()]:
    ok("Cloud ▸ Apps renders All Apps ONLY over the fleet's packages, in the cloud branch")
else: bad("Cloud ▸ Apps does not render the All Apps grid over Sections.constellationPackages")
if re.search(r"val ourApps = (?:remember\(epoch\) \{ )?Sections\.constellationPackages\(ctx\.packageName\)", suite) and \
   "allApps(ctx, exclude = ourApps)" in suite:
    ok("Phone ▸ Apps excludes that same set — the two grids partition one list")
else: bad("Phone ▸ Apps no longer excludes Sections.constellationPackages — the mirror is broken")
if "(only == null || it.packageName in only)" in phone_ui and "exclude.isEmpty() && only == null" in phone_ui:
    ok("allApps honours `only` and never caches a filtered grouping")
else: bad("allApps ignores `only`, or writes a filtered result into the shared cache")

print("== T5: carry-over survived the move into the Cloud tab ==")
for needle, what in (
        ("details.add(app to states[app.id])", "#496 detail sheet"),
        ("Fleet.downgradePolicy = Fleet.DowngradePolicy { app, candidateCode, installedCode ->", "#496 downgrade confirm armed while visible"),
        ("Fleet.downgradePolicy = Fleet.DowngradePolicy { _, _, _ -> false }", "#496 refuse-by-default restored on leave"),
        ("{ it.label.lowercase() }", "#334 A-Z within a run"),
        ("UpdateProgress.addObserver(progressObserver)", "#88 live progress"),
        ("StoreBar.Bar(StoreBar.Verbs(", "#565 the Cloud tab draws the shared Store bar")):
    # the progress row (Cancel, the observer) is the one StoreProgressPanel both Store tabs draw
    if needle in cloud or needle in store_files.get("StoreProgressPanel.kt", ""): ok(what)
    else: bad(what + " — missing from StoreCloudFragment")
# #565: the header moved into the ONE bar both Store tabs draw.
bar = store_files.get("StoreBar.kt", "")
for needle, what in (
        ("AutoUpdatePrefs.setEnabled(ctx, !autoOn)", "#274/#99 auto-update toggle"),
        ("ConstellationWorker.start(ctx)", "#274/#99 auto-update reschedules the fleet worker")):
    if needle in bar: ok(what)
    else: bad(what + " — missing from StoreBar")

print("== T6: Phone Apps buttons come from ONE capability derivation and ONE install path (#564) ==")
# #564 replaced #563's Open + App info with Update | Open | Stop | Remove |
# App info | {origin store}. WHICH of them work is decided on the device and
# asserted on the resolved value by app/src/test/.../apps/StorePhoneActionsTest.kt;
# this pins the wiring that test cannot see from inside one call.
if re.search(r'btn\(ctx, "', phone): bad("Phone Apps hardcodes a button label - its buttons must come from PhoneAppActions.of")
elif "PhoneAppActions.of(" in phone: ok("Phone Apps draws each row from PhoneAppActions.of")
else: bad("Phone Apps does not draw its buttons from PhoneAppActions.of")
# #625 split Fleet.install into Fleet.download + Fleet.commit so a batch can
# fetch everything before installing anything. The one-path rule did not change,
# only the two calls it is made of: FleetInstall.kt is still the only file in the
# store that reaches the fleet ENGINE, and BatchInstall composes FleetInstall's
# two halves rather than calling Fleet itself.
installers = sorted(f for f, t in store_files.items() if re.search(r"\bFleet\.commit\(", t))
if installers == ["ExternalInstall.kt", "FleetInstall.kt"]:
    ok("the installer is entered from FleetInstall.kt (fleet) and ExternalInstall.kt (#571 ladder) alone")
else: bad("Fleet.commit is called from %s - the store has more than one install path" % installers)
# #571 moved Phone Apps' call into installOne(app, r) -> FleetInstall.run(app, fleetApp):
# the argument names changed, the one path did not. Match the call, not its locals.
if re.search(r"\bFleetInstall\.run\(", phone): ok("StorePhoneFragment updates through FleetInstall.run")
else: bad("StorePhoneFragment does not update through FleetInstall.run")
# #774 the Cloud tab drives Download / Install / Clear as separate stages, so it
# calls StoreStages — whose Install stage is FleetInstall.install, the same half
# FleetInstall.run is made of. Still one path into the installer, just one stage at a time.
stages = store_files.get("StoreStages.kt", "")
if re.search(r"\bStoreStages\.(download|install|clear)\(", cloud) and "FleetInstall::install" in stages \
        and not re.search(r"\bFleet\.commit\(", stages):
    ok("StoreCloudFragment updates through StoreStages, whose Install is FleetInstall.install")
else: bad("StoreCloudFragment's stages do not reach the installer through FleetInstall.install")
assets = os.path.join(store_dir, "../../../../../assets/appstore-install-sources.json")
src = json.loads(read(assets))
keys = sorted(src["sources"])
if not keys: bad("the install-source map declares no store")
named = ["%s: %s" % (f, k) for f, t in store_files.items() for k in keys if '"%s"' % k in t]
if keys and not named: ok("no installer package from the map (%s) is written into the store's code" % ", ".join(keys))
else: bad("an installer package is hardcoded outside the one map (#102): %s" % named)

print("== T7: #642 the COMMITS and CI-CD feeds are a READER over ONE declaration ==")
# Same rule as the install-source map above, applied to the feeds: the store may
# hold no repository, endpoint, JSON field or status word of its own. Every
# assertion is derived FROM the declaration, so it grows with the file - a third
# feed is covered the moment it is declared, and a test that only ever sees
# today's two is exactly the hollow shape this file already avoids for stores.
feeds_asset = os.path.join(store_dir, "../../../../../assets/appstore-feeds.json")
if not os.path.exists(feeds_asset):
    bad("appstore-feeds.json is missing — the feed tabs cannot be declared")
else:
    decl = json.loads(read(feeds_asset))
    feeds = decl.get("feeds", [])
    viewer = store_files.get("FeedViewer.kt", "")
    # The feeds are DRAWN by the Cloud page's Commits / CI-CD panels (libs:appstore StoreFeedPanels, Compose); FeedViewer is the reader (fetch, parse, templates).
    feedpage = os.path.join(store_dir, "StoreFeedPanels.kt")
    feedpage = code(feedpage) if os.path.exists(feedpage) else ""
    if not viewer: bad("FeedViewer.kt is missing — nothing renders the declared feeds")
    # (a) The declaration is usable: every feed needs the id/label/url a tab is
    #     built from. A feed short of one draws a nameless tab that cannot fetch.
    short = [f.get("id", "?") for f in feeds if not (f.get("id") and f.get("label") and f.get("url"))]
    if feeds and not short: ok("all %d declared feed(s) carry id, label and url" % len(feeds))
    else: bad("feed(s) with no id/label/url, or no feed declared at all: %s" % (short or "none declared"))
    # (b) ZERO KOTLIN for a new feed. No id, label, url, host, items key, state
    #     field or status word from the declaration may appear in the store's
    #     code. This is the assertion that fails when someone hardcodes a tab.
    owned = set()
    # #841 `proxy` may be an object {url, items, ref, title, subtitle, link,
    # state} describing the fleet git-proxy's reduced body: its strings are
    # declared strings too, so each is walked as one more feed-shaped entry.
    walk = []
    for f in feeds:
        p = f.get("proxy")
        if isinstance(p, dict):
            walk += [dict(f, proxy=None), dict(p, id=None, label=None, proxy=None)]
        else: walk.append(f)
    for f in walk:
        for key in ("id", "label", "url", "proxy", "items", "state"):
            if f.get(key): owned.add(f[key])
        for key in ("ok", "bad"):
            owned.update(f.get(key) or [])
        # the repo/host out of the endpoint, and every templated field path
        for m in (re.match(r"https?://([^/]+)/([^?]*)", f.get(k) or "") for k in ("url", "proxy")):
            if not m: continue
            owned.add(m.group(1))
            owned.update(p for p in m.group(2).split("/") if p)
        for key in ("ref", "title", "subtitle", "link"):
            owned.update(re.findall(r"\{([A-Za-z0-9_.]+)\}", f.get(key, "")))
    owned = {w for w in owned if len(w) > 2}
    # SourceResolver reads a non-2xx body's own error keys (GitHub `message`,
    # the git-proxy's {error, code}); that is the error parser, not a feed
    # template, so those three words are exempt in that one file only.
    err_keys = {"message", "error", "code"}
    # FDroidIndex reads F-Droid's own signed index-v1 schema (packageName, name, summary, license,
    # localized): those are that format's keys, not feed templates, so they are exempt in that file only.
    index_keys = {"packageName", "name", "summary", "license", "localized"}
    # #ipc IpcMesh reads the generated fleet IPC registry (aa_cloud-superapp/data/ipc-mesh.json): its schema
    # keys (an endpoint's name, its intent actions, ...) are that file's, not feed templates; exempt in that file only.
    ipc_keys = {"name", "actions", "id", "kind", "uris", "authority", "callers", "source", "permission", "level",
                "methods", "interface", "members", "shared", "libs", "door", "pkg", "label", "endpoints", "component",
                "method", "aidl", "groups", "apps"}
    leaked = sorted({"%s: %r" % (fn, w) for fn, t in store_files.items()
                     for w in owned if '"%s"' % w in t
                     and not (fn == "SourceResolver.kt" and w in err_keys)
                     and not (fn == "FDroidIndex.kt" and w in index_keys)
                     and not (fn == "IpcMesh.kt" and w in ipc_keys)})
    if owned and not leaked:
        ok("none of the %d declared feed strings is written in the store's code" % len(owned))
    else: bad("a feed string is hardcoded outside the one declaration: %s" % "; ".join(leaked[:6]))
    # (c) ONE REQUEST PATH. The reader must not open a connection of its own —
    #     a second one is a second timeout and a second redirect policy.
    if "SourceResolver.getBody(" in viewer: ok("the reader fetches through SourceResolver, the store's one request path")
    else: bad("FeedViewer does not fetch through SourceResolver — it has its own network path")
    own_net = re.search(r"openConnection|HttpURLConnection|URL\(", viewer)
    if not own_net: ok("FeedViewer opens no connection of its own")
    else: bad("FeedViewer opens its own connection (%s) beside SourceResolver" % own_net.group(0))
    # (d) A READER CANNOT ACT. No install, commit or uninstall reachable from it.
    acts = [n for n in ("Fleet.commit(", "Fleet.install", "FleetInstall.run(", "Fleet.uninstall(",
                        "BootstrapInstall.") if n in viewer]
    if not acts: ok("the feed reader reaches no install or uninstall path")
    else: bad("FeedViewer can act on the device: %s" % acts)
    # (e) EMPTY IS NOT FAILED. Both outcomes must be drawn, and drawn
    #     differently: a fetch that threw and rendered as an empty list is the
    #     quiet-green shape that keeps costing this repo days.
    if "explain(it)" in feedpage and "l.error != null" in feedpage and "entries.isEmpty()" in feedpage:
        ok("a failed fetch and an empty feed are separate branches")
    else: bad("FeedViewer does not distinguish a failed fetch from an empty feed")
    # (f) IN-FLIGHT IS NEUTRAL. A run that has not finished carries a state in
    #     neither ok nor bad, and must not be coloured as a failure.
    if re.search(r"in feed\.ok\s*->", feedpage) and re.search(r"in feed\.bad\s*->", feedpage) \
       and re.search(r"else\s*->", feedpage):
        ok("a state in neither ok nor bad falls to a neutral else — unfinished is not failed")
    else: bad("FeedViewer has no neutral branch: an in-flight run would be coloured as a verdict")
    # (g) The strip is a RENDERING of the declaration, and parse() is separable
    #     so a test can hand it a feed list it invented.
    #     #732: each feed tab is the feed's own label dressed by its page
    #     declaration, so the strip maps the declared feeds, in order.
    #     #896 the feeds are the Feed page's top tabs now: a Compose page in Cloud Store reads the SAME
    #     declaration through FeedViewer.feeds, and the Cloud page draws none.
    if "FeedViewer.feeds(ctx)" in feedpage and "feeds.map" not in cloud and "FeedViewer" not in cloud: ok("the Cloud page's feed panels read the declared feeds through FeedViewer, and the Cloud page itself draws none")
    else: bad("the feed panels do not read the declared feeds, or Cloud still draws feeds itself")
    if re.search(r"fun parse\(decl: JSONObject\)", viewer): ok("parse() takes a declaration, so it can be handed an invented one")
    else: bad("FeedViewer.parse is not separable from the asset")
    # (h) #668 THE PAGE SIZE IS DATA. It lives in the declared url's query, so
    #     raising it is an asset edit. A page size written in Kotlin - as a
    #     literal or appended to the url - is the same defect as a hardcoded
    #     host, and (b) above cannot see it because it only knows declared
    #     strings. Derived from the declaration, so it covers whatever the
    #     declared sizes actually are.
    sizes = {m for f in feeds for m in re.findall(r"per_page=(\d+)", f.get("url", ""))}
    if sizes:
        pinned = sorted({"%s: per_page=%s" % (fn, s) for fn, t in store_files.items()
                         for s in sizes if ("per_page=%s" % s) in t or ('"%s"' % s) in t})
        if not pinned: ok("the page size (%s) is declared in the url, not written in Kotlin" % ", ".join(sorted(sizes)))
        else: bad("a page size is hardcoded in the store's code: %s" % "; ".join(pinned))
    else: bad("no declared feed carries a per_page — the page size is not data")
    if not re.search(r'per_page|[?&]page=|"\+\s*\w*[Pp]erPage', viewer):
        ok("FeedViewer builds no paging query of its own")
    else: bad("FeedViewer assembles paging onto the url instead of reading it declared")
    # (i) #668 A 403 MUST NAME ITS CAUSE. An exhausted anonymous GitHub quota
    #     answers 403 with its reason in the BODY, and the old error read the
    #     status only - so a self-healing rate limit and a real permissions
    #     failure produced the same sentence. The reader already separates
    #     failure from empty (e); this asserts the failure carries the server's
    #     own words, and that it is read from errorStream, since inputStream
    #     throws on a non-2xx and would swallow the reason.
    resolver = store_files.get("SourceResolver.kt", "")
    if "errorStream" in resolver: ok("a non-2xx reads the server's own message off errorStream")
    else: bad("SourceResolver discards the error body, so a 403 cannot name its cause")
    if re.search(r'error\((?:.|\n)*?\$code(?:.|\n)*?\$why', resolver) or \
       re.search(r'"HTTP \$code from \$url — \$why"', resolver):
        ok("the thrown message carries both the status and the reason")
    else: bad("the thrown message does not carry the server's reason alongside the status")
    # ... and no cache may soften it: a stale feed shown as live is worse than
    #     one that says it could not read.
    cache = [n for n in ("DiskLruCache", "useCache = true", "setUseCaches(true)", "CacheControl") if n in viewer]
    if not cache: ok("the reader caches nothing, so a 403 cannot be hidden behind stale rows")
    else: bad("FeedViewer caches feed content (%s) — a stale feed would render as live" % cache)
    # (j) #668 NO FAILURE DRAWS AS EMPTY. (e) only proves two branches exist;
    #     it stayed green while a 404 or a body without the declared array
    #     returned emptyList() into the SUCCESS branch and drew "Nothing in this
    #     feed." The fetch path (load .. explain) may hold no emptyList at all:
    #     the only empty draw is a 2xx carrying an empty array.
    fetch = viewer[viewer.find("fun load("):viewer.find("fun explain(")] if "fun explain(" in viewer else ""
    if fetch and "emptyList" not in fetch and "throw SourceResolver.HttpStatus(404" in fetch:
        ok("a 404 and a shapeless body throw; the fetch path has no route to an empty list")
    else: bad("the feed fetch can turn a failure into an empty list (404/shape -> 'Nothing in this feed')")
    # (k) #668 SAY WHICH. The failure branch renders explain(), and explain()
    #     has a distinct sentence for each cause the owner has to act on
    #     differently: quota (wait), unreachable (network), refused (url/auth).
    if re.search(r"Loaded\(emptyList\(\),\s*FeedViewer\.explain\(it\)\)", feedpage):
        ok("the failure branch draws explain(), not a generic sentence")
    else: bad("the feed's failure branch does not go through explain()")
    ex = viewer[viewer.find("fun explain("):viewer.find("fun fill(")] if "fun explain(" in viewer else ""
    causes = {"quota": r"HttpStatus && t\.quota\s*->", "unreachable": r"t is IOException\s*->",
              "refused": r"t is SourceResolver\.HttpStatus\s*->"}
    missing = [k for k, rx in causes.items() if not re.search(rx, ex)]
    if not missing: ok("explain() names quota, unreachable and refused as separate causes")
    else: bad("explain() does not distinguish: %s" % missing)
    #     ... and the quota verdict is TYPED at the one request path, from HTTP
    #     conventions (429; 403 + X-RateLimit-Remaining: 0 is GitHub's exhausted
    #     anonymous quota), not re-parsed out of a sentence.
    if "throw HttpStatus(" in resolver and re.search(r"code == 429", resolver) \
       and re.search(r'"X-RateLimit-Remaining"\) == "0"', resolver):
        ok("getBody throws a typed HttpStatus whose quota flag reads 429 and X-RateLimit-Remaining: 0")
    else: bad("SourceResolver does not type a quota failure (429 / X-RateLimit-Remaining: 0)")
    # (l) #668 PUBLIC DATA NEVER NEEDS THE FLEET. Every feed's `url` is a
    #     public address, and the proxy - when declared - is only tried first:
    #     a proxy failure must fall through to `url`.
    mesh = [f.get("id") for f in feeds if re.match(r"https?://(10\.|localhost|127\.|[^/]*\.internal)", f.get("url", ""))]
    if not mesh: ok("every feed's url is public, so reading it never requires the fleet")
    else: bad("feed url points into the mesh, so public data now needs the fleet up: %s" % mesh)
    if re.search(r"read\(feed\.proxyShape, proxy,[^\n]*\)\s*\}\s*catch[^{]*\{\s*try\s*\{\s*read\(feed\.shape, feed\.url,", fetch):
        ok("a declared proxy is tried first and falls back to the public url")
    else: bad("a proxy failure does not fall back to the public url")
    # #841 THE FLEET BEARER NEVER LEAVES THE FLEET. Every read of the public
    # `url` sends no headers; only the proxy leg may carry Authorization.
    pub = re.findall(r"read\(feed\.shape, feed\.url, ([^\n]*)", fetch)
    if pub and all(h.startswith("emptyMap())") for h in pub) and "fleetBearer" in fetch:
        ok("the fleet bearer goes to the proxy only; every public read sends no credential")
    else: bad("the public GitHub read can carry the fleet bearer (or no bearer is sent at all): %s" % pub)
    # (m) #674 ADOPTION IS DATA. Every feed declares a `proxy` key (null until
    #     the route exists) and the reader parses it, so adopting the proxy is
    #     setting values in the asset - no Kotlin.
    noproxy = [f.get("id") for f in feeds if "proxy" not in f]
    if feeds and not noproxy and re.search(r'optString\("proxy"\)', viewer):
        ok("all %d feed(s) declare a proxy slot the reader parses — adoption is a data edit" % len(feeds))
    else: bad("proxy adoption is not a data edit (undeclared on %s, or not parsed)" % (noproxy or "reader"))

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
