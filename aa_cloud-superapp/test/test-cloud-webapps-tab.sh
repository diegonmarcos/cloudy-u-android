#!/usr/bin/env bash
# Cloud ▸ tabs: App | WebApps | Setup (#1930 MERGED: Linktree removed, Configs renamed Setup); WebApps (#1062) is the cloud-webapps index embedded in the shell, its address declared ONCE (webapps_base + webapps_path).
#   T1 the Cloud section lists exactly those four visible pages, in that order
#   T2 WebApps declares webapps_base (https) + webapps_path with embed=1, and no second url or per-app list
#   T3 WebPageFragment appends the CloudSuperApp-Embed/1 user-agent marker, keyed off embed=1 only
#   T4 an embed page is a pane: no floating Back, pull-to-refresh, the offline message, other sites leave via the link rules
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
WP="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/WebPageFragment.kt"
for f in "$APP/build.json" "$WP"; do [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }; done

out="$(python3 - "$APP/build.json" <<'PY'
import json, sys
s = [x for x in json.load(open(sys.argv[1]))["ui"]["sections"] if x["id"] == "cloud"][0]
vis = [p for p in s["pages"] if not p.get("hidden")]
print("ORDER", ",".join(p["id"] for p in vis))
print("LABELS", ",".join(p["label"] for p in vis))
w = [p for p in s["pages"] if p["id"] == "webapps"]
w = w[0] if w else {}
b = w.get("webapps_base", "").rstrip("/")
path = w.get("webapps_path", "")
ok = b.startswith("https://") and "embed=1" in path and not w.get("url") and "webapps" not in w
print("URL", "ok " + b + "/" + path.lstrip("/") if ok else "BAD base=%r path=%r url=%r" % (b, path, w.get("url")))
PY
)"
[ "$(grep '^ORDER' <<<"$out" | cut -d' ' -f2)" = "apps,webapps,configs" ] \
  && ok "T1 Cloud lists apps, webapps, configs (Setup) in that order" || bad "T1 Cloud's tabs are not App | WebApps | Setup ($(grep '^ORDER' <<<"$out"))"
[ "$(grep '^LABELS' <<<"$out" | cut -d' ' -f2)" = "App,WebApps,Setup" ] \
  && ok "T1 and they read App | WebApps | Setup" || bad "T1 the tab labels moved ($(grep '^LABELS' <<<"$out"))"
URL="$(grep '^URL' <<<"$out" | cut -d' ' -f2-)"
case "$URL" in ok*) ok "T2 WebApps: $URL";; *) bad "T2 WebApps declaration is wrong: $URL";; esac
grep -qF 'const val UA_MARKER = " CloudSuperApp-Embed/1"' "$WP" && grep -qF 'if (embed) CloudWeb.appendUserAgent(this, UA_MARKER)' "$WP" \
  && ok "T3 the UA marker is appended on embed pages only" || bad "T3 the user-agent marker is missing or unconditional"
grep -qF 'if (!embed) Icon(painterResource(R.drawable.ic_back)' "$WP" \
  && ok "T4 an embed page has no floating Back" || bad "T4 an embed page still floats a Back control over the site"
grep -qF 'PullDownToRefresh(modifier' "$WP" && grep -qF 'internal fun PullDownToRefresh(' "$WP" && ok "T4 pull-to-refresh" || bad "T4 no pull-to-refresh"
grep -qF 'You look to be offline' "$WP" && ok "T4 an offline page says so" || bad "T4 the error page still blames the mesh for a public site"
grep -qF 'sameSite(url, target.toString())' "$WP" && grep -qF '.launchUri(target.toString())' "$WP" \
  && ok "T4 other sites leave through the app's link rules" || bad "T4 a link to another site loads inside the pane"
# mutation: dropping the marker must turn T3 red
MUT="$(sed 's/CloudWeb.appendUserAgent(this, UA_MARKER)/Unit/' "$WP")"
if grep -qF 'CloudWeb.appendUserAgent(this, UA_MARKER)' <<<"$MUT"; then
  bad "mutation: the UA check cannot see the marker go"; else ok "mutation: removing the marker is visible to T3"; fi
# T5 SuperApp's own Cloud strip is shown on every page incl. WebApps: nothing declares hide_tabs, and the code has no such switch
h="$(python3 - "$APP/build.json" <<'PY'
import json, sys
s = [x for x in json.load(open(sys.argv[1]))["ui"]["sections"] if x["id"] == "cloud"][0]
print(",".join(p["id"] for p in s["pages"] if p.get("hide_tabs")))
PY
)"
[ -z "$h" ] && ok "T5 no Cloud page declares hide_tabs (WebApps keeps the shell tab row)" || bad "T5 hide_tabs is on '$h'"
! grep -rqE 'hide_tabs|hideTabs' "$APP/app/src/main" && ok "T5 the shell has no strip-hiding switch" || bad "T5 the shell still can hide the tab strip"
# the page being embedded is the cloud-webapps index (the in-page tab row is hidden by the site in embed mode)
grep -q '"webapps_base": "https://diegonmarcos.github.io/cloud-webapps"' "$APP/build.json" && ok "T6 WebApps points at the cloud-webapps index" || bad "T6 webapps_base is not the cloud-webapps site"
echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" -eq 0 ]
