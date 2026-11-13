#!/usr/bin/env bash
# #1712 the app (the Server page first) is drawn with the fleet's dark theme tokens only, never a colour of its own.
#
# THE FAILURE THIS EXISTS FOR. The Server page clashed with the dark app (light backgrounds and text). The page's colours were literals
# (Tone.* hex values) and the theme was a bare MaterialTheme with no default content colour, so anything not named explicitly fell back to
# Material's own (light-theme) defaults. Now the theme is libs:ui-kit's CloudKitTheme over the libs:theme-background dark palette and the
# status colours are the kit's state tokens.
#   C1 no colour literal in any Kotlin file of the app (Color(0x..), Color(r,g,b), Color.White/Black/Red/..., parseColor, Color.rgb/argb, hex 0xAARRGGBB)
#   C2 no light scheme: no lightColorScheme / dynamicLightColorScheme / dynamicColorScheme / isSystemInDarkTheme / MaterialTheme(colorScheme=..) outside the kit
#   C3 the theme is the kit's: WebserverTheme wraps CloudKitTheme over a palette built from ThemeBackground.colors(true)
#   C4 Tone (the status / card colours every page uses) takes its values from KitPalette state tokens and ThemeBackground, nothing else
#   C5 the Server page (Configs > Server) and its cards read their colours through Tone / MaterialTheme.colorScheme only
# Each rule is proven red by a mutation (run with MUTATE=1).
set -u
cd "$(dirname "$0")/.." || exit 2
check() { # $1 = app dir
  python3 - "$1" <<'PY'
import os, re, sys
app = sys.argv[1]
kt = []
for d, _, fs in os.walk(os.path.join(app, 'src-tauri/gen/android/app/src')):
    for f in fs:
        if f.endswith('.kt'): kt.append(os.path.join(d, f))
e = []
def strip(s):  # code without comments and string literals
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)
LIT = [r'\bColor\s*\(\s*0x', r'\bColor\s*\(\s*\d', r'\bColor\.(White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray|Unspecified)\b',
       r'\bparseColor\b', r'\bColor\.(rgb|argb|valueOf)\b', r'\b0x[0-9A-Fa-f]{8}\b', r'\bandroid\.graphics\.Color\b', r'\bandroid\.R\.color\b']
LIGHT = [r'\blightColorScheme\b', r'\bdynamicLightColorScheme\b', r'\bdynamicDarkColorScheme\b', r'\bdynamicColorScheme\b', r'\bisSystemInDarkTheme\b',
         r'\bMaterialTheme\s*\(\s*colorScheme\b', r'\bdarkColorScheme\b']
for p in kt:
    s = strip(open(p).read()); rel = os.path.relpath(p, app)
    for pat in LIT:
        if re.search(pat, s): e.append('C1 %s: colour literal (%s)' % (rel, pat))
    for pat in LIGHT:
        if re.search(pat, s): e.append('C2 %s: %s (the kit owns the colour scheme)' % (rel, pat))
kit = open(os.path.join(app, 'src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/Kit.kt')).read()
m = re.search(r'fun WebserverTheme\(.*?\n}\n', kit, re.S)
if not m or 'CloudKitTheme(' not in m.group(0): e.append('C3 WebserverTheme must wrap CloudKitTheme')
m = re.search(r'fun webserverPalette\(\).*?\n}\n', kit, re.S)
if not m or 'ThemeBackground.colors(true)' not in m.group(0): e.append('C3 the palette must come from ThemeBackground.colors(true)')
t = re.search(r'object Tone \{(.*?)\n}\n', kit, re.S)
if not t: e.append('C4 Tone object missing')
else:
    for ln in t.group(1).splitlines():
        ln = ln.strip()
        if ln.startswith('val ') and not re.search(r'=\s*(KitPalette\.DEFAULT_(OK|WARN|BAD)|c\.[a-zA-Z]+|ThemeBackground\.colors\(true\)\.[a-zA-Z]+)\s*$', ln):
            e.append('C4 Tone value is not a kit / theme token: %s' % ln)
cfg = open(os.path.join(app, 'src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/ConfigsPages.kt')).read()
if 'fun ServerConfigPage' not in cfg: e.append('C5 ServerConfigPage missing')
for pat in (r'\bColor\b', r'\bbackground\s*\(\s*Color'):
    if re.search(pat, strip(cfg)): e.append('C5 ConfigsPages.kt names a Color (%s)' % pat)
print('\n'.join(e)); sys.exit(1 if e else 0)
PY
}
check . || { echo "FAIL"; exit 1; }
echo "ok: the app is drawn with fleet theme tokens only"
if [ "${MUTATE:-}" = 1 ]; then
  t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
  K=src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui
  mut() { rm -rf "$t/m"; mkdir -p "$t/m"; cp -r . "$t/m/"; sed -i "$3" "$t/m/$2"; check "$t/m" >/dev/null && { echo "mutation not red: $1"; exit 1; }; echo "mutation proven red: $1"; }
  mut "hex literal in Tone" $K/Kit.kt 's/val Up = KitPalette.DEFAULT_OK/val Up = Color(0xFF3FB950)/'
  mut "a white card" $K/ConfigsPages.kt 's/PageColumn("c3_cfg_server") {/PageColumn("c3_cfg_server") { androidx.compose.ui.graphics.Color.White/'
  mut "light scheme" $K/HomePage.kt 's/^package .*/&\nval x = lightColorScheme()/'
  mut "bare MaterialTheme" $K/Kit.kt 's/CloudKitTheme(palette, content)/MaterialTheme(colorScheme = x) { content() }/'
  mut "Tone off the tokens" $K/Kit.kt 's/val Down = c.inkDim/val Down = other()/'
fi
