#!/usr/bin/env bash
# #1693 Drive types no git token, password or passphrase: GitHostsUi and the repo sheet take it from Cloud Account (App credentials)
# over the per-credential grant, through CloudCredentials. Each mutation is planted in a scratch copy and must go RED.
#   C1 no password field in GitReposScreen / GitHostsUi        C2 both call CloudCredentials (read + openAccount)
#   C3 ids are the catalogue's (slug), never a literal        C4 the tester's own mutations are caught
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
check() {
python3 - "$1" <<'PY'
import os, re, sys
R = sys.argv[1]; S = os.path.join(R, "ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync")
rd = lambda n: open(os.path.join(S, n), encoding="utf-8").read()
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
repos, hosts, cc = rd("GitReposScreen.kt"), rd("GitHostsUi.kt"), rd("CloudCredentials.kt")
code = lambda t: "\n".join(l for l in t.split("\n") if not l.strip().startswith(("//", "*", "/*")))
ok("PasswordVisualTransformation(" not in code(repos) and "PasswordVisualTransformation(" not in code(hosts), "C1 no password field in the git pages", "C1 a git page types a secret")
ok("CloudCredentials.read(" in code(repos) and "CloudCredentials.read(" in code(hosts) and "CloudCredentials.openAccount(" in code(repos) and "openAccount(" in code(hosts),
   "C2 both pages read and link through CloudCredentials", "C2 a page does not use CloudCredentials")
ok("AppCredentialsContract.slug(" in cc and "AccountClient.credential(" in cc and re.search(r'"git\.(host|repo|ssh)\.', cc) is not None and "git.github_token" not in cc,
   "C3 ids come from the catalogue's slug over the AccountClient read", "C3 CloudCredentials lost the catalogue read")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file sed-expression
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive"; cp -r "$ROOT/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync" "$TMP/r/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/"
  sed -i "$3" "$TMP/r/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/$2"
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "host token field is back" GitHostsUi.kt 's|Pill(stringResource(R.string.git_host_set_token), onOpenCredentials)|OutlinedTextField("", {}, visualTransformation = PasswordVisualTransformation())|'
mutate "repo sheet types a secret again" GitReposScreen.kt 's|if (authKind == "https" \|\| authKind == "ssh") {|if (authKind == "https") { OutlinedTextField(secret, { secret = it }, visualTransformation = PasswordVisualTransformation()) }\n if (authKind == "https" \|\| authKind == "ssh") {|'
mutate "host page stops reading Cloud Account" GitHostsUi.kt 's|CloudCredentials.read(|Other.read(|'
mutate "ids hardcoded" CloudCredentials.kt 's|AppCredentialsContract.slug(|String(|'
mutate "client read dropped" CloudCredentials.kt 's|AccountClient.credential(|Nothing.credential(|'
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
