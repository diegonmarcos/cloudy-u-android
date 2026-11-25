#!/usr/bin/env bash
# Every upstream app of the S21 profile is DECLARED, and the play-anon rung is honest.
#
# What this holds, statically (no build, no device, no network):
#  1. Every package in a0_tasks/upstream-apps-s21.json has a row under
#     resolver.apps of the install-source map: a ladder, or an explicit
#     "unresolved": "<why>". Nothing is undeclared.
#  2. Every ladder is a subsequence of resolver.order, and the order is the
#     declared one: vendor, fdroid, izzy, play-anon, samsung, aurora, play
#     (izzy = IzzyOnDroid, a second signed F-Droid-format repo, ranked with fdroid).
#  3. play-anon is a FETCHING kind with its config declared (dispensers in
#     order, https; a device profile that exists in libs/gplayapi/profiles and
#     matches the pin's sha256; a positive token TTL).
#  4. No dispenser URL (or its host) is a literal in any Kotlin file of
#     libs:appstore or Cloud Store: the dispensers are DECLARED.
#  5. play-anon never claims success without a sha match: PlayAnonFetcher
#     verifies only by digest, refuses a delivery without a sha256, and has no
#     structural / size-only fallback.
#  6. A dispenser outage is reported as "Play token unavailable" (the named
#     TokenUnavailable), never as "not installable".
#  8. Every declared row has `integrity` in {none, play, unknown}; a `play`
#     row renders the "Play Integrity: may refuse to run" badge on Store >
#     Phone, and the install plan counts those apps separately.
#  7. Every vendor rung carries `verified` evidence (the date and the answer
#     its URL gave), so a vendor URL nobody checked does not parse as declared.
#
# Then it plants each mutation below in a scratch copy and requires the
# checks to go red, so a check that cannot fail does not count as a check.
#
# Usage: ./test-store-upstream-sources.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

REL_MAP="fleet-private-data/appstore-install-sources.json"
REL_LIST="a0_tasks/upstream-apps-s21.json"
REL_FETCH="ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/PlayAnonFetcher.kt"
REL_KT1="ab_cloud-libs-shared/libs/appstore/src/main/java"
REL_KT2="ac_cloud-store/app/src/main/java"
REL_GP="ab_cloud-libs-shared/libs/gplayapi"

# check <root> → PASS/FAIL lines, exit = #fails
check() {
  python3 - "$1" "$REL_MAP" "$REL_LIST" "$REL_FETCH" "$REL_KT1" "$REL_KT2" "$REL_GP" <<'PY'
import hashlib, json, os, re, sys
root, rmap, rlist, rfetch, kt1, kt2, gp = sys.argv[1:8]
fails = 0
def ok(m): print("  PASS: " + m)
def bad(m):
    global fails; fails += 1; print("  FAIL: " + m)
def strip(s):
    s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)

m = json.load(open(os.path.join(root, rmap)))
r = m["resolver"]; apps = r["apps"]; order = r["order"]
want = [x["package"] for x in json.load(open(os.path.join(root, rlist)))]

# 1
missing = [p for p in want if p not in apps]
empty = [p for p in want if p in apps and not apps[p].get("sources") and not apps[p].get("unresolved")]
if missing: bad(f"{len(missing)} upstream package(s) have no row: {missing[:5]}")
elif empty: bad(f"rows with neither a ladder nor an unresolved reason: {empty[:5]}")
else: ok(f"all {len(want)} upstream packages are declared")

# 2
if order == ["vendor", "fdroid", "izzy", "play-anon", "samsung", "aurora", "play"]: ok("order is the declared ladder")
else: bad(f"order is {order}")
badl = []
for p, a in apps.items():
    ks = [s["kind"] for s in a.get("sources", [])]
    rk = [order.index(k) if k in order else -1 for k in ks]
    if -1 in rk or rk != sorted(rk) or len(set(rk)) != len(rk): badl.append((p, ks))
    if a.get("unresolved") and ks: badl.append((p, "unresolved with sources"))
if badl: bad(f"ladders not a subsequence of order: {badl[:4]}")
else: ok("every ladder is a subsequence of order")

# 3
k = r["kinds"].get("play-anon", {})
pa = r.get("play_anon", {})
ds = pa.get("dispensers", [])
prof = os.path.join(root, gp, "profiles", pa.get("device_profile", "-"))
pin = json.load(open(os.path.join(root, gp, "data", "gplayapi-pin.json")))
pinned = pin["files"].get("profiles/" + pa.get("device_profile", "-"), {}).get("sha256")
if not (k.get("fetches") is True and k.get("catalogue") == "play"): bad("play-anon kind is not fetches:true / catalogue:play")
elif not ds or not all(d.get("url", "").startswith("https://") and d.get("user_agent") for d in ds): bad("play_anon.dispensers missing or not https")
elif not os.path.isfile(prof): bad(f"device profile {prof} does not exist")
elif hashlib.sha256(open(prof, "rb").read()).hexdigest() != pinned: bad("device profile does not match the pinned sha256")
elif not (isinstance(pa.get("token_ttl_minutes"), int) and pa["token_ttl_minutes"] > 0): bad("token_ttl_minutes not a positive int")
elif "Play token unavailable" not in k.get("_doc", ""): bad("play-anon _doc does not state the dispenser-outage honesty rule")
else: ok(f"play-anon declared: {len(ds)} dispenser(s), profile pinned, ttl {pa['token_ttl_minutes']} min")

# 4
lits = set()
for d in ds:
    lits.add(d["url"]); lits.add(re.sub(r'^https://([^/]+).*', r'\1', d["url"]))
hits = []
for base in (kt1, kt2):
    for dp, _, fs in os.walk(os.path.join(root, base)):
        for f in fs:
            if f.endswith(".kt"):
                t = open(os.path.join(dp, f), encoding="utf-8").read()
                hits += [f"{f}: {l}" for l in lits if l in t]
if hits: bad(f"dispenser literal in Kotlin: {hits[:3]}")
else: ok("no dispenser URL or host is a Kotlin literal")

# 5
src = strip(open(os.path.join(root, rfetch), encoding="utf-8").read())
fetch = src[src.find("fun fetch("):src.find("fun verify(")]
verify = src[src.find("fun verify("):src.find("fun describe(")]
if "VerifiedApk.byDigest" not in verify or re.search(r'structural\(|bySize\(', src):
    bad("PlayAnonFetcher verifies by something other than the digest")
elif not re.search(r'f\.sha256\s*\?:\s*error\(', fetch):
    bad("PlayAnonFetcher.fetch does not refuse a delivery without a sha256")
elif not re.search(r'verify\(target, digest\)\s*\?:', fetch):
    bad("PlayAnonFetcher.fetch does not fail on a digest mismatch")
else: ok("play-anon succeeds only on a sha256 match")

# 6
if not re.search(r'const val TOKEN_UNAVAILABLE = "Play token unavailable"', src):
    bad("TOKEN_UNAVAILABLE is not \"Play token unavailable\"")
elif not re.search(r'class TokenUnavailable\(why: String\) : IllegalStateException\("\$TOKEN_UNAVAILABLE', src):
    bad("TokenUnavailable does not lead with TOKEN_UNAVAILABLE")
elif not re.search(r'throw TokenUnavailable\("no declared dispenser', src):
    bad("token() does not throw TokenUnavailable when every dispenser fails")
elif re.search(r'not installable', src, re.I):
    bad("PlayAnonFetcher calls an outage 'not installable'")
else: ok("a dispenser outage reads 'Play token unavailable'")

# 7
nov = [p for p, a in apps.items() for s in a.get("sources", []) if s["kind"] == "vendor" and not s.get("verified")]
if nov: bad(f"vendor rung(s) without verified evidence: {nov}")
else: ok("every vendor rung carries verified evidence")

# 8
enum = {"none", "play", "unknown"}
noint = [p for p, a in apps.items() if a.get("integrity") not in enum]
if noint: bad(f"rows without a valid integrity: {noint[:5]}")
else: ok("every declared row has integrity none/play/unknown")
appdir = os.path.join(root, kt1, "com/diegonmarcos/superapp/appstore")
frag = strip(open(os.path.join(appdir, "StorePhoneFragment.kt"), encoding="utf-8").read())
imp = strip(open(os.path.join(appdir, "StoreImport.kt"), encoding="utf-8").read())
strings = open(os.path.join(root, kt1, "../res/values/strings.xml"), encoding="utf-8").read()
if not re.search(r'if \(integrity\(ctx, r\) == SourceResolver\.Integrity\.PLAY\)\s*Text\(ctx\.getString\(R\.string\.store_phone_integrity_badge\)[^}]*testTag\(INTEGRITY_TAG_PREFIX \+ r\.pkg\)', frag):
    bad("a play row does not render the Play Integrity badge")
elif "Play Integrity: may refuse to run" not in strings:
    bad("the badge text is not 'Play Integrity: may refuse to run'")
elif not re.search(r'plan\.needIntegrity\.size', imp) or "need Play Integrity" not in strings:
    bad("the install plan does not count the Play Integrity apps separately")
elif not re.search(r'store_phone_group_managed', frag) or not re.search(r'store_phone_group_play', frag):
    bad("Store > Phone is not grouped into Managed by our Store / Vendor requires Play")
else: ok("play rows carry the badge, the plan counts them, the page groups them")

sys.exit(fails)
PY
}

echo "== real tree"
check "$ROOT"; real=$?

SCRATCH="$(mktemp -d)"; trap 'rm -rf "$SCRATCH"' EXIT
copy() {
  rm -rf "$SCRATCH/t"; mkdir -p "$SCRATCH/t"
  for p in "$REL_MAP" "$REL_LIST" "$REL_GP" "$REL_KT1" "$REL_KT2" "ab_cloud-libs-shared/libs/appstore/src/main/res"; do
    mkdir -p "$SCRATCH/t/$(dirname "$p")"; cp -R "$ROOT/$p" "$SCRATCH/t/$p"
  done
}
mut() {  # mut <name> <python editing files under argv[1]>
  copy
  python3 - "$SCRATCH/t" "$REL_MAP" "$REL_FETCH" "$REL_KT1" <<PY
import json, sys, os
root, rmap, rfetch, kt1 = sys.argv[1:5]
P = os.path.join(root, rmap); F = os.path.join(root, rfetch)
def jload(): return json.load(open(P))
def jsave(d): json.dump(d, open(P, "w"), indent=2)
def edit(f, a, b):
    s = open(f).read(); assert a in s, a; open(f, "w").write(s.replace(a, b))
$2
PY
  if check "$SCRATCH/t" >/dev/null; then echo "  FAIL: mutation '$1' stayed GREEN"; return 1; fi
  echo "  PASS: mutation '$1' goes RED"
}
echo "== mutations"
mf=0
mut "one upstream package removed" 'd=jload(); p=json.load(open(os.path.join(root,"a0_tasks/upstream-apps-s21.json")))[0]["package"]; del d["resolver"]["apps"][p]; jsave(d)' || mf=$((mf+1))
mut "play-anon accepts structure without a sha" 'edit(F, "VerifiedApk.byDigest(file, sha256)", "VerifiedApk.structural(file)")' || mf=$((mf+1))
mut "missing sha falls through" 'edit(F, "val digest = f.sha256", "val digest = f.sha256 ?: \"\"")' || mf=$((mf+1))
mut "outage renamed" 'edit(F, "const val TOKEN_UNAVAILABLE = \"Play token unavailable\"", "const val TOKEN_UNAVAILABLE = \"not installable\"")' || mf=$((mf+1))
mut "order: play-anon above fdroid" 'd=jload(); o=d["resolver"]["order"]; o.remove("play-anon"); o.insert(1,"play-anon"); jsave(d)' || mf=$((mf+1))
mut "ladder out of order" 'd=jload(); a=next(v for v in d["resolver"]["apps"].values() if len(v.get("sources",[]))>1); a["sources"].reverse(); jsave(d)' || mf=$((mf+1))
mut "dispenser literal in Kotlin" 'u=jload()["resolver"]["play_anon"]["dispensers"][0]["url"]; open(os.path.join(root,kt1,"Planted.kt"),"w").write("val x = \"%s\"\n" % u)' || mf=$((mf+1))
mut "unverified vendor rung" 'd=jload(); s=next(s for v in d["resolver"]["apps"].values() for s in v.get("sources",[]) if s["kind"]=="vendor"); s.pop("verified"); jsave(d)' || mf=$((mf+1))
mut "a row without integrity" 'd=jload(); a=next(iter(d["resolver"]["apps"].values())); a.pop("integrity"); jsave(d)' || mf=$((mf+1))
mut "a play row without the badge" 'edit(os.path.join(root,kt1,"com/diegonmarcos/superapp/appstore/StorePhoneFragment.kt"), "testTag(INTEGRITY_TAG_PREFIX + r.pkg)", "testTag(CACHE_TAG_PREFIX + r.pkg)")' || mf=$((mf+1))

echo
if [ "$real" -eq 0 ] && [ "$mf" -eq 0 ]; then echo "ALL GREEN (real tree green, 10 mutations red)"; exit 0; fi
echo "RED: real-tree failures=$real, mutations that stayed green=$mf"; exit 1
