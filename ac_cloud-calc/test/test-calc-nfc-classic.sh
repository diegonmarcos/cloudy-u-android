#!/usr/bin/env bash
# #1322 Measure > NFC reads hotel-style MIFARE Classic cards. Gradle proves it compiles and runs MifareTest; this guards the DECLARATIONS
# and the scope, each check proven red by a mutation.
#   C1 CATALOG: the Classic key list is exactly the four PUBLIC keys (FF transport, 00 blank, MAD A0A1.., NDEF D3F7..), the Classic SAKs
#      (08 18 09 88 28 38 ...) and the DESFire SAK/versions are declared, and no other 6-byte key-looking hex hides in the catalog.
#   C2 SCOPE: the lib's Kotlin carries no 12-hex key literal, no write/cloning/crack verbs, and its KDoc states the scope.
#   C3 WIRED: the reader opens Classic through the catalog keys only, names DESFire over IsoDep, shows "locked (private key)" without
#      raising it as an error, the unsupported-phone line and the com.nxp.mifare capability in the status panel.
#   C4 JVM tests exist.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
LIB="$ROOT/ab_cloud-libs-shared/libs/nfc/src"
CAT="$ROOT/data/fleet/nfc-catalog.json"; PK="$LIB/main/java/com/diegonmarcos/superapp/nfc"
MIF="$PK/Mifare.kt"; RDR="$PK/NfcReader.kt"; CTL="$PK/NfcController.kt"; UI="$PK/NfcUi.kt"
TST="$LIB/test/java/com/diegonmarcos/superapp/nfc/MifareTest.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

c1() { python3 -I - "$1" <<'PY'
import json, re, sys
t = open(sys.argv[1]).read(); d = json.loads(t); bad = 0
m = d.get("mifare_classic") or {}
want = ["FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "D3F7D3F7D3F7"]
if [k.get("hex") for k in m.get("public_keys", [])] != want: print("    public_keys is not exactly the four public keys"); bad = 1
sak = set(m.get("saks", []))
for s in ("08", "18", "09", "88", "28", "38"):
    if s not in sak: print("    classic saks lack", s); bad = 1
    if s not in d.get("sak", {}): print("    sak names lack", s); bad = 1
if "20" not in (d.get("desfire") or {}).get("saks", []): print("    desfire sak 20 missing"); bad = 1
names = " ".join(v.get("name", "") for v in (d.get("desfire") or {}).get("versions", []))
for n in ("EV1", "EV2", "EV3", "Light"):
    if n not in names: print("    desfire versions lack", n); bad = 1
if len(m.get("_doc", "")) < 80: print("    no _doc on mifare_classic"); bad = 1
extra = [h for h in re.findall(r'"([0-9A-Fa-f]{12})"', t) if h.upper() not in want]
if extra: print("    other 6-byte hex in the catalog:", extra); bad = 1
sys.exit(bad)
PY
}
c2() { # $1 Mifare.kt, $2 NfcReader.kt
    if grep -E -q '"[0-9A-Fa-f]{12}"' "$1" "$2"; then echo "    a 12-hex key literal in Kotlin"; return 1; fi
    local code; code="$(grep -h -v -E '^\s*(/?\*|//)' "$1" "$2")"
    if grep -E -i -q 'writeBlock|\.increment\(|\.decrement\(|\.transfer\(|\.restore\(|darkside|nested|hardnested|mfkey|crypto1' <<<"$code"; then echo "    a write / clone / crack verb in the Classic code"; return 1; fi
    for t in 'key recovery' 'leaked or vendor keys' 'writing to, cloning or emulating' 'locked (private key)'; do grep -qF -- "$t" "$1" || { echo "    KDoc/scope lacks: $t"; return 1; }; done; }
c3() { # $1 Mifare.kt, $2 NfcReader.kt, $3 NfcController.kt, $4 NfcUi.kt
    for t in 'MifareClassic.get(tag)' 'authenticateSectorWithKeyA' 'catalog.classicKeys' 'Desfire.probe(' 'isDesfireSak' 'unsupportedLine('; do grep -qF -- "$t" "$2" || { echo "    reader lacks $t"; return 1; }; done
    for t in 'cannot read MIFARE Classic; only the UID/SAK are available' 'FEATURE_NXP_MIFARE = "com.nxp.mifare"' '"locked (private key)"' 'normal for a hotel key card'; do grep -qF -- "$t" "$1" || { echo "    Mifare.kt lacks $t"; return 1; }; done
    grep -qF 'phoneHasMifareClassic' "$3" || { echo "    controller lacks the capability check"; return 1; }
    grep -qF 'MIFARE Classic (hotel cards)' "$4" || { echo "    status panel lacks the capability line"; return 1; }
    # A locked sector must never reach the error list.
    local body; body="$(sed -n '/private fun classicSection(/,/^    }$/p' "$2")"
    case "$body" in *errors*) echo "    classicSection writes to errors"; return 1;; esac; }
c4() { [ "$(grep -c '@Test' "$1")" -ge 12 ]; }

echo "── C1 catalog ──"; c1 "$CAT" && pass "four public keys, Classic + DESFire SAKs and names, no stray key hex" || fail "catalog"
echo "── C2 scope ──";   c2 "$MIF" "$RDR" && pass "no key literal, no write/crack verbs, scope in the KDoc" || fail "scope"
echo "── C3 wired ──";   c3 "$MIF" "$RDR" "$CTL" "$UI" && pass "catalog keys only, DESFire, locked is not an error, unsupported-phone line, capability line" || fail "wiring"
c4 "$TST" && pass "MifareTest has >= 12 tests" || fail "MifareTest missing or thin"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
red() { local n="$1"; shift; "$@" >/dev/null 2>&1 && fail "$n passed (vacuous)" || pass "$n → RED"; }
pycat() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); exec(sys.argv[3]); json.dump(d,open(sys.argv[2],"w"))' "$CAT" "$TMP/cat.json" "$1"; }
pycat 'd["mifare_classic"]["public_keys"].append({"name":"x","hex":"112233445566"})'; red "fifth key added" c1 "$TMP/cat.json"
pycat 'd["mifare_classic"]["public_keys"].pop()';                                      red "NDEF key dropped" c1 "$TMP/cat.json"
pycat 'd["mifare_classic"]["saks"].remove("08")';                                      red "SAK 08 undeclared" c1 "$TMP/cat.json"
pycat 'd["desfire"]["versions"]=[v for v in d["desfire"]["versions"] if "EV3" not in v["name"]]'; red "DESFire EV3 name dropped" c1 "$TMP/cat.json"
pycat 'd["note"]="AABBCCDDEEFF"'; python3 - "$TMP/cat.json" <<'PY'
import sys; t=open(sys.argv[1]).read().replace('"AABBCCDDEEFF"','"AABBCCDDEEFF"'); open(sys.argv[1],"w").write(t)
PY
red "stray key-looking hex in the catalog" c1 "$TMP/cat.json"
sed '$a val k = "A1B2C3D4E5F6"' "$MIF" > "$TMP/m1.kt"; red "key literal in Kotlin" c2 "$TMP/m1.kt" "$RDR"
sed '$a fun w(mc: MifareClassic) = mc.writeBlock(4, ByteArray(16))' "$RDR" > "$TMP/r1.kt"; red "writeBlock added" c2 "$MIF" "$TMP/r1.kt"
sed 's/key recovery/key stuff/' "$MIF" > "$TMP/m2.kt"; red "KDoc scope removed" c2 "$TMP/m2.kt" "$RDR"
sed 's/catalog.classicKeys/hotelKeys/' "$RDR" > "$TMP/r2.kt"; red "reader stops using catalog keys" c3 "$MIF" "$TMP/r2.kt" "$CTL" "$UI"
sed 's/Desfire.probe(/Other.probe(/' "$RDR" > "$TMP/r3.kt"; red "DESFire probe unwired" c3 "$MIF" "$TMP/r3.kt" "$CTL" "$UI"
sed 's/cannot read MIFARE Classic; only the UID\/SAK are available/cannot read it/' "$MIF" > "$TMP/m3.kt"; red "unsupported-phone line changed" c3 "$TMP/m3.kt" "$RDR" "$CTL" "$UI"
sed 's/"com.nxp.mifare"/"com.other"/' "$MIF" > "$TMP/m4.kt"; red "NXP feature flag changed" c3 "$TMP/m4.kt" "$RDR" "$CTL" "$UI"
sed 's/MIFARE Classic (hotel cards)/x/' "$UI" > "$TMP/u1.kt"; red "status panel line removed" c3 "$MIF" "$RDR" "$CTL" "$TMP/u1.kt"
sed 's/guard("MIFARE Classic") {/errors += "locked"; guard("MIFARE Classic") {/; s/private fun classicSection(mc: MifareClassic, catalog: NfcCatalog): InfoSection {/&\n        errors += "locked"/' "$RDR" > "$TMP/r4.kt"; red "locked sector raised as error" c3 "$MIF" "$TMP/r4.kt" "$CTL" "$UI"
head -c 0 /dev/null > "$TMP/empty.kt"; red "MifareTest emptied" c4 "$TMP/empty.kt"
c1 "$CAT" >/dev/null && c2 "$MIF" "$RDR" >/dev/null && c3 "$MIF" "$RDR" "$CTL" "$UI" >/dev/null && c4 "$TST" && pass "unmutated tree is still green" || fail "the unmutated tree went red"
echo
if [ "$FAILURES" -eq 0 ]; then echo "test-calc-nfc-classic: all checks passed"; else echo "test-calc-nfc-classic: $FAILURES check(s) FAILED"; exit 1; fi
