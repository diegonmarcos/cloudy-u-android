#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ #1707 WRITER PARITY: every feature, page and setting of the retired   ║
# ║ standalone Cloud Writer exists in Cloud Keyboard's Writer             ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# The standalone app (ac_cloud-writer) was removed because the Writer tab of Cloud Keyboard is the same code
# (libs:writer-ui, hosted by reference). This tester is the proof it ran against BEFORE the folder was deleted, and
# stays as the guard that nothing of the old app is lost afterwards (rule 16: nothing existing is removed). The old
# app's declarations are frozen below as the BASELINE (copied from its build.json, AndroidManifest.xml and
# WriterPrefs.kt at the commit before deletion); each baseline row must still be found in the keyboard.
#
#   P1 PAGES      every ui page of the old app (the five bottom-nav pages, the five Tools pages, Configs with its six pages,
#                 Voice transcription) is declared in libs:writer-ui (writer-config.json ui + WriterSurface page ids) and is
#                 reachable from the keyboard's Writer tab (KbTab.WRITER host routes). The ONE named exception is Home (the
#                 document list): removed from the Writer's top nav on purpose by the owner (#1706); WriterSurface still draws
#                 it, so no code is lost.
#   P2 SETTINGS   every preference key of the old app's text_tools store is still a WriterPrefs key (the store migrates with
#                 the keyboard: fleet-config stores.text_tools used_by lib-writer-ui).
#   P3 ENTRY      the old app's manifest surface is in the keyboard's manifest: share text, share image, PROCESS_TEXT, the five
#                 Tools activities, ListenService, the OCR FileProvider and its paths, the debug-API provider, the permissions.
#   P4 REGISTRY   writer_ai (providers, styles, tones, summaries), writer_routes (speech, translation, OCR, voice engine,
#                 debug API group) and update are in libs:writer-ui's writer-config.json.
#   P5 DATA       fleet-config: the keyboard links lib-writer-ui / lib-writer-core, text_tools is used by lib-writer-ui, the
#                 Writer's documents are declared as the keyboard's item; the old `writer` app entry is gone.
#   P6 RETIRED    nothing of the app remains and nothing offers it: no ac_cloud-writer dir, no ship workflow, no release or
#                 ghcr asset, no fleet / apk-manifest / lib-consumer / launcher entry; the Store lists it as retired.
#   P7 NOT VACUOUS the checks above are run again on a copy with one baseline row removed per rule and must FAIL.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
command -v python3 >/dev/null || { echo "FAIL   python3 is absent; this tester would pass on nothing"; exit 1; }

exec python3 - "$ROOT" <<'PY'
import glob, json, os, re, shutil, sys, tempfile

BASELINE = {
    "bottom_nav": ["enhancer", "drafter", "home", "translator", "grammar"],
    "tools_pages": ["enhance", "translation", "grammar", "ai_routing", "routes"],
    "settings_pages": ["general", "perms", "about", "appearance", "models", "ocr"],
    "sections": ["enhancer", "drafter", "home", "translator", "grammar", "writer", "tools", "settings"],
    "default_section": "home",
    # page id of WriterSurface -> the keyboard host route id (hostRoute("<id>")) that reaches it; None = intentionally not a top-nav page
    "keyboard_routes": {
        "enhancer": "enhancer", "drafter": "drafter", "translator": "translator", "grammar": "grammar",
        "tools": "tools", "tools.enhance": "tools.enhance", "tools.translation": "tools.translation",
        "tools.grammar": "tools.grammar", "tools.ai_routing": "tools.ai_routing", "tools.routes": "tools.routes",
        "dictation": "dictation", "settings": "settings",
    },
    "home_exception": "home",
    "prefs_file": "text_tools",
    "pref_keys": {
        "KEY_PROVIDER": "ai_provider", "KEY_MODEL_PREFIX": "ai_model_", "KEY_TOOL_MODEL_PREFIX": "ai_tool_model_",
        "KEY_ENHANCE_STYLE": "enhance_style", "KEY_ENHANCE_TONE": "enhance_tone", "KEY_ENHANCE_LENGTH": "enhance_length",
        "KEY_ENHANCE_LANGUAGE": "enhance_language", "KEY_SUMMARY_STYLE": "summary_style", "KEY_ENHANCE_SCOPE": "enhance_scope",
        "KEY_ENHANCE_TOOLBAR_KEY": "enhance_toolbar_key", "KEY_TRANSLATE_TARGET": "translate_default_target",
        "KEY_TRANSLATE_AUTO_DETECT": "translate_auto_detect", "KEY_TRANSLATE_APPLY_MODE": "translate_apply_mode",
        "KEY_TRANSLATE_LIVE_COMMIT": "translate_live_commit", "KEY_THEME": "writer_theme", "KEY_GRAMMAR_MODE": "grammar_mode",
        "KEY_GRAMMAR_FIX_CAPITALIZE_I": "grammar_fix_capitalize_i", "KEY_GRAMMAR_FIX_SENTENCE_CAPS": "grammar_fix_sentence_caps",
        "KEY_GRAMMAR_FIX_REPEATED_WORDS": "grammar_fix_repeated_words", "KEY_GRAMMAR_REMOTE_URL": "grammar_remote_url",
        "KEY_GRAMMAR_PT_VARIANT": "grammar_pt_variant", "KEY_GRAMMAR_NGRAM_URL": "grammar_ngram_url",
        "KEY_SEEDED": "seeded_from_serving_app",
    },
    "permissions": [
        "android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_NETWORK_STATE", "com.diegonmarcos.cloudkeyboardlibs.BIND_ENGINE",
    ],
    "tool_activities": ["TextEnhanceActivity", "TranslationActivity", "GrammarCheckActivity", "AiRoutingActivity", "RoutesActivity"],
    "providers": ["cloud", "openrouter"],
    "styles": ["grammar", "clarity", "polish"],
    "routes_keys": ["openrouter", "speech", "translation", "voice_engine", "debug_api", "ocr"],
    "debug_ops": ["listen/status", "translate", "route"],
    "debug_group": "writer",
}

SURFACE = "ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter/WriterSurface.kt"
PREFS = "ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter/WriterPrefs.kt"
CONFIG = "ab_cloud-libs-shared/libs/writer-ui/writer-config.json"
NAV = "ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/settings/ConfigsOrder.kt"
MANIFEST = "ac_cloud-keyboard/app/src/main/AndroidManifest.xml"
DEBUG = "ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/WriterDebugApi.kt"
OCRPATHS = "ac_cloud-keyboard/app/src/main/res/xml/ocr_paths.xml"
FLEETCFG = "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
TEXTTOOLS = "ab_cloud-libs-shared/libs/text-tools/src/main/java/com/diegonmarcos/superapp/texttools/TextTools.kt"
RETIRED = "ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-retired-apps.json"
SOURCES = [SURFACE, PREFS, CONFIG, NAV, MANIFEST, DEBUG, OCRPATHS, FLEETCFG, TEXTTOOLS, RETIRED]


def run(root, label):
    bad = []
    ok = []

    def need(tag, msg, cond):
        (ok if cond else bad).append("%s %s" % (tag, msg))

    def rd(rel):
        try:
            return open(os.path.join(root, rel), encoding="utf-8").read()
        except OSError:
            return None

    def js(rel):
        t = rd(rel)
        try:
            return json.loads(t) if t is not None else None
        except ValueError:
            return None

    # ── P1 pages ────────────────────────────────────────────────────────────
    cfg = js(CONFIG) or {}
    ui = cfg.get("ui") or {}
    need("P1", "writer-config.json ui.bottom_nav is the old bar %s" % BASELINE["bottom_nav"], ui.get("bottom_nav") == BASELINE["bottom_nav"])
    need("P1", "ui.default_section is %s" % BASELINE["default_section"], ui.get("default_section") == BASELINE["default_section"])
    secs = {s.get("id"): s for s in ui.get("sections") or []}
    need("P1", "ui.sections has every old section %s" % BASELINE["sections"], all(s in secs for s in BASELINE["sections"]))
    need("P1", "Tools pages %s" % BASELINE["tools_pages"], [p.get("id") for p in (secs.get("tools") or {}).get("pages") or []] == BASELINE["tools_pages"])
    need("P1", "Configs pages %s" % BASELINE["settings_pages"], [p.get("id") for p in (secs.get("settings") or {}).get("pages") or []] == BASELINE["settings_pages"])
    surface = rd(SURFACE) or ""
    for pid in BASELINE["bottom_nav"] + ["tools", "settings", "dictation", "writer"]:
        need("P1", "WriterSurface knows page id %r" % pid, ('"%s"' % pid in surface) or re.search(r'PAGE_\w+\s*=\s*WriterTabs\.%s\b' % pid.upper(), surface) is not None)
    for tp in BASELINE["tools_pages"]:
        need("P1", "WriterSurface has the Tools page tools.%s" % tp, '"tools.%s"' % tp in surface)
    for cp in BASELINE["settings_pages"]:
        need("P1", "WriterSurface draws the Configs page %r" % cp, ('"%s"' % cp in surface) or ('"%s" ->' % cp in surface))
    nav = rd(NAV) or ""
    for pid, route in BASELINE["keyboard_routes"].items():
        need("P1", "the keyboard Writer tab reaches %r (hostRoute(\"%s\"))" % (pid, route), 'hostRoute("%s")' % route in nav)
    need("P1", "WriterSurface still draws Home (DocsScreen) and the editor", "DocsScreen()" in surface and "EditorScreen()" in surface)

    # ── P2 settings ─────────────────────────────────────────────────────────
    prefs = rd(PREFS) or ""
    m = re.search(r'private const val FILE\s*=\s*"([^"]+)"', prefs)
    need("P2", "the prefs file is still %r" % BASELINE["prefs_file"], bool(m) and m.group(1) == BASELINE["prefs_file"])
    for const, key in BASELINE["pref_keys"].items():
        mm = re.search(r'const val %s\s*=\s*"([^"]*)"' % const, prefs)
        need("P2", "preference %s = %r" % (const, key), bool(mm) and mm.group(1) == key)

    # ── P3 entry points ─────────────────────────────────────────────────────
    mf = rd(MANIFEST) or ""
    for perm in BASELINE["permissions"]:
        need("P3", "manifest uses-permission %s" % perm, 'android:name="%s"' % perm in mf)
    for a in BASELINE["tool_activities"]:
        need("P3", "manifest declares com.diegonmarcos.cloudwriter.%s" % a, 'android:name="com.diegonmarcos.cloudwriter.%s"' % a in mf)
    need("P3", "manifest declares ListenService (microphone foreground service)",
         re.search(r'com\.diegonmarcos\.cloudwriter\.ListenService"[^>]*foregroundServiceType="microphone"', mf, re.S) is not None)
    act = re.search(r'<activity android:name="\.WriterShareActivity".*?</activity>', mf, re.S)
    act = act.group(0) if act else ""
    need("P3", "WriterShareActivity is exported", 'android:exported="true"' in act)
    need("P3", "share text/plain into the Writer", re.search(r'android.intent.action.SEND".*?text/plain', act, re.S) is not None)
    need("P3", "share image/* into the Writer (OCR)", re.search(r'android.intent.action.SEND".*?image/\*', act, re.S) is not None)
    need("P3", "PROCESS_TEXT (text-selection menu) opens the Writer", re.search(r'android.intent.action.PROCESS_TEXT".*?text/plain', act, re.S) is not None)
    need("P3", "OCR FileProvider authority ${applicationId}.ocr with its paths file",
         '${applicationId}.ocr' in mf and "@xml/ocr_paths" in mf and '<cache-path name="ocr" path="ocr/"' in (rd(OCRPATHS) or ""))
    need("P3", "debug-API provider is declared", ".WriterDebugApiProvider" in mf and "writerdebugapi" in mf)
    dbg = rd(DEBUG) or ""
    for op in BASELINE["debug_ops"]:
        need("P3", "debug API op %s" % op, 'AppDebugServer.Op("%s"' % op in dbg)
    need("P3", "debug API group literal %r equals writer_routes.debug_api.group" % BASELINE["debug_group"],
         'AppDebugServer.route(\n            "%s"' % BASELINE["debug_group"] in dbg and (cfg.get("writer_routes") or {}).get("debug_api", {}).get("group") == BASELINE["debug_group"])

    # ── P4 registry ─────────────────────────────────────────────────────────
    wa = cfg.get("writer_ai") or {}
    need("P4", "writer_ai providers %s" % BASELINE["providers"], all(p in (wa.get("providers") or {}) for p in BASELINE["providers"]))
    need("P4", "writer_ai styles %s" % BASELINE["styles"], all(s in (wa.get("styles") or {}) for s in BASELINE["styles"]))
    need("P4", "writer_ai has tones, lengths, languages, summaries, tool_models",
         all(wa.get(k) for k in ("tones", "lengths", "languages", "summaries", "tool_models")))
    wr = cfg.get("writer_routes") or {}
    need("P4", "writer_routes has %s" % BASELINE["routes_keys"], all(k in wr for k in BASELINE["routes_keys"]))
    need("P4", "update block (Store tap target)", bool((cfg.get("update") or {}).get("store_package")))
    need("P4", "display_name", bool(cfg.get("display_name")))

    # ── P5 data ─────────────────────────────────────────────────────────────
    fc = js(FLEETCFG) or {}
    kb = (fc.get("apps") or {}).get("keyboard") or {}
    need("P5", "the keyboard links lib-writer-ui and lib-writer-core", "lib-writer-ui" in (kb.get("libs") or []) and "lib-writer-core" in (kb.get("libs") or []))
    need("P5", "stores.text_tools is used_by lib-writer-ui", "lib-writer-ui" in ((fc.get("stores") or {}).get("text_tools") or {}).get("used_by", []))
    need("P5", "the Writer's documents are declared as a keyboard item", any(i.get("id") == "writer-documents" for i in kb.get("items") or []))
    need("P5", "the old `writer` app entry is gone", "writer" not in (fc.get("apps") or {}))

    # ── P6 retired ──────────────────────────────────────────────────────────
    need("P6", "ac_cloud-writer directory is gone", not os.path.exists(os.path.join(root, "ac_cloud-writer")))
    need("P6", "no ship-cloud-writer workflow", not glob.glob(os.path.join(root, ".github/workflows/ship-cloud-writer*")) and not glob.glob(os.path.join(root, "1_cicd/*/cicd/ship-cloud-writer*")))
    tt = rd(TEXTTOOLS) or ""
    need("P6", "TextTools.SERVICE_PACKAGES no longer names cloudwriter", 'listOf(' in tt and '"com.diegonmarcos.cloudwriter"' not in tt)
    offered = []
    for rel in ["2_configs/apk-manifest.json", "aa_cloud-superapp/data/constellation-fleet.json", "aa_cloud-superapp/data/lib-consumers.json",
                "aa_cloud-superapp/build.json", "data/fleet/app-perms.json", "data/fleet/fleet-perms.json",
                "data/fleet/launcher-folder-assignments/cloud.json", "data/fleet/launcher-folder-assignments/phone.json",
                "1_cicd/src/data/nav-walk-apps.json", "1_cicd/src/data/lib-primary-consumers.json", "1_cicd/src/data/debug-ports.json"]:
        t = rd(rel)
        if t is None:
            offered.append(rel + " (missing)")
        elif re.search(r'Cloud-Writer|ac_cloud-writer|"com\.diegonmarcos\.cloudwriter"|extapp:cloud-writer|"id":\s*"writer"\s*,\s*"label":\s*"cloud-writer"', t):
            offered.append(rel)
    need("P6", "no registration or asset offer remains (%s)" % ", ".join(offered), not offered)
    ret = js(RETIRED) or {}
    need("P6", "the Store lists the installed copy as retired (appstore-retired-apps.json)",
         any(a.get("package") == "com.diegonmarcos.cloudwriter" for a in ret.get("apps") or []))
    return ok, bad


def main(root):
    ok, bad = run(root, "real tree")
    for l in ok:
        print("ok     " + l)
    for l in bad:
        print("FAIL   " + l)
    failures = len(bad)

    # ── P7 not vacuous ───────────────────────────────────────────────────────
    def mutated(name, rel, edit):
        tmp = tempfile.mkdtemp()
        try:
            for r in SOURCES + ["2_configs/apk-manifest.json", "aa_cloud-superapp/data/constellation-fleet.json", "aa_cloud-superapp/data/lib-consumers.json",
                                "aa_cloud-superapp/build.json", "data/fleet/app-perms.json", "data/fleet/fleet-perms.json",
                                "data/fleet/launcher-folder-assignments/cloud.json", "data/fleet/launcher-folder-assignments/phone.json",
                                "1_cicd/src/data/nav-walk-apps.json", "1_cicd/src/data/lib-primary-consumers.json", "1_cicd/src/data/debug-ports.json"]:
                src = os.path.join(root, r)
                if os.path.isfile(src):
                    os.makedirs(os.path.dirname(os.path.join(tmp, r)), exist_ok=True)
                    shutil.copy(src, os.path.join(tmp, r))
            p = os.path.join(tmp, rel)
            s = open(p, encoding="utf-8").read()
            t = edit(s)
            if t == s:
                return "the mutation %r did not land (fixture moved)" % name
            open(p, "w", encoding="utf-8").write(t)
            _, b = run(tmp, name)
            return None if b else "the mutation %r stayed green" % name
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    muts = [
        ("PROCESS_TEXT filter removed", MANIFEST, lambda s: s.replace("android.intent.action.PROCESS_TEXT", "android.intent.action.NOPE")),
        ("image share removed", MANIFEST, lambda s: s.replace("image/*", "video/*")),
        ("a Tools activity removed", MANIFEST, lambda s: s.replace('com.diegonmarcos.cloudwriter.RoutesActivity', 'com.diegonmarcos.cloudwriter.Gone')),
        ("a preference key renamed", PREFS, lambda s: s.replace('"grammar_ngram_url"', '"grammar_ngram_url2"')),
        ("a Tools page route dropped", NAV, lambda s: s.replace('hostRoute("tools.routes")', 'hostRoute("tools.gone")')),
        ("the OCR Configs page dropped", CONFIG, lambda s: s.replace('"id": "ocr"', '"id": "ocr2"')),
        ("the documents item undeclared", FLEETCFG, lambda s: s.replace('"id": "writer-documents"', '"id": "writer-docs-x"')),
        ("the retired Store entry dropped", RETIRED, lambda s: s.replace("com.diegonmarcos.cloudwriter", "com.example.other")),
        ("cloudwriter back in SERVICE_PACKAGES", TEXTTOOLS, lambda s: s.replace('listOf(\n        "com.diegonmarcos.cloudkeyboard"', 'listOf(\n        "com.diegonmarcos.cloudwriter",\n        "com.diegonmarcos.cloudkeyboard"')),
    ]
    for name, rel, edit in muts:
        msg = mutated(name, rel, edit)
        if msg:
            print("FAIL   P7 " + msg)
            failures += 1
        else:
            print("ok     P7 %s goes red" % name)
    print("RESULT: %d failure(s)" % failures)
    return 1 if failures else 0


sys.exit(main(sys.argv[1]))
PY
