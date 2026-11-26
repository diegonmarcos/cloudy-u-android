#!/usr/bin/env bash
# Tester: Cloud > Apps: the Me row reads Lnktree | Me-Pers Me-Prof Me-Prj | Me-Invent. Me-Buro Me-LLC (#1903; the Profile tile left it and stays in Setup);
# the Account tile lives in the Me group, last, behind a '|' rule (#1362),
# and is gone from Tools Primary (array position is render position). Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; BJ="$APP/build.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
G='.ui.sections[] | select(.id == "cloud") | .tile_groups[]'
eq "Me row reads Lnktree | Me-Pers Me-Prof Me-Prj | Me-Invent. Me-Buro Me-LLC (#1903)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[].label] | join(\" \")" "$BJ")" "Lnktree | Me-Pers Me-Prof Me-Prj | Me-Invent. Me-Buro Me-LLC"
eq "separators are declared separator tiles with no target" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.label == \"|\") | (.separator == true and (.target // \"\") == \"\")] | all" "$BJ")" "true"
eq "every Me sub-tile deep-links its Cloud Me section (Me-Inventory opens Tasks > Backlogs, Me-Pers, Me-Prof and Me-Prj open Cloud Me Pers, Prof and Proj, #1903)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select((.target // \"\") | startswith(\"extapp:cloud-me#\")) | .target | capture(\"^extapp:cloud-me#page:(?<s>[a-z]+)/\").s] | join(\",\")" "$BJ")" "pers,prof,proj,tasks,buro,llc"
eq "MySocials and MyTrips left the Me row" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.id == \"mysocials\" or .id == \"mytrips\")] | length" "$BJ")" "0"
eq "Me row starts with the Lnktree tile (#1771, was Me)" "$(jq -r "$G | select(.title == \"Me\") | .tiles[0].label" "$BJ")" "Lnktree"
eq "Me row ends Me-Buro, Me-LLC: the Profile tile left it (#1903)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[-2:][].label] | join(\",\")" "$BJ")" "Me-Buro,Me-LLC"
eq "Profile is still reachable: Setup ends Profile | About (#1903)" "$(jq -r "$G | select(.title == \"Setup\") | [.tiles[-2:][].label] | join(\",\")" "$BJ")" "Profile,About"
eq "the Profile tile (configs-profile) opens Cloud Account's profile" "$(jq -r "$G | select(.title == \"Setup\") | .tiles[] | select(.id == \"configs-profile\") | .target" "$BJ")" "extapp:cloud-account"
eq "the old Profile tile id (tools-account) is not in the Me row any more" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.id == \"tools-account\")] | length" "$BJ")" "0"
eq "Primary (renamed from 'Tools Primary', #1627) has no Account tile" "$(jq -r "$G | select(.title == \"Primary\") | [.tiles[] | select(.label == \"Account\")] | length" "$BJ")" "0"
eq "Primary now ends with Calc (#1916 MOVED Wallet to Data, where it follows Vault)" "$(jq -r "$G | select(.title == \"Primary\") | .tiles[-1].label" "$BJ")" "Calc"
eq "Wallet follows Vault in Data (#1916)" "$(jq -r "$G | select(.title == \"Data\") | [.tiles[].label] | (index(\"Wallet\")) as \$i | .[\$i-1]" "$BJ")" "Vault"
eq "Lnktree tile (id me) opens the Cloud Me app on its default Lnktree section (#1771)" "$(jq -r "$G | select(.title == \"Me\") | .tiles[0] | [.id, .target, .icon] | join(\" \")" "$BJ")" "me extapp:cloud-me ic_tree"   # #1931 the icons of Lnktree and Me-Prj swapped
eq "Me-Prj tile (id myroadmap, was Socials) deep-links Cloud Me's Proj Home (#1931: was the stale proj/lnktree-site, which Cloud Me aliases to Lnktree), icon swapped with Lnktree" "$(jq -r "$G | select(.title == \"Me\") | .tiles[] | select(.label == \"Me-Prj\") | [.id, .target, .icon] | join(\" \")" "$BJ")" "myroadmap extapp:cloud-me#page:proj/pj-home ic_vcard"
eq "Me-Pers (id myhealth) opens Pers and Me-Prof (id myexperiences) opens Prof, ids stable (#1903)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.id == \"myhealth\" or .id == \"myexperiences\") | [.id, .label, .target] | join(\" \")] | join(\";\")" "$BJ")" "myhealth Me-Pers extapp:cloud-me#page:pers/pers-profiles;myexperiences Me-Prof extapp:cloud-me#page:prof/prof-web"
eq "no Me row tile is still labelled Me or Tasks (#1771)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.label == \"Me\" or .label == \"Tasks\")] | length" "$BJ")" "0"
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
