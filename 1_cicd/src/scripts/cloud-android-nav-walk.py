#!/usr/bin/env python3
"""cloud-android-nav-walk: the shared nav pattern, enforced at RUNTIME on a real emulator.

The static guards (ChromeScrollGuardTest, nav-shape-guard) read source; this reads the screen. For ONE installed app it
walks EVERY section and page its build.json::ui declares (ui.bottom_nav ids, ui.sections[].pages, nested pages too) and,
for each, checks what the owner keeps finding by hand:

  P1 no crash: the process is alive, the app owns the foreground and logcat holds no FATAL EXCEPTION for it.
  P2 the first row's title dropdown (the breadcrumb) opens, lists the page, and closes again.
  P3 drag up on the content and HOLD the finger down: if the page scrolled the chrome MUST be HIDDEN (first row, second
     row and bottom bar all gone). SCROLL-HIDE (Configs > UI, on by default): lifting the finger changes nothing, every
     bar STAYS gone until an upward scroll of a NEW gesture: reversing the SAME held touch must leave them away. Swipe down: DEFAULT, all of it is back. A page that did not scroll (nothing moved,
     nothing hidden) is reported `fixed` and only has to leave the chrome intact: a page that scrolls but leaves the chrome
     showing FAILS. The walk pins the nav mode to Classic (the global setting cloud_nav_ui_mode, read by the lib's NavUiPrefs):
     it drives the pages through the Classic title dropdown, which Clean mode (Cloud Notes' default) replaces by the drawer.
  P5 a section declared vertical_side_menu shows the side menu and its toggle flips expanded <-> rail.
  P4 the pick landed: after choosing the page in the dropdown the title path ends in its label.

  SCENES (#1369, nav-walk-apps.json `scenes`): after the walk, an app may declare named scenes (a fresh launch, then steps:
     tap a resource id, tap a text or content description, type, a key, an edge swipe, a wait; `scene_setup` pushes repo
     files and runs device commands once before), photographed under each theme of
     `scene_themes` (system night mode) and the nav mode `scene_nav_mode`. They are evidence (shots/scene_<theme>_<name>.png),
     reported PASS or, when a step found nothing, SKIP with the step named; they never change a page's verdict.

Nothing here is relaxed to get green: a failing page is reported with its reason, a screenshot and the UI dump.

It drives the app through UiAutomator (`uiautomator dump`, `input`), so it needs no instrumentation APK and no Gradle. The
bars expose their test tags as resource ids (libs:ui-nav-bars sets testTagsAsResourceId on the scaffold).

  cloud-android-nav-walk.py --app ac_cloud-calc --build-json ac_cloud-calc/build.json --pkg com.x --out out/
Exit 0 when every page passed, 1 when any failed, 2 when the app could not be driven at all (counted as failed pages).
"""
import argparse
import shlex
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# The shared bars' tags (libs:bottomnav TopBarTags / BottomNavTags, libs:ui-nav-bars TopFirstRowTags).
TITLE, CONFIGS, MORE = "topbar_title", "topbar_configs", "navtop1_more"
CRUMB0 = "navtop1_crumb_0"
ENTRY = "navtop1_entry_"
ITEM, LABEL = "bottomnav_item_", "bottomnav_label_"
ISLAND = "bottomnav_island"
TAB = "pagetabs_tab_"
STRIP = "pagetabs_strip"
# Configs > General / Configs > About (libs:ui-nav-bars fleetconfigs, rule 1_cicd/src/data/configs-pages.json)
DROPDOWN = "navtop1_dropdown"
CFG_ENTRY = {"general": "navtop1_configs_general", "about": "navtop1_configs_about"}
FC_PAGE = {"general": "fleetcfg_general", "about": "fleetcfg_about"}
FC_ANCHOR, FC_SECTION, FC_ABOUT, FC_BACK = "fleetcfg_anchor_", "fleetcfg_section_", "fleetcfg_about_", "fleetcfg_back"
FC_ABOUT_FIELDS = ["header", "name", "tagline", "version", "what", "features", "screens", "guide", "privacy", "perms", "backends", "related",
                   "build", "repo", "changelog", "debug", "store", "superapp"]


def adb(*a, timeout=90):
    return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout)


def shell(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout).stdout


PAGE_BUDGET = 240  # seconds per page in a section's budget
SECTION_BUDGET = 900  # seconds one section may take; a wedged emulator must fail the section, not hold the job to its 120 minute limit


class SectionTimeout(Exception):
    pass


def emulator_alive():
    """True while adb answers and system_server runs (the walk once lost its launcher and could open nothing after)."""
    try:
        if adb("get-state", timeout=20).stdout.strip() != "device":
            return False
        return shell("echo ok", timeout=20).strip() == "ok" and bool(shell("pidof system_server", timeout=20).strip())
    except Exception:
        return False


class Node:
    __slots__ = ("rid", "text", "desc", "l", "t", "r", "b", "scrollable", "clickable", "pkg", "cls")

    def __init__(self, e):
        a = e.attrib
        self.rid = a.get("resource-id", "").split("/")[-1]
        self.text, self.desc = a.get("text", ""), a.get("content-desc", "")
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", a.get("bounds", ""))
        self.l, self.t, self.r, self.b = (int(x) for x in m.groups()) if m else (0, 0, 0, 0)
        self.scrollable, self.clickable = a.get("scrollable") == "true", a.get("clickable") == "true"
        self.pkg, self.cls = a.get("package", ""), a.get("class", "")

    @property
    def cx(self): return (self.l + self.r) // 2

    @property
    def cy(self): return (self.t + self.b) // 2

    @property
    def h(self): return self.b - self.t


def dump(tries=8, _depth=0):
    """The visible UI as a list of Node (the active window: the dropdown's popup while it is open), or None."""
    for _ in range(tries):
        out = shell("uiautomator dump /sdcard/nw.xml >/dev/null 2>&1; cat /sdcard/nw.xml")
        k = out.find("<?xml")
        if k >= 0 and "<hierarchy" in out:
            try:
                nodes = [Node(e) for e in ET.fromstring(out[k:].strip()).iter("node")]
            except ET.ParseError:
                nodes = None
            if nodes is not None:
                if _depth < 4 and dismiss_emulator_prompt(nodes):
                    return dump(tries, _depth + 1)
                return nodes
        time.sleep(1.2)
    return None


ANRS = [0]


def anr_evidence(pkg=""):
    """Say WHY a "not responding" dialog came: the system's own ANR line, its CPU load (an overloaded emulator shows a load far above its
    cores) and the app's main-thread frames, so real main-thread work can be told from a slow emulator."""
    out = shell("logcat -d -b main,system,events -v brief 2>/dev/null | grep -E 'ANR in|Reason:|Load:|am_anr' | tail -6", timeout=60).strip()
    traces = shell("cat /data/anr/anr_* 2>/dev/null | grep -A12 '\"main\" prio' | head -16", timeout=60).strip()
    cores = shell("nproc").strip()
    print("  ANR evidence (emulator cores %s):\n    %s\n    main thread: %s" % (cores, out.replace("\n", "\n    ") or "none in logcat", traces.replace("\n", "\n    ") or "no /data/anr trace"), flush=True)


def dismiss_emulator_prompt(nodes):
    """The emulator's own "Allow wireless debugging on this network?" dialog covers any app that starts after the network comes up."""
    wait = find(nodes, "aerr_wait")
    if wait is not None and ANRS[0] < 40:  # an emulator under load reports "not responding": wait it out, and count it in the report
        ANRS[0] += 1
        anr_evidence()
        tap(wait)
        time.sleep(3.0)
        return True
    if nodes and "packageinstaller" in nodes[0].pkg:  # an app's own self-update asking for "install unknown apps" (Cloud Mail's test build): decline it wherever it lands
        cancel = find(nodes, "button2")
        if cancel is not None:
            tap(cancel)
            time.sleep(1.0)
            return True
    title = find(nodes, "alertTitle")
    if title is not None and "wireless debugging" in title.text.lower():
        always, allow = find(nodes, "alwaysUse"), find(nodes, "button1")
        if always is not None:
            tap(always)
            time.sleep(0.5)
        if allow is not None:
            tap(allow)
            time.sleep(1.0)
            return True
    return False


def find(nodes, rid):
    return next((n for n in nodes if n.rid == rid), None)


def findall(nodes, prefix):
    return [n for n in nodes if n.rid.startswith(prefix)]


def texts_in(nodes, box):
    return " ".join(n.text for n in nodes if n.text and n.l >= box.l and n.r <= box.r and n.t >= box.t and n.b <= box.b) + " " + box.text + " " + box.desc


BLOCKER_TEXTS = ("Get Started", "Skip for now", "Continue with local files", "Skip", "Not now", "Later", "Got it", "No thanks", "Maybe later", "Dismiss", "While using the app", "Allow", "OK", "Continue", "Next", "Finish", "Done")


def clear_blockers(nodes):
    """First-run screens, the default-app role prompt and permission dialogs: dismiss ONE and say so. Used only while the shared bars are missing."""
    wait = find(nodes, "aerr_wait")
    if wait is not None:  # "<app> isn't responding": a slow start on a shared emulator is given the chance to finish; a hang still fails the launch
        tap(wait)
        return True
    if nodes and "packageinstaller" in nodes[0].pkg:  # an app's own self-update asking for "install unknown apps": not the page under test, decline it
        cancel = find(nodes, "button2")
        if cancel is not None:
            tap(cancel)
            return True
    title = find(nodes, "title") or find(nodes, "alertTitle")
    if title is not None and ("default" in title.text.lower() or "caller id" in title.text.lower()):
        cancel = find(nodes, "button2")
        if cancel is not None:
            tap(cancel)
            return True
    dismiss = find(nodes, "signin_fre_dismiss_button")  # a browser first-run screen an app handed off to: leave it, the app is relaunched
    if dismiss is not None:
        tap(dismiss)
        return True
    for want in BLOCKER_TEXTS:
        hits = [n for n in nodes if n.text == want]
        hits.sort(key=lambda n: not (n.clickable or n.cls.endswith("Button")))
        if hits:
            tap(hits[0])
            return True
    return False


def tap(n):
    shell("input tap %d %d" % (n.cx, n.cy))


def swipe(x, y1, y2, ms=350):
    shell("input swipe %d %d %d %d %d" % (x, y1, x, y2, ms))


def motion(action, x, y):
    shell("input motionevent %s %d %d" % (action, x, y))


def settle(s=0.9):
    time.sleep(s)


def screen_size():
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def pages_of(section):
    """Every destination page of a declared section, depth-first: [(id, label, is_launch, ancestor ids)]."""
    out = []

    def walk(pages, up):
        for p in pages or []:
            out.append((p["id"], p.get("label", p["id"]), bool(p.get("action")), up))
            walk(p.get("pages"), up + [p["id"]])

    walk(section.get("pages"), [])
    return out


class Walk:
    def __init__(self, a):
        self.a = a
        self.pkg = a.pkg
        self.out = a.out
        self.shots = set(x for x in (a.shots or "").split(",") if x)
        os.makedirs(os.path.join(self.out, "shots"), exist_ok=True)
        os.makedirs(os.path.join(self.out, "dumps"), exist_ok=True)
        ui = json.load(open(a.build_json, encoding="utf-8"))["ui"]
        self.bottom = list(ui.get("bottom_nav") or [])
        self.sections = {s["id"]: s for s in ui.get("sections", [])}
        self.fill_registry_labels(a.build_json, ui)
        self.w, self.h = screen_size()
        self.results = []
        here = os.path.dirname(os.path.abspath(__file__))
        apps = json.load(open(os.path.join(here, "..", "data", "nav-walk-apps.json"), encoding="utf-8"))["apps"]
        # per-app launch recipe from nav-walk-apps.json: `seed` (a test-only receiver that poses state) and `start_page` (a local page)
        self.cfg = next((x for x in apps if x["app"] == a.app), {})
        self.seeded = False
        self.page_url = None

    # ── driving ──────────────────────────────────────────────────────────────
    def launch(self):
        act = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER %s" % self.pkg).strip().splitlines()
        act = act[-1].strip() if act else ""
        if "/" not in act:
            raise RuntimeError("no launcher activity for %s (%s)" % (self.pkg, act))
        shell("am force-stop %s" % self.pkg)
        # The CI emulator has 2 cores: its LAUNCHER often stops answering ("Input dispatching timed out") and the system's "isn't
        # responding" dialog then covers whichever app is walked. The dialogs are switched off; an ANR of the app under test is
        # still found, in the events log (app_anr), and fails its page with the emulator's load beside it.
        shell("settings put global hide_error_dialogs 1")
        shell("settings put global cloud_nav_ui_mode classic")
        shell("logcat -b all -c")
        self.seed()
        view = self.start_page()
        if view:
            shell("am start -W -n %s -a android.intent.action.VIEW -d %s -t multipart/related" % (act, view), timeout=120)
        else:
            shell("am start -W -n %s" % act, timeout=120)
        deadline = time.time() + 150  # a first-run wizard (Cloud Gallery: five steps) is tapped through
        while time.time() < deadline:
            nodes = dump(2)
            if nodes and any(n.rid.startswith(ITEM) for n in nodes):
                settle(1.5)
                return nodes
            if nodes and clear_blockers(nodes):
                settle(1.5)
                continue
            if nodes and nodes[0].pkg not in (self.pkg, "android", "com.android.systemui") and not self.foreground():
                shell("monkey -p %s -c android.intent.category.LAUNCHER 1" % self.pkg)  # brought back to the front after a hand-off
            settle(2)
        return None

    def seed(self):
        """Pose the state a test-only receiver of the app declares (nav-walk-apps.json `seed`: "<class>:<action>"), once, before the first launch."""
        spec = self.cfg.get("seed")
        if not spec or self.seeded:
            return
        self.seeded = True
        cls, action = spec.split(":", 1)
        print("seed: %s" % shell("am broadcast -n %s/%s -a %s" % (self.pkg, cls, action)).strip().replace("\n", " | "), flush=True)
        settle(2)
        print("seed log: %s" % shell("logcat -d -s NavWalkSeed:I | tail -3").strip().replace("\n", " | "), flush=True)

    def start_page(self):
        """The VIEW uri of the deterministic local test page (nav-walk-apps.json `start_page` = "mhtml"), no network: a saved-page archive,
        long enough to scroll, on the app's own external files dir (readable by it without any permission). None when the app has none."""
        if self.cfg.get("start_page") != "mhtml":
            return None
        if self.page_url:
            return self.page_url
        rows = "".join("<p>Row %d. The quick brown fox jumps over the lazy dog, and the page keeps going so that it scrolls.</p>\n" % i for i in range(1, 161))
        html = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>nav walk page</title></head><body><h1>Nav walk</h1>\n%s</body></html>\n" % rows
        mht = ("MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=\"NAVWALK\"; type=\"text/html\"\r\n\r\n"
               "--NAVWALK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\nContent-Location: https://navwalk.invalid/\r\n\r\n"
               + html + "\r\n--NAVWALK--\r\n")
        local = os.path.join(self.out, "navwalk.mhtml")
        open(local, "w", encoding="utf-8", newline="").write(mht)
        for d in ("/sdcard/Android/data/%s/files" % self.pkg, "/sdcard/Download"):
            shell("mkdir -p %s" % d)
            adb("push", local, d + "/navwalk.mhtml")
            if "No such file" not in shell("ls %s/navwalk.mhtml 2>&1" % d):
                self.page_url = "file://%s/navwalk.mhtml" % d
                break
        print("start page: %s" % self.page_url, flush=True)
        return self.page_url

    def alive(self):
        return bool(shell("pidof %s" % self.pkg).strip())

    def foreground(self):
        # one dumpsys on a loaded emulator can time out or land between two activities of the app (Cloud Nav routes, run
        # 40798012538: the page dump showed the app on top); three reads, the app must be on top in one of them
        for i in range(3):
            if self.pkg in shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'", timeout=30):
                return True
            settle(1.0)
        return False

    def app_anr(self):
        """An ANR of the app under test since the last `logcat -c`, with the emulator's load: real main-thread work shows up as an
        am_anr line for THIS package; an overloaded emulator shows ANRs of the launcher or of system apps and a load above its cores."""
        mine = shell("logcat -d -b events -v brief 2>/dev/null | grep am_anr | grep -F %s" % self.pkg, timeout=60).strip()
        if not mine:
            return None
        load = shell("cat /proc/loadavg").strip()
        return "%s (emulator load %s, %s cores)" % (mine.splitlines()[-1][-160:], load, shell("nproc").strip())

    def crashed(self):
        log = shell("logcat -d -b crash -v brief") + shell("logcat -d -v brief AndroidRuntime:E *:S")
        return ("Process: %s" % self.pkg in log or "FATAL EXCEPTION" in log and self.pkg in log), log

    def ime_up(self):
        return "mInputShown=true" in shell("dumpsys input_method | grep -m1 mInputShown")

    def hide_ime(self):
        if self.ime_up():
            shell("input keyevent KEYCODE_BACK")
            settle(0.8)

    def evidence(self, name):
        safe = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
        adb("shell", "screencap -p /sdcard/nw.png")
        adb("pull", "/sdcard/nw.png", os.path.join(self.out, "shots", safe + ".png"))
        adb("pull", "/sdcard/nw.xml", os.path.join(self.out, "dumps", safe + ".xml"))

    def page_shots(self, name, frames=4):
        """--shots: a passing page of a listed section is photographed too (top, then one frame per swipe down the list),
        so a reviewer sees the page as rendered, not only the failures. The chrome comes back via ensure_chrome."""
        self.evidence("page_%s_0" % name)
        x = self.w // 2
        for i in range(1, frames):
            swipe(x, int(self.h * 0.75), int(self.h * 0.3))
            settle(1.0)
            self.evidence("page_%s_%d" % (name, i))
        for _ in range(frames + 1):
            swipe(x, int(self.h * 0.3), int(self.h * 0.8))
            settle(0.4)

    def open_dropdown(self):
        """Open the first row's title dropdown. A host whose title is an input (Cloud Browser) opens it from the chevron."""
        nodes = dump()
        if nodes is None:
            return None
        if find(nodes, CRUMB0) or findall(nodes, ENTRY):
            return nodes
        opener = find(nodes, MORE) or find(nodes, TITLE)
        if opener is None:
            return None
        # ONE tap (the title sets open = true: the app ignores nothing). The dropdown is a Popup window that a loaded 2-core emulator
        # draws a few seconds late (Cloud Mail's inbox spinner, Cloud Search's jobs page), so the hierarchy is polled for it, not re-tapped.
        tap(opener)
        settle(1.0)
        for _ in range(4):
            nodes = dump()
            if nodes and (find(nodes, CRUMB0) or findall(nodes, ENTRY)):
                return nodes
            settle(1.5)
        if self.ime_up():
            shell("input keyevent KEYCODE_BACK")
            settle(0.6)
        return None

    def close_dropdown(self):
        shell("input keyevent KEYCODE_BACK")
        settle(0.8)
        nodes = dump()
        return nodes is not None and not (find(nodes, CRUMB0) or findall(nodes, ENTRY))

    def pick(self, pid):
        """Tap [pid]'s entry in the open dropdown, scrolling the dropdown; None when it is not listed (the dropdown lists the
        siblings and children of the page shown, so a page elsewhere in the tree is reached through its ancestors)."""
        nodes = dump()
        if nodes is None:
            return False
        for _ in range(6):
            e = find(nodes, ENTRY + pid)
            if e is not None:
                tap(e)
                settle(1.6)
                return True
            entries = findall(nodes, ENTRY)
            if not entries:
                break
            top, bottom = min(n.t for n in entries), max(n.b for n in entries)
            swipe(entries[0].cx, bottom - 20, max(top + 20, bottom - 500), 300)
            settle(0.6)
            again = dump() or nodes
            if sorted(n.rid for n in findall(again, ENTRY)) == sorted(n.rid for n in entries):
                break  # the list did not move: that is all of it
            nodes = again
        return False

    def back_if_dropdown(self):
        """Close the title dropdown with Back only while it is open: a Back on the page itself leaves the app."""
        nodes = dump() or []
        if find(nodes, CRUMB0) or findall(nodes, ENTRY):
            shell("input keyevent KEYCODE_BACK")
            settle(0.8)

    def strip_tab(self, pid):
        """Tap page [pid]'s tab on a second-row strip (scrolling the strip sideways): how a reader reaches a page the dropdown cannot list
        because a container page always shows its first child, so the dropdown's neighbourhood is the child's, never the container's."""
        # A strip that is the first item of a scrolling page (Cloud Search's vertical sub-pages) is scrolled out of the tree by the scroll
        # check of the page before: bring the page back to its top (swipe down) until the tab is in the hierarchy.
        for _ in range(5):
            nodes = dump() or []
            if find(nodes, TAB + pid) is not None:
                break
            swipe(self.w // 2, int(self.h * 0.35), int(self.h * 0.8), 250)
            settle(0.6)
        for direction in (1, -1):
            for _ in range(6):
                nodes = dump() or []
                tab = find(nodes, TAB + pid)
                if tab is not None:
                    tap(tab)
                    settle(1.6)
                    return True
                strips = findall(nodes, STRIP)
                if not strips:
                    break
                st = strips[0]
                half = (st.r - st.l) // 3
                shell("input swipe %d %d %d %d 300" % (st.cx + direction * half, st.cy, st.cx - direction * half, st.cy))
                settle(0.5)
        return False

    def goto(self, pid, label, up=()):
        """Pick page [pid] in the dropdown; when it is not listed from here, climb to the section through the path crumb and descend through its ancestors."""
        if self.open_dropdown() is None:
            return "the title dropdown did not open"
        if self.pick(pid):
            return None
        self.back_if_dropdown()
        if self.strip_tab(pid):
            return None
        if self.open_dropdown() is None:
            return "the title dropdown did not open"
        nodes = dump() or []
        self.evidence("dropdown_%s" % pid.replace("/", "_"))  # what the dropdown listed when the page was not in it
        # pick() left the list scrolled to its end: the path row is at the top of the dropdown, so scroll back there first
        box = find(nodes, "navtop1_dropdown")
        if box is not None:
            for _ in range(4):
                swipe(box.cx, box.t + 60, min(box.t + 60 + 600, box.b - 20), 250)
                settle(0.4)
            nodes = dump() or nodes
        crumb = find(nodes, "navtop1_crumb_1")
        # a deep path overflows the crumb row (it scrolls sideways): bring its start into view before reading the section crumb
        for _ in range(3):
            if crumb is not None:
                break
            c0 = find(nodes, "navtop1_crumb_2") or find(nodes, "navtop1_crumb_3") or find(nodes, "navtop1_crumb_4")
            if c0 is None:
                break
            shell("input swipe %d %d %d %d 300" % (c0.cx, c0.cy, min(c0.cx + 600, self.w - 20), c0.cy))
            settle(0.6)
            nodes = dump() or nodes
            crumb = find(nodes, "navtop1_crumb_1")
        if crumb is None:
            self.back_if_dropdown()
            return "the dropdown lists no entry for page %s" % pid
        tap(crumb)  # the section: its children are listed next
        settle(1.6)
        for step in list(up) + [pid]:
            if self.open_dropdown() is None:
                return "the title dropdown did not open (descending to %s)" % pid
            # the section page behind the crumb can still be drawing (Cloud Search's jobs page on a loaded 2-core emulator): the
            # dropdown then lists the stale neighbourhood for a moment, so the entry is polled for, not read once
            picked = self.pick(step)
            for _ in range(3):
                if picked:
                    break
                self.back_if_dropdown()
                settle(2.5)
                if self.open_dropdown() is None:
                    return "the title dropdown did not open (descending to %s)" % pid
                picked = self.pick(step)
            if not picked:
                self.back_if_dropdown()
                if not self.strip_tab(step):
                    return "the dropdown lists no entry for page %s (via %s)" % (pid, step)
        return None

    # ── chrome state ─────────────────────────────────────────────────────────
    def chrome(self, nodes, selected=None):
        title = find(nodes, TITLE)
        items = [n for n in nodes if n.rid.startswith(ITEM)]
        labels = {n.rid[len(LABEL):] for n in nodes if n.rid.startswith(LABEL)}
        # only the shared second row's tabs: a page's own icon row may reuse the tab tag but sits in no strip or group box
        boxes = [n for n in nodes if n.rid in (STRIP, "navtop2_groups_box")]
        tabs = [n for n in nodes if n.rid.startswith(TAB) and any(b.l <= n.l and b.t <= n.t and n.r <= b.r and n.b <= b.b for b in boxes)]
        island, strip = find(nodes, ISLAND), find(nodes, STRIP)
        return {
            "row": title is not None,
            "items": len(items),
            "labels": labels,
            # the bars' own boxes: a tab or item is reported at Compose's 48 dp touch target, which a shrunk bar does not go below
            "bar_h": island.h if island else max((n.h for n in items), default=0),
            "tab_h": strip.h if strip else 0,
            "tabs": len(tabs),
            "scrollable": [n for n in nodes if n.scrollable and not n.rid.startswith(("pagetabs_", "navtop", "navside", "topbar", "bottomnav_")) and n.h >= 150],
        }

    def content_box(self, nodes):
        """The area between the first row (or the top) and the bottom bar: where the page lives."""
        title = find(nodes, TITLE)
        items = [n for n in nodes if n.rid.startswith(ITEM)]
        top = title.b if title else 0
        bottom = min((n.t for n in items), default=self.h)
        return top, bottom

    def signature(self, nodes, top, bottom):
        """What the page shows, with where: used to tell whether a swipe scrolled it. Chrome nodes are left out."""
        return sorted((n.text, n.l, n.t - top) for n in nodes if (n.text or n.desc) and n.t >= top and n.b <= bottom
                      and not n.rid.startswith(("bottomnav_", "pagetabs_", "navtop", "topbar")))

    # ── one page ─────────────────────────────────────────────────────────────
    def fill_registry_labels(self, build_json, ui):
        """A page whose label is data, not typed in build.json (Cloud Me Lnktree > Proj: ui.repo_tree.areas maps each page to a registry
        area code, the chip label is the area's label in the shipped outline) gets that label, so P4 compares against what is on screen."""
        rt = ui.get("repo_tree") or {}
        if not rt.get("areas"):
            return
        try:
            outline = json.load(open(os.path.join(os.path.dirname(os.path.abspath(build_json)), "data", "repos", rt.get("outline_asset", "repos.outline.json")), encoding="utf-8"))
        except (OSError, ValueError):
            return
        names = {x.get("code"): x.get("label") or x.get("name") for x in outline.get("areas", [])}
        by_page = {x["page"]: names.get(x["code"]) for x in rt["areas"] if x.get("page")}
        def fill(pages):
            for p in pages or []:
                if not p.get("label") and by_page.get(p.get("id")):
                    p["label"] = by_page[p["id"]]
                fill(p.get("pages"))
        for sec in self.sections.values():
            fill(sec.get("pages"))

    def has_pages(self, section):
        return bool((self.sections.get(section) or {}).get("pages"))

    def labels_under(self, section, pid):
        """Labels of the pages below [pid]: a container page shows its first child, so its title path ends in a child's label."""
        def find_page(pages):
            for p in pages or []:
                if p.get("id") == pid:
                    return p
                hit = find_page(p.get("pages"))
                if hit:
                    return hit
            return None
        def labels(p):
            return [c.get("label", "") for c in p.get("pages", [])] + [l for c in p.get("pages", []) for l in labels(c)]
        hit = find_page((self.sections.get(section) or {}).get("pages")) if pid else None
        return [l for l in labels(hit) if l] if hit else []

    def check_page(self, section, label, via, pid=None):
        """Run P1-P4 on the page now shown. Returns (checks dict, [failures])."""
        checks, fails = {}, []
        nodes = dump()
        if nodes is None:
            return checks, ["the UI could not be read (uiautomator never returned a hierarchy)"]
        if not self.foreground():
            fails.append("P1 the app is not in the foreground after opening the page")
        title = find(nodes, TITLE)
        # A page that composes hundreds of rows keeps the 2-core emulator's main thread busy, and the accessibility tree uiautomator
        # reads can be a partial one for a few seconds. The first row is still required: poll for it and record how long it took.
        waited = 0
        while title is None and waited < 6 and self.foreground():
            settle(1.5)
            waited += 1
            nodes = dump() or nodes
            title = find(nodes, TITLE)
        if waited:
            checks["title_wait"] = "%s after %d poll(s)" % ("appeared" if title else "never appeared", waited)
        if title is None:
            fails.append("P4 the shared first row is not on screen (no %s)" % TITLE)
        elif via and not any(l.lower() in texts_in(nodes, title).lower() for l in [label] + self.labels_under(section, pid)):
            fails.append("P4 the title path does not end in '%s' after picking it (shows: %s)" % (label, texts_in(nodes, title).strip()[:80]))
        # P2 the dropdown opens, lists this page's neighbourhood, and closes
        dd = self.open_dropdown()
        if dd is None:
            fails.append("P2 the title dropdown did not open")
        else:
            if self.has_pages(section) and not findall(dd, ENTRY):
                fails.append("P2 the dropdown opened with no page entries")
            # the dropdown grows to its list, capped at the room below its anchor (NavDropdownSizing): never past the screen's bottom
            box = find(dd, DROPDOWN)
            if box is not None:
                checks["dropdown_h"] = box.h
                if box.b > self.h:
                    fails.append("P2 the dropdown runs off the screen (bottom %d > %d)" % (box.b, self.h))
            if section in self.shots:
                self.evidence("dropdown_open_%s_%s" % (section, pid or label))
            if not self.close_dropdown():
                fails.append("P2 the dropdown did not close on Back")
            elif not self.foreground() or not self.alive():
                fails.append("P2 closing the dropdown left the app")
        checks["dropdown"] = "ok" if not any(f.startswith("P2") for f in fails) else "FAIL"
        # P5 a section declared vertical_side_menu draws the menu (expanded or rail) and its toggle flips between the two states
        if (self.sections.get(section) or {}).get("nav_mode") == "vertical_side_menu" and self.has_pages(section):
            side = dump() or nodes
            if find(side, "navside_menu") is None:
                fails.append("P5 the section declares vertical_side_menu but no side menu is on screen")
            else:
                rail0 = bool(findall(side, "navside_rail_"))
                toggle = find(side, "navside_toggle")
                if toggle is None:
                    fails.append("P5 the side menu has no expand/collapse toggle")
                else:
                    tap(toggle)
                    settle(0.9)
                    flipped = dump() or []
                    if bool(findall(flipped, "navside_rail_")) == rail0:
                        fails.append("P5 the side menu's toggle did not switch between the expanded menu and the rail")
                    t2 = find(flipped, "navside_toggle")
                    if t2 is not None:
                        tap(t2)
                        settle(0.6)
            checks["sidemenu"] = "ok" if not any(f.startswith("P5") for f in fails) else "FAIL"
        # P3 scroll the content up: the chrome must collapse when the page scrolled
        nodes = dump() or nodes
        before = self.chrome(nodes)
        top, bottom = self.content_box(nodes)
        if not before["row"] or before["items"] == 0 or bottom - top < 200:
            checks["scroll"] = "n/a"
            return checks, fails
        sig0 = self.signature(nodes, top, bottom)
        x = self.w // 2
        y1, y2 = int(top + (bottom - top) * 0.8), int(top + (bottom - top) * 0.2)
        # HIDDEN: drag up and keep the finger on the screen; everything must be gone while it is down.
        # A heavy embedded site (me/leafy, run 40822135662) can still be loading when the first drag lands and ignores it: the
        # same assertion is kept, the hold is only repeated (up to 3 drags, finger lifted between them) until the page takes it
        held = []
        for attempt in range(3):
            motion("DOWN", x, y1)
            steps = 8
            for i in range(1, steps + 1):
                motion("MOVE", x, y1 + (y2 - y1) * i // steps)
                time.sleep(0.05)
            # the exit animation of the bars may need a moment on the 2-core emulator: poll for the bars to be gone
            gone = False
            for _ in range(6):
                settle(0.8)
                held = dump() or []
                h0 = self.chrome(held)
                if not (h0["row"] or find(held, ISLAND) is not None or h0["items"] or find(held, STRIP) is not None or h0["tabs"]):
                    gone = True
                    break
            if gone or attempt == 2:
                break
            motion("UP", x, y2)
            settle(2.0)
        # PER GESTURE: the touch that hid the bars cannot bring them back. Still holding the same finger, drag back DOWN the screen
        # (an upward scroll); every bar must stay away. Only a NEW gesture (finger lifted, then an upward swipe, below) restores them.
        lift_y = y2
        if gone:
            for i in range(1, 5):
                motion("MOVE", x, y2 + (y1 - y2) * i // 8)
                time.sleep(0.05)
            lift_y = y2 + (y1 - y2) // 2
            settle(1.2)
            rev = dump() or []
            r0 = self.chrome(rev)
            checks["same_gesture"] = "hidden"
            if r0["row"] or find(rev, ISLAND) is not None or r0["items"] or find(rev, STRIP) is not None or r0["tabs"]:
                checks["same_gesture"] = "FAIL"
                fails.append("P3 per-gesture: scrolling back up within the SAME held touch brought the bars back (only a new gesture may)")
        motion("UP", x, lift_y)
        settle(0.5)
        # a NEW downward gesture (lifted first): the bars stay hidden
        swipe(x, y1, y2)
        settle(1.2)
        self.hide_ime()   # a long press in a text page may raise the keyboard, which hides the bar by design
        nodes2 = dump()
        # a page's own scroll reaches the chrome through a bridge on the 2-core emulator's main thread: give it a few seconds to
        # land before reading the final state (the same end state is asserted, only the wait is longer: a frame that still shows the page
        # before the gesture, e.g. the previous page's title under a faded first row, is a loaded emulator that has not drawn yet)
        for _ in range(10):
            if nodes2 is None or not self.chrome(nodes2)["row"]:
                break
            settle(1.2)
            nodes2 = dump() or nodes2
        if nodes2 is None:
            fails.append("P3 the UI could not be read after scrolling")
            return checks, fails
        after = self.chrome(nodes2)
        # a fling over a heavy embedded site (me/leafy, run 40790949210) keeps the page scrolling after the finger lifts, and the bar
        # keeps scrolling after the finger lifts: give it a moment to settle (the end state asserted is: every bar still away)
        for _ in range(2):
            if after["row"] or after["items"]:
                break
            settle(1.2)
            nodes2 = dump() or nodes2
            after = self.chrome(nodes2)
        checks["metrics"] = "bar %d->%d tabs %d->%d labels %d->%d" % (before["bar_h"], after["bar_h"], before["tab_h"], after["tab_h"], len(before["labels"]), len(after["labels"]))
        collapsed = not after["row"]
        sig1 = self.signature(nodes2, *self.content_box(nodes2)) if after["row"] else None
        if collapsed:
            checks["scroll"] = "scrolled"
            h = self.chrome(held)
            if h["row"] or find(held, ISLAND) is not None or h["items"] or find(held, STRIP) is not None or h["tabs"]:
                fails.append("P3 hidden: while the finger is down every bar must be away (first row %s, bottom items %d, second-row tabs %d)" % (h["row"], h["items"], h["tabs"]))
            checks["states"] = "default>hidden>hidden>default"
            if after["items"]:
                fails.append("P3 scroll-hide: after the finger lifted the bottom bar came back (every bar stays away until an upward scroll)")
            if find(nodes2, STRIP) is not None or after["tabs"]:
                fails.append("P3 scroll-hide: after the finger lifted the second row came back (every bar stays away until an upward scroll)")
            # a NEW upward gesture restores: each swipe is its own touch (input swipe lifts the finger between them)
            for _ in range(3):
                swipe(x, y2, y1)
                settle(0.5)
            settle(1.0)
            nodes3 = dump() or []
            back = self.chrome(nodes3)
            # a heavy embedded site (me/leafy, run 40805138692) lands the restore a few frames late; its evidence shot showed the full
            # chrome back: poll for it (same end state asserted: first row, every label, full bar)
            for _ in range(6):
                if back["row"] and len(back["labels"]) >= before["items"]:
                    break
                settle(1.2)
                nodes3 = dump() or nodes3
                back = self.chrome(nodes3)
            if not back["row"]:
                fails.append("P3 scrolled back up: the first row did not return")
            if len(back["labels"]) < before["items"]:
                fails.append("P3 scrolled back up: bottom labels did not return (%d of %d)" % (len(back["labels"]), before["items"]))
            if abs(back["bar_h"] - before["bar_h"]) > 3:
                fails.append("P3 scrolled back up: the bottom bar did not restore (%d vs %d px)" % (back["bar_h"], before["bar_h"]))
        else:
            moved = sig1 != sig0
            if moved and before["scrollable"]:
                checks["scroll"] = "scrolled"
                fails.append("P3 the content scrolled but the chrome did not collapse (first row still on screen)")
            elif moved:
                # something moved but the page offers no scrollable container: a map or canvas panned, not a list scrolled
                checks["scroll"] = "panned"
                if after["items"] != before["items"] or len(after["labels"]) != len(before["labels"]):
                    fails.append("P3 a page that does not scroll changed the bottom bar")
            else:
                checks["scroll"] = "fixed"
                if after["items"] != before["items"] or len(after["labels"]) != len(before["labels"]):
                    fails.append("P3 a page that does not scroll changed the bottom bar")
        return checks, fails

    def ensure_chrome(self, section):
        """A page that left the chrome collapsed must not fail every page after it: bring it back, relaunching if it will not come."""
        nodes = dump() or []
        if find(nodes, TITLE) is not None:
            return
        x = self.w // 2
        for _ in range(3):
            swipe(x, int(self.h * 0.3), int(self.h * 0.8))
            settle(0.8)
        nodes = dump() or []
        if find(nodes, TITLE) is None:
            self.relaunch_to(section)

    def run_page(self, section, pid, label, via_dropdown, up=(), retry=False):
        name = "%s/%s" % (section, pid)
        t0 = time.time()
        shell("logcat -b all -c")
        shell("cmd statusbar collapse")  # a stray swipe from the top edge must not leave the shade over every later page
        self.hide_ime()
        if "packageinstaller" in shell("dumpsys window | grep -m1 mCurrentFocus"):  # the app's own self-update asked for "install unknown apps": decline it
            clear_blockers(dump() or [])
            settle(1.5)
        if not self.foreground():  # an earlier page left the app (a stray Back, a death): come back before walking this one
            self.relaunch_to(section)
        self.ensure_chrome(section)
        err = self.goto(pid, label, up) if via_dropdown else None
        checks, fails = ({}, [err]) if err else self.check_page(section, label, via_dropdown, pid)
        died = not self.alive()
        crashed, log = self.crashed()
        if died and not crashed and not retry and "dying proc com.google.android.gms" in shell("logcat -d -b events -v brief 2>/dev/null | grep am_kill | grep -F %s" % self.pkg, timeout=60):
            # the emulator's Google Play services restarted and the system took this app (a downloadable-fonts client) down with it:
            # not a nav fault. Relaunch and walk the page again once; a second death is reported
            print("note %s: killed with Google Play services on the emulator, walking it again" % name, flush=True)
            self.relaunch_to(section)
            return self.run_page(section, pid, label, via_dropdown, up, retry=True)
        anr = self.app_anr()
        if anr:
            fails.insert(0, "P1 the app did not respond (ANR in %s)" % anr)
        if crashed or died:
            fails.insert(0, "P1 the app %s" % ("crashed (FATAL EXCEPTION in logcat)" if crashed else "died"))
            with open(os.path.join(self.out, "dumps", re.sub(r"\W", "_", name) + ".logcat.txt"), "w") as f:
                f.write(log)
                if died and not crashed:  # a death with no crash: who killed it (the system, a signal, finish())
                    f.write("\n--- process events ---\n" + shell("logcat -d -b all -v brief 2>/dev/null | grep -E 'am_proc_died|am_kill|am_finish|ActivityManager.*(%s|Killing)|lowmemorykiller|libc.*Fatal signal' | tail -40" % re.escape(self.pkg), timeout=60))
        checks["alive"] = "FAIL" if (crashed or died) else "ok"
        if fails:
            self.evidence(name)
        elif section in self.shots:
            self.page_shots(name)
        self.results.append({"section": section, "page": pid, "label": label, "status": "FAIL" if fails else "PASS",
                             "checks": checks, "failures": fails, "seconds": round(time.time() - t0, 1)})
        print("%s %-34s %s" % ("FAIL" if fails else "ok  ", name, "; ".join(fails) or checks), flush=True)
        if crashed or died:
            self.relaunch_to(section)

    def relaunch_to(self, section):
        if self.launch() is not None and section in self.bottom:
            nodes = dump() or []
            n = find(nodes, ITEM + section)
            if n:
                tap(n)
                settle(1.2)

    def find_scrolling(self, rid, frames=8):
        """Swipe the page up until [rid] is on screen (the About cards and General's sections are one long column)."""
        for _ in range(frames):
            nodes = dump() or []
            n = find(nodes, rid)
            if n is not None and n.t < self.h * 0.9:
                return n
            swipe(self.w // 2, int(self.h * 0.75), int(self.h * 0.35))
            settle(0.6)
        return None

    def visit_configs_pages(self):
        """Configs > General and Configs > About, the two pages every app on the shared scaffold gets from libs:ui-nav-bars: opened from the
        last two entries of the title dropdown, checked by tag (General: one anchor and one section per declared id, a tap on an anchor
        brings its section up; About: every field card and both Open buttons) and photographed top to bottom (always, not only with --shots:
        the owner reviews these two pages by screenshot). Back closes the page and the app stays in front."""
        build = json.load(open(self.a.build_json, encoding="utf-8"))
        decl = build.get("configs_pages") or {}
        sids = [x.get("id") for x in ((decl.get("general") or {}).get("sections") or [])]
        if "update" not in sids:  # every app gets Update by default, right after Perms (FleetConfigs.withDefaults)
            sids.insert(sids.index("perms") + 1 if "perms" in sids else 0, "update")
        for page in ("general", "about"):
            t0, fails, checks = time.time(), [], {}
            if self.bottom:
                self.relaunch_to(self.bottom[0])
            nodes = self.open_dropdown()
            entry = None
            if nodes is None:
                fails.append("P4 the title dropdown did not open")
            else:
                for _ in range(6):
                    entry = find(nodes, CFG_ENTRY[page])
                    if entry is not None:
                        break
                    box = find(nodes, DROPDOWN)
                    if box is None:
                        break
                    swipe(box.cx, box.b - 30, box.t + 30, 300)
                    settle(0.6)
                    nodes = dump() or nodes
                if entry is None:
                    fails.append("C1 the dropdown has no %s entry (Configs \u25b8 %s)" % (CFG_ENTRY[page], page.capitalize()))
                    self.back_if_dropdown()
            if entry is not None:
                tap(entry)
                settle(2.0)
                nodes = dump() or []
                if find(nodes, FC_PAGE[page]) is None:
                    fails.append("C2 %s did not open (no %s)" % (page, FC_PAGE[page]))
                else:
                    self.evidence("configs_%s_0" % page)
                    if page == "general":
                        missing = [i for i in sids if find(nodes, FC_ANCHOR + i) is None]
                        checks["anchors"] = "%d/%d" % (len(sids) - len(missing), len(sids))
                        if missing:
                            fails.append("C3 General has no anchor for %s" % ", ".join(missing))
                        for i in sids:
                            # the v2 grid sits at the top of the one General page: back up to it before each tap. A tap on the
                            # title jumps there; swiping over the sections could land on a live control (the UI section's nav
                            # toggles) and close Configs. Swipes are the fallback for a build without the title jump.
                            t = find(dump() or [], "fleetcfg_title")
                            if t is not None:
                                tap(t)
                                settle(0.6)
                            a = None
                            for _ in range(6):
                                a = find(dump() or [], FC_ANCHOR + i)
                                if a is not None:
                                    break
                                swipe(self.w // 2, int(self.h * 0.3), int(self.h * 0.85))
                                settle(0.4)
                            if a is None:
                                fails.append("C4 the %s anchor is not on the grid" % i)
                                continue
                            tap(a)
                            settle(1.2)
                            sec = find(dump() or [], FC_SECTION + i)
                            if sec is None or sec.t > self.h * 0.6:
                                fails.append("C4 the %s anchor did not bring its section up" % i)
                            self.evidence("configs_general_%s" % i)
                            if i == "perms":
                                pn = dump() or []
                                if find(pn, "perms:grant-all") is None and find(pn, "perms:all-granted") is None and find(pn, "perms:section") is None:
                                    fails.append("C7 General's Perms section shows neither Grant all nor all granted")
                            if i == "update":
                                chk = find(dump() or [], "fleetcfg_update_check")
                                if chk is None:
                                    fails.append("C8 General's Update section has no Check for updates")
                                else:
                                    tap(chk)
                                    settle(6.0)
                                    self.evidence("configs_general_update_checked")
                                if find(dump() or [], "fleetcfg_update_open_store") is None:
                                    fails.append("C8 General's Update section has no Update button")
                    else:
                        seen = []
                        for f in FC_ABOUT_FIELDS:
                            if self.find_scrolling(FC_ABOUT + f) is None:
                                fails.append("C5 About has no %s card" % f)
                            else:
                                seen.append(f)
                        for btn in ("fleetcfg_open_store", "fleetcfg_open_superapp"):
                            if self.find_scrolling(btn) is None:
                                fails.append("C5 About has no %s button" % btn)
                        checks["fields"] = "%d/%d" % (len(seen), len(FC_ABOUT_FIELDS))
                        for _ in range(8):
                            swipe(self.w // 2, int(self.h * 0.3), int(self.h * 0.8))
                            settle(0.3)
                        for i in range(0, 10):
                            self.evidence("configs_about_%d" % i)
                            swipe(self.w // 2, int(self.h * 0.8), int(self.h * 0.25))
                            settle(0.8)
                    back = find(dump() or [], FC_BACK)
                    if back is not None:
                        tap(back)
                    else:
                        shell("input keyevent KEYCODE_BACK")
                    settle(1.0)
                    if not self.foreground():
                        fails.append("C6 closing %s left the app" % page)
            if self.crashed()[0]:  # crashed() returns (flag, log): the tuple itself is always truthy
                fails.append("the app crashed on Configs \u25b8 %s" % page.capitalize())
            if fails:
                self.evidence("configs_%s_fail" % page)
            self.results.append({"section": "configs", "page": page, "label": "Configs \u25b8 " + page.capitalize(),
                                 "status": "FAIL" if fails else "PASS", "checks": checks, "failures": fails, "seconds": int(time.time() - t0)})

    def visit_section(self, sid):
        sec = self.sections.get(sid)
        if sec is None:
            self.results.append({"section": sid, "page": "-", "label": sid, "status": "FAIL", "checks": {},
                                 "failures": ["build.json::ui.bottom_nav names a section ui.sections does not declare"], "seconds": 0})
            return
        on_bar = sid in self.bottom
        self.hide_ime()
        nodes = dump() or []
        if not any(n.rid.startswith(ITEM) for n in nodes) and clear_blockers(nodes):
            settle(1.5)
            nodes = dump() or []
        opener = find(nodes, ITEM + sid) if on_bar else find(nodes, CONFIGS)
        if opener is None:
            self.results.append({"section": sid, "page": "-", "label": sec.get("label", sid), "status": "FAIL", "checks": {},
                                 "failures": ["P4 no %s on screen to open the section" % ((ITEM + sid) if on_bar else CONFIGS)], "seconds": 0})
            self.evidence("%s_no_opener" % sid)
            return
        tap(opener)
        settle(1.6)
        pages = [p for p in pages_of(sec)]
        # the section itself (its first page is what it opens on)
        self.run_page(sid, sid, sec.get("label", sid), False)
        for pid, label, launch, up in pages:
            if launch:
                self.results.append({"section": sid, "page": pid, "label": label, "status": "SKIP", "checks": {"note": "launch hand-off to another app, not a page"},
                                     "failures": [], "seconds": 0})
                continue
            if pid == sid:
                continue
            self.run_page(sid, pid, label, True, up)

    def run(self):
        if self.launch() is None:
            self.evidence("launch")
            seen = " ".join(n.text for n in (dump() or []) if n.text)
            if "Add account" in seen:
                # a mail client has no chrome until an account exists, and the CI emulator has no mail server to sign in to: reported as
                # SKIP with the reason, never as a pass (the side menu is held by the Robolectric tests, the account-less screen by none)
                self.results.append({"section": "-", "page": "launch", "label": "launch", "status": "SKIP",
                                     "checks": {"note": "onboarding (Add account): the shared bars exist only once an account is signed in"},
                                     "failures": [], "seconds": 60})
                self.report()
            self.results.append({"section": "-", "page": "launch", "label": "launch", "status": "FAIL", "checks": {},
                                 "failures": ["the shared bottom nav never appeared within 60 s of launch (no %s*)" % ITEM], "seconds": 60})
        else:
            import signal

            def _alarm(_sig, _frm):
                raise SectionTimeout("section exceeded its time budget")

            signal.signal(signal.SIGALRM, _alarm)
            for sid in self.bottom + [s for s in self.sections if s not in self.bottom]:
                if not emulator_alive():
                    self.results.append({"section": sid, "page": "-", "label": sid, "status": "FAIL", "checks": {},
                                         "failures": ["emulator unresponsive (adb or system_server gone): remaining sections not walked"], "seconds": 0})
                    break
                # the budget grows with the section's pages: Cloud Me's "me" holds 10 embedded sites at 1.5 to 3.3 min each on the
                # emulator and overran a flat 900 s while alive (run 40790949210); a wedged emulator is still caught per section
                signal.alarm(max(SECTION_BUDGET, PAGE_BUDGET * (1 + len(pages_of(self.sections.get(sid) or {})))))
                try:
                    self.visit_section(sid)
                except Exception as ex:  # a driver fault must show as a failed page, never as silence
                    self.results.append({"section": sid, "page": "-", "label": sid, "status": "FAIL", "checks": {},
                                         "failures": ["driver error: %r" % ex], "seconds": 0})
                    try:
                        self.evidence("%s_driver_error" % sid)
                    except Exception:
                        pass
                finally:
                    signal.alarm(0)
            if emulator_alive():
                signal.alarm(max(SECTION_BUDGET, PAGE_BUDGET * 4))
                try:
                    self.visit_configs_pages()
                except Exception as ex:  # a driver fault must show as a failed page, never as silence
                    self.results.append({"section": "configs", "page": "-", "label": "Configs", "status": "FAIL", "checks": {},
                                         "failures": ["driver error: %r" % ex], "seconds": 0})
                finally:
                    signal.alarm(0)
            if emulator_alive() and self.cfg.get("scenes"):
                signal.alarm(max(SECTION_BUDGET, 90 * len(self.cfg["scenes"]) * len(self.cfg.get("scene_themes", ["dark", "light"]))))
                try:
                    self.run_scenes()
                except Exception as ex:  # evidence only: a driver fault is reported, never silent
                    self.results.append({"section": "scene", "page": "-", "label": "scenes", "status": "SKIP", "checks": {"note": "scene driver error: %r" % ex},
                                         "failures": [], "seconds": 0})
                finally:
                    signal.alarm(0)
        self.report()

    # ── scenes (#1369): declared screenshots for a reviewer, dark and light ──
    def find_any(self, nodes, want):
        """A node whose resource id, text or content description equals [want] (the WebView exposes aria-labels as descriptions)."""
        for n in nodes or []:
            if want in (n.rid, n.text, n.desc):
                return n
        return None

    def scene_step(self, step):
        kind, arg = step[0], (step[1] if len(step) > 1 else None)
        if kind == "wait":
            settle(float(arg)); return True
        if kind == "type":  # into the focused field
            shell("input text %s" % shlex.quote(str(arg).replace(" ", "%s"))); settle(0.8); return True
        if kind == "push":  # a repo path onto the device (scene_setup): ["push", "<repo-relative src>", "<device dest>"]
            repo = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
            adb("push", os.path.join(repo, arg), step[2]); return True
        if kind == "shell":  # a device shell command (scene_setup); {pkg} is the app's package
            shell(str(arg).replace("{pkg}", self.pkg)); return True
        if kind == "key":
            shell("input keyevent %s" % arg); settle(1.0); return True
        if kind == "edge":  # a swipe in from the left or right edge (Obsidian's drawers)
            y = self.h // 2
            x0, x1 = (5, int(self.w * 0.7)) if arg == "left" else (self.w - 5, int(self.w * 0.3))
            shell("input swipe %d %d %d %d 300" % (x0, y, x1, y)); settle(1.2); return True
        if kind == "tap":  # resource id, text or content description; waits up to 10 s for it
            deadline = time.time() + 10
            while time.time() < deadline:
                n = self.find_any(dump(2), arg)
                if n is not None:
                    tap(n); settle(1.5); return True
                settle(1.0)
            return False
        raise ValueError("unknown scene step %r" % (step,))

    def run_scenes(self):
        scenes = self.cfg.get("scenes") or []
        if not scenes:
            return
        shell("settings put global cloud_nav_ui_mode %s" % self.cfg.get("scene_nav_mode", "classic"))
        for st in self.cfg.get("scene_setup", []):
            self.scene_step(st)
        try:
            for theme in self.cfg.get("scene_themes", ["dark", "light"]):
                shell("cmd uimode night %s" % ("yes" if theme == "dark" else "no"))
                for sc in scenes:
                    name, t0 = "scene_%s_%s" % (theme, sc["name"]), time.time()
                    shell("am force-stop %s" % self.pkg)
                    shell("monkey -p %s -c android.intent.category.LAUNCHER 1" % self.pkg)
                    settle(float(self.cfg.get("scene_boot_s", 8)))
                    missed = next((st for st in sc.get("steps", []) if not self.scene_step(st)), None)
                    settle(1.5)
                    self.evidence(name)
                    self.results.append({"section": "scene", "page": "%s/%s" % (theme, sc["name"]), "label": sc["name"],
                                         "status": "SKIP" if missed else "PASS",
                                         "checks": {"note": ("step found nothing: %r" % (missed,)) if missed else "shots/%s.png" % name},
                                         "failures": [], "seconds": int(time.time() - t0)})
        finally:
            shell("cmd uimode night no")
            shell("settings put global cloud_nav_ui_mode classic")

    def report(self):
        app = self.a.app
        with open(os.path.join(self.out, "report.json"), "w") as f:
            json.dump({"app": app, "pkg": self.pkg, "results": self.results}, f, indent=1)
        n = {k: sum(1 for r in self.results if r["status"] == k) for k in ("PASS", "FAIL", "SKIP")}
        lines = ["### %s (%s): %d passed, %d failed, %d skipped (%d 'not responding' dialogs waited out)" % (app, self.pkg, n["PASS"], n["FAIL"], n["SKIP"], ANRS[0]), "",
                 "| section / page | result | dropdown | scroll | metrics (px) | detail |", "|---|---|---|---|---|---|"]
        for r in self.results:
            c = r["checks"]
            lines.append("| %s/%s | %s | %s | %s | %s | %s |" % (r["section"], r["page"], r["status"], c.get("dropdown", "-"), c.get("scroll", "-"), c.get("metrics", "-"),
                                                          ("; ".join(r["failures"]) or c.get("note", "")).replace("|", "/")))
        open(os.path.join(self.out, "report.md"), "w").write("\n".join(lines) + "\n")
        print("\n".join(lines))
        sys.exit(1 if n["FAIL"] else 0)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--app", required=True)
    p.add_argument("--build-json", required=True)
    p.add_argument("--pkg", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--shots", default="", help="comma list of section ids whose passing pages are photographed too")
    Walk(p.parse_args()).run()
