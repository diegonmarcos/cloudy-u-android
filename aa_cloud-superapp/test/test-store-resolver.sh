#!/usr/bin/env bash
# #571 WE ARE THE STORE — the source resolver's declaration and its readers.
# Behaviour (parse refusals, actions, the rendered empty-phone page) is asserted
# by app/src/test/.../apps/StoreResolverTest.kt; this tester holds the contracts
# a Robolectric run cannot see, and PROVES ITSELF BY MUTATION: the validator in
# T1 is run again over deliberately broken copies of the declaration and must go
# RED on each — a validator that passes a fake Play URL is no validator.
#
# T1 DECLARATION HONESTY (validator + mutations). One `resolver` block inside
#    the ONE #564 map; `order` and `kinds` name the SAME kinds in the same
#    order (#627, so the page's source tabs are the declaration). Every app row
#    is EXACTLY ONE of: a ladder (non-empty, a strict subsequence of `order`),
#    or `unresolved` (a non-blank reason and no rung at all: the row says why
#    there is nothing to install from). Every row declares `integrity`. A
#    `play` / `play-anon` rung carries nothing but its kind (Play publishes no
#    APK URL; play-anon's URLs come from Play's delivery at fetch time). Since
#    play-anon fetches Play's own catalogue, a declared `play` hand-off always
#    sits BELOW a `play-anon` rung: no declared row is Play-only, and the
#    Play-only case is an UNDECLARED package, which SourceResolver.resolve
#    falls back to the Play hand-off alone. Every vendor `apk`/`feed`/`sha256`
#    is https; no URL anywhere points at play.google.com; the F-Droid signer
#    pin is a 64-hex sha256; at least one app this store fetches itself and one
#    it cannot (the hand-off / badge path) are declared.
# T2 ONE INSTALLER, ONE DOWNLOADER. ExternalInstall commits through
#    Fleet.commit and nothing else; SourceResolver and FDroidIndex download
#    through the updater's Download; no store file opens its own
#    HttpURLConnection for bytes, names a PackageInstaller, or names an
#    installer package (#564's rule, extended to the new files).
# T3 F-DROID IS VERIFIED. FDroidIndex opens the index as a verifying JarFile,
#    reads codeSigners, compares against the declared pin, and refuses an
#    unsigned entry; APKs are VerifiedApk.byDigest against the index hash.
# T4 THE PAGE. Phone Apps passes real Install all / Update all verbs, builds its
#    rows from installed ∪ fleet ∪ declared, and derives every not-installed
#    row's buttons from PhoneAppActions.forMissing.
# T5 REGISTRATION. The JVM test exists and names the honesty cases.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main"
MAP="$LIB/assets/appstore-install-sources.json"

python3 - "$APP" "$LIB" "$MAP" <<'PY'
import json, os, re, sys, glob, copy
app, lib, mapf = sys.argv[1:]
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

def validate(doc):
    """The honesty rules, as a list of violations. Empty = honest."""
    v = []
    r = doc.get("resolver")
    if not isinstance(r, dict): return ["no resolver block"]
    order = r.get("order")
    # #627 the ranking is no longer a fixed triple — new stores are declared, not
    # coded — so the rule is that the two halves of the declaration agree. A kind
    # ranked with no `kinds` entry is a source with no tab and no label; a `kinds`
    # entry with no rank is a tab with no place in the ladder.
    kinds = r.get("kinds")
    if not isinstance(order, list) or not order: v.append("order is %r" % (order,))
    elif not isinstance(kinds, dict): v.append("kinds is %r" % (kinds,))
    elif list(kinds.keys()) != order: v.append("kinds %r does not match order %r" % (list(kinds.keys()), order))
    else:
        for k, spec in kinds.items():
            if not str(spec.get("label", "")).strip(): v.append("kind %s has no label" % k)
            if not isinstance(spec.get("fetches"), bool): v.append("kind %s does not declare fetches" % k)
            # A hand-off has no bytes for us, so it must name the store app it
            # hands off to, and that package must be in the #564 map.
            if spec.get("fetches") is False:
                inst = spec.get("installer")
                if inst not in doc.get("sources", {}):
                    v.append("hand-off kind %s names installer %r, absent from the #564 sources map" % (k, inst))
            cat = spec.get("catalogue")
            if cat is not None and cat not in kinds: v.append("kind %s aliases undeclared catalogue %r" % (k, cat))
    for req in ("vendor", "fdroid", "play"):
        if order and req not in order: v.append("the resolver branches on %s but it is not declared" % req)
    fd = r.get("fdroid", {})
    if not re.fullmatch(r"[0-9a-f]{64}", str(fd.get("cert_sha256", ""))): v.append("fdroid cert_sha256 is not a sha256 hex")
    for k in ("repo", "index", "index_entry", "api"):
        if not str(fd.get(k, "")).startswith("https://") and k != "index" and k != "index_entry": v.append("fdroid.%s not https" % k)
    play = r.get("play", {}).get("installer")
    if play not in doc.get("sources", {}): v.append("play installer %r is not in the #564 sources map" % play)
    apps = r.get("apps", {})
    if not apps: v.append("no apps")
    # Which kinds hand us bytes, from the declaration itself (#627), so a new
    # hand-off store is "not ours to install" by construction, as in Kotlin.
    fetching = {k for k, spec in (r.get("kinds") or {}).items() if isinstance(spec, dict) and spec.get("fetches") is True}
    n_direct = n_not_ours = 0
    for pkg, a in apps.items():
        if a.get("integrity") not in ("none", "play", "unknown"):
            v.append("%s: integrity %r is not none/play/unknown" % (pkg, a.get("integrity")))
        srcs = a.get("sources", [])
        if not isinstance(srcs, list): v.append("%s: sources is %r" % (pkg, srcs)); continue
        if "unresolved" in a:
            # The unresolved rung: an app with no public source SAYS WHY and
            # claims no rung. It resolves to nothing to install from, honestly.
            if not isinstance(a["unresolved"], str) or not a["unresolved"].strip():
                v.append("%s: unresolved gives no reason (%r)" % (pkg, a["unresolved"]))
            if srcs: v.append("%s: unresolved but still claims rungs %r" % (pkg, [s.get("kind") for s in srcs]))
            pg = a.get("official_page")
            if pg is not None and (not str(pg).startswith("https://") or "play.google.com" in str(pg)):
                v.append("%s: official_page %r is not an https publisher page" % (pkg, pg))
            n_not_ours += 1
            continue
        kinds = [s.get("kind") for s in srcs]
        ranks = [order.index(k) if k in (order or []) else -1 for k in kinds]
        if not srcs or -1 in ranks or ranks != sorted(ranks) or len(set(ranks)) != len(ranks):
            v.append("%s ladder %r is not a subsequence of order (and no unresolved reason)" % (pkg, kinds))
        if "play" in kinds and "play-anon" not in kinds:
            v.append("%s: a declared Play hand-off with no play-anon rung above it - this store fetches Play's catalogue itself" % pkg)
        if any(k in fetching for k in kinds): n_direct += 1
        else: n_not_ours += 1
        for s in srcs:
            if s.get("kind") in ("play", "play-anon") and set(s) != {"kind"}: v.append("%s: a %s rung carries %s" % (pkg, s.get("kind"), sorted(set(s) - {"kind"})))
            if s.get("kind") == "vendor":
                if not (s.get("apk") or s.get("apk_key")): v.append("%s: vendor rung names no apk" % pkg)
                for k in ("apk", "feed", "sha256"):
                    if k in s and not s[k].startswith("https://"): v.append("%s: vendor %s not https" % (pkg, k))
            for k, val in s.items():
                if isinstance(val, str) and "play.google.com" in val: v.append("%s: %s points at play.google.com — Play has no APK URL" % (pkg, k))
    if n_not_ours == 0: v.append("no declared app this store cannot fetch (the hand-off / badge path would be untested)")
    if n_direct == 0: v.append("no direct app declared")
    return v

# The Play-only case lives in CODE now: an undeclared package resolves to the
# Play hand-off alone. Checked over the source text so a mutation can break it.
FALLBACK = re.compile(r"fun resolve\(cfg: Config, pkg: String\): External =\s*cfg\.apps\[pkg\] \?: External\(pkg, pkg, listOf\(Source\.Play\), declared = false\)")
def fallback_ok(sr_text): return bool(FALLBACK.search(sr_text))

print("== T1: declaration honesty, proven by mutation ==")
doc = json.loads(read(mapf))
# ONE INSTALL-SOURCE MAP, derived from CONTENT rather than from the filename —
# see the same rule in test-store-phone-bar.sh. A pinned name would let a second
# resolver in as resolver2.json; this will not, and it no longer trips over a
# declaration that installs nothing (#642's read-only feed reader).
maps = sorted(os.path.basename(p) for p in glob.glob(os.path.join(lib, "assets/*.json"))
              if {"sources", "resolver"} & set(json.loads(read(p))))
if maps == ["appstore-install-sources.json"]: ok("the resolver lives inside the ONE #564 map (%s)" % maps[0])
else: bad("a second asset map: %s" % maps)
viol = validate(doc)
if not viol: ok("declaration passes every honesty rule (%d apps)" % len(doc["resolver"]["apps"]))
else: bad("declaration violates: " + " | ".join(viol))

apps = doc["resolver"]["apps"]
ladder = lambda a: [s["kind"] for s in a.get("sources", [])]
rung = lambda a, k: next(s for s in a["sources"] if s["kind"] == k)
direct = next(p for p, a in apps.items() if len(ladder(a)) > 1 and ladder(a)[-1] == "play")
vendor = next(p for p, a in apps.items() if ladder(a)[:1] == ["vendor"] and "apk" in a["sources"][0])
unresolved = next(p for p, a in apps.items() if "unresolved" in a)
mutations = {
    "a URL on a Play rung": lambda d: rung(d["resolver"]["apps"][direct], "play").__setitem__("apk", "https://play.google.com/fake.apk"),
    "a URL on a play-anon rung": lambda d: rung(d["resolver"]["apps"][direct], "play-anon").__setitem__("apk", "https://example.invalid/fake.apk"),
    "Play ranked above a direct rung": lambda d: d["resolver"]["apps"][direct].__setitem__("sources", list(reversed(d["resolver"]["apps"][direct]["sources"]))),
    "a declared Play-only row (play-anon dropped)": lambda d: d["resolver"]["apps"][direct].__setitem__("sources", [{"kind": "play"}]),
    "a plain-http vendor APK": lambda d: d["resolver"]["apps"][vendor]["sources"][0].__setitem__("apk", "http://example.invalid/x.apk"),
    "an unresolved row that also claims a rung": lambda d: d["resolver"]["apps"][unresolved].__setitem__("sources", [{"kind": "play-anon"}]),
    "an unresolved row with a blank reason": lambda d: d["resolver"]["apps"][unresolved].__setitem__("unresolved", "  "),
    "an official page over plain http": lambda d: d["resolver"]["apps"][next(p for p, a in d["resolver"]["apps"].items() if a.get("official_page"))].__setitem__("official_page", "http://example.invalid/"),
    "a row with neither a ladder nor an unresolved reason": lambda d: d["resolver"]["apps"][unresolved].pop("unresolved"),
    "a row without integrity": lambda d: d["resolver"]["apps"][unresolved].pop("integrity"),
    "a truncated F-Droid signer pin": lambda d: d["resolver"]["fdroid"].__setitem__("cert_sha256", "abc"),
    "a Play installer outside the #564 map": lambda d: d["resolver"]["play"].__setitem__("installer", "com.example.nostore"),
}
for name, mut in mutations.items():
    m = copy.deepcopy(doc); mut(m)
    if validate(m): ok("mutation goes RED: " + name)
    else: bad("mutation stayed GREEN — the validator does not see: " + name)

# #627 MUTATIONS: the declaration must be closed at both ends.
for name, mut in (
    ("a kind ranked in `order` with no `kinds` entry",
     lambda d: d["resolver"]["kinds"].pop(d["resolver"]["order"][-1])),
    ("a `kinds` entry that is not ranked in `order`",
     lambda d: d["resolver"]["kinds"].__setitem__("invented", {"label": "Invented", "fetches": True})),
    ("a hand-off kind that names no installer",
     lambda d: d["resolver"]["kinds"]["play"].pop("installer", None)),
):
    m = copy.deepcopy(doc); mut(m)
    if validate(m): ok("mutation goes RED: " + name)
    else: bad("mutation stayed GREEN - the validator does not see: " + name)

print("== T2: one installer, one downloader ==")
kt_dir = os.path.join(lib, "java/com/diegonmarcos/superapp/appstore")
kt = {os.path.basename(p): code(p) for p in glob.glob(kt_dir + "/*.kt")}
for f in ("SourceResolver.kt", "FDroidIndex.kt", "ExternalInstall.kt", "StorePhoneFragment.kt", "PhoneAppActions.kt"):
    if f not in kt: print("FATAL: %s missing" % f); sys.exit(2)
ext = kt["ExternalInstall.kt"]
if "Fleet.commit(" in ext and not re.search(r"PackageInstaller|ACTION_INSTALL_PACKAGE|HttpURLConnection|Download\.toFile", ext):
    ok("ExternalInstall commits through Fleet.commit and owns no installer or downloader")
else: bad("ExternalInstall grew a second installer/downloader")
for f in ("SourceResolver.kt", "FDroidIndex.kt"):
    if "Download.toFile(" in kt[f] and "PackageInstaller" not in kt[f]: ok(f + " downloads through the updater's Download")
    else: bad(f + " does not use Download.toFile, or reaches PackageInstaller")
if re.search(r"^\s*object Download\b", read(os.path.join(lib, "../../../updater/src/main/java/com/diegonmarcos/superapp/updater/source/Download.kt")), re.M):
    ok("updater Download is public, so the store can share it")
else: bad("updater Download is not public — the store cannot share the one downloader")
installers = sorted(doc["sources"])
leak = ["%s: %s" % (f, k) for f, t in kt.items() for k in installers if '"%s"' % k in t]
leak += [f for f, t in kt.items() if "play.google.com" in t]
if not leak: ok("no store file names an installer package or a Play URL")
else: bad("hardcoded installer / Play URL: %s" % leak)

print("== T3: F-Droid is verified ==")
fdi = kt["FDroidIndex.kt"]
for needle, what in (("JarFile(jar, true)", "the index is opened as a VERIFYING JarFile"),
                     ("codeSigners", "the entry's signers are read"),
                     ("not signed", "an unsigned entry is refused"),
                     ("certSha256.lowercase() !in fingerprints", "the signer must match the declared pin"),
                     ("VerifiedApk.byDigest(target, v.sha256)", "the APK is verified against the signed index hash"),
                     ("JsonReader", "the index is streamed, not parsed into a tree")):
    if needle in fdi: ok(what)
    else: bad("FDroidIndex: " + what + " — missing " + needle)
if 'hashType == "sha256"' in fdi: ok("only sha256-hashed versions are offered")
else: bad("FDroidIndex offers versions without a sha256 hash")

print("== T4: the page ==")
phone = kt["StorePhoneFragment.kt"]
m = re.search(r"StoreBar\.Verbs\((.*?)\)\)", phone, re.S)
if m and re.search(r"installAll\s*=\s*\{\s*installAll\(\)\s*\}", m.group(1)) and re.search(r"updateAll\s*=\s*\{\s*updateAll\(\)\s*\}", m.group(1)):
    ok("Phone Apps passes real Install all / Update all verbs")
else: bad("Phone Apps does not pass real batch verbs")
for needle, what in (("AppInventory.launchable(ctx)", "rows start from what is installed"),
                     ("StoreMembership.phoneOnly(AppInventory.launchable(ctx), fleetPkgs)", "rows list the phone's launchable apps minus the fleet (the Cloud page's)"),
                     ("resolver.apps.values.filter { StoreMembership.isPhone(it.pkg, fleetPkgs) }.forEach", "rows add every declared external app, never a fleet one"),
                     ("PhoneAppActions.forMissing(", "not-installed rows derive their buttons from the one action source"),
                     ("SourceResolver.ofFleet(Fleet.status(", "fleet rows are probed by the fleet's own status"),
                     ("SourceResolver.check(app, resolver", "external rows are probed by the resolver")):
    if needle in phone: ok(what)
    else: bad("StorePhoneFragment: " + what + " — missing " + needle)
sr = kt["SourceResolver.kt"]
if "require(o.length() == 1)" in sr: ok("the parser refuses a Play rung that carries anything")
else: bad("the parser accepts a Play rung with extra fields")
if "Fleet.candidateIdentity(ctx, apk.file)" in sr and "id.pkg != pkg" in sr: ok("a downloaded APK must be the package asked for")
else: bad("SourceResolver installs bytes without checking their package")
if fallback_ok(sr): ok("an undeclared package resolves to the Play hand-off alone (the Play-only case)")
else: bad("SourceResolver.resolve no longer falls back to the Play hand-off for an undeclared package")
if not fallback_ok(sr.replace("listOf(Source.Play), declared = false", "listOf(Source.PlayAnon), declared = false")):
    ok("mutation goes RED: an undeclared package falls back to play-anon instead of the Play hand-off")
else: bad("mutation stayed GREEN - the fallback check does not see: an undeclared package falls back to play-anon")

print("== T6 (#627): the source tabs derive from the declaration ==")
# EVERY kind's user-visible label lives in the asset. It used to live in a `when`
# over three KIND_* constants mapping to three string resources — a second list
# of the stores that exist, which is why declaring Samsung or Aurora would have
# rendered them as the empty string on a row's ladder.
labels = [k["label"] for k in doc["resolver"]["kinds"].values()]
# Scoped to the files that RESOLVE or RENDER a kind: StoreCloudFragment's own
# "Direct" button is the fleet's BootstrapInstall hand-off, a different thing
# that happens to share a word with the vendor kind's label, and folding it in
# would make this assertion about vocabulary instead of about derivation.
renders = ("SourceResolver.kt", "StorePhoneFragment.kt", "StoreSourceTabs.kt", "PhoneAppActions.kt")
leaked = ["%s: %s" % (f, l) for f in renders for l in labels if '"%s"' % l in kt.get(f, "")]
if not leaked: ok("no declared kind label is written into the store's code (%s)" % ", ".join(labels))
else: bad("a kind label is hardcoded in Kotlin: %s" % leaked)
tabs = kt.get("StoreSourceTabs.kt", "")
if not tabs: bad("StoreSourceTabs.kt missing - the tab strip has nowhere derived to come from")
elif re.search(r"listOf\s*\(\s*\"", tabs): bad("StoreSourceTabs builds a literal list of tabs")
elif "kinds.map { it.label }" in tabs: ok("the strip is one pill per declared kind, labelled from the declaration")
else: bad("StoreSourceTabs does not derive its pills from the declared kinds")
page = kt["StorePhoneFragment.kt"]
if "StoreSourceTabs.Strip(" in page and "cfg?.kinds" in page:
    ok("Store > Phone draws the strip from the resolved kinds")
else: bad("Store > Phone does not draw the strip from the resolved kinds")
if "it.external?.inTab(tab)" in page and ".filter { if (installedOnly) it.installed else it.external?.declared == true }" in page:
    ok("the source tab COMPOSES with the #619 Declared/Installed filter over the same rows")
else: bad("the source tab replaces the #619 filter instead of composing with it")

print("== T5: registration ==")
jvm = os.path.join(app, "app/src/test/java/com/diegonmarcos/superapp/apps/StoreResolverTest.kt")
if os.path.exists(jvm):
    t = read(jvm)
    for needle in ("a URL on a Play rung is refused", "Play ranked above a direct rung is refused",
                   "a Play-only app - Install disabled", "an empty phone renders every fleet app"):
        if needle in t: ok("JVM test covers: " + needle)
        else: bad("JVM test lacks: " + needle)
else: bad("StoreResolverTest.kt missing")
tabs_jvm = os.path.join(app, "app/src/test/java/com/diegonmarcos/superapp/apps/StoreSourceTabsTest.kt")
if os.path.exists(tabs_jvm):
    t = read(tabs_jvm)
    for needle in ("renders one tab per declared kind", "Samsung is a separate catalogue",
                   "a tab holds only apps whose declared ladder names that source"):
        if needle in t: ok("JVM test covers: " + needle)
        else: bad("JVM test lacks: " + needle)
else: bad("StoreSourceTabsTest.kt missing - the #627 derivation is unproven")

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
