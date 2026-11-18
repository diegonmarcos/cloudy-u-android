# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-publish-module-guard.py ───
#!/usr/bin/env python3
"""Every publish to ghcr.io / a GitHub release goes through ONE module.

WHY. A GHCR package's visibility is decided when it is CREATED. Pushed with a PAT
(`secrets.GHCR_TOKEN || secrets.GITHUB_TOKEN`) it belongs to the account and is
born private; pushed with the workflow's GITHUB_TOKEN and labelled
`org.opencontainers.image.source=<repo>` it is linked to the repository and takes
its visibility. The owner's rule: CI-published packages follow their repository,
automatically, never by hand. Hand-rolled push steps re-decide that every time
(the Cloud Notes images were born private and 403'd the fleet Ship), so the login,
the labels and the visibility check live in
1_cicd/src/actions/publish-ghcr (generated to .github/actions/publish-ghcr)
and nowhere else.

WHAT FAILS
  * a workflow/template/script line that pushes (oras push|login, docker push|login,
    buildx --push, build-push-action, gh release create|upload, GHCR_TOKEN) outside
    the module, beyond the counted baseline in
    1_cicd/src/data/publish-module-baseline.json. The baseline is a RATCHET: a
    count above it fails (new hand-rolled push), a count BELOW it fails too
    (migrated: lower the number), so it can only shrink to zero.
  * the module losing GITHUB_TOKEN-only auth, the source label, or its visibility check.
  * a `uses: ./.github/actions/publish-ghcr` step handing it a secret other than
    GITHUB_TOKEN.
  * any `GHCR_TOKEN ||` fallback (2026-10-17): push logins use GITHUB_TOKEN with
    `permissions: packages: write`. A step that truly needs the PAT (cross-repo
    package, delete, private pull) names `secrets.GHCR_TOKEN` alone, on purpose.

Plain python3 + stdlib.
"""
import json, os, re, sys

ROOT = os.environ.get("CLOUD_ANDROID_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
BASELINE = "1_cicd/src/data/publish-module-baseline.json"
MODULE = "1_cicd/src/actions/publish-ghcr"
SCAN = [("1_cicd/src/cicd", (".yml",)), ("1_cicd/src/templates", (".in", ".yml")),
        ("1_cicd/src/scripts", (".sh", ".py"))]
# release-swap IS part of the publish module (#1783 P4): the one place that uploads release assets atomically.
SKIP = {"1_cicd/src/scripts/cloud-android-publish-module-guard.py",
        "1_cicd/src/scripts/cloud-android-release-swap.sh"}
# Narrow exemption: dispatch-only, DELETE/GET-only workflows that need the PAT
# (GHCR_TOKEN) because GITHUB_TOKEN cannot delete or list user packages. They may
# mention GHCR_TOKEN only as `GH_TOKEN: ${{ secrets.GHCR_TOKEN }}` and may contain
# no push/login/release verb at all.
EXEMPT = {"1_cicd/src/cicd/ghcr-delete-package.yml", "1_cicd/src/cicd/ghcr-visibility-audit.yml",
          "1_cicd/src/cicd/ghcr-secret-scan.yml"}
DELETE_ALLOW = {"cloud-notes-web", "cloud-notes-sync", "kg-store", "kg-store-binaries", "redis-binaries", "hickory-dns-binaries", "gha-runner", "gha-runner-binaries", "cloud-builder-x", "cloud-builder-x-binaries",
                "alerts-api", "umami", "cloud-data-reports", "matrix-mautrix-whatsapp", "matrix-mautrix-whatsapp-binaries", "session-memory", "session-memory-binaries",
                "dozzle", "fluent-bit", "sauron-forwarder", "crawlee-cloud", "rig-agentic-sonn-14bq8", "rig-agentic-sonn-14bq8-binaries", "umami-setup", "umami-db", "matomo-hybrid", "fincept-server", "radicale-binaries", "kg-graph-binaries", "caddy-l4-binaries", "claude-openai-bridge", "claude-openai-bridge-binaries", "kg-bridge", "kg-bridge-binaries", "kg-mcp", "kg-mcp-binaries", "cloud-cgc-mcp-octocode-db",
                "crawlee-cloud-binaries", "ollama-hai-binaries", "cf-worker-http-to-wg-public-bridge", "cf-worker-http-to-wg-public-bridge-binaries",
                "cloud-lib-analytics-sink", "cloud-lib-cal", "cloud-lib-calc", "cloud-lib-decisions-engine", "cloud-lib-feed", "cloud-lib-fleetconfig", "cloud-lib-gh",
                "cloud-lib-ml-l-image-mlkit", "cloud-lib-ml-l-sound-yamnet", "cloud-lib-net-wg", "cloud-lib-news", "cloud-lib-ops-engine", "c3-watchdog"}
PUSH = re.compile(
    r"(\boras\s+(push|login)\b|\bdocker\s+(push|login)\b|\bbuildx\s+build\b.*--push\b|"
    r"docker/(login|build-push)-action|\bgh\s+release\s+(create|upload)\b|GHCR_TOKEN)")

problems = []
def fail(m): problems.append(m)
def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8", errors="replace") as f:
        return f.read()

def count(text):
    n = 0
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        if PUSH.search(line):
            n += 1
    return n

def _scan():
    out = {}
    for d, exts in SCAN:
        full = os.path.join(ROOT, d)
        for dp, dn, fn in os.walk(full):
            if os.sep + "test" in dp[len(full):] or "__pycache__" in dp:
                continue
            for f in sorted(fn):
                rel = os.path.relpath(os.path.join(dp, f), ROOT)
                if rel in SKIP or rel in EXEMPT or not f.endswith(exts):
                    continue
                n = count(read(rel))
                if n:
                    out[rel] = n
    return out

if "--write-baseline" in sys.argv:
    # Maintenance only: re-count after migrating a site. Never raise a number by hand.
    with open(os.path.join(ROOT, BASELINE), "w") as f:
        json.dump({"_doc": "Ratchet for cloud-android-publish-module-guard.py: hand-rolled push/login/release lines still outside 1_cicd/src/actions/publish-ghcr, per file. Only ever goes DOWN (migrate the site, then `python3 1_cicd/src/scripts/cloud-android-publish-module-guard.py --write-baseline`). New files are not allowed to appear here.", "files": _scan()}, f, indent=1, sort_keys=True)
        f.write("\n")
    sys.exit(0)

try:
    baseline = json.loads(read(BASELINE)).get("files", {})
except Exception as e:
    print("publish-module-guard: cannot read %s: %s" % (BASELINE, e)); sys.exit(1)

seen = _scan()

for rel, n in sorted(seen.items()):
    allowed = baseline.get(rel, 0)
    if n > allowed:
        fail("%s: %d push/login/release line(s) outside the publish module (baseline %d). "
             "Use `uses: ./.github/actions/publish-ghcr`." % (rel, n, allowed))
    elif n < allowed:
        fail("%s: only %d hand-rolled line(s) left, baseline says %d -- lower it in %s" % (rel, n, allowed, BASELINE))
for rel, allowed in sorted(baseline.items()):
    if rel not in seen and allowed:
        fail("%s: baseline lists %d but the file has none -- remove it from %s" % (rel, allowed, BASELINE))

# the exempt workflows stay delete/read-only
for rel in sorted(EXEMPT):
    try:
        t = read(rel)
    except OSError:
        continue
    for line in t.splitlines():
        if line.lstrip().startswith("#"):
            continue
        if "GHCR_TOKEN" in line and not re.fullmatch(r"\s*GH_TOKEN: \$\{\{ secrets\.GHCR_TOKEN \}\}\s*", line):
            fail("%s: GHCR_TOKEN may only appear as `GH_TOKEN: ${{ secrets.GHCR_TOKEN }}`" % rel)
        if re.search(r"\b(oras\s+(push|login)|docker\s+(push|login)|--push\b|gh\s+release)", line):
            fail("%s: an exempt workflow may not push or release" % rel)
    if rel.endswith("delete-package.yml"):
        if "-X DELETE" not in t:
            fail("%s: lost its DELETE call" % rel)
        names = set(re.findall(r"[a-z0-9][a-z0-9-]*[a-z0-9]", " ".join(
            re.sub(r'^.*?(options:\s*\[|case\s+"\$PKG"\s+in)', "", l).split(")")[0].split("]")[0]
            for l in t.splitlines() if "case " in l or "options:" in l)))
        if names - DELETE_ALLOW:
            fail("%s: allowlist grew beyond %s: %s" % (rel, sorted(DELETE_ALLOW), sorted(names - DELETE_ALLOW)))

# the module itself
try:
    act, sh = read(MODULE + "/action.yml"), read(MODULE + "/publish.sh")
except OSError as e:
    fail("publish module missing: %s" % e); act = sh = ""
if act:
    if "default: ${{ github.token }}" not in act:
        fail("module: the token input must default to ${{ github.token }}")
    if "secrets." in act:
        fail("module: action.yml must not read any secret (GITHUB_TOKEN only)")
    if "secrets." in sh or "GHCR_TOKEN" in sh:
        fail("module: publish.sh must not use a PAT")
    for need in ("org.opencontainers.image.source", "verify_visibility", "$P_TOKEN"):
        if need not in sh:
            fail("module: publish.sh lost %r" % need)

# callers must not hand it a PAT
for d, exts in SCAN[:2]:
    full = os.path.join(ROOT, d)
    for f in sorted(os.listdir(full)) if os.path.isdir(full) else []:
        if not f.endswith(exts):
            continue
        t = read(os.path.join(d, f))
        for m in re.finditer(r"publish-ghcr\b(?:(?!\n\s*-\s).)*", t, re.S):
            blk = m.group(0)
            if re.search(r"token:\s*.*secrets\.", blk) and not re.search(r"token:\s*\$\{\{\s*secrets\.GITHUB_TOKEN\s*\}\}", blk):
                fail("%s/%s: publish-ghcr called with a non-GITHUB_TOKEN secret" % (d, f))

# no PAT fallback in any push login: GITHUB_TOKEN, or GHCR_TOKEN explicitly
FALLBACK = re.compile(r"GHCR_TOKEN\s*\|\|")
for d, exts in SCAN[:2]:
    full = os.path.join(ROOT, d)
    for f in sorted(os.listdir(full)) if os.path.isdir(full) else []:
        if not f.endswith(exts):
            continue
        for i, line in enumerate(read(os.path.join(d, f)).splitlines(), 1):
            if not line.lstrip().startswith("#") and FALLBACK.search(line):
                fail("%s/%s:%d: `GHCR_TOKEN ||` fallback -- log in with secrets.GITHUB_TOKEN "
                     "(permissions: packages: write)" % (d, f, i))

if problems:
    print("publish-module-guard FAILED:")
    for p in problems: print("  - " + p)
    sys.exit(1)
print("publish-module-guard ok: %d file(s) still carry baselined hand-rolled pushes (%d lines); module intact"
      % (len(seen), sum(seen.values())))
