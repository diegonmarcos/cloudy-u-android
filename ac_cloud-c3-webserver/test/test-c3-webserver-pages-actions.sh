#!/bin/sh
# test-c3-webserver-pages-actions.sh — #1710: Pages and File Explorer keep EVERY action the last good version had, and the File
# Explorer renders through the webserver's own framework.
#
# THE FAILURE THIS EXISTS FOR. The #1682 P1 move to native Compose rebuilt Pages and File Explorer from the web shell and quietly lost
# actions (per-entry Open in Browser | Open Embedded, Open Folder on the Drive, edit config, the embedded choice per served link) and
# replaced the explorer's rendering by a bare text tree beside a 150 dp preview, away from the shared fleet-webserver UI (folder tree,
# Markdown / JSON viewer, Eruda). The last good version is server/ui/shell.html (4cdefa4d5, still in the tree, still served on
# /__app__/). Every action hook it declares is mapped below to the native tag that carries it; a hook the web shell has and this map
# does not know fails (so a new web action cannot be forgotten), and so does a tag Kotlin does not draw.
#
#   A1  every data-hook of the web shell's Pages (renderRootRow, renderPage, renderUndeclared, renderRootBar, tree) is in HOOKS
#   A2  every native tag HOOKS names is a literal in PagesPage.kt / Links.kt (the button exists)
#   A3  the per-entry actions of #1710 exist (Browser, Embedded, Folder on the Drive, Copy path, edit config, link Embedded, the framework
#       and tree views, an out-of-frame tap goes to the link chooser)
#   A4  the File Explorer frame is the framework: the Kotlin explorer loads the root's frame_url (its own origin), and the Rust API emits it
#
#   sh test-c3-webserver-pages-actions.sh          the guard, then every mutation
#   sh test-c3-webserver-pages-actions.sh check    the guard alone
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
KDIR="src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver"

guard() {
  python3 - "$1" "$KDIR" <<'PY'
import re, sys
app, kdir = sys.argv[1], sys.argv[2]
web = open(app + "/server/ui/shell.html").read()
kt = open(app + "/" + kdir + "/ui/PagesPage.kt").read() + open(app + "/" + kdir + "/ui/Links.kt").read()
fr = open(app + "/" + kdir + "/ui/WebFrame.kt").read()
rs = open(app + "/server/src/roots.rs").read()
errors = []

# web hook -> native tag (the last good version's actions, each with the button that now carries it)
HOOKS = {
    "data-root-open": "c3_root_open_",
    "data-start-app": "c3_root_start_app",
    "data-copy-clone": "c3_copy_clone",
    "data-root-close": "c3_root_close",
    "data-root-reload": "c3_root_reload",
    "data-root-ext": "c3_root_ext",
    "data-tree-dir": "c3_tree_dir",
    "data-tree-file": "c3_tree_file",
    "data-toggle": "c3_served_toggle",
    "data-goto": "c3_served_config",
    "data-rescan": "c3_rescan",
    "data-undeclared": "c3_undeclared_declare",
    "data-choice": "c3_link_choice_",
    "data-remember": "c3_link_remember",
}
EXTRA = {   # the pairs of the web hooks whose button is a server-described action or a sibling of one
    "perms": "c3_root_perms", "start (served)": "c3_served_start_app", "source": "c3_served_source", "link": "c3_served_link",
    "remove": "c3_served_remove", "stop undeclared": "c3_undeclared_stop", "form": "c3_form_submit",
    # #1710 per entry: Open in Browser | Open Embedded, Open Folder (Drive + path), the views of the explorer
    "root browser": "c3_root_browser", "root embedded": "c3_root_embedded", "root folder (drive)": "c3_root_folder", "root copy path": "c3_root_copy_path",
    "bar embedded": "c3_root_embed", "explorer view switch": "c3_root_view", "served link embedded": "c3_served_link_embedded", "framework frame": "c3_xframework",
}
# A1: the web shell's hooks are all known
used = set(re.findall(r'\b(data-(?:root-open|start-app|copy-clone|root-close|root-reload|root-ext|tree-dir|tree-file|toggle|goto|rescan|undeclared|choice|remember))\b', web))
for h in HOOKS:
    if h not in used:
        errors.append("A1 the web shell no longer carries %s: the baseline list is stale" % h)
for h in sorted(set(re.findall(r'\b(data-(?:root|tree|start|copy|rescan|undeclared|toggle|goto|choice|remember|adb)[a-z-]*)=?', web))):
    if h in ("data-root", "data-root-ext", "data-copy-about", "data-copy-doors") or h in HOOKS: continue   # about and doors are not Pages
    if h.startswith(("data-root-", "data-tree-", "data-copy-", "data-start-")) and h not in HOOKS:
        errors.append("A1 the web shell has action hook %s that HOOKS does not map: map it to a native tag" % h)
# A2: every tag is drawn
for name, tag in list(HOOKS.items()) + list(EXTRA.items()):
    if '"%s' % tag not in kt:
        errors.append("A2 %s: the native tag %s is not a literal of PagesPage.kt / Links.kt (the button is gone)" % (name, tag))
# A3: the framework path and the in-frame link handling
if "onLink" not in fr or "override fun shouldOverrideUrlLoading" not in fr:
    errors.append("A3 WebFrame no longer hands an out-of-frame tap to the link chooser (onLink / shouldOverrideUrlLoading)")
if "openLink(ctx" not in kt or "onLink = {" not in kt:
    errors.append("A3 the frames of Pages / File Explorer do not pass taps to openLink")
# A4: the explorer renders through the framework
if 'optString("frame_url")' not in kt or "WebFrame(frameUrl" not in kt:
    errors.append("A4 the File Explorer does not load the root's frame_url (the shared server's own UI)")
if '"frame_url"' not in rs or '"drive_url"' not in rs or "fn frame_urls" not in rs:
    errors.append("A4 roots.rs does not emit frame_url / drive_url")
print("\n".join(errors) if errors else "ok")
sys.exit(1 if errors else 0)
PY
}

FAILURES=0
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }
if guard "$APP" >/tmp/pa.$$ 2>&1; then echo "ok   the Pages / File Explorer action parity holds on the real tree"; else cat /tmp/pa.$$; fail "the real tree"; fi
rm -f /tmp/pa.$$
[ "${1:-}" = check ] && { [ "$FAILURES" -eq 0 ] && exit 0 || exit 1; }

ed() { python3 - "$1" "$2" "$3" <<'PY'
import sys; p, a, b = sys.argv[1:4]; s = open(p).read()
if a not in s: sys.exit(1)
open(p, "w").write(s.replace(a, b, 1))
PY
}
mutate() {
  title="$1"; mut="$2"
  c="$(mktemp -d)"
  mkdir -p "$c/server/ui" "$c/server/src" "$c/$KDIR/ui"
  cp "$APP/server/ui/shell.html" "$c/server/ui/"; cp "$APP/server/src/roots.rs" "$c/server/src/"
  cp "$APP/$KDIR/ui/PagesPage.kt" "$APP/$KDIR/ui/Links.kt" "$APP/$KDIR/ui/WebFrame.kt" "$c/$KDIR/ui/"
  if ! "$mut" "$c"; then fail "M: mutation '$title' did not apply"; rm -rf "$c"; return 0; fi
  if guard "$c" >/dev/null 2>&1; then fail "M: mutation '$title' stayed green"; else echo "ok   red: $title"; fi
  rm -rf "$c"
}
K="$KDIR/ui"
m_browser()  { ed "$1/$K/PagesPage.kt" '"c3_root_browser"' '"c3_root_b"'; }
m_embedded() { ed "$1/$K/PagesPage.kt" '"c3_root_embedded"' '"c3_root_e"'; }
m_folder()   { ed "$1/$K/PagesPage.kt" '"c3_root_folder"' '"c3_root_f"'; }
m_config()   { ed "$1/$K/PagesPage.kt" '"c3_served_config"' '"c3_served_c"'; }
m_linkemb()  { ed "$1/$K/PagesPage.kt" '"c3_served_link_embedded"' '"c3_x"'; }
m_start()    { ed "$1/$K/PagesPage.kt" '"c3_root_start_app"' '"c3_y"'; }
m_nolink()   { ed "$1/$K/WebFrame.kt" 'override fun shouldOverrideUrlLoading' 'fun shouldOverrideUrlLoadingX'; }
m_noframe()  { ed "$1/$K/PagesPage.kt" 'WebFrame(frameUrl' 'WebFrame(Api.url("/")'; }
m_norust()   { ed "$1/server/src/roots.rs" '"frame_url": frame,' '"frame_urlx": frame,'; }
m_newhook()  { ed "$1/server/ui/shell.html" 'data-root-reload>' 'data-root-reload data-root-pin>'; }
mutate "Open in Browser dropped from a root"        m_browser
mutate "Open Embedded dropped from a root"          m_embedded
mutate "Open Folder (Drive) dropped"                m_folder
mutate "edit config dropped from a served page"     m_config
mutate "embedded choice dropped from a served link" m_linkemb
mutate "Start app dropped from a root"              m_start
mutate "in-frame links bypass the link chooser"     m_nolink
mutate "the explorer leaves the framework"          m_noframe
mutate "the core stops emitting frame_url"          m_norust
mutate "a new web action hook with no native tag"   m_newhook

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-pages-actions: all green ──"; else echo "── test-c3-webserver-pages-actions: $FAILURES FAILED ──"; exit 1; fi
