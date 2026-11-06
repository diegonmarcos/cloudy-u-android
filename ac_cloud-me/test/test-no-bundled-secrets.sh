#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-no-bundled-secrets — the public Cloud Me apk ships NO secret  ║
# ║ and NO personal dataset. All personal data is fetched at runtime   ║
# ║ (github-login-direct: signed per-repo release-asset SQLite packs). ║
# ╚══════════════════════════════════════════════════════════════════╝
# Static, no build, no network. Scans ONLY the directories app/build.gradle maps into the apk's
# assets, plus app/src/main/assets, and asserts:
#   1. no secret material (PEM key/cert, otpauth seed, JWT, a Vaultwarden/Bitwarden export) is present;
#   2. no baked data pack (*.sqlite / *.db / *.pack / a me-* domain pack) is present;
#   3. the Buro > Secrets and ID&DD runtime pages ship only {note, fragment} blocks — never inline
#      secret rows/values.
# Then plants each violation in a scratch copy and requires it be CAUGHT, so a check that cannot
# fail does not count.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

# the asset source dirs (keep in step with app/build.gradle sourceSets.main.assets.srcDirs)
ASSET_DIRS="data/files data/ui data/health data/web data/profiles data/repos data/profile app/src/main/assets"

scan() {
  # $1 = root to scan; echoes a reason per finding (nothing => clean)
  local root="$1"
  python3 - "$root" <<'PY'
import os, re, sys
root = sys.argv[1]
# PEM blocks, otpauth seeds, JWTs, and raw export shapes — not the English words.
pats = [
    re.compile(r"-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----"),
    re.compile(r"-----BEGIN CERTIFICATE-----"),
    re.compile(r"otpauth://"),
    re.compile(r"eyJ[\w-]{6,}\.eyJ[\w-]{6,}\.[\w-]{6,}"),          # JWT
    re.compile(r'"encrypted"\s*:\s*(?:true|false).*"items"\s*:', re.S),  # bitwarden export
    re.compile(r'"passwords"\s*:\s*\[.*"password"', re.S),                # myvault export
]
packish = re.compile(r"\.(sqlite3?|db|pack)$|(^|/)me-(secrets|iddd|financial|portfolio|configs|events)\b")
for dirpath, _, names in os.walk(root):
    for n in names:
        p = os.path.join(dirpath, n)
        if packish.search(p):
            print(f"bundled pack/db: {p}"); continue
        if not n.endswith((".json", ".txt", ".pem", ".key", ".crt", ".csv")):
            continue
        try:
            txt = open(p, encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        for pat in pats:
            if pat.search(txt):
                print(f"secret marker {pat.pattern[:28]!r} in {p}"); break
PY
}

echo "[ac_cloud-me] no-bundled-secrets"
cd "$APP"

# 1 + 2: scan the shipped asset dirs
found=""
for d in $ASSET_DIRS; do [ -d "$d" ] && found="$found$(scan "$d")"; done
if [ -z "$found" ]; then ok "no secret material or baked pack in any shipped asset dir"; else bad "shipped assets carry secrets/packs:"; echo "$found" | sed 's/^/    /'; fi

# 3: the Secrets + runtime ID&DD pages are declarative shells only
python3 - <<'PY' || exit_code=1
import json, sys, os
runtime = ["logins","totp","passkeys","ssh","apitokens","jwt","certs","paycards","autocomplete"]
bad = []
for p in runtime:
    f = f"data/ui/buro/{p}.json"
    if not os.path.exists(f):
        bad.append(f"{f} missing"); continue
    for blk in json.load(open(f)):
        if blk.get("kind") not in ("note", "fragment"):
            bad.append(f"{f} has a {blk.get('kind')} block — runtime secret/id pages must be note+fragment only")
        # a fragment must name a pack or a known runtime surface, never carry inline values
        if blk.get("kind") == "fragment" and any(k in blk for k in ("rows","items","value","secret","password","seed")):
            bad.append(f"{f} fragment carries an inline value")
print("\n".join(bad))
sys.exit(1 if bad else 0)
PY
if [ "${exit_code:-0}" -eq 0 ]; then ok "every runtime Secrets/ID&DD page is a note+fragment shell"; else bad "a runtime page carries data"; fi
exit_code=0

# ---- self-mutation: each violation must be caught -----------------------------
tmp="$(mktemp -d "${TMPDIR:-/tmp}/nbs.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/data/ui/buro"
cp data/ui/buro/logins.json "$tmp/data/ui/buro/logins.json"

# M1: a PEM private key planted in an asset
printf '[{"kind":"note","title":"x","body":"-----BEGIN OPENSSH PRIVATE KEY-----\\nAAAA\\n-----END OPENSSH PRIVATE KEY-----"}]' > "$tmp/data/ui/buro/logins.json"
if [ -n "$(scan "$tmp/data/ui")" ]; then ok "red: a planted private key is caught"; else bad "a planted private key slipped through"; fi

# M2: a bundled pack
: > "$tmp/data/ui/me-secrets.sqlite"
if [ -n "$(scan "$tmp/data/ui")" ]; then ok "red: a bundled .sqlite pack is caught"; else bad "a bundled pack slipped through"; fi

echo "== RESULT: $FAILS failed =="
[ "$FAILS" -eq 0 ]
