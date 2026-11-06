#!/usr/bin/env bash
# Tester: Cloud > Apps: the Me row reads Me | Me-Health Experiences Tasks | Me-Inventory Me-Buro Me-LLC | Profile (#1440, was Account);
# the Account tile lives in the Me group, last, behind a '|' rule (#1362),
# and is gone from Tools Primary (array position is render position). Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; BJ="$APP/build.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
G='.ui.sections[] | select(.id == "cloud") | .tile_groups[]'
eq "Me row reads Me | Me-Health Experienc. Tasks | Me-Invent. Me-Buro Me-LLC | Profile" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[].label] | join(\" \")" "$BJ")" "Me | Me-Health Experienc. Tasks | Me-Invent. Me-Buro Me-LLC | Profile"
eq "separators are declared separator tiles with no target" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.label == \"|\") | (.separator == true and (.target // \"\") == \"\")] | all" "$BJ")" "true"
eq "every Me sub-tile deep-links its Cloud Me section (Tasks and Me-Inventory open the two Tasks subpages, #1367)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select((.target // \"\") | startswith(\"extapp:cloud-me#\")) | .target | capture(\"^extapp:cloud-me#page:(?<s>[a-z]+)/\").s] | join(\",\")" "$BJ")" "health,experiences,tasks,tasks,buro,llc,profile"
eq "MySocials and MyTrips left the Me row" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[] | select(.id == \"mysocials\" or .id == \"mytrips\")] | length" "$BJ")" "0"
eq "Me row starts with the Me tile" "$(jq -r "$G | select(.title == \"Me\") | .tiles[0].label" "$BJ")" "Me"
eq "Me row ends |, Profile (#1362, #1440)" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[-2:][].label] | join(\",\")" "$BJ")" "|,Profile"
eq "Profile tile opens Cloud Me's profile page (#1440)" "$(jq -r "$G | select(.title == \"Me\") | .tiles[-1].target" "$BJ")" "extapp:cloud-me#page:profile/profile"
eq "Tools Primary has no Account tile" "$(jq -r "$G | select(.title == \"Tools Primary\") | [.tiles[] | select(.label == \"Account\")] | length" "$BJ")" "0"
eq "Tools Primary now ends with Vault (Light moved to Cloud Calc > Toolkit)" "$(jq -r "$G | select(.title == \"Tools Primary\") | .tiles[-1].label" "$BJ")" "Vault"
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
