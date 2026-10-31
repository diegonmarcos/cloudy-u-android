# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-nav-shape-guard.py ───
#!/usr/bin/env python3
"""
cloud-android-nav-shape-guard — #868: every app's navigation is ONE declaration
(build.json::ui) drawn by ONE library (libs:bottomnav), and an app cannot quietly
grow a second nav of its own.

DECLARATION  1_cicd/src/data/nav-shape.json (the rules, the forbidden widgets, and
             the exemption ledger: every not-yet-migrated app, each with its reason)

  N0  libs:bottomnav still ships NavDecl, PageTabs and PageTabsView
  N11 libs:ui-nav-bars is the ONE nav-bars lib (first row, second row, side menu, bottom nav); the retired per-bar libs stay gone and unnamed
  N1  ui.bottom_nav is min_bottom..max_bottom ids (2..8; the owner lifted the old cap of 5,
      and the island never scrolls: a crowded bar widens and scales down so every item fits,
      8 on a 360dp phone being 44dp cells), each one a ui.sections id;
      ui.default_section is one of them
  N2  the app compiles libs:bottomnav
  N3  no hand-rolled nav widget (BottomNavigationView, NavigationBar, NavigationRail,
      BottomAppBar, TabLayout, TabRow, ScrollableTabRow, PrimaryTabRow, a local
      @Composable BottomNav) in the app's own sources; comments are blanked first
  N4  the app's gradle bakes UI_BOTTOM_NAV and UI_SECTIONS_B64, and no section/page
      id literal in Kotlin names an id build.json does not declare
  N5  a section with two or more pages (or a page with two or more sub-pages) is
      drawn by PageTabs / PageTabsView
  N6  every exemption names a real app and carries a reason; an exempt app does not
      already pass N1-N5 (it migrated: delete its exemption)
  N7  parity: the island and the tab strips look the SAME in every app, because the look
      is libs:bottomnav's alone. A held app's island/strip call sites pass no colour
      scheme, palette, dimen, typography, elevation, size or inset; no source assigns
      such a property on BottomNavIslandView / PageTabsView; the app declares no
      island-ish colour/dimen/style resource of its own; and some source calls the
      lib's FleetChrome.apply (the window Cloud SuperApp has)

  N8  declared exceptions: ui.style is "fleet" (or absent) for every app but the ones
      nav-shape.json::variants names (today only Cloud Search, "search-html", at the owner's
      explicit request). A named app must declare exactly that style, draw it through the lib's
      variant call and no second island; nobody else may reference the variant's symbols. The
      variant itself lives in libs:bottomnav, so the colours, sizes and shape stay the lib's.

  W1-W4  a held app whose UI is a WebView page (Cordova Cloud Code, #1158) cannot call the Compose bars, so
      it draws the same ones from a declaration (nav-shape.json::web). W1 = N1. W2 its own nav JSON lists the
      same bottom pages, sub-pages (each with an icon) and default as build.json ui. W3 its bar sizes and
      colours (nav.json::bars) equal the Kotlin constants of libs:ui-nav-bars, -second-row,
      -bottom, bottomnav SearchHtmlTokens and theme-background, so the web bars cannot drift from the libs.
      W4 its sources draw the three bars (markers) and nothing swipes the hamburger open.

  N9  page backgrounds: every ui.sections[].background / pages[].background is one of
      nav-shape.json::backgrounds ("dark" default, "light", "theme" = the app's light/dark mode);
      it picks the strip's lib-owned TabSurface. No held app's source names a TabSurface
      itself (the surface comes from the declaration, through NavDecl.stripSurface), and an
      app that declares a non-dark background calls stripSurface( (else the line is dead).

  N12 (#1199) a page id names ONE page in the whole app: no two pages of ui.sections[].pages[] (at any depth) share an id,
      or the breadcrumb/title path resolves to the first owner (House's Listing showed "Jobs"). Holds for every app, exempt
      or not; nav-shape.json::duplicate_page_ids is the ledger of apps that still have duplicates (each with its reason and
      the duplicated ids), and an entry that no longer matches fails, so the ledger can only shrink.

  N13 the NAV UI setting (Classic / Clean + scroll-hide), declared ONCE in nav-shape.json::nav_ui and held equal to the lib's
      NavUi.kt: the modes, the Clean drawer's rows and icons and the setting ids are the same strings in both; the first row's
      fill token (NavTokens.topRowFill) is fully transparent; the shared Configs > UI card exists. Every held app lists
      `nav_ui` in build.json::ui.configs_ui AND exposes the card: it draws the lib's NavBarTopFirstRowScaffold (whose "Configs > UI"
      entry opens the card) without `configsUi = false`, or calls NavUiConfigCard( itself; an app that does neither is named in
      nav_ui.pending with a reason (and is stale once it passes). ui.nav_ui.mode is one of the modes, and an app whose default is
      not classic is named in nav_ui.app_defaults with that mode (Cloud Notes: clean), so a default cannot change silently.

Rules N1-N5, N7 and N9 hold for every app that is NOT exempt. The baseline is all-exempt, so
this is green today; each migration batch deletes its apps' entries.

USAGE  cloud-android-nav-shape-guard.py [ROOT]
EXIT   0 ok · 1 at least one violation
"""
import glob, json, os, re, sys

DATA = "1_cicd/src/data/nav-shape.json"
SKIP = {".git", "build", ".gradle", "node_modules", ".cxx", "test", "tests", "androidTest"}
DEP = re.compile(r"""(?:\bapi|[iI]mplementation)\b["')\s]*,?\s*\(?\s*project\(\s*['"]:libs:bottomnav['"]""")


def read(p):
    with open(p, encoding="utf-8", errors="replace") as h:
        return h.read()


def blank_comments(text, xml=False):
    """`text` with comment CONTENT replaced by spaces (offsets and newlines kept).
    Strings are left alone: an id literal is a string. Kotlin block comments nest."""
    out, i, n = list(text), 0, len(text)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        if xml:
            if text.startswith("<!--", i):
                end = text.find("-->", i)
                end = n if end == -1 else end + 3
                blank(i, end); i = end
            else:
                i += 1
            continue
        two = text[i:i + 2]
        if two == "//":
            end = text.find("\n", i); end = n if end == -1 else end
            blank(i, end); i = end
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text[j:j + 2] == "/*":
                    depth, j = depth + 1, j + 2
                elif text[j:j + 2] == "*/":
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(i, j); i = j
        elif text[i] == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3); i = n if end == -1 else end + 3
            else:
                j = i + 1
                while j < n and text[j] not in '"\n':
                    j += 2 if text[j] == "\\" else 1
                i = j + 1
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] not in "'\n":
                j += 2 if text[j] == "\\" else 1
            i = j + 1
        else:
            i += 1
    return "".join(out)


def walk(top, exts):
    for d, dirs, files in os.walk(top):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for f in sorted(files):
            if f.endswith(exts):
                yield os.path.join(d, f)


def line_of(text, idx):
    return text.count("\n", 0, idx) + 1


def page_ids(pages):
    for p in pages or []:
        if isinstance(p, dict):
            yield p.get("id")
            yield from page_ids(p.get("pages"))


def duplicate_page_ids(sections):
    """{id: [paths]} for every page id (any depth, section ids excluded) declared by more than one page."""
    seen = {}

    def walk(pages, path):
        for p in pages or []:
            if isinstance(p, dict):
                seen.setdefault(p.get("id"), []).append("/".join(path + [str(p.get("id"))]))
                walk(p.get("pages"), path + [str(p.get("id"))])

    for sec in sections or []:
        if isinstance(sec, dict):
            walk(sec.get("pages"), [str(sec.get("id"))])
    return {k: v for k, v in seen.items() if len(v) > 1}


def needs_strip(sections):
    def multi(pages):
        pages = [p for p in (pages or []) if isinstance(p, dict)]
        return len(pages) >= 2 or any(multi(p.get("pages")) for p in pages)
    return any(multi(s.get("pages")) for s in sections if isinstance(s, dict))


def top_level(text, open_idx):
    """The text of the call whose `(` is at open_idx with everything nested (inner parens,
    brackets, braces, strings) blanked: what is left is the call's own top-level arguments."""
    out, depth, i, n = [], 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                j += 2 if text[j] == "\\" else 1
            out.append(" " * (j + 1 - i)); i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                break
        out.append(c if depth == 1 and c not in "([{" else " ")
        i += 1
    return "".join(out)


RES_NAME = re.compile(r"<(?:color|dimen|integer|fraction|style|attr|font|bool)\s+name=\"([^\"]+)\"")


def check_parity(root, app, spec, code, bad):
    """N7: nothing app-side can change how the island and the strips look."""
    n7 = spec["n7"]
    rel = lambda p: os.path.relpath(p, root)
    calls = re.compile(r"\b(%s)\s*\(" % "|".join(re.escape(c) for c in n7["calls"]))
    args = re.compile(r"(?<![\w.])(%s)\s*=(?!=)" % "|".join(re.escape(a) for a in n7["forbidden_args"]))
    # `v.insets = x` and a bare `insets = x` (inside an `apply { }`), but not a declaration (`val insets = x`)
    props = re.compile(r"(?:(?<=\.)|(?<![\w.])(?<!val )(?<!var ))(%s)\s*=(?!=)" % "|".join(re.escape(a) for a in n7["forbidden_props"]))
    for p, text in code.items():
        if not p.endswith(".kt"):
            continue
        for m in calls.finditer(text):
            if re.search(r"\bfun\s+$", text[max(0, m.start() - 8):m.start()]):
                continue  # a declaration, not a call
            hit = args.search(top_level(text, m.end() - 1))
            if hit:
                bad.append(f"N7 {app}: {rel(p)}:{line_of(text, m.start())} passes `{hit.group(1)}` to {m.group(1)} — "
                           f"the island and strips look is libs:bottomnav's alone (FleetChrome); an app passes entries, selection and callbacks")
        if any(v in text for v in n7["view_hosts"]):
            for m in props.finditer(text):
                bad.append(f"N7 {app}: {rel(p)}:{line_of(text, m.start())} assigns `{m.group(1)}` on an island/strip view — "
                           f"the property is gone on purpose; the look is libs:bottomnav's alone")
    names = re.compile(n7["resource_names"])
    for t in (os.path.join(d, "src", "main", "res") for d in module_dirs(root, app)):
        for p in walk(t, (".xml",)):
            if f"{os.sep}values" not in p:
                continue
            for m in RES_NAME.finditer(blank_comments(read(p), xml=True)):
                if names.search(m.group(1)):
                    bad.append(f"N7 {app}: {rel(p)} declares `{m.group(1)}` — an island/strip colour, dimen or style "
                               f"of its own; libs:bottomnav's res/values is the one declaration")
    if not any(n7["chrome_call"] in t for t in code.values()):
        bad.append(f"N7 {app}: no source calls {n7['chrome_call']}…) — every app wears the window Cloud SuperApp has "
                   f"(edge-to-edge, transparent bars); call the lib's FleetChrome from the main activity")


def module_dirs(root, app):
    """The app's Gradle module dirs: ./app and ./ (the fleet convention) plus build.json
    android.gradle_module when the Android project is nested (Cloud Notes vendors the upstream's, #868)."""
    dirs = [os.path.join(root, app, "app"), os.path.join(root, app)]
    try:
        gm = (json.load(open(os.path.join(root, app, "build.json"))).get("android") or {}).get("gradle_module")
    except (OSError, ValueError):
        gm = None
    if isinstance(gm, str) and gm.strip("./"):
        dirs.append(os.path.normpath(os.path.join(root, app, gm)))
    return dirs


def app_code(root, app):
    """{path: comment-blanked source} of an app's main Kotlin/Java."""
    out = {}
    for t in (os.path.join(d, "src", "main") for d in module_dirs(root, app)):
        for p in walk(t, (".kt", ".java")):
            out[p] = blank_comments(read(p))
    return out


def check_variants(root, apps, uis, spec, exempt, bad):
    """N8: the fleet's island has ONE declared exception; everyone else stays on it."""
    variants = {k: v for k, v in (spec.get("variants") or {}).items() if not k.startswith("_")}
    styles = spec.get("styles") or ["fleet"]
    symbols = re.compile(r"\b(%s)\b" % "|".join(re.escape(x) for x in spec.get("variant_symbols") or ["SearchHtmlIsland"]))
    rel = lambda p: os.path.relpath(p, root)
    for app in apps:
        style = (uis.get(app) or {}).get("style")
        want = (variants.get(app) or {}).get("style", "fleet")
        if style is not None and style not in styles:
            bad.append(f"N8 {app}: ui.style {style!r} is not one of {styles}")
        elif (style or "fleet") != want:
            bad.append(f"N8 {app}: ui.style is {style or 'fleet'!r} but {DATA} " +
                       (f"declares {want!r} for it" if app in variants else "only lets the apps in `variants` leave the fleet island"))
        if app in variants:
            continue
        for p, text in app_code(root, app).items():
            m = symbols.search(text)
            if m:
                bad.append(f"N8 {app}: {rel(p)}:{line_of(text, m.start())} uses `{m.group(1)}`, the declared variant of "
                           f"{', '.join(sorted(variants))} — every other app wears the fleet island")
    for app, v in sorted(variants.items()):
        if app not in apps:
            bad.append(f"N8 {app}: a variant in {DATA} but there is no such app (a[acd]_*/build.json)")
            continue
        if app in exempt:
            bad.append(f"N8 {app}: a variant but also exempt — a variant app is held to N1-N7")
        if v.get("style") not in styles or v.get("style") == "fleet":
            bad.append(f"N8 {app}: variant style {v.get('style')!r} must be one of {[x for x in styles if x != 'fleet']}")
        if len(str(v.get("reason", "")).strip()) < spec["min_reason_chars"]:
            bad.append(f"N8 {app}: the variant needs a reason of at least {spec['min_reason_chars']} characters")
        code = "\n".join(app_code(root, app).values())
        if v.get("call", "") not in code:
            bad.append(f"N8 {app}: declares ui.style {v.get('style')!r} but no source calls {v.get('call')}…) — "
                       f"a variant that is not drawn is a stale exception: delete it")
        if re.search(r"\bBottomNavIsland\s*\(", code):
            bad.append(f"N8 {app}: draws BottomNavIsland as well as its variant — one island per app")


def check_n1(app, ui, spec, bad):
    """N1: ui.bottom_nav is 2..8 ids, each a ui.sections id, and the default is one of them. Returns (sections, ids)."""
    sections = [s for s in (ui.get("sections") or []) if isinstance(s, dict)]
    ids = {s.get("id") for s in sections}
    bar = ui.get("bottom_nav")

    # N1
    if not isinstance(bar, list) or not bar:
        bad.append(f"N1 {app}: build.json ui.bottom_nav is missing or empty")
        bar = []
    if len(bar) > spec["max_bottom"]:
        bad.append(f"N1 {app}: ui.bottom_nav has {len(bar)} ids, the island holds at most {spec['max_bottom']}")
    if bar and len(bar) < spec["min_bottom"]:
        bad.append(f"N1 {app}: ui.bottom_nav has {len(bar)} ids, the island needs at least {spec['min_bottom']}")
    for b in bar:
        if b not in ids:
            bad.append(f"N1 {app}: ui.bottom_nav id {b!r} is not a ui.sections id")
    if not sections:
        bad.append(f"N1 {app}: build.json ui.sections is missing or empty")
    if ui.get("default_section") not in bar:
        bad.append(f"N1 {app}: ui.default_section {ui.get('default_section')!r} is not one of ui.bottom_nav")
    return sections, ids


def check_app(root, app, ui, spec, bad):
    sections, ids = check_n1(app, ui, spec, bad)
    bar = ui.get("bottom_nav")
    bar = bar if isinstance(bar, list) else []

    # N2
    gradles = [p for pat in ("build.gradle*", os.path.join("app", "build.gradle*"), "settings.gradle*")
               for p in glob.glob(os.path.join(root, app, pat))]
    for d in module_dirs(root, app)[2:]:  # a nested Android project: its module and its settings
        gradles += glob.glob(os.path.join(d, "build.gradle*")) + glob.glob(os.path.join(os.path.dirname(d), "settings.gradle*"))
    try:
        cfg = json.load(open(os.path.join(root, app, "build.json")))
    except (OSError, ValueError):
        cfg = {}
    mods = cfg.get("modules") if isinstance(cfg.get("modules"), dict) else {}
    if not any(DEP.search(read(g)) for g in gradles) and "libs:bottomnav" not in mods:
        bad.append(f"N2 {app}: does not compile libs:bottomnav (no project(':libs:bottomnav') dependency, no build.json module)")

    # N4a
    app_gradles = [g for g in gradles if "settings" not in os.path.basename(g)]
    gtext = "\n".join(read(g) for g in app_gradles)
    for field in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64"):
        if field not in gtext:
            bad.append(f"N4 {app}: build.gradle does not bake {field}")

    # sources
    tops = [os.path.join(d, "src", "main") for d in module_dirs(root, app)]
    kt, xml = [], []
    for t in tops:
        kt += list(walk(t, (".kt", ".java")))
        xml += list(walk(t, (".xml",)))
    code = {p: blank_comments(read(p)) for p in kt}
    layouts = {p: blank_comments(read(p), xml=True) for p in xml if f"{os.sep}res{os.sep}layout" in p}
    rel = lambda p: os.path.relpath(p, root)

    # N3
    for p, text in {**code, **layouts}.items():
        for f in spec["forbidden"]:
            m = re.search(f["re"], text)
            if m:
                bad.append(f"N3 {app}: {rel(p)}:{line_of(text, m.start())} draws its own nav ({f['id']}) — "
                           f"the bar is libs:bottomnav's BottomNavIsland/BottomNavIslandView, the strips PageTabs/PageTabsView")

    # N4b
    decl_sections = ids
    decl_pages = {i for s in sections for i in page_ids(s.get("pages"))}
    calls = "|".join(re.escape(c) for c in spec["id_calls"])
    call_rx = re.compile(r"\b(?:%s)\(\s*\"([^\"\n]+)\"" % calls)
    page_rx = re.compile(r"\"page:([\w-]+)/([\w-]+)\"")
    for p, text in code.items():
        for m in call_rx.finditer(text):
            if m.group(1) not in decl_sections:
                bad.append(f"N4 {app}: {rel(p)}:{line_of(text, m.start())} names section {m.group(1)!r}, which build.json ui.sections does not declare")
        for m in page_rx.finditer(text):
            if m.group(1) not in decl_sections or m.group(2) not in decl_pages:
                bad.append(f"N4 {app}: {rel(p)}:{line_of(text, m.start())} names page:{m.group(1)}/{m.group(2)}, which build.json does not declare")

    # N5
    if needs_strip(sections) and not any(u in t for t in code.values() for u in spec["strip_users"]):
        bad.append(f"N5 {app}: a section has two or more pages but no source renders PageTabs/PageTabsView")

    # N7
    check_parity(root, app, spec, code, bad)

    # N9
    check_backgrounds(app, sections, spec, code, rel, bad)


KT_NUM = re.compile(r"^(-?\d+(?:\.\d+)?)(?:\.(?:dp|sp))?$")
KT_COLOR = re.compile(r"Color\(\s*(0x[0-9A-Fa-f]{8})\s*\)")


def kt_object(text, name):
    """The body of `object NAME { ... }` (brace-balanced), comments blanked."""
    m = re.search(r"\bobject\s+%s\s*\{" % re.escape(name), text)
    if not m:
        return None
    depth, i = 1, m.end()
    while i < len(text) and depth:
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    return text[m.end():i - 1]


def kt_consts(body):
    """{NAME: expression} of every `val NAME: Type = expr` line in an object body."""
    return {m.group(1): m.group(2).strip() for m in re.finditer(r"\bval\s+(\w+)\s*:\s*\w+\s*=\s*([^\n]+)", body)}


def kt_number(expr, consts, others):
    """A Kotlin dp/sp/int literal, or a reference to a sibling constant / `Object.name` of another object."""
    m = KT_NUM.match(expr.strip())
    if m:
        return float(m.group(1))
    m = re.match(r"^(?:(\w+)\.)?(\w+)$", expr.strip())
    if m:
        pool = consts if not m.group(1) else others.get(m.group(1), {})
        if m.group(2) in pool:
            return kt_number(pool[m.group(2)], consts if not m.group(1) else pool, others)
    return None


def check_web(root, app, ui, spec, bad):
    """Rules W1-W4 for a held app whose UI is a WebView page (Cordova): it cannot call the Compose bars, so it draws the
    SAME bars from a declaration, and the guard holds that declaration to the libs.
      W1  N1: build.json ui.bottom_nav / sections / default_section
      W2  the app's own nav JSON lists the same bottom pages, sub-pages and default as build.json ui
      W3  its bar numbers and colours equal the Kotlin constants of the shared libs (web.parity)
      W4  its sources draw the three bars (web.markers)
    """
    w = spec["web"]
    cfg = w["apps"][app]
    rel = lambda p: os.path.relpath(p, root)
    sections, ids = check_n1(app, ui, spec, bad)
    try:
        nav = json.load(open(os.path.join(root, cfg["nav"])))
    except (OSError, ValueError) as e:
        bad.append(f"W2 {app}: {cfg['nav']} is unreadable ({e})")
        return
    tabs = nav.get("tabs") or []
    if [t.get("id") for t in tabs] != (ui.get("bottom_nav") or []):
        bad.append(f"W2 {app}: {cfg['nav']} tabs {[t.get('id') for t in tabs]} differ from build.json ui.bottom_nav {ui.get('bottom_nav')}")
    if nav.get("default_tab") != ui.get("default_section"):
        bad.append(f"W2 {app}: {cfg['nav']} default_tab {nav.get('default_tab')!r} differs from ui.default_section {ui.get('default_section')!r}")
    by_id = {s.get("id"): s for s in sections}
    for t in tabs:
        want = [p.get("id") for p in (by_id.get(t.get("id"), {}).get("pages") or [])]
        got = [p.get("id") for p in (t.get("pages") or [])]
        if want != got:
            bad.append(f"W2 {app}: tab {t.get('id')} sub-pages {got} differ from build.json ui section pages {want}")
        sec = by_id.get(t.get("id"), {})
        if sec.get("icon") != t.get("icon"):
            bad.append(f"W2 {app}: tab {t.get('id')} icon {t.get('icon')!r} differs from build.json ui section icon {sec.get('icon')!r}")
        ui_pages = {p.get("id"): p for p in sec.get("pages") or []}
        for p in t.get("pages") or []:
            if not p.get("icon"):
                bad.append(f"W2 {app}: sub-page {t.get('id')}/{p.get('id')} declares no icon — the second row always draws one")
            elif ui_pages.get(p.get("id"), {}).get("icon") != p.get("icon"):
                bad.append(f"W2 {app}: sub-page {t.get('id')}/{p.get('id')} icon {p.get('icon')!r} differs from build.json ui ({ui_pages.get(p.get('id'), {}).get('icon')!r})")

    # W3
    bars = nav.get("bars") or {}
    files = {}
    for key, ent in w["parity"].items():
        path = os.path.join(root, ent["file"])
        try:
            files[ent["file"]] = blank_comments(read(path))
        except OSError:
            bad.append(f"W3 {app}: the shared lib source {ent['file']} is gone")
    others = {}
    for ent in w["parity"].values():
        text = files.get(ent["file"])
        for obj in [ent["object"]] + ent.get("also", []):
            body = kt_object(text or "", obj) if text else None
            if body is None:
                for t2 in files.values():
                    body = kt_object(t2, obj)
                    if body is not None:
                        break
            if body is not None:
                others[obj] = kt_consts(body)
    for key, ent in w["parity"].items():
        text = files.get(ent["file"])
        if text is None:
            continue
        consts = others.get(ent["object"])
        if consts is None:
            bad.append(f"W3 {app}: {ent['file']} no longer declares `object {ent['object']}`")
            continue
        for js, kt in sorted(ent["map"].items()):
            want = kt_number(consts.get(kt, ""), consts, others)
            got = (bars.get(key) or {}).get(js)
            if want is None:
                bad.append(f"W3 {app}: {ent['object']}.{kt} is not a number the guard can read")
            elif got != want:
                bad.append(f"W3 {app}: {cfg['nav']} bars.{key}.{js} is {got!r} but {ent['object']}.{kt} is {want:g} — change the lib, then the file, never one alone")
    # colours: ThemeColors DARK/LIGHT fields and SearchHtmlTokens' `fun name(dark)` pairs
    tf = w["theme_file"]
    ttext = files.get(tf) or blank_comments(read(os.path.join(root, tf)))
    tab_selected = KT_COLOR.search(re.search(r"\bval\s+TAB_SELECTED\s*:\s*Color\s*=\s*([^\n]+)", ttext).group(1)).group(1)
    for mode, name in (("dark", "DARK"), ("light", "LIGHT")):
        m = re.search(r"\bval\s+%s\s*=\s*ThemeColors\((.*?)\n    \)" % name, ttext, re.S)
        if not m:
            bad.append(f"W3 {app}: {tf} no longer declares ThemeColors {name}")
            continue
        for fm in re.finditer(r"\b(\w+)\s*=\s*(Color\(\s*0x[0-9A-Fa-f]{8}\s*\)|TAB_SELECTED)", m.group(1)):
            lit = tab_selected if fm.group(2) == "TAB_SELECTED" else KT_COLOR.search(fm.group(2)).group(1)
            got = ((bars.get("theme") or {}).get(mode) or {}).get(fm.group(1))
            if (got or "").upper().replace("0X", "0x") != lit.upper().replace("0X", "0x"):
                bad.append(f"W3 {app}: {cfg['nav']} bars.theme.{mode}.{fm.group(1)} is {got!r} but {tf} {name} has {lit}")
    st = w["bottom_colors_file"]
    stext = blank_comments(read(os.path.join(root, st)))
    for js in ("fill", "border", "accent", "idle"):
        fm = re.search(r"fun\s+%s\(dark: Boolean\):\s*Color\s*=\s*if \(dark\)\s*(Color\([^)]*\))\s*else\s*(Color\([^)]*\))" % js, stext)
        if not fm:
            bad.append(f"W3 {app}: {st} no longer declares SearchHtmlTokens.{js}(dark)")
            continue
        for mode, lit in (("dark", fm.group(1)), ("light", fm.group(2))):
            hexv = KT_COLOR.search(lit).group(1)
            got = ((bars.get("bottom_colors") or {}).get(mode) or {}).get(js)
            if (got or "").upper().replace("0X", "0x") != hexv.upper().replace("0X", "0x"):
                bad.append(f"W3 {app}: {cfg['nav']} bars.bottom_colors.{mode}.{js} is {got!r} but {st} {js}() has {hexv}")

    # W4
    srcs = "\n".join(blank_comments(read(os.path.join(root, f))) for f in w["sources"] if os.path.isfile(os.path.join(root, f)))
    for marker in w["markers"]:
        if marker not in srcs:
            bad.append(f"W4 {app}: no source of {', '.join(w['sources'])} draws `{marker}` — the shared bars are not on screen")
    for f in w["forbidden"]:
        for p in w["sources"]:
            pp = os.path.join(root, p)
            if os.path.isfile(pp) and re.search(f["re"], read(pp)):
                bad.append(f"W4 {app}: {p} {f['why']}")
    check_backgrounds(app, sections, spec, {}, rel, bad)



def declared_backgrounds(nodes, where=""):
    """(path, value) for every `background` key in sections/pages, to any depth."""
    for n in nodes or []:
        if not isinstance(n, dict):
            continue
        here = f"{where}/{n.get('id')}" if where else str(n.get("id"))
        if "background" in n:
            yield here, n.get("background")
        yield from declared_backgrounds(n.get("pages"), here)


ONE_ROW_MODES = ("horizontal_one_row", "one_row")
TWO_ROWS_MODES = ("horizontal_two_rows", "many_rows")
SIDE_MENU_MODES = ("vertical_side_menu",)
DYNAMIC_CHILDREN = ("mail_folders_labels",)


def check_second_rows(app, sections, bad):
    """N10: ui.sections[].nav_mode is horizontal_one_row (alias one_row, the default) | horizontal_two_rows (alias many_rows) |
    vertical_side_menu; groups ({id,label,icon,pages:[page ids]}) only with horizontal_two_rows or vertical_side_menu;
    in horizontal_two_rows every page of the section is in exactly one group, in vertical_side_menu a page may stay ungrouped
    (it is listed after the groups); a group names only the section's pages, ids unique. `dynamic_children` (the side menu's
    items come from the app's own data, e.g. the mail account's folders and labels) names a known source and needs the side menu."""
    for s in sections or []:
        if not isinstance(s, dict):
            continue
        sid, mode, groups = s.get("id"), s.get("nav_mode", "horizontal_one_row"), s.get("groups")
        known = ONE_ROW_MODES + TWO_ROWS_MODES + SIDE_MENU_MODES
        if mode not in known:
            bad.append(f"N10 {app}: ui.sections {sid} declares nav_mode {mode!r}, not one of {', '.join(known)}")
        dyn = s.get("dynamic_children")
        if dyn is not None:
            if dyn not in DYNAMIC_CHILDREN:
                bad.append(f"N10 {app}: ui.sections {sid} declares dynamic_children {dyn!r}, not one of {', '.join(DYNAMIC_CHILDREN)}")
            if mode not in SIDE_MENU_MODES:
                bad.append(f"N10 {app}: ui.sections {sid} declares dynamic_children but nav_mode is not vertical_side_menu")
        grouped = mode in TWO_ROWS_MODES or mode in SIDE_MENU_MODES
        if groups is None and mode not in TWO_ROWS_MODES:
            continue
        if not grouped:
            bad.append(f"N10 {app}: ui.sections {sid} declares groups but nav_mode is not horizontal_two_rows or vertical_side_menu")
            continue
        flat = [p.get("id") for p in s.get("pages") or [] if isinstance(p, dict)]
        ids, seen = [], []
        for g in groups if isinstance(groups, list) else []:
            gid = g.get("id") if isinstance(g, dict) else None
            names = g.get("pages") if isinstance(g, dict) else None
            if not gid or not names or not all(isinstance(n, str) for n in names):
                bad.append(f"N10 {app}: ui.sections {sid} has a group with no id, or pages that are not a list of page ids")
                continue
            ids.append(gid)
            for pid in names:
                if pid not in flat:
                    bad.append(f"N10 {app}: ui.sections {sid} group {gid!r} names page {pid!r}, which is not in the section's pages")
                if pid in seen:
                    bad.append(f"N10 {app}: ui.sections {sid} page {pid!r} is in two groups")
                seen.append(pid)
        if len(ids) != len(set(ids)):
            bad.append(f"N10 {app}: ui.sections {sid} repeats a group id")
        if mode in TWO_ROWS_MODES:
            for pid in flat:
                if pid not in seen:
                    bad.append(f"N10 {app}: ui.sections {sid} horizontal_two_rows: page {pid!r} is in no group")


def check_backgrounds(app, sections, spec, code, rel, bad):
    check_second_rows(app, sections, bad)
    allowed = spec["backgrounds"]
    found = list(declared_backgrounds(sections))
    for where, value in found:
        if value not in allowed:
            bad.append(f"N9 {app}: ui.sections {where} declares background {value!r}, not one of {allowed}")
    for p, text in code.items():
        m = re.search(r"\bTabSurface\.(Dark|Light)\b", text)
        if m:
            bad.append(f"N9 {app}: {rel(p)}:{line_of(text, m.start())} names TabSurface.{m.group(1)} — the strip's surface "
                       f"comes from build.json (ui.sections[].background) through NavDecl.stripSurface, not from code")
    if any(v in allowed and v != "dark" for _, v in found) and not any("stripSurface(" in t for t in code.values()):
        bad.append(f"N9 {app}: declares a light/theme page background but no source calls stripSurface( — "
                   f"the declaration reaches no strip")


def kotlin_strings(text, enum):
    """The declared strings of `enum class <enum>(... val declared: String) { A("a"), B("b") }`, in order."""
    m = re.search(r"enum class %s\([^)]*\)\s*\{(.*?)\n\}" % re.escape(enum), text, re.S)
    return re.findall(r'^\s*\w+\("([^"]+)"\)', m.group(1), re.M) if m else []


def check_nav_ui(root, apps, uis, exempt, spec, bad):
    """N13: the nav UI setting (Classic / Clean, scroll-hide) is declared once, held equal to the lib, and every held app exposes it."""
    nu = spec.get("nav_ui")
    if not nu:
        bad.append(f"N13 {DATA}: the nav_ui declaration is missing")
        return
    rel = lambda p: os.path.relpath(p, root)
    ktf = os.path.join(root, nu["kotlin"])
    kt = blank_comments(read(ktf)) if os.path.isfile(ktf) else ""
    if not kt:
        bad.append(f"N13 {nu['kotlin']} is gone — the nav UI declaration (modes, drawer rows, scroll policy) lives there")
    else:
        # DrawerRow: every row the Clean drawer can draw (drawer_rows; older declarations list only clean_drawer_rows).
        # NavActionsPlace: where an app's launch pages live (actions_places, #1369); NavBottomKind: what Clean's bottom nav holds (bottom_kinds, #1369).
        for enum, key in (("NavUiMode", "modes"), ("DrawerRow", "drawer_rows" if "drawer_rows" in nu else "clean_drawer_rows"),
                          ("NavQuickAction", "clean_drawer_actions"), ("NavActionsPlace", "actions_places"),
                          ("NavBottomKind", "bottom_kinds")):
            if key not in nu:
                continue
            if kotlin_strings(kt, enum) != nu.get(key):
                bad.append(f"N13 {nu['kotlin']} {enum} declares {kotlin_strings(kt, enum)} but {DATA}::nav_ui.{key} is {nu.get(key)}")
        for const, key in (("SETTING_MODE", "settings_mode"), ("SETTING_SCROLL_HIDE", "settings_scroll_hide"), ("CONFIGS_UI_ID", "configs_ui_id")):
            m = re.search(r'const val %s: String = "([^"]*)"' % const, kt)
            if not m or m.group(1) != nu.get(key):
                bad.append(f"N13 {nu['kotlin']} {const} is {m.group(1) if m else None!r} but {DATA}::nav_ui.{key} is {nu.get(key)!r}")
        for const, key in (("CLEAN_DRAWER_ROWS", "clean_drawer_rows"), ("CLEAN_DRAWER_ROWS_WITH_ACTIONS", "clean_drawer_rows_with_actions")):
            if key not in nu:
                continue
            m = re.search(r"val %s\b[^=]*=\s*listOf\(([^)]*)\)" % const, kt)
            rows = [x.strip().split(".")[-1] for x in m.group(1).split(",")] if m else []
            want = ["".join(w.capitalize() for w in r.split("_")) for r in nu[key]]
            if rows != want:
                bad.append(f"N13 {nu['kotlin']} {const} is {rows} but the declared rows are {want}")
        if "ChromeScrollPolicy.Hide" not in kt or "Hidden" not in kt:
            bad.append(f"N13 {nu['kotlin']} no longer declares the scroll-hide policy (a downward scroll hides ALL bars)")
        if not re.search(r"secondRow\s*=\s*!clean", kt) or not re.search(r"hamburger\s*=\s*clean", kt) or not re.search(r"bottomLabels\s*=\s*!clean", kt):
            bad.append(f"N13 {nu['kotlin']} plan(): Clean must have the hamburger, no second row and no bottom labels")
    tok = os.path.join(root, nu["tokens"])
    m = re.search(r"val topRowFill: Color = Color\(0x([0-9A-Fa-f]{8})\)", read(tok)) if os.path.isfile(tok) else None
    if not m or m.group(1)[:2] != "00":
        bad.append(f"N13 {nu['tokens']} topRowFill must be a fully transparent colour (alpha 00) — the first row has no grey in any mode; found {m.group(1) if m else None}")
    for key in ("cleanBarHeight", "cleanCellHeight", "cleanIconSize"):
        if not os.path.isfile(tok) or not re.search(r"val %s: Dp = [\d.]+\.dp" % key, read(tok)):
            bad.append(f"N13 {nu['tokens']} no longer declares the Clean size token {key}")
    card = os.path.join(root, nu["card"]["file"])
    if not os.path.isfile(card) or nu["card"]["symbol"] not in read(card):
        bad.append(f"N13 {nu['card']['file']} no longer declares `{nu['card']['symbol']}` — the shared Configs > UI card")
    scaffold = os.path.join(root, nu["scaffold"]["file"])
    if not os.path.isfile(scaffold) or nu["scaffold"]["needle"] not in read(scaffold):
        bad.append(f"N13 {nu['scaffold']['file']} no longer carries `{nu['scaffold']['needle']}` — the first row's Configs > UI entry")
    for hold in nu.get("must_hold") or []:
        hf = os.path.join(root, hold["file"])
        if not os.path.isfile(hf) or hold["needle"] not in blank_comments(read(hf)):
            bad.append(f"N13 {hold['file']} no longer carries `{hold['needle']}` — {hold['why']}")
    pending = {k: v for k, v in (nu.get("pending") or {}).items() if not k.startswith("_")}
    defaults = {k: v for k, v in (nu.get("app_defaults") or {}).items() if not k.startswith("_")}
    for app in apps:
        if app in exempt:
            continue
        ui = uis.get(app) or {}
        decl = ui.get("nav_ui")
        mode = (decl or {}).get("mode", "classic") if isinstance(decl, dict) or decl is None else None
        if decl is not None and (not isinstance(decl, dict) or mode not in nu["modes"] or not isinstance(decl.get("scroll_hide", True), bool)):
            bad.append(f"N13 {app}: ui.nav_ui must be an object with mode one of {nu['modes']} and a boolean scroll_hide")
            continue
        if decl and "bottom_kinds" in nu and decl.get("bottom", "sections") not in nu["bottom_kinds"]:
            bad.append(f"N13 {app}: ui.nav_ui.bottom is {decl.get('bottom')!r}, not one of {nu['bottom_kinds']}")
        if mode != defaults.get(app, "classic"):
            bad.append(f"N13 {app}: ui.nav_ui.mode is {mode!r} but {DATA}::nav_ui.app_defaults says {defaults.get(app, 'classic')!r} — a default is declared in both places")
        code = "\n".join(app_code(root, app).values())
        calls_card = nu["card_call"] in code
        scaffold_ok = nu["scaffold_call"] in code and not re.search(r"\bconfigsUi\s*=\s*false\b", code)
        exposed = calls_card or scaffold_ok
        listed = nu["configs_ui_id"] in (ui.get("configs_ui") or [])
        if app in pending:
            if exposed and listed:
                bad.append(f"N13 {app}: exposes Configs > UI now — delete its entry in {DATA}::nav_ui.pending")
            if len(str(pending[app]).strip()) < spec["min_reason_chars"]:
                bad.append(f"N13 {app}: nav_ui.pending needs a reason of at least {spec['min_reason_chars']} characters")
            continue
        if not listed:
            bad.append(f"N13 {app}: build.json ui.configs_ui must list {nu['configs_ui_id']!r} — every app with the shared nav offers Configs > UI (nav mode, scroll-hide)")
        if not exposed:
            bad.append(f"N13 {app}: no source draws the Configs > UI card — use libs:ui-nav-bars NavBarTopFirstRowScaffold (its Configs > UI entry) or call {nu['card_call']}…) in the app's Configs")
    for app in sorted(set(pending) | set(defaults)):
        if app not in apps:
            bad.append(f"N13 {app}: in {DATA}::nav_ui but there is no such app (a[acd]_*/build.json)")
        elif app in exempt:
            bad.append(f"N13 {app}: in {DATA}::nav_ui but exempt — exempt apps draw no shared nav")


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    spec = json.load(open(os.path.join(root, DATA)))
    exempt = spec.get("exempt") or {}
    web_apps = {k: v for k, v in ((spec.get("web") or {}).get("apps") or {}).items() if not k.startswith("_")}
    bad = []

    # N0
    lib = os.path.join(root, spec["lib"])
    for fname, symbol in sorted(spec["lib_symbols"].items()):
        # #876: the declaration and the strip live in src/commonMain (shared with the wasm page), the View hosts in src/main.
        hits = [p for sub in ("main", "commonMain") for p in walk(os.path.join(lib, "src", sub), (".kt",)) if os.path.basename(p) == fname]
        if not hits:
            bad.append(f"N0 {spec['lib']}: {fname} is gone — the nav declaration and strips live there")
        elif not any(symbol in read(p) for p in hits):
            bad.append(f"N0 {spec['lib']}: {fname} no longer declares `{symbol}`")

    # N11 (#1189): ONE shared nav-bars lib (libs:ui-nav-bars) holds the first row, the second row, the side menu and the bottom nav;
    # the retired per-bar libs are gone and no app names them; the lib keeps the symbols the scroll machine and the side menu are held to.
    ui_lib = spec.get("ui_nav_bars")
    if ui_lib:
        base = os.path.join(root, ui_lib["dir"])
        if not os.path.isfile(os.path.join(base, "build.gradle")):
            bad.append(f"N11 {ui_lib['dir']}: the shared nav-bars lib is gone")
        for fname, symbol in sorted(ui_lib["symbols"].items()):
            hits = [p for sub in ("main", "commonMain") for p in walk(os.path.join(base, "src", sub), (".kt",)) if os.path.basename(p) == fname]
            if not hits:
                bad.append(f"N11 {ui_lib['dir']}: {fname} is gone")
            elif not any(symbol in read(p) for p in hits):
                bad.append(f"N11 {ui_lib['dir']}: {fname} no longer declares `{symbol}`")
        for retired in ui_lib["retired"]:
            if os.path.isdir(os.path.join(root, "ab_cloud-libs-shared", "libs", retired)):
                bad.append(f"N11 ab_cloud-libs-shared/libs/{retired} is back — its code lives in {ui_lib['dir']}")
        for b in sorted(glob.glob(os.path.join(root, "a[acd]_*", "build.json"))):
            try:
                mods = json.load(open(b)).get("modules") or {}
            except (OSError, ValueError):
                continue
            for retired in ui_lib["retired"]:
                if f"libs:{retired}" in mods:
                    bad.append(f"N11 {os.path.basename(os.path.dirname(b))}: build.json modules name the retired libs:{retired}; use libs:ui-nav-bars")

    apps = sorted(os.path.basename(os.path.dirname(b)) for b in glob.glob(os.path.join(root, "a[acd]_*", "build.json")))
    checked = 0
    for app in apps:
        try:
            ui = json.load(open(os.path.join(root, app, "build.json"))).get("ui") or {}
        except (OSError, ValueError) as e:
            bad.append(f"N6 {app}: build.json is unreadable ({e})")
            continue
        if app in exempt:
            if ui.get("bottom_nav"):
                # Declaring the key is not migrating (superapp has had one for a long time): the
                # exemption is stale only when the app would pass N1-N5 outright.
                mine = []
                check_app(root, app, ui, spec, mine)
                if not mine:
                    bad.append(f"N6 {app}: passes N1-N5 but is still exempt — it migrated; delete its entry in {DATA}")
            continue
        checked += 1
        if app in web_apps:
            check_web(root, app, ui, spec, bad)
        else:
            check_app(root, app, ui, spec, bad)

    uis = {}
    for app in apps:
        try:
            uis[app] = json.load(open(os.path.join(root, app, "build.json"))).get("ui") or {}
        except (OSError, ValueError):
            pass
    check_variants(root, apps, uis, spec, exempt, bad)

    for app, cfg in sorted(web_apps.items()):
        if app not in apps:
            bad.append(f"N6 {app}: a web app in {DATA} but there is no such app (a[acd]_*/build.json)")
        if app in exempt:
            bad.append(f"N6 {app}: a web app but also exempt — a web app is held to N1 + W1-W4")
        if len(str(cfg.get("reason", "")).strip()) < spec["min_reason_chars"]:
            bad.append(f"N6 {app}: a web app needs a reason of at least {spec['min_reason_chars']} characters (why it is not drawn by the Compose libs)")

    for app, why in sorted(exempt.items()):
        if app not in apps:
            bad.append(f"N6 {app}: exempt in {DATA} but there is no such app (a[acd]_*/build.json)")
        if not isinstance(why, str) or len(why.strip()) < spec["min_reason_chars"]:
            bad.append(f"N6 {app}: exemption needs a reason of at least {spec['min_reason_chars']} characters")

    # N12
    known = {k: v for k, v in (spec.get("duplicate_page_ids") or {}).items() if not k.startswith("_")}
    for app in apps:
        dups = duplicate_page_ids((uis.get(app) or {}).get("sections"))
        entry = known.get(app)
        if dups and entry is None:
            bad.append(f"N12 {app}: page ids declared more than once {dict(sorted(dups.items()))} — make each id unique in the app (e.g. jobs-listing), "
                       f"the breadcrumb resolves to the first owner")
        elif dups and sorted(entry.get("ids", [])) != sorted(dups):
            bad.append(f"N12 {app}: {DATA}::duplicate_page_ids lists {sorted(entry.get('ids', []))} but the duplicates are {sorted(dups)}")
        elif entry is not None and not dups:
            bad.append(f"N12 {app}: no duplicate page ids any more — delete its entry in {DATA}::duplicate_page_ids")
        if entry is not None and len(str(entry.get("reason", "")).strip()) < spec["min_reason_chars"]:
            bad.append(f"N12 {app}: duplicate_page_ids entry needs a reason of at least {spec['min_reason_chars']} characters")
    for app in sorted(known):
        if app not in apps:
            bad.append(f"N12 {app}: in {DATA}::duplicate_page_ids but there is no such app (a[acd]_*/build.json)")

    check_nav_ui(root, apps, uis, exempt, spec, bad)

    for b in bad:
        print("FAIL     " + b)
    print(f"── {len(apps)} app(s): {checked} held to N1-N5 + N7 + N8 + N9, {len(apps) - checked} exempt; {len(bad)} violation(s) ──")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
