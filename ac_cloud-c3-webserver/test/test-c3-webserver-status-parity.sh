#!/bin/sh
# test-c3-webserver-status-parity.sh -- #1775 REORGANISE ONLY (rule 16): the Status (Home) page and the Pages rows were regrouped, nothing was removed.
#   S1  FIELD PARITY: every tag / action label the previous HomePage.kt and PagesPage.kt (origin/main 7f217f996) drew is still a literal in the current
#       Kotlin (frozen lists below; the lists only grow)
#   S2  Status by category: StatusPage.kt has the summary, Mode (View only / Edit), the Keep-alive master, the Server / Pages / API / SSHD / ADB Shell
#       bands and the Endpoints list (device, pages, SSHD, MCP, ADB); HomePage.kt draws them in that order
#   S3  Keep alive defaults to ON: build.json::power.default, PowerState.kt
#   S4  Pages rows are one expandable row with the three chips (page, app, serving), serving details, Open page and Open app/folder; the three
#       chips come from REAL probes in roots.rs (a connect probe), not from the status word alone
#   S5  the front-html root is named _front-html___ (the #1750 rename), at the real clone path git/front-html, with a clone command to that path
#   Each check is proven red by a mutation.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
guard() {
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
K = os.path.join(app, "src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver")
def rd(p):
    return open(p, encoding="utf-8").read()
home, status, pages = rd(K + "/ui/HomePage.kt"), rd(K + "/ui/StatusPage.kt"), rd(K + "/ui/PagesPage.kt")
roots, bj = rd(app + "/server/src/roots.rs"), json.load(open(app + "/build.json"))
bad = []
FROZEN_HOME = ["Copy All Infos", "Follow Keep alive", "Open the API topology (MCP \u2192 API \u2192 IPC)", "Refresh", "Turn OFF", "Turn ON", "Turn the Server OFF?", "c3_copy_about", "c3_home", "c3_home_device", "c3_home_goto_api", "c3_home_goto_device", "c3_home_goto_pages", "c3_home_goto_sshd", "c3_home_pages", "c3_home_ports", "c3_home_refresh", "c3_home_sshd", "c3_home_status", "c3_home_topology", "c3_power", "c3_power_keep_alive", "c3_power_server", "c3_power_ssh_door", "c3_power_ssh_follow"]
FROZEN_PAGES = ["Clone it on the phone (Cloud Termux), then reopen this tab:", "Copy command", "Copy path", "Declare", "Open Embedded", "Open Folder (Drive)", "Open in Browser", "Opening in cloud-browser", "Rescan", "Rescanned", "Stop", "c3_browser_", "c3_clone_", "c3_copy_clone", "c3_explorer_", "c3_form", "c3_form_", "c3_form_opt_", "c3_form_submit", "c3_pages_", "c3_pages_refresh", "c3_rbar", "c3_rescan", "c3_rloc", "c3_root_", "c3_root_browser", "c3_root_close", "c3_root_copy_path", "c3_root_embed", "c3_root_embedded", "c3_root_exit", "c3_root_ext", "c3_root_folder", "c3_root_open_", "c3_root_perms", "c3_root_reload", "c3_root_start_app", "c3_root_view", "c3_roots", "c3_served", "c3_served_", "c3_served_config", "c3_served_link", "c3_served_link_embedded", "c3_served_remove", "c3_served_source", "c3_served_start_app", "c3_served_toggle", "c3_sub_act", "c3_sub_group", "c3_sub_link", "c3_tree_dir", "c3_tree_file", "c3_undeclared", "c3_undeclared_declare", "c3_undeclared_stop", "c3_xframework", "c3_xprev", "c3_xtree"]
for lit in FROZEN_HOME:
    if '"' + lit not in home + status: bad.append("S1 Home lost %r" % lit)
for lit in FROZEN_PAGES:
    if '"' + lit not in pages + rd(K + "/ui/Links.kt"): bad.append("S1 Pages lost %r" % lit)
for need in ["c3_status_summary", "c3_mode_edit", "c3_mode_view", "View only", "c3_master_keep_alive", "c3_sec_", "c3_status_pages", "c3_status_api", "c3_status_adb", "c3_status_endpoints", '"MCP"', '"SSHD"', '"ADB SHELL"', "mcpTools"]:
    if need not in status: bad.append("S2 StatusPage lacks %s" % need)
order = [home.find('SectionBand("%s"' % b) for b in ("server", "pages", "api", "sshd", "adb", "endpoints")]
if -1 in order or order != sorted(order): bad.append("S2 HomePage bands missing or out of order: %r" % order)
if bj["power"]["default"]["keep_alive"] is not True: bad.append("S3 build.json keep_alive default is not ON")
if 'flag(ctx, "keep_alive", true)' not in rd(K + "/PowerState.kt"): bad.append("S3 PowerState default is not ON")
for need in ["c3_root_toggle_", "c3_root_chips_", '"page "', '"app "', '"serving "', "c3_root_serving_", "Open page", "Open app/folder", "View only"]:
    if need not in pages: bad.append("S4 PagesPage lacks %s" % need)
for need in ['"page_on"', '"app_on"', '"serving"', "serving::is_up"]:
    if need not in roots: bad.append("S4 roots.rs lacks %s" % need)
pg = {x["id"]: x for x in bj["roots"]["pages"]}
fh = pg.get("front-html", {})
if fh.get("label") != "_front-html___" or fh.get("repo") != "git/front-html" or not fh.get("clone", "").endswith("_front-html___ ~/git/front-html"):
    bad.append("S5 front-html root is %r" % fh)
d = [x for x in bj["repos"]["direct"]["repos"] if "front-html" in x["label"]]
if len(d) != 1 or d[0] != {"label": "_front-html___", "source": "termux", "repo": "git/front-html"}: bad.append("S5 repos.direct front-html is %r" % d)
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
}
if [ "${1:-}" = check ]; then guard "$APP"; exit $?; fi
guard "$APP" || { echo "FAIL (real tree)"; exit 1; }
echo "PASS real tree"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/a"; mkdir -p "$T/a"; cp -R "$APP/build.json" "$APP/server" "$APP/src-tauri" "$T/a/" 2>/dev/null; ( cd "$T/a" && sed -i "$2" "$3" ); if guard "$T/a" >/dev/null 2>&1; then echo "FAIL mutation not caught: $1"; exit 1; fi; echo "  caught: $1"; }
K=src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver
mut "Home loses a frozen tag" 's/c3_home_pages/c3_gone/' $K/ui/HomePage.kt
mut "Mode toggle removed" 's/c3_mode_view/c3_x/' $K/ui/StatusPage.kt
mut "keep alive default OFF" 's/"keep_alive": true/"keep_alive": false/' build.json
mut "serving probe removed" 's/serving::is_up/serving::gone/' server/src/roots.rs
mut "front-html path back" 's#git/front-html#git-repos-master/front-html#' build.json
echo "all green"
