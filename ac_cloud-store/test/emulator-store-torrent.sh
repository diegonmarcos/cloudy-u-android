#!/usr/bin/env bash
# #1790 Cloud Store > Torrent on an emulator, END TO END with a real, legal download: a public-domain Mozart item from the Internet
# Archive. The .torrent web address is typed into the Torrent page's field and added with Add (the same path a user takes); the
# page is shot while it downloads (progress, speed, peers, web seeds, ETA) and once it is complete (Completed list, Open, files);
# Open is tapped. Then every downloaded file is pulled and its size, SHA-1 and MD5 are checked against archive.org/metadata/<item>.
# Red when the engine is unavailable, the add fails, the download does not complete in time, or a file is missing or differs.
# Usage: emulator-store-torrent.sh STORE_APK OUT_DIR ITEM [TIMEOUT_S]
set -u
APK="$1"; OUT="$2"; ITEM="$3"; TIMEOUT="${4:-1200}"; PKG=com.diegonmarcos.cloudstore
mkdir -p "$OUT/files"
URL="https://archive.org/download/$ITEM/${ITEM}_archive.torrent"
curl -sSfL --retry 4 "https://archive.org/metadata/$ITEM" -o "$OUT/metadata.json" || { echo "::error::cannot read archive.org metadata for $ITEM"; exit 1; }
curl -sSfL --retry 4 "$URL" -o "$OUT/item.torrent" || { echo "::error::cannot fetch $URL"; exit 1; }
adb install -r -g "$APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
# the phone's setup: all-files access granted on the Perms page, so the default folder is Downloads/Cloudy-store/Torrent
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
adb logcat -c || true
ACT=$(adb shell cmd package resolve-activity --brief "$PKG" | tr -d '\r' | tail -1)
n=0
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
center() { grep -oE "$1[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '; }
tap_at() { set -- $1; [ $# -eq 4 ] || return 1; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
tap_text() { dump cur; tap_at "$(center "(text|content-desc)=\"$1\"")"; }
adb shell am start -W -n "$ACT" --es tab torrent >/dev/null; sleep 10
for i in 1 2 3 4 5 6; do   # first-run system dialogs
  if tap_text Wait || tap_text Cancel || tap_text Allow; then sleep 3
  elif ! grep -q 'Import .torrent' "$OUT/cur.xml" 2>/dev/null; then adb shell input keyevent KEYCODE_BACK; sleep 3; adb shell am start -W -n "$ACT" --es tab torrent >/dev/null; sleep 5
  else break; fi
done
dump start; shoot torrent-1-empty
grep -q 'Import .torrent' "$OUT/start.xml" || { echo "::error::the Torrent page did not open"; adb logcat -d > "$OUT/logcat.txt"; exit 1; }
if grep -q 'BitTorrent engine unavailable' "$OUT/start.xml"; then echo "::error::engine unavailable on the emulator: $(grep -o 'BitTorrent engine unavailable[^"]*' "$OUT/start.xml" | head -1)"; adb logcat -d > "$OUT/logcat.txt"; exit 1; fi
# type the .torrent address into the one field and press Add
dump cur; tap_at "$(center 'class="android.widget.EditText"')" || { echo "::error::no add field"; exit 1; }
sleep 1; adb shell input text "$URL"; sleep 2
dump typed; shoot torrent-2-typed
if ! grep -qF "$URL" "$OUT/typed.xml"; then   # a dropped keystroke: clear and type once more
  for i in $(seq 1 120); do adb shell input keyevent KEYCODE_DEL; done; adb shell input text "$URL"; sleep 2; dump typed; shoot torrent-2-typed
  grep -qF "$URL" "$OUT/typed.xml" || echo "::warning::the field does not read back the address exactly"
fi
tap_text Add || { echo "::error::no Add button"; exit 1; }
sleep 8; dump added; shoot torrent-3-added
grep -oE 'text="[^"]*(Added to Torrent downloads|Could not|cannot|not a valid)[^"]*"' "$OUT/added.xml" | head -3
grep -q 'Added to Torrent downloads\|Already in Torrent downloads' "$OUT/added.xml" || { echo "::error::the add did not report Added"; adb logcat -d > "$OUT/logcat.txt"; exit 1; }
# watch it download: a shot at the first progress, then every ~60 s, until Completed (1)
start=$(date +%s); seen=0; last=0; done=0
while [ $(( $(date +%s) - start )) -lt "$TIMEOUT" ]; do
  dump watch
  line=$(grep -oE 'text="(downloading|fetching metadata|checking files|queued|paused|error|completed|seeding)[^"]*"' "$OUT/watch.xml" | head -1)
  echo "[$(( $(date +%s) - start ))s] ${line:-no row yet}" | tee -a "$OUT/progress.log"
  if grep -q 'Completed (1)' "$OUT/watch.xml"; then done=1; break; fi
  case "$line" in text=\"downloading*)
    if [ $seen -eq 0 ] || [ $(( $(date +%s) - last )) -ge 60 ]; then seen=$((seen+1)); last=$(date +%s); shoot "torrent-4-progress-$seen"; cp "$OUT/watch.xml" "$OUT/progress-$seen.xml"; fi ;;
  esac
  sleep 5
done
adb logcat -d -s CloudTorrent:V > "$OUT/logcat-torrent.txt" 2>/dev/null || true
if [ $done -ne 1 ]; then shoot torrent-timeout; echo "::error::not complete after ${TIMEOUT}s: $(tail -1 "$OUT/progress.log")"; adb logcat -d > "$OUT/logcat.txt"; exit 1; fi
sleep 3; dump finished; shoot torrent-5-finished
X=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${X%x*}; H=${X#*x}
adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/4)) 400; sleep 2; dump finished-2; shoot torrent-6-finished-files
if tap_text Open; then sleep 4; shoot torrent-7-open; dump open; adb shell input keyevent KEYCODE_BACK; sleep 2; else echo "::warning::no Open button seen"; fi
# pull what was downloaded and check it against archive.org's metadata
for d in "/sdcard/Download/Cloudy-store/Torrent/$ITEM" "/sdcard/Android/data/$PKG/files/Download/Cloudy-store/Torrent/$ITEM"; do
  if adb shell ls "$d" >/dev/null 2>&1; then echo "downloaded to $d" | tee "$OUT/where.txt"; adb pull "$d" "$OUT/files/" >/dev/null && break; fi
done
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
python3 -I - "$OUT/metadata.json" "$OUT/files/$ITEM" "$OUT/item.torrent" "$OUT/verify.json" <<'PY'
import hashlib, json, os, sys
meta, root, tor, out = sys.argv[1:5]
m = json.load(open(meta))
# the torrent's own file list (bencode, minimal)
d = open(tor, 'rb').read()
def dec(i):
    c = d[i:i+1]
    if c == b'i': j = d.index(b'e', i); return int(d[i+1:j]), j+1
    if c in (b'l', b'd'):
        i += 1; L = []
        while d[i:i+1] != b'e': v, i = dec(i); L.append(v)
        return (dict(zip(L[::2], L[1::2])) if c == b'd' else L), i+1
    j = d.index(b':', i); n = int(d[i:j]); return d[j+1:j+1+n], j+1+n
t, _ = dec(0)
in_torrent = ['/'.join(p.decode() for p in f[b'path']) for f in t[b'info'][b'files']]
by_name = {f['name']: f for f in m['files']}
rows, bad = [], []
for name in in_torrent:
    p = os.path.join(root, name); f = by_name.get(name, {})
    have = os.path.isfile(p)
    size = os.path.getsize(p) if have else -1
    sha1 = hashlib.sha1(open(p, 'rb').read()).hexdigest() if have else None
    md5 = hashlib.md5(open(p, 'rb').read()).hexdigest() if have else None
    ok = have and str(size) == str(f.get('size', size)) and (f.get('sha1') in (None, sha1)) and (f.get('md5') in (None, md5))
    rows.append({'file': name, 'size': size, 'meta_size': f.get('size'), 'sha1': sha1, 'meta_sha1': f.get('sha1'), 'md5': md5, 'meta_md5': f.get('md5'), 'ok': ok})
    if not ok: bad.append(name)
audio = [r for r in rows if r['file'].lower().endswith(('.mp3', '.ogg', '.flac'))]
res = {'item': os.path.basename(root), 'title': m['metadata'].get('title'), 'creator': m['metadata'].get('creator'), 'licence': m['metadata'].get('licenseurl'),
       'files': rows, 'audio_ok': bool(audio) and all(r['ok'] for r in audio), 'all_ok': not bad, 'mismatched': bad}
json.dump(res, open(out, 'w'), indent=1)
for r in rows: print(('OK   ' if r['ok'] else 'BAD  ') + '%s  %s B  sha1 %s' % (r['file'], r['size'], r['sha1']))
# Archive.org rewrites its own _meta.xml / _meta.sqlite / _files.xml when an item changes; everything else must match byte for byte.
hard = [b for b in bad if not b.endswith(('_meta.xml', '_meta.sqlite', '_files.xml'))]
if not res['audio_ok'] or hard:
    print('::error::verification failed: %s' % (hard or 'no audio file verified')); sys.exit(1)
if bad: print('::warning::archive.org metadata files differ (rewritten by archive.org): %s' % bad)
print('verified: %d files, audio %s' % (len(rows), ', '.join(r['file'] for r in audio)))
PY
