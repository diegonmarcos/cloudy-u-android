#!/usr/bin/env python3
"""#595 -- bakes agent tooling (git, node, claude) into the nix-on-droid store
this terminal ships, so a fresh install never has to run upstream's
interactive, networked first-login wizard just to get a shell with a package
in it.

Runs AFTER patch_bootstrap_ids.py, on the already id-rewritten zip. Requires
`nix` (flakes + nix-command experimental features) on PATH and network
access to realize the declared attrs -- CI-time only, never on the phone.

This bootstrap format is TermuxInstaller-derived (see that class's extraction
loop): a plain zip entry carries no permission bits at all -- ZipInputStream
ignores them -- so two manifest entries do the real work instead:
  SYMLINKS.txt     "<target>←<link-path-relative-to-root>" per line
  EXECUTABLES.txt  one link-path-relative-to-root per line, chmod 0700 each
Regular files therefore need no special handling; every symlink and every
executable this script adds MUST go through one of those two manifests, or
the installed app will silently ship a plain-file copy of a symlink, or a
binary nothing ever chmods +x.

Usage:
    bake_default_packages.py <input.zip> <output.zip> <nixpkgs_pin> \
        <comma-separated attrs> <profile_link> <fallback_init_script> <app_id> \
        <nix_system, e.g. aarch64-linux> <shared_root_name> <login_shell_attr> \
        <dns resolv_conf> <dns nameservers csv> <extras expr> <extras attrs csv> <preload>

#612 also patches bin/login here (not a separate script): it is the same
"add text to a generated file" job as the login-inner patch above, on a zip
this function already has open.
"""
import importlib.util
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# #644 -- the declarative link store, shared with ac_cloud-termux. Located from
# this file rather than passed in, so wiring it needed no new gradle argument and
# no edit to cloud-nix-on-droid-fork-engine.sh (whose vendored build.sh copy must
# stay byte-identical to it).
STORE_SRC = Path(__file__).resolve().parents[5] / "ab_cloud-terminal-store"


def load_render_store():
    """ab_cloud-terminal-store/render-store.py, imported by path (its name has a dash)."""
    path = STORE_SRC / "render-store.py"
    if not path.is_file():
        raise ValueError(f"the shared link store is missing: {path}")
    spec = importlib.util.spec_from_file_location("render_store", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_linux_tools():
    """ab_cloud-terminal-store/fetch-linux-tools.py, imported by path: the one fetcher both bakes use."""
    path = STORE_SRC / "fetch-linux-tools.py"
    if not path.is_file():
        raise ValueError(f"the shared linux-tools fetcher is missing: {path}")
    spec = importlib.util.spec_from_file_location("fetch_linux_tools", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def profile_missing(generation: str, tools) -> list:
    """#644 -- declared tool names with nothing behind them in the realized profile.

    default_packages.binaries is maintained beside attrs and provides and can
    drift from both; a drifted name becomes a link that dangles on every phone,
    which is the #638/#640/#641 shape one layer up. Pure, so the tester proves
    both verdicts without a nix profile.
    """
    return [t for t in tools
            if not os.path.islink(os.path.join(generation, "bin", t))
            and not os.path.exists(os.path.join(generation, "bin", t))]


def store_init_line(install_dir: str) -> str:
    """#644 -- the line that makes a login initialise, verify and repair the store.

    Guarded on readability, so a bootstrap built before this block (or one whose
    store failed to bake) still reaches a shell: the store is worth a terminal's
    tooling, never a terminal's existence. Pure str -> str like every other patch
    here, so the tester asserts the real line.
    """
    return (f'if [ -r "/{install_dir}/login-init.sh" ]; then\n'
            f'  . "/{install_dir}/login-init.sh"\n'
            'fi')

SESSION_INIT_TEMPLATE = (
    '. "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/'
    'nix-on-droid-session-init.sh"'
)
DROPPED_ENTRY = "etc/static/UNINTIALISED"
# The real file behind the etc/profile symlink (SYMLINKS.txt: /etc/static/profile←etc/profile).
ETC_PROFILE_ENTRY = "etc/static/profile"

# #638 -- the two UNCONDITIONAL execs through /usr/bin/env that upstream's
# generated login-inner carries, verbatim. /usr/bin/env is NOT in the bootstrap
# zip: `usr/bin/` is an empty directory in it and SYMLINKS.txt has no line for
# env. On a stock nix-on-droid install that path only appears once the
# first-login wizard has run `nix-on-droid switch`, which symlinks it into
# coreutils. Dropping DROPPED_ENTRY above is exactly what stops that wizard from
# running, so on a fresh install of THIS app both lines below exec a file that
# does not exist -- `/usr/bin/env: No such file or directory`, exit 127, no
# shell, ever. Guarding the first one lets execution fall through to the
# usershell block right below it, which execs an absolute /nix/store bash path
# this zip really does ship (and really does chmod +x via EXECUTABLES.txt).
ENV_EXEC = "exec /usr/bin/env bash  # otherwise it'll be a limited bash that came with Nix"
ENV_EXEC_ARGV = 'exec /usr/bin/env "$@"'

# #640 -- the guards above keep the TERMINAL booting when /usr/bin/env is absent,
# and they stay: falling through to an absolute /nix/store bash is strictly better
# than an unconditional exec either way. But they fix the boot only. `#!/usr/bin/env
# <interp>` is the most common shebang there is, and with that path absent EVERY
# such script in the shell dies "No such file or directory" -- so the file itself
# has to exist. bin/login binds files/usr/usr onto /usr, so this link path, once
# extracted into $PREFIX/usr, IS /usr/bin/env as the kernel resolves a shebang.
ENV_SYMLINK = "usr/bin/env"


def env_symlink_line(profile_link: str) -> str:
    """The SYMLINKS.txt line that makes /usr/bin/env exist on a fresh install.

    The target is derived from profile_link -- the SAME declaration that produces
    the default-profile symlink below -- so no /nix/store hash is ever written
    here: the nixpkgs pin moves, every hash under it moves, and a literal would
    rot into a dangling link silently.

    Pure str -> str like patch_login_inner, so test/test-bootstrap-baked.sh can
    build a sandbox out of the REAL line and EXECUTE a `#!/usr/bin/env sh` script
    through it, with no nix and no bootstrap zip.
    """
    return f"/{profile_link}/bin/env←{ENV_SYMLINK}"


def env_target_unreachable(env_rel, existing, new_files, new_executables):
    """None when the resolved coreutils `env` really ships AND gets chmod +x.

    A symlink onto an absent or non-executable target is WORSE than no symlink at
    all: it converts a clean "no such file or directory" into a permission error
    nothing in the shell explains. Pure, so the tester can prove both verdicts.
    """
    if env_rel in existing:
        return None  # already shipped by the input zip, with its own manifest lines
    if env_rel not in new_files:
        return f"{env_rel} is not a file entry in the output zip"
    if env_rel not in new_executables:
        return f"{env_rel} is not in EXECUTABLES.txt, so nothing ever chmods it +x"
    return None


# ── ~/flakes/{system,hm} and the Android host tools (flakes/flakes.json) ──────
FLAKES_SRC = Path(__file__).resolve().parents[4] / "flakes"
BOOTSTRAP_SRC_JSON = Path(__file__).resolve().parents[4] / "bootstrap-src" / "bootstrap-src.json"
ETC_ZSHENV_ENTRY = "etc/static/zshenv"
_PLACEHOLDER = re.compile(r"@[A-Z_]+@")


def load_flakes_decl(src: Path = FLAKES_SRC) -> dict:
    path = src / "flakes.json"
    if not path.is_file():
        raise ValueError(f"the flakes declaration is missing: {path}")
    return json.loads(path.read_text())


def flake_home(app_id: str) -> str:
    """$HOME inside the session (upstream nix-on-droid's user.home, after the id rewrite)."""
    return f"/data/data/{app_id}/files/home"


EXTRAS_NIX = Path(__file__).resolve().parent / "rootfs-extras.nix"
ROOTFS_JSON = Path(__file__).resolve().parents[5] / "ac_cloud-termux" / "rootfs" / "rootfs.json"
# rootfs-extras.nix reaches its three inputs by repo-relative paths; in ~/flakes/system they
# sit beside it. Each rewrite must match exactly once, or the copy would read a path the
# phone does not have.
EXTRAS_REWRITES = (
    ("../../../../../ac_cloud-termux/rootfs/rootfs.json", "./rootfs.json"),
    ("(builtins.fromJSON (builtins.readFile ../../../../build.json))"
     ".forks.nixdroid.bootstrap.default_packages.extras",
     "builtins.fromJSON (builtins.readFile ./extras.json)"),
    ("../../../../../ab_cloud-terminal-store/noexec-shebang.c", "./noexec-shebang.c"),
    ("../../../../../ab_cloud-libs-shared/libs/fleet-webserver", "./fleet-webserver"),
)
# The shared web server's build inputs (Option 37.1), staged as ~/flakes/system/fleet-webserver/*
# so `nix-on-droid switch` builds the same fleet-webserver the bake does, from the same bytes.
WEBSERVER_LIB = Path(__file__).resolve().parents[5] / "ab_cloud-libs-shared" / "libs" / "fleet-webserver"
WEBSERVER_FILES = ("Cargo.toml", "Cargo.lock", "build.rs", "assets", "vendor", "src/api.rs", "src/files.rs",
                   "src/http.rs", "src/lib.rs", "src/main.rs", "src/render.rs", "src/sha256.rs",
                   "src/write.rs")


def webserver_files() -> dict:
    """'fleet-webserver/<relpath>' -> bytes for every WEBSERVER_FILES entry (a directory: all of it)."""
    out = {}
    for entry in WEBSERVER_FILES:
        path = WEBSERVER_LIB / entry
        if not path.exists():
            raise ValueError(f"the shared webserver lib is missing {path}")
        for f in sorted([path] if path.is_file() else (q for q in path.rglob("*") if q.is_file())):
            out[f"fleet-webserver/{f.relative_to(WEBSERVER_LIB).as_posix()}"] = f.read_bytes()
    return out


def extras_files(extras_decl: dict) -> dict:
    """name -> bytes: a self-contained rootfs-extras.nix for ~/flakes/system and its inputs,
    so `nix-on-droid switch` builds goose, hermes, the noexec shim (and every other
    default_packages.extras attr) from the SAME expression and pins the bake uses."""
    text = EXTRAS_NIX.read_text()
    for old, new in EXTRAS_REWRITES:
        if text.count(old) != 1:
            raise ValueError(f"rootfs-extras.nix: expected exactly one {old!r} to rewrite for ~/flakes/system")
        text = text.replace(old, new)
    if any("../" in l for l in text.splitlines() if not l.lstrip().startswith("#")):
        raise ValueError("rootfs-extras.nix still reads a repo-relative ../ path after the rewrite")
    return {
        "rootfs-extras.nix": text.encode(),
        "extras.json": (json.dumps(extras_decl, indent=2, sort_keys=True) + "\n").encode(),
        "rootfs.json": ROOTFS_JSON.read_bytes(),
        "noexec-shebang.c": (STORE_SRC / "noexec-shebang.c").read_bytes(),
        **webserver_files(),
    }


def render_flakes(decl: dict, app_id: str, pin: str, nod_rev: str, attrs, login_shell: str,
                  nix_system: str, extras_attrs, extras_decl: dict, src: Path = FLAKES_SRC) -> dict:
    """zip path -> (bytes, executable) for the seed script and the ~/flakes templates.

    Pure apart from reading src, so the tester renders the REAL templates. Raises on a
    placeholder left unfilled: a flake.nix naming @NIXPKGS_PIN@ would only fail on the
    phone, at the user's first switch.
    """
    install = decl["install_dir"].strip("/")
    tools = decl["android_host"]["tools"]
    values = {
        "NIXPKGS_PIN": pin,
        "NOD_REV": nod_rev,
        "HOME": flake_home(app_id),
        "HOME_DIR": decl["home_dir"],
        "USER_NAME": decl["user_name"],
        "NIX_SYSTEM": nix_system,
        "LOGIN_SHELL": login_shell,
        "PACKAGES": "\n".join(f'    pkgs."{a}"' for a in attrs),
        "HOST_TOOLS": " ".join(tools),
        "EXTRAS": "\n".join(f'    extras."{a}"' for a in extras_attrs),
    }
    out = {f"{install}/seed.sh": ((src / "seed.sh").read_bytes(), True)}
    for name, data in extras_files(extras_decl).items():
        out[f"{install}/system/{name}"] = (data, False)
    for dest, tmpl in decl["templates"].items():
        text = (src / tmpl).read_text()
        if tmpl.endswith(".in"):
            text = _PLACEHOLDER.sub(lambda m: values.get(m.group(0).strip("@"), m.group(0)), text)
            left = _PLACEHOLDER.findall(text)
            if left:
                raise ValueError(f"flakes/{tmpl}: unfilled placeholders {sorted(set(left))}")
        out[f"{install}/{dest}"] = (text.encode(), dest.endswith("android-host-exec"))
    return out


def android_host_files(decl: dict, src: Path = FLAKES_SRC) -> dict:
    """zip path -> (bytes, executable): one copy of android-host-exec per tool name
    under android_host.bin_dir (copies, not links: the wrapper reads its own $0)."""
    host = decl["android_host"]
    data = (src / "android-host-exec").read_bytes()
    out = {f"{host['bin_dir'].strip('/')}/android-host-exec": (data, True)}
    for t in host["tools"]:
        out[f"{host['bin_dir'].strip('/')}/{t}"] = (data, True)
    return out


def flakes_seed_line(install_dir: str, home_dir: str) -> str:
    """The login-inner line that seeds ~/<home_dir>; guarded and never fatal."""
    install_dir = install_dir.strip("/")
    return (f'if [ -r "/{install_dir}/seed.sh" ]; then\n'
            f'  sh "/{install_dir}/seed.sh" "{home_dir}" || true\n'
            'fi')


def patch_login_inner(login_inner: str, app_id: str, fallback_script: str,
                      profile_link: str, login_shell: str, store_install_dir: str = "",
                      flakes_line: str = "") -> str:
    """The text edits #595/#638/#641 make to the generated usr/lib/login-inner.

    Pure str -> str so test/test-bootstrap-baked.sh can run the REAL patch
    offline, with no nix and no bootstrap zip, and then EXECUTE the result.
    Raises ValueError on anything it does not recognise: a silently unpatched
    login-inner is precisely how #638 shipped a boot crash behind a green CI.
    """
    want = SESSION_INIT_TEMPLATE.format(app_id=app_id)
    if want not in login_inner:
        raise ValueError(f"expected session-init line not found in usr/lib/login-inner:\n  {want}")
    head, _, tail = login_inner.rpartition(want)
    fallback_line = (
        'cloud_trace "session init: sourcing"\n'
        f'if [ -e "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh" ]; then\n  {want}\nelse\n  . /{fallback_script}\nfi\n'
        'cloud_trace "session init: done"')
    # #644 -- and the store, UNCONDITIONALLY: outside the if/else above, because
    # the branch that runs depends on whether a real nix-on-droid generation was
    # ever built, while the link store has to be initialised and verified either
    # way. It goes after, so the profile PATH the fallback exports is already set.
    if store_install_dir:
        fallback_line = (fallback_line + '\ncloud_trace "store init: start"\n'
                         + store_init_line(store_install_dir) + '\ncloud_trace "store init: done"')
    if flakes_line:
        fallback_line += '\n' + flakes_line + '\ncloud_trace "flakes seed: done"'
    login_inner = head + fallback_line + tail
    login_inner = add_login_trace(login_inner)

    # #641 -- this exec GOES, it is not merely guarded any more, and #640 is why:
    # the moment /usr/bin/env exists the #638 guard becomes TRUE, this line fires,
    # and login-inner execs bash and never reaches the usershell block below it.
    # Guarding it kept the terminal alive when env was missing; keeping it now
    # would make bash the permanent login shell and #641's fish unreachable. What
    # replaces it is upstream's OWN shell selection right below -- which still
    # tests -x and still falls back to bash -- so nothing is loosened: the fix is
    # strictly upstream's mechanism instead of upstream's shortcut.
    if login_inner.count(ENV_EXEC) != 1:
        raise ValueError(f"expected exactly one unconditional env exec in usr/lib/login-inner:\n  {ENV_EXEC}")
    login_inner = login_inner.replace(
        ENV_EXEC,
        "# #641: the `exec /usr/bin/env bash` upstream puts here is dropped, so the\n"
        "# usershell block below chooses the login shell (and still falls back to bash).",
        1)

    # The "called with arguments" path (RunCommandService, `login <cmd>`) has the
    # same dependency and no block below it to fall through to. bin/sh here IS
    # bash, whose exec already resolves its argv against PATH, so env adds
    # nothing but the missing file.
    if login_inner.count(ENV_EXEC_ARGV) != 1:
        raise ValueError(f"expected exactly one env exec of the caller's argv:\n  {ENV_EXEC_ARGV}")
    login_inner = login_inner.replace(ENV_EXEC_ARGV, 'exec "$@"', 1)

    require_fish_login_shell(login_shell)
    return probe_usershell_exec(
        retarget_usershell(login_inner, profile_link, login_shell))


# The post-banner hang -- the LAST statement a login runs is upstream's
# `exec -a "-${usershell##*/}" "$usershell"`, and it is UNBOUNDED: upstream's -x
# test only proves the file is executable, not that the shell can run. A fish
# that wedges under the old proot-static (rust fish 4.x has form there) is a
# blank screen forever, after the welcome banner, with nothing printed. Both
# literals below are module constants so the tester can un-apply the probe
# textually and prove its own assertion discriminates.
USERSHELL_EXEC = 'exec -a "-${usershell##*/}" "$usershell"'
# The probe above passed on the phone and fish STILL drew no prompt: `-c exit`
# never reaches the interactive half of fish's startup, which is where the
# device wedged (tty left in cooked mode, no child, forever). So the shell is no
# longer exec'd blind. It runs as a foreground job beside a watchdog, and fish
# proves it reached the reader by writing $CLOUD_LOGIN_PROMPT_SEEN from its first
# fish_prompt event. No proof within the deadline -> one named stderr line, the
# shell is killed, bash. `set -m` (only with a tty) makes bash hand the terminal
# to the job and take it back when the job dies, so the bash after a kill is
# never left in a background process group. The deadline outlasts fish's own
# 10 s wait for a terminal that ignores its startup queries.
USERSHELL_PROMPT_DEADLINE_S = 20
# The bash tier needs no terminal handshake, so it gets half the time.
BASH_PROMPT_DEADLINE_S = 10
USERSHELL_PROMPT_BOUNDED = (
    f'cloud_try_shell "$usershell" "${{CLOUD_LOGIN_PROMPT_DEADLINE:-{USERSHELL_PROMPT_DEADLINE_S}}}" bash '
    '"-${usershell##*/}" "$usershell" --init-command \''
    'echo "[fish] config read, init-command ran" >>$CLOUD_LOGIN_LOG; '
    'function __cloud_prompt_seen --on-event fish_prompt; functions -e __cloud_prompt_seen; '
    'echo "[fish] first prompt" >>$CLOUD_LOGIN_LOG; '
    'true >$CLOUD_LOGIN_PROMPT_SEEN; set -e CLOUD_LOGIN_PROMPT_SEEN; end\''
)
# #715 -- v0.3.11 fell back to `exec -l bash` and the phone showed no bash prompt
# either, so the blocker is not fish. Two more tiers, each a bisection step:
#   2. bash with NO profile and NO rc, bounded like fish (PROMPT_COMMAND is the
#      marker, and with --norc nothing can override it). If this prompts, the
#      blocker is in the profile/env layer; if it does not, it is below any
#      shell's own scripts.
#   3. rescue: bash running /dev/stdin as a SCRIPT. Non-interactive, so it skips
#      every interactive-only step (job-control handshake, tty modes, line
#      editing) and simply executes each line typed. If even this does not run
#      a typed line, the blocker is below the shell (proot / the kernel).
USERSHELL_FALLBACK = (
    'export PROMPT_COMMAND=\': >"$CLOUD_LOGIN_PROMPT_SEEN"; unset PROMPT_COMMAND CLOUD_LOGIN_PROMPT_SEEN\'\n'
    f'  cloud_try_shell bash "${{CLOUD_LOGIN_BASH_DEADLINE:-{BASH_PROMPT_DEADLINE_S}}}" "rescue mode" '
    '-bash bash --noprofile --norc -i\n'
    '  unset PROMPT_COMMAND\n'
    '  trap \'\' TTOU; stty sane 2>/dev/null || true; trap - TTOU\n'
    '  cloud_trace "rescue: $(cloud_proc $$)"\n'
    '  echo "⚠ rescue mode: no shell drew a prompt; each line typed still runs (no prompt, no editing). Log: $CLOUD_LOGIN_LOG" >&2\n'
    '  exec bash --noprofile --norc /dev/stdin'
)
USERSHELL_PROBE = (
    'cloud_trace "probe: $usershell -c exit (bounded 5s)"\n'
    '  if ! command -v timeout >/dev/null 2>&1 '
    '|| timeout 5 "$usershell" -c exit >/dev/null 2>&1; then\n'
    '    cloud_trace "probe: passed"\n'
    f'    {USERSHELL_PROMPT_BOUNDED}\n'
    '  else\n'
    '    echo "⚠ login shell $usershell failed its 5s liveness probe; falling back to bash" >&2\n'
    '  fi\n'
    f'  {USERSHELL_FALLBACK}'
)

# #715 -- the trace every step above writes through, plus the bounded start each
# shell tier goes through. Inserted right after login-inner's `set -eo pipefail`
# (bin/sh there is bash 5.2, so EPOCHREALTIME and printf %()T are builtins and
# nothing here needs PATH, which is not set yet).
#
# cloud_proc reads /proc/<pid>/stat: pgrp vs tpgid (the tty's FOREGROUND process
# group) is the job-control question. A shell whose pgrp is not the tty's
# foreground pgrp stops itself with SIGTTIN before its prompt -- and under proot
# its kill(0, SIGTTIN) stops proot too. Measured off-device with the shipped
# proot-static: that gives exactly the phone's screen (typed line echoed by the
# cooked tty, never run, shell with no child). state/wchan/syscall/signal masks
# at the deadline then say whether a hung shell is STOPPED (T/t: job control) or
# SLEEPING in a syscall (S + the syscall number: what it is blocked on).
#
# Screen lines only for an interactive login ($# = 0); a `login <cmd>` caller
# (RunCommandService) gets its command's output and nothing else, while the file
# still gets the lines.
LOGIN_TRACE_ANCHOR = "set -eo pipefail\n"
LOGIN_TRACE = r"""
CLOUD_LOGIN_LOG="${CLOUD_LOGIN_LOG:-${HOME:-/tmp}/.cloud-login.log}"
export CLOUD_LOGIN_LOG
if [ "$#" -eq 0 ]; then CLOUD_LOGIN_SCREEN=1; else CLOUD_LOGIN_SCREEN=; fi
cloud_trace() {
  local t
  printf -v t '%(%H:%M:%S)T.%s' -1 "${EPOCHREALTIME#*.}"
  printf '[login %s] %s\n' "${t:0:12}" "$*" >> "$CLOUD_LOGIN_LOG" 2>/dev/null || true
  if [ -n "$CLOUD_LOGIN_SCREEN" ]; then printf '[login %s] %s\n' "${t:0:12}" "$*" >&2 || true; fi
}
cloud_proc() {
  local st="" w="?" sc="?" k v sig="" fg
  { read -r st < "/proc/$1/stat"; } 2>/dev/null || { echo "pid $1: gone"; return 0; }
  set -- "$1" ${st##*) }
  { read -r w < "/proc/$1/wchan"; } 2>/dev/null || true
  { read -r sc _ < "/proc/$1/syscall"; } 2>/dev/null || true
  while read -r k v; do
    case "$k" in State:|SigPnd:|ShdPnd:|SigBlk:|SigIgn:|TracerPid:) sig="$sig ${k%:}=${v%% *}";; esac
  done 2>/dev/null < "/proc/$1/status" || true
  if [ "$7" = "-1" ]; then fg="NO-CONTROLLING-TTY"
  elif [ "$4" = "$7" ]; then fg="FOREGROUND"
  else fg="BACKGROUND(tty foreground pgrp $7)"; fi
  echo "pid $1 ppid=$3 pgrp=$4 sid=$5 tpgid=$7 $fg wchan=${w:-?} syscall=${sc:-?}$sig"
}
cloud_tty() {
  local - m o=""
  set -f
  m="$(stty -a 2>&1)" || { echo "tty: stty failed: $m"; return 0; }
  for w in $m; do case "$w" in icanon|-icanon|echo|-echo|isig|-isig) o="$o $w";; esac; done
  echo "tty modes:$o"
}
# $1 the shell's name in messages, $2 the deadline (s), $3 the next tier's name,
# then exec -a's argv0 and the command. Returns only when the shell never drew a
# prompt; a shell that did ends the login with its own status.
cloud_try_shell() {
  local name="$1" deadline="$2" next="$3" argv0="$4" rc=0 watchdog why
  shift 4
  CLOUD_LOGIN_PROMPT_SEEN="${TMPDIR:-/tmp}/.cloud-login-prompt.$$"
  export CLOUD_LOGIN_PROMPT_SEEN
  rm -f "$CLOUD_LOGIN_PROMPT_SEEN" "$CLOUD_LOGIN_PROMPT_SEEN.pid" "$CLOUD_LOGIN_PROMPT_SEEN.late"
  # set -m (only with a tty) makes bash hand the terminal to the job and take it
  # back when the job dies, so the next tier is never left in a background pgrp.
  if [ -t 0 ]; then set -m; fi
  # TTOU ignored: with TOSTOP on, a background write to the tty would stop the
  # watchdog itself, and a stopped watchdog never kills the hung shell.
  ( trap '' TTOU
    sleep "$deadline"
    if [ ! -e "$CLOUD_LOGIN_PROMPT_SEEN" ]; then
      : > "$CLOUD_LOGIN_PROMPT_SEEN.late"
      p="$(cat "$CLOUD_LOGIN_PROMPT_SEEN.pid" 2>/dev/null)" || p=""
      cloud_trace "$name: no prompt after ${deadline}s: $(cloud_proc "$p"); $(cloud_tty)"
      kill -KILL "$p" 2>/dev/null || true
    fi ) &
  watchdog=$!
  # The job is a waiter bash whose own stderr is /dev/null, so bash's "Killed"
  # report about the shell lands there; the shell itself (exec'd one level
  # down, so its pid is known) takes the terminal's stderr back from fd 3.
  # The waiter, not this shell, is what parks stderr: under set -m bash hands
  # the terminal over through its OWN stderr, and redirecting it here left the
  # shell in a background process group (SIGTTIN, status 149).
  ( exec 3>&2 2>/dev/null
    ( exec 2>&3 3>&-
      me=$BASHPID
      echo "$me" > "$CLOUD_LOGIN_PROMPT_SEEN.pid"
      cloud_trace "$name: starting, $(cloud_proc "$me")"
      exec -a "$argv0" "$@" ) || exit $? ) || rc=$?
  kill "$watchdog" 2>/dev/null || true
  set +m
  if [ -e "$CLOUD_LOGIN_PROMPT_SEEN" ]; then
    rm -f "$CLOUD_LOGIN_PROMPT_SEEN" "$CLOUD_LOGIN_PROMPT_SEEN.pid"
    cloud_trace "$name: drew its prompt, session ended with status $rc"
    exit "$rc"
  fi
  why="ended (status $rc) before drawing a prompt"
  if [ -e "$CLOUD_LOGIN_PROMPT_SEEN.late" ]; then why="drew no prompt within ${deadline}s"; fi
  # A shell STOPPED by job control (status 149: SIGTTIN) is still alive; the next tier must not inherit it.
  kill -KILL "$(cat "$CLOUD_LOGIN_PROMPT_SEEN.pid" 2>/dev/null)" 2>/dev/null || true
  rm -f "$CLOUD_LOGIN_PROMPT_SEEN.pid" "$CLOUD_LOGIN_PROMPT_SEEN.late"
  cloud_trace "$name: $why"
  echo "⚠ login shell $name $why; falling back to $next" >&2
}
cloud_trace "login-inner: start, $(cloud_proc $$); stdin is $([ -t 0 ] || printf 'NOT ')a tty"
cloud_trace "login-inner: parent (proot) $(cloud_proc $PPID)"
"""


# #715 -- the Android-side half of the trace (bin/login is /system/bin/sh, i.e.
# mksh, and runs BEFORE proot). It starts the log fresh for each interactive
# login, records whether the session leader owns the tty before proot is even
# exec'd, and states the storage probe's real error instead of only a verdict.
BIN_LOGIN_TRACE = r"""CLOUD_LOGIN_LOG="$HOME/.cloud-login.log"
if [ "$#" -eq 0 ]; then CLOUD_LOGIN_SCREEN=1; : > "$CLOUD_LOGIN_LOG" 2>/dev/null || true; else CLOUD_LOGIN_SCREEN=; fi
cloud_trace() {
  m="[login $(/system/bin/date +%H:%M:%S 2>/dev/null || echo '?') android] $*"
  echo "$m" >> "$CLOUD_LOGIN_LOG" 2>/dev/null || true
  if [ -n "$CLOUD_LOGIN_SCREEN" ]; then echo "$m" >&2; fi
}
cloud_jobctl() {
  st=""
  { read -r st < "/proc/$1/stat"; } 2>/dev/null || { echo "pid $1: gone"; return 0; }
  set -- "$1" ${st##*) }
  echo "pid $1 state=$2 pgrp=$4 sid=$5 tpgid=$7"
}
cloud_trace "bin/login: start, $(cloud_jobctl $$)"
"""


# The ordered candidates for the shared store's root: the raw volume, the pass-through FUSE, the
# androidwritable view, then the ordinary (slow) /storage/emulated/0 as the fallback. MIRRORS the
# `for cand in ...` list in ac_cloud-termux/rootfs/enter.sh; test/test-shared-store-probe.sh fails
# if the two drift.
SHARED_STORE_PROBE_ROOTS = (
    "/data/media/0",
    "/mnt/pass_through/0/emulated/0",
    "/mnt/androidwritable/0/emulated/0",
    "/storage/emulated/0",
)


# cloud-shared-store (ac_cloud-termux/build.json::shared_store): the store's owner package and its
# folder in the owner's $HOME. MIRRORS that declaration; test/test-shared-store-probe.sh fails on drift.
STORE_OWNER_PACKAGE = "cld.termux"
STORE_DIR = "cloud-shared-store"
LEGACY_STORE_NAME = "cloud-drive-shared-store"  # legacy_store_migration: mirrors build.json::shared_store.legacy_name
STORE_SFTP_PORT = 8023  # the owner's sshd (the watchdog's declared termux backend), the EXEC-less fallback

# legacy_store_migration: the ONE-TIME, idempotent move of the store's OLD name into the store (build.json::
# shared_store.legacy_name). Same text as ac_cloud-termux/rootfs/enter.sh's block with every command spelled
# /system/bin/<cmd> (test/test-shared-store.sh pins that), because bin/login runs before PATH has anything.
LEGACY_STORE_MIGRATION = r'''# legacy_store_migration begin (build.json::shared_store.legacy_name): one-time, idempotent, never deletes user data.
# The old store name is only ever an INPUT here: whatever is under it is merged into the store and the old
# link/dir goes. test/test-shared-store.sh pins this block equal to cloud-terminal-nix's (bake_default_packages.py).
legacy_store_merge() {  # $1 = dir emptied into $2; $3 = where a differing file of the same name is kept
    for e in "$1"/* "$1"/.[!.]* "$1"/..?*; do
        [ -e "$e" ] || [ -L "$e" ] || continue
        n="${e##*/}"
        if [ ! -e "$2/$n" ] && [ ! -L "$2/$n" ]; then /system/bin/mv "$e" "$2/$n"
        elif [ -d "$e" ] && [ ! -L "$e" ] && [ -d "$2/$n" ] && [ ! -L "$2/$n" ]; then
            ( legacy_store_merge "$e" "$2/$n" "$3/$n" ); /system/bin/rmdir "$e" 2>/dev/null || true
        elif [ -f "$e" ] && [ ! -L "$e" ] && [ -f "$2/$n" ] && /system/bin/cmp -s "$e" "$2/$n"; then /system/bin/rm -f "$e"
        else
            /system/bin/mkdir -p "$3"; t="$3/$n"; [ ! -e "$t" ] && [ ! -L "$t" ] || t="$t.$$"
            /system/bin/mv "$e" "$t"
        fi
    done
}
legacy_store_migrate() {  # $1 = the old name in $HOME, $2 = the store
    if [ -L "$1" ]; then
        if [ ! -e "$2" ] && [ ! -L "$2" ]; then /system/bin/mv "$1" "$2"; else /system/bin/rm -f "$1"; fi
        return 0
    fi
    [ -d "$1" ] || return 0
    /system/bin/mkdir -p "$2" 2>/dev/null || true
    legacy_store_merge "$1" "$2" "$2/.legacy-backup-$(/system/bin/date +%Y%m%d)"
    /system/bin/rmdir "$1" 2>/dev/null || echo "⚠ $1 still has content or is a mount point; it is left in place and migrated again at the next login" >&2
}
# legacy_store_migration end
'''


# startup-step: storage_links
def storage_setup(shared_root_name: str, roots=SHARED_STORE_PROBE_ROOTS,
                  owner: str = STORE_OWNER_PACKAGE, store_dir: str = STORE_DIR) -> str:
    """#612/#736: bin/login's shared-storage block. ~/emulated and ~/cloud-shared-store are
    SYMLINKS to /storage/emulated/0 and its <shared_root_name> store. bin/login runs proot with no
    -r (it binds individual dirs onto the real Android root, it does not chroot), so $HOME and
    /storage are the same paths inside the session as outside it and a plain link needs no bind;
    the binds this replaces only ever showed the terminal what the mount step happened to leave.

    /storage/emulated/0 exists as a directory even without the storage grant, but is then not
    traversable, so readability (ls), not existence, is probed; when it fails ls's own error is
    traced and ONE line says why. #730: nothing is left that reads as an empty STORE -- only our
    link or an EMPTY directory is ever removed (rmdir never removes content). The upstream ~/storage
    tree (termux-setup-storage's links) is a second entry for the same storage, so its symlinks go.

    #748: EVERY command here that is not a shell builtin is named by its /system/bin path, as
    upstream's own bin/login does for pgrep and mv. bin/login is mksh on the Android host, before
    proot, and its PATH is the session's $PREFIX/bin -- which in this rootfs holds only login,
    proot-static and an sh symlink into /nix/store that does not resolve outside proot. A bare `rm`
    is "inaccessible or not found", exit 127, and under set -e that ends every login: the phone
    showed `usr/bin/login[62]: rm: inaccessible or not found` for every command once an older
    install's ~/storage tree gave the cleanup loop a link to remove.

    Fast path (mirrors ac_cloud-termux/rootfs/enter.sh): /storage/emulated/0 is the sdcardfs/FUSE
    view, and git on it stalled sessions for minutes. SHARED_STORE_PROBE_ROOTS are the same volume
    through cheaper paths; the first whose store is readable and passes an rw probe (mkdir -p, touch,
    rm of a temp file) gets both links. /storage/emulated/0 is last, so it is the fallback, and the
    "not mounted" message stays for when it is not even readable. test/test-shared-store-probe.sh
    runs this text over stub dirs and guards that the list matches termux's.

    cloud-shared-store: ~/cloud-shared-store is THE store in cld.termux's private home, reached
    DIRECTLY because this app runs as the same uid (android:sharedUserId cld.termux.shared). The
    owner's home is derived from OUR $HOME by swapping the package segment (a second Android user
    has /data/user/<n>/...), so no absolute path is baked. When the shared uid is NOT in effect the
    directory does not answer: the link then falls back to the LEGACY CloudDrive mirror found by the
    probe above (fallback only) and one line names the live store over the owner's sshd (SFTP 8023).
    The store's old name is migrated into it once and removed (LEGACY_STORE_MIGRATION)."""
    fallback = roots[-1]
    cands = " ".join(roots)
    # startup-step: shared_store
    store = (
        f'CLOUD_STORE_OWNER="${{HOME%/*/files/home}}/{owner}/files/home/{store_dir}"\n'
        'if [ -d "$CLOUD_STORE_OWNER" ] && [ -r "$CLOUD_STORE_OWNER" ] && [ -w "$CLOUD_STORE_OWNER" ] && [ -x "$CLOUD_STORE_OWNER" ]; then\n'
        '  CLOUD_SHARED_STORE_MODE=direct\n'
        f'  cloud_storage_link "$HOME/{store_dir}" "$CLOUD_STORE_OWNER"\n'
        'else\n'
        '  CLOUD_SHARED_STORE_MODE=sftp\n'
        f'  if [ -n "$CLOUD_SDCARD" ] && [ -d "$CLOUD_SDCARD/{shared_root_name}" ]; then\n'
        f'    cloud_storage_link "$HOME/{store_dir}" "$CLOUD_SDCARD/{shared_root_name}"\n'
        '  else\n'
        f'    cloud_storage_unlink "$HOME/{store_dir}"\n'
        '  fi\n'
        f'  echo "⚠ ~/{store_dir} is the {shared_root_name} mirror (no shared uid yet: reinstall {owner} with it); live store: sftp -P {STORE_SFTP_PORT} 127.0.0.1:{store_dir}/" >&2\n'
        'fi\n'
        'cloud_trace "cloud-shared-store: $CLOUD_SHARED_STORE_MODE ($CLOUD_STORE_OWNER)"\n'
        f'export CLOUD_SHARED_STORE_MODE CLOUD_SHARED_STORE_SFTP="sftp://127.0.0.1:{STORE_SFTP_PORT}/~/{store_dir}"\n'
        + LEGACY_STORE_MIGRATION
        + f'legacy_store_migrate "$HOME/{LEGACY_STORE_NAME}" "$HOME/{store_dir}"  # legacy_store_migration\n'
    )
    return (
        'cloud_storage_link() {\n'
        '  [ -L "$1" ] || /system/bin/rmdir "$1" 2>/dev/null || true\n'
        '  if [ -e "$1" ] && [ ! -L "$1" ]; then\n'
        '    echo "⚠ $1 is a directory with content, so it is not replaced by the link to $2" >&2\n'
        '  else\n'
        '    /system/bin/ln -sfn "$2" "$1"\n'
        '  fi\n'
        '}\n'
        'cloud_storage_unlink() {\n'
        '  if [ -L "$1" ]; then /system/bin/rm -f "$1"; else /system/bin/rmdir "$1" 2>/dev/null || true; fi\n'
        '}\n'
        'if [ -d "$HOME/storage" ] && [ ! -L "$HOME/storage" ]; then\n'
        '  for l in "$HOME/storage"/*; do [ ! -L "$l" ] || /system/bin/rm -f "$l"; done\n'
        '  /system/bin/rmdir "$HOME/storage" 2>/dev/null || true\n'
        'fi\n'
        'CLOUD_SDCARD=""\n'
        f'for cand in {cands}; do\n'
        f'  t="$cand/{shared_root_name}/.cloud-rw-probe.$$"\n'
        f'  if [ -d "$cand" ] && /system/bin/mkdir -p "$cand/{shared_root_name}" 2>/dev/null \\\n'
        f'     && [ -r "$cand/{shared_root_name}" ] \\\n'
        '     && /system/bin/touch "$t" 2>/dev/null; then\n'
        '    /system/bin/rm -f "$t" 2>/dev/null || true\n'
        '    CLOUD_SDCARD="$cand"; break\n'
        '  fi\n'
        'done\n'
        'if [ -n "$CLOUD_SDCARD" ]; then\n'
        f'  if [ "$CLOUD_SDCARD" = "{fallback}" ]; then CLOUD_SPEED=fallback; else CLOUD_SPEED=fast; fi\n'
        f'  cloud_trace "legacy {shared_root_name} mirror: $CLOUD_SDCARD ($CLOUD_SPEED)"\n'
        '  cloud_storage_link "$HOME/emulated" "$CLOUD_SDCARD"\n'
        f'elif CLOUD_LS_ERR="$(/system/bin/ls {fallback} 2>&1 >/dev/null)"; then\n'
        f'  cloud_trace "legacy {shared_root_name} mirror: {fallback} (fallback, not writable)"\n'
        f'  cloud_storage_link "$HOME/emulated" {fallback}\n'
        f'  [ ! -d "{fallback}/{shared_root_name}" ] || CLOUD_SDCARD={fallback}\n'
        'else\n'
        f'  cloud_trace "storage: ls {fallback} failed: $CLOUD_LS_ERR"\n'
        '  cloud_storage_unlink "$HOME/emulated"\n'
        '  echo "⚠ ~/emulated: shared store not mounted, storage access is not granted. Allow it on the Cloud Terminal prompt, then open a new session." >&2\n'
        'fi\n'
        + store
    )

# (part of startup-step storage_links: runs in the same pre-proot block)
def passwd_setup(app_id: str, shell: str, name: str = "nix-on-droid") -> str:
    """bin/login's /etc/passwd + /etc/group entry for the app's REAL uid/gid (Android assigns it at
    install, e.g. 10597, so nothing baked can match it): without it whoami says "cannot find name
    for user ID". /etc in the guest is files/usr/etc. Any older <name> line or line for the same id
    is replaced; an up-to-date file is left alone. mksh builtins + /system/bin tools only, never
    fails the login."""
    etc = f"/data/data/{app_id}/files/usr/etc"
    return (
        'cloud_ids_entry() {\n'
        '  [ -f "$1" ] && /system/bin/grep -qxF "$3" "$1" 2>/dev/null && return 0\n'
        '  { if [ -f "$1" ]; then while IFS= read -r l; do\n'
        f'      case "$l" in {name}:*) continue ;; esac\n'
        '      r="${l#*:*:}"; [ "${r%%:*}" = "$2" ] && continue\n'
        '      echo "$l"\n'
        '    done < "$1"; fi; echo "$3"; } > "$1.cloud.$$" && /system/bin/mv -f "$1.cloud.$$" "$1"\n'
        '}\n'
        f'CLOUD_ETC={etc}\n'
        'CLOUD_UID="$(/system/bin/id -u 2>/dev/null)" || CLOUD_UID=""\n'
        'CLOUD_GID="$(/system/bin/id -g 2>/dev/null)" || CLOUD_GID=""\n'
        'if [ -n "$CLOUD_UID" ] && [ -n "$CLOUD_GID" ]; then\n'
        f'  cloud_ids_entry "$CLOUD_ETC/passwd" "$CLOUD_UID" "{name}:x:$CLOUD_UID:$CLOUD_GID:{name}:$HOME:{shell}" 2>/dev/null || true\n'
        f'  cloud_ids_entry "$CLOUD_ETC/group" "$CLOUD_GID" "{name}:x:$CLOUD_GID:" 2>/dev/null || true\n'
        f'  cloud_trace "passwd: {name} = $CLOUD_UID:$CLOUD_GID"\n'
        'fi\n'
    )


def add_login_trace(login_inner: str) -> str:
    """#715 -- define the trace and the bounded shell start, and log the start.

    Pure str -> str like every patch here; raises on a drifted anchor rather
    than shipping a login-inner whose cloud_trace calls are undefined (which,
    under login-inner's set -e, would end every login at its first step).
    """
    if login_inner.count(LOGIN_TRACE_ANCHOR) != 1:
        raise ValueError(f"expected exactly one {LOGIN_TRACE_ANCHOR.strip()!r} in usr/lib/login-inner")
    return login_inner.replace(LOGIN_TRACE_ANCHOR, LOGIN_TRACE_ANCHOR + LOGIN_TRACE, 1)


def probe_usershell_exec(login_inner: str) -> str:
    """Bound the login-shell exec with a liveness probe; bash is the net.

    `timeout 5 "$usershell" -c exit` is the cheapest complete life sign a shell
    has: it must exec, parse and exit. A shell that cannot do that in 5s does
    not get the terminal -- the login says so in ONE stderr line and falls back
    to upstream's own `exec -l bash`, so a prompt ALWAYS arrives. When `timeout`
    itself is absent (a broken profile) the probe abstains rather than exiling a
    healthy shell to bash. Pure str -> str like every patch here, so the tester
    executes both verdicts.
    """
    if login_inner.count(USERSHELL_EXEC) != 1:
        raise ValueError("expected exactly one usershell exec in usr/lib/login-inner:\n"
                         f"  {USERSHELL_EXEC}")
    return login_inner.replace(USERSHELL_EXEC, USERSHELL_PROBE, 1)


def require_fish_login_shell(login_shell: str) -> None:
    """USERSHELL_PROMPT_BOUNDED hands the shell a fish --init-command; any other
    login shell would reject it and land in bash on every login, loudly but
    permanently. Fail the build instead of shipping that."""
    if login_shell != "fish":
        raise ValueError(f"login_shell_attr is {login_shell!r}, but the bounded login-shell "
                         "start speaks fish (--init-command / fish_prompt)")


def retarget_usershell(login_inner: str, profile_link: str, login_shell: str) -> str:
    """#641 -- upstream's generated `usershell=` points at the zip's own bash.

    MEASURED in the pinned zip: usershell="/nix/store/<hash>-bash-5.2-p15/bin/bash",
    and that literal appears again in the "Cannot execute shell ..." fallback
    message, so BOTH move -- a retargeted assignment with a message still naming
    bash is how a user is told the wrong thing about his own shell. The new value
    is derived from profile_link, never a /nix/store literal, for the same reason
    the env link is: every hash moves with the nixpkgs pin.
    """
    m = re.search(r'^usershell="([^"]+)"$', login_inner, re.M)
    if not m:
        raise ValueError('expected one `usershell="..."` assignment in usr/lib/login-inner')
    old = m.group(1)
    new = f"/{profile_link}/bin/{login_shell}"
    if old == new:
        raise ValueError(f"usershell is already {new}; the patch would be a silent no-op")
    return login_inner.replace(old, new)


def patch_etc_profile(etc_profile: str, fallback_script: str) -> str:
    """#641 -- the noise on EVERY login, and it is upstream's file, not ours.

    Diego's phone printed, verbatim:

      -bash: /nix/store/5pmf0nlk34…-nix-on-droid-session-init.sh/etc/profile.d/nix-on-droid-session-init.sh: No such file or directory

    (hash elided to a prefix on purpose -- the tester asserts no full store hash
    survives anywhere in this file, and a quoted one would defeat that check. The
    full line is in test-bootstrap-baked.sh's fixture, where it is the subject.)

    That is the ENTIRE content of etc/static/profile in the pinned zip (symlinked
    as etc/profile, read by the login bash), sourcing a store path that the zip
    does not contain -- `nix-on-droid switch` is what would build it, and skipping
    that wizard is this whole block's purpose. So the line can never succeed on a
    fresh install of this app. It is guarded exactly like login-inner's own
    session-init line, falling back to the baked PATH script, and NOT deleted: a
    phone that later does run a real nix-on-droid generation gets it back.
    """
    body = [l for l in etc_profile.splitlines() if l.strip()]
    if len(body) != 1 or not body[0].lstrip().startswith('. "') or '"' not in body[0][3:]:
        raise ValueError("etc/static/profile is not the single `. \"<path>\"` line this patch "
                         f"knows how to guard:\n  {etc_profile!r}")
    src = body[0].strip()
    path = src.split('"')[1]
    return (f'if [ -e "{path}" ]; then\n  {src}\nelse\n  . /{fallback_script}\nfi\n')


# ── #758: the shell resolves through Android, i.e. through the SuperApp's DNS menu ──
# MEASURED on the pinned zip: etc/resolv.conf -> /etc/static/resolv.conf, which
# names two public resolvers (Cloudflare's and Google's), so every lookup in this terminal skipped the menu and
# Private DNS. Rewriting that file would not hold: `nix-on-droid switch` repoints
# etc/static at a store generation that names them again. A proot FILE bind over
# /etc/resolv.conf holds whatever /etc/static points at (measured both ways with
# the pinned proot-static: the guest reads the bound file after a simulated
# switch), and -p moves the guest's 127.0.0.1:53 to the app's SystemDnsBridge
# (libs/sysdns, bridge_port), exactly as the termux terminal's enter.sh does.
# Measured: glibc in this guest reaches a responder on :2053 only with BOTH -p and
# the bind; either alone fails. The bind must come AFTER the /etc dir bind --
# proot sanitizes a bind's guest path against the binds before it, and one listed
# first is dropped with "Not a directory".
ETC_BIND_FMT = "  -b /data/data/{app_id}/files/usr/etc:/etc \\\n"
LOOPBACK_NAMESERVERS = ("127.0.0.1", "::1")


def resolv_conf_body(nameservers: list) -> str:
    """The file the guest sees as /etc/resolv.conf. Loopback only: any other server
    is a resolver of the terminal's own, the thing #741 forbids."""
    if not nameservers or any(n not in LOOPBACK_NAMESERVERS for n in nameservers):
        raise ValueError(f"dns_bridge.nameservers must be loopback only (the bridge), got {nameservers!r}")
    return "".join(f"nameserver {n}\n" for n in nameservers)


# startup-step: dns_resolver
def patch_bin_login_dns(bin_login: str, app_id: str, resolv_conf: str) -> str:
    """#758 -p plus a bind of the baked resolv.conf ($PREFIX/<resolv_conf>) over
    /etc/resolv.conf, inserted right after the /etc bind of bin/login's proot exec."""
    etc_bind = ETC_BIND_FMT.format(app_id=app_id)
    if bin_login.count(etc_bind) != 1:
        raise ValueError(f"expected exactly one /etc bind in bin/login's proot exec:\n  {etc_bind}")
    dns = f"  -b /data/data/{app_id}/files/usr/{resolv_conf}:/etc/resolv.conf \\\n"
    dns += "  -p \\\n"  # the guest's 127.0.0.1:53 -> the bridge's port
    return bin_login.replace(etc_bind, etc_bind + dns, 1)


def shizuku_client_binds() -> list:
    """build.json::shizuku_client.binds, read from THIS repo's build.json (the same
    DATA the lib bakes into BuildConfig and enter.sh renders in the termux fork).
    Returns [] when the block is absent. Data-driven: adding/moving a bind is a
    build.json edit, never a Kotlin or bake-script one (Pillar: DATA-DRIVEN)."""
    build_json = Path(__file__).resolve().parents[4] / "build.json"
    client = json.loads(build_json.read_text()).get("shizuku_client") or {}
    return client.get("binds") or []


# startup-step: rish_bridge
def patch_bin_login_rish(bin_login: str, app_id: str) -> str:
    """Put `rish` into the rootfs, mirroring patch_bin_login_dns: bind each
    shizuku_client.binds[] entry's app-written source ($PREFIX/<stage>) onto its
    guest path, inserted right after the /etc bind of bin/login's proot exec.
    RishBridge (libs:shizuku-adb-debug-tools) writes the sources into
    /data/data/<app>/files/usr/<stage> on launch. No-op without the block."""
    binds = shizuku_client_binds()
    if not binds:
        return bin_login
    etc_bind = ETC_BIND_FMT.format(app_id=app_id)
    if bin_login.count(etc_bind) != 1:
        raise ValueError(f"expected exactly one /etc bind in bin/login's proot exec:\n  {etc_bind}")
    exec_line = f"exec /data/data/{app_id}/files/usr/bin/proot-static \\"
    if bin_login.count(exec_line) != 1:
        raise ValueError(f"expected exactly one proot-static exec line in bin/login:\n  {exec_line}")
    # Each bind only when RishBridge has staged its source: proot warns "can't sanitize binding"
    # on every login for a missing one (the app writes them on launch, not before the first login).
    setup = 'CLOUD_RISH_BINDS=""\n'
    for b in binds:
        stage, guest = b.get("stage"), b.get("guest")
        if not stage or not guest:
            raise ValueError(f"shizuku_client.binds entry needs stage+guest: {b!r}")
        src = f"/data/data/{app_id}/files/usr/{stage}"
        setup += f'[ ! -e {src} ] || CLOUD_RISH_BINDS="$CLOUD_RISH_BINDS -b {src}:{guest}"\n'
    bin_login = bin_login.replace(exec_line, setup + exec_line, 1)
    return bin_login.replace(etc_bind, etc_bind + "  $CLOUD_RISH_BINDS \\\n", 1)


def webserver_build_json() -> Path:
    """THIS app's build.json, whose runtime.webserver declares the port (the same file
    shizuku_client_binds reads)."""
    return Path(__file__).resolve().parents[4] / "build.json"


def render_local_webserver(build_json: Path) -> str:
    """ab_cloud-terminal-store/render-local-webserver.py's render(), imported by path."""
    path = STORE_SRC / "render-local-webserver.py"
    if not path.is_file():
        raise ValueError(f"the shared local-webserver renderer is missing: {path}")
    spec = importlib.util.spec_from_file_location("render_local_webserver", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.render(str(build_json))


# startup-step: local_webserver
def patch_bin_login_webserver(bin_login: str, app_id: str, profile_link: str, rendered: str) -> str:
    """Option 37.1 -- this terminal serves its own $HOME through fleet-webserver on 127.0.0.1, port
    from build.json::runtime.webserver. `rendered` is ab_cloud-terminal-store/local-webserver.sh
    with that port (render_local_webserver: the same text the termux terminal stages beside
    enter.sh); it is inlined before bin/login's proot exec, followed by ONE call that starts the
    server only when nothing LISTENs on the port yet.

    The launcher is DERIVED from bin/login's own proot exec, never restated: the same proot with
    the same binds (so the dns and rish binds patched in before this are there too), minus `exec`
    and --kill-on-exit (the server outlives the session that started it), with login-inner swapped
    for the binary's guest path under the baked profile. A bin/login whose exec has another
    shape is refused rather than guessed at. Call this AFTER every bind patch."""
    exec_line = f"exec /data/data/{app_id}/files/usr/bin/proot-static \\"
    lines = bin_login.split("\n")
    starts = [i for i, line in enumerate(lines) if line.strip() == exec_line]
    if len(starts) != 1:
        raise ValueError(f"expected exactly one proot-static exec line in bin/login:\n  {exec_line}")
    end = starts[0]
    while lines[end].rstrip().endswith("\\"):
        end += 1
        if end >= len(lines):
            raise ValueError("bin/login's proot exec never ends (a trailing \\ on its last line)")
    block = lines[starts[0]:end + 1]
    if "login-inner" not in block[-1]:
        raise ValueError(f"bin/login's proot exec does not end in login-inner:\n  {block[-1]}")
    launcher = []
    for line in block[:-1]:
        line = re.sub(r"\s--kill-on-exit\b", "", line)
        if line.strip() != "\\":
            launcher.append(line)
    launcher[0] = launcher[0].replace("exec ", 'cloud_webserver_autostart "$HOME" ', 1)
    m = re.search(r"^CLOUD_WEBSERVER_BIN=([A-Za-z0-9._-]+)$", rendered, re.M)
    if not m:
        raise ValueError("the rendered local-webserver.sh sets no CLOUD_WEBSERVER_BIN")
    launcher.append(f"  /{profile_link}/bin/{m.group(1)}")
    call = ("# startup-step: local_webserver -- fleet-webserver on its declared loopback port, once\n"
            + rendered.rstrip("\n") + "\n"
            + "\n".join(launcher) + "\n"
            + 'cloud_trace "local webserver: port $CLOUD_WEBSERVER_PORT checked"\n\n')
    out = lines[:starts[0]] + call.split("\n")[:-1] + lines[starts[0]:]
    return "\n".join(out)


# claude-code carries an unfree license in nixpkgs (Anthropic's own terms,
# not a nixpkgs restriction) -- nix's eval refuses it unless this is set, the
# same override `nixos-rebuild`/`nix-env` users are told to add for it.
NIX_ENV = {**os.environ, "NIXPKGS_ALLOW_UNFREE": "1"}


# #771 -- the two system files both terminals bake, at the paths this zip's /etc is bound
# from (bin/login: files/usr/etc -> /etc). nixpkgs' git reads /etc/gitconfig (sysconfdir=/etc);
# nixpkgs' glibc reads /etc/ld-nix.so.preload, not ld.so.preload (dont-use-system-ld-so-preload).
# #846 -- outputs the closure drags in BY REFERENCE (a binary that embeds its doc dir, a
# .pc/cmake file that names its -dev) but nothing on the phone runs or loads: headers,
# pkg-config, man pages, HTML docs. Measured on the #847 bake (x86_64): 21 store paths,
# ~4.2 MB of the zip. Cut from the baked zip -- input bootstrap entries included -- and
# PROVEN harmless by verify_login_closure.py, which fails the bake if any shipped ELF's
# interpreter or DT_NEEDED, any /nix/store shebang, or any login/PATH path resolves into
# one. Matched on the output-name suffix, never a store hash, which moves with every pin.
CUT_OUTPUT = re.compile(r"^nix/store/[0-9a-z]{32}-[^/]+-(doc|man|dev)(/|$)")


def cut_output(rel: str) -> bool:
    return bool(CUT_OUTPUT.match(rel.lstrip("/")))


GITCONFIG_ENTRY = "etc/gitconfig"
PRELOAD_ENTRY = "etc/ld-nix.so.preload"


def extras_refs(expr: str, pin: str, nix_system: str, attrs) -> list:
    """One `--expr` per default_packages.extras attr: rootfs-extras.nix called with the SAME
    pinned nixpkgs, for the SAME target system, as the flake refs of the plain attrs."""
    pkgs = f'(builtins.getFlake "github:NixOS/nixpkgs/{pin}").legacyPackages.{nix_system}'
    return [f'(import {expr} {{ pkgs = {pkgs}; }})."{a}"' for a in attrs]


def run(cmd):
    print("+ " + " ".join(cmd), file=sys.stderr)
    return subprocess.run(cmd, check=True, env=NIX_ENV)


def capture(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, env=NIX_ENV).stdout


# #795 -- the zip entry ab_cloud-terminal-store/proot.json replaces, and the proot extensions bin/login
# runs it with (--link2symlink --sysvipc, plus #758's -p). --kill-on-exit only lets the CI run
# tear a session down; it changes nothing a check looks at.
PROOT_ENTRY = "bin/proot-static"
PTY_PROOT_FLAGS = ["--kill-on-exit", "--link2symlink", "--sysvipc", "-p"]
# Every check fails under the zip's own 24.05 proot, because this profile's glibc is 2.42.
PTY_MUTANT_RED = ("tty", "stty", "prompt", "claude-tui")


def pty_gate(proot: str, old_proot: str, generation: str, login_shell: str, nix_system: str,
             store_dir: str, store_files: dict) -> int:
    """#795: ../ab_cloud-terminal-store/pty-selftest.json in a real pty under `proot`, with this
    profile's own login shell and claude (the store paths the zip ships, realized on this runner),
    then the same run under the zip's original proot, which must fail every check: that mutant IS
    the phone of 2026-10-03, so a green mutant would mean the checks cannot see the bug."""
    host = {"arm64": "aarch64"}.get(os.uname().machine, os.uname().machine)
    if nix_system.split("-")[0] != host:
        print(f"COVERAGE-GAP #795/#797: the pty selftests need a {nix_system} host (an Android proot cannot "
              f"trace under qemu-user); this {host} runner ships the {nix_system} proot without running "
              "them. The x86_64 leg runs the same checks on the same proot revision, and the termux "
              "rootfs job runs them (pty-check included) natively on arm64.", file=sys.stderr)
        return 0

    def selftest(p):
        with tempfile.TemporaryDirectory() as home:
            env = {"HOME": home, "TMPDIR": home, "TERM": "xterm-256color", "LANG": "C.UTF-8",
                   "PATH": f"{generation}/bin:/usr/bin:/bin"}
            r = subprocess.run([sys.executable, str(STORE_SRC / "pty-selftest.py"),
                                str(STORE_SRC / "pty-selftest.json"), "nix", "--", p, *PTY_PROOT_FLAGS,
                                os.path.join(generation, "bin", login_shell), "-l"],
                               env=env, capture_output=True, text=True, timeout=900)
            return r.returncode, r.stdout + r.stderr

    rc, out = selftest(proot)
    print(f"#795 pty selftest under the shipped proot:\n{out}", file=sys.stderr)
    if rc != 0:
        print("FAIL: a login under the proot this zip ships has no usable terminal (see FAIL lines above)",
              file=sys.stderr)
        return 1
    rc, out = selftest(old_proot)
    survived = [n for n in PTY_MUTANT_RED if f"FAIL {n}:" not in out]
    if survived:
        print(f"FAIL MUTATION SURVIVED #795: under the 24.05 proot {survived} still passed -- those checks "
              f"cannot see the phone's failure:\n{out}", file=sys.stderr)
        return 1
    print(f"ok   mutation proved #795: under the 24.05 proot {list(PTY_MUTANT_RED)} all go red", file=sys.stderr)
    return phone_pty_gate(proot, generation, login_shell, store_dir, store_files)


# #797 -- the checks the PHONE runs: data/fleet/app/cld.termux.nix/terminal-selftest.json's pty-check lines,
# each handed to the login shell over PIPES exactly as /api/terminal/selftest hands it, with the
# store directory this zip ships bound where bin/login binds it. pty-selftest.py above models
# Android's ioctl policy around the python harness; this proves the harness the phone has (pty-check:
# zsh/zpty, jq and GNU grep out of this very profile) opens a pty under this proot and judges every
# declared check. Three mutants, one property each of the 2026-10-03 phone: a claude answering with
# the --print refusal (claude-tui), a login config stalling fish 5 s so no prompt comes within 3 s of
# its spawn (prompt), a /dev with no ptmx so no pty opens at all (tty, stty).
SELFTEST_JSON = Path(__file__).resolve().parents[5] / "data" / "fleet" / "app" / "cld.termux.nix" / "terminal-selftest.json"  # #1547 served data, not an asset
PRINT_REFUSAL = "Error: Input must be provided either through stdin or as a prompt argument when using --print"


def phone_pty_gate(proot: str, generation: str, login_shell: str, store_dir: str, store_files: dict) -> int:
    checks = [c for c in json.loads(SELFTEST_JSON.read_text())["checks"] if "pty-check" in c]
    if not checks:
        print(f"FAIL #797: {SELFTEST_JSON} runs no pty-check line", file=sys.stderr)
        return 1
    base_path = f"{generation}/bin:/usr/bin:/bin"
    with tempfile.TemporaryDirectory() as store:
        for name, (data, executable) in store_files.items():
            f = Path(store) / name
            f.write_bytes(data)
            if executable:
                f.chmod(0o755)

        def failures(env_extra=None, binds=()):
            with tempfile.TemporaryDirectory() as home:
                env = {"HOME": home, "TMPDIR": home, "TERM": "xterm-256color", "LANG": "C.UTF-8",
                       "PATH": base_path, **(env_extra or {})}
                failed = []
                for check in checks:
                    # A check that PRINTED its `ok` verdict yet exited non-zero is the proot's
                    # pty hang-up racing the shell's own exit (ship 37483578657 and 37529288589,
                    # x86_64 leg, the same check green in the runs around them), not a verdict:
                    # run it once more, and fail with the exit status named if it does it again.
                    # A check that printed anything else fails at once.
                    for attempt in (1, 2):
                        r = subprocess.run([proot, *PTY_PROOT_FLAGS, *binds, "-b", f"{store}:/{store_dir}",
                                            os.path.join(generation, "bin", login_shell), "-l", "-c", check],
                                           env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                                           timeout=600)
                        last = (r.stdout + r.stderr).strip().splitlines()[-1:] or ["(no output)"]
                        if r.returncode == 0 or not last[0].startswith("ok "):
                            break
                    if r.returncode != 0:
                        failed.append(f"{check} => exit {r.returncode}: {last[0][:300]}")
                return failed

        got = failures()
        if got:
            print("FAIL #797: a pty-check line of terminal-selftest.json fails under the shipped proot:\n  "
                  + "\n  ".join(got), file=sys.stderr)
            return 1
        print(f"ok   #797: all {len(checks)} pty-check lines pass under the shipped proot (zsh/zpty, a real pty)",
              file=sys.stderr)

        def red(why, got, *names):
            rc = 0
            for n in names:
                if any(f"pty-check {n}'" in g for g in got):
                    print(f"ok   mutation proved #797: {why} turns pty-check {n} red", file=sys.stderr)
                else:
                    print(f"FAIL MUTATION SURVIVED #797: {why} left pty-check {n} green -- the phone's check "
                          "cannot see this failure:\n  " + "\n  ".join(got or ["(all green)"]), file=sys.stderr)
                    rc = 1
            return rc

        rc = 0
        with tempfile.TemporaryDirectory() as stub:
            Path(stub, "claude").write_text(f"#!/bin/sh\necho '{PRINT_REFUSAL}' >&2\nexit 1\n")
            os.chmod(os.path.join(stub, "claude"), 0o755)
            rc |= red("a claude that refuses in --print mode", failures({"PATH": f"{stub}:{base_path}"}), "claude-tui")
        with tempfile.TemporaryDirectory() as cfg:
            os.makedirs(os.path.join(cfg, "fish"))
            Path(cfg, "fish", "config.fish").write_text("sleep 5\n")
            rc |= red("a login config that stalls fish 5 s", failures({"XDG_CONFIG_HOME": cfg}), "prompt")
        with tempfile.TemporaryDirectory() as nodev:
            rc |= red("a /dev with no ptmx", failures(binds=("-b", f"{nodev}:/dev", "-b", "/dev/null:/dev/null")),
                      "tty", "stty")
        return rc


def main() -> int:
    if len(sys.argv) != 17:
        print(
            "usage: bake_default_packages.py <input.zip> <output.zip> "
            "<nixpkgs_pin> <attrs csv> <profile_link> <fallback_init_script> <app_id> <nix_system> "
            "<shared_root_name> <login_shell_attr> <dns resolv_conf> <dns nameservers csv> "
            "<extras expr> <extras attrs csv> <preload> <proot-static>",
            file=sys.stderr,
        )
        return 2

    (input_zip, output_zip, pin, attrs_csv, profile_link, fallback_script, app_id, nix_system,
     shared_root_name, login_shell, dns_resolv_conf, dns_nameservers,
     extras_expr, extras_csv, preload, proot) = sys.argv[1:17]
    attrs = [a for a in attrs_csv.split(",") if a]
    extras = [a for a in extras_csv.split(",") if a]
    if not attrs:
        print("no attrs given", file=sys.stderr)
        return 2
    if login_shell not in attrs:
        print(f"FAIL: login_shell_attr {login_shell!r} is not in default_packages.attrs "
              f"({attrs}) -- the login shell would be a path nothing bakes", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as work:
        profile = os.path.join(work, "profile")
        # The CI runner is always x86_64-linux; legacyPackages.<system> (rather
        # than the plain #attr shorthand, which resolves against the CALLER's
        # system) is what lets one runner bake either ABI's binaries, pulling
        # pre-built substitutes for the foreign arch from cache.nixos.org for
        # most attrs. That assumption held for git/nodejs_22 but not for
        # claude-code: cache.nixos.org does not carry an aarch64-linux
        # substitute for every claude-code release, so an arm64 job with no
        # foreign builder registered hard-fails with "platform mismatch"
        # instead of falling back to a (slower, but working) build. Registering
        # binfmt_misc for the foreign arch -- one-time, host-wide, via the
        # already-present docker daemon -- and telling nix that arch is locally
        # buildable turns that hard-fail into a QEMU-emulated build instead.
        host_arch = capture(["uname", "-m"]).strip()
        foreign_arch = nix_system.split("-")[0]
        nix_arch_of_host = {"x86_64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(host_arch, host_arch)
        extra_platform_args = []
        if foreign_arch != nix_arch_of_host:
            print(f"host is {host_arch}, baking for {nix_system}: registering QEMU emulation "
                  f"so nix can build (not just substitute) {foreign_arch} derivations", file=sys.stderr)
            run(["docker", "run", "--rm", "--privileged", "tonistiigi/binfmt", "--install", "all"])
            extra_platform_args = ["--extra-platforms", nix_system]

        refs = [f"github:NixOS/nixpkgs/{pin}#legacyPackages.{nix_system}.{a}" for a in attrs]
        run(["nix", "profile", "install", "--profile", profile, *refs, "--impure",
             "--extra-experimental-features", "nix-command flakes", *extra_platform_args])
        # #771 -- goose, hermes and the noexec shim: built by rootfs-extras.nix, same profile.
        for expr in extras_refs(extras_expr, pin, nix_system, extras):
            run(["nix", "profile", "install", "--profile", profile, "--impure", "--expr", expr,
                 "--extra-experimental-features", "nix-command flakes", *extra_platform_args])

        generation = capture(["readlink", "-f", profile]).strip()
        if not os.path.exists(generation):
            print(f"FAIL: realized profile generation {generation} does not exist", file=sys.stderr)
            return 1

        closure = capture(["nix-store", "-qR", generation]).split()
        if not closure:
            print("FAIL: empty closure for the realized profile", file=sys.stderr)
            return 1
        print(f"closure: {len(closure)} store paths", file=sys.stderr)

        with zipfile.ZipFile(input_zip) as zin:
            existing = set(zin.namelist())
            login_inner = zin.read("usr/lib/login-inner").decode()
            bin_login = zin.read("bin/login").decode()
            symlinks_txt = zin.read("SYMLINKS.txt").decode()
            executables_txt = zin.read("EXECUTABLES.txt").decode()
            etc_profile = zin.read(ETC_PROFILE_ENTRY).decode()
            proot_bytes = Path(proot).read_bytes()

            # ── #644: the declarative link store, and its login wiring ─────
            # (before the #795 gate: #797 runs the phone's pty checks with these files bound in)
            try:
                render_store = load_render_store()
                store_decl = render_store.load()["store"]
                store_dir = store_decl["install_dir"]
                store_files = {
                    store_decl["engine"]: ((STORE_SRC / store_decl["engine"]).read_bytes(), True),
                    "login-init.sh": ((STORE_SRC / "login-init.sh").read_bytes(), False),
                    "login-exec": ((STORE_SRC / "login-exec").read_bytes(), True),
                    "declaration.sh": (render_store.render("nix").encode(), False),
                    # #797 the pty selftest the phone runs (terminal-selftest.json calls it by check
                    # name at /<store_dir>/pty-check) and the one declaration of its checks.
                    "pty-check": ((STORE_SRC / "pty-check").read_bytes(), False),
                    "pty-selftest.json": ((STORE_SRC / "pty-selftest.json").read_bytes(), False),
                }
                store_tools = render_store.tools_of("nix")
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1

            # ── #795: the session's terminal, proven in a pty before the proot is swapped in
            if PROOT_ENTRY not in existing:
                print(f"FAIL: the input zip has no {PROOT_ENTRY} for ab_cloud-terminal-store/proot.json to replace",
                      file=sys.stderr)
                return 1
            old_proot = os.path.join(work, "proot-static.zip-original")
            Path(old_proot).write_bytes(zin.read(PROOT_ENTRY))
            os.chmod(old_proot, 0o755)
            if pty_gate(proot, old_proot, generation, login_shell, nix_system, store_dir, store_files) != 0:
                return 1

            # ── #612/#736: shared storage + the cloud-drive shared store in
            # $HOME (storage_setup), the same generated-text-injection
            # technique as the session-init patch below, run before the exec.
            exec_line = f"exec /data/data/{app_id}/files/usr/bin/proot-static \\"
            if bin_login.count(exec_line) != 1:
                print(f"FAIL: expected exactly one proot-static exec line in bin/login:\n  {exec_line}",
                      file=sys.stderr)
                return 1
            mount_setup = (BIN_LOGIN_TRACE + storage_setup(shared_root_name)
                           + passwd_setup(app_id, f"/{profile_link}/bin/{login_shell}")) + 'cloud_trace "exec proot-static"\n\n'
            bin_login = bin_login.replace(exec_line, mount_setup + exec_line, 1)

            # ── #758: lookups go to the app's bridge, never to a server of the zip's own
            try:
                bin_login = patch_bin_login_dns(bin_login, app_id, dns_resolv_conf)
                dns_resolv_body = resolv_conf_body([n for n in dns_nameservers.split(",") if n])
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1

            # ── build.json::shizuku_client: bind `rish` into the rootfs so a shell
            #    inside proot reaches adb-shell privilege through the fleet provider.
            try:
                bin_login = patch_bin_login_rish(bin_login, app_id)
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1

            # ── option 37.1: fleet-webserver on build.json::runtime.webserver's port, once per
            #    boot. LAST of the bin/login patches: its launcher copies the exec's binds.
            try:
                bin_login = patch_bin_login_webserver(
                    bin_login, app_id, profile_link, render_local_webserver(webserver_build_json()))
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1
            if dns_resolv_conf in existing:
                print(f"FAIL: the input zip already carries {dns_resolv_conf}", file=sys.stderr)
                return 1

            # ── patch login-inner's session-init line and its env execs ────
            try:
                flakes_decl = load_flakes_decl()
                nod_rev = json.loads(BOOTSTRAP_SRC_JSON.read_text())["upstream_rev"]
                extras_decl = json.loads((Path(__file__).resolve().parents[4] / "build.json").read_text()
                                         )["forks"]["nixdroid"]["bootstrap"]["default_packages"]["extras"]
                flake_files = render_flakes(flakes_decl, app_id, pin, nod_rev, attrs,
                                            login_shell, nix_system, extras, extras_decl)
                flake_files.update(android_host_files(flakes_decl))
                login_inner = patch_login_inner(
                    login_inner, app_id, fallback_script, profile_link, login_shell, store_dir,
                    flakes_seed_line(flakes_decl["install_dir"], flakes_decl["home_dir"]))
                etc_profile = patch_etc_profile(etc_profile, fallback_script)
                # zsh's /etc/zshenv is upstream's same one-line source of the session-init
                # store path the zip never ships; guarded exactly like etc/profile.
                etc_zshenv = (patch_etc_profile(zin.read(ETC_ZSHENV_ENTRY).decode(), fallback_script)
                              if ETC_ZSHENV_ENTRY in existing else None)
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1

            # ── new manifest lines ─────────────────────────────────────────
            new_symlinks = []
            new_executables = []
            new_files = {}  # zip path -> bytes

            def add_tree(store_path: str):
                base = store_path.lstrip("/")
                if base in existing or base in new_files or any(
                    s.endswith(f"←{base}") for s in new_symlinks
                ):
                    return  # this exact store path already shipped
                for root, dirs, files in os.walk(store_path):
                    rel_root = os.path.relpath(root, store_path)
                    for name in dirs + files:
                        full = os.path.join(root, name)
                        rel = f"{base}/{name}" if rel_root == "." else f"{base}/{rel_root}/{name}"
                        if rel in existing:
                            continue
                        if os.path.islink(full):
                            target = os.readlink(full)
                            new_symlinks.append(f"{target}←{rel}")
                        elif os.path.isdir(full):
                            continue  # created implicitly on extraction
                        else:
                            data = Path(full).read_bytes()
                            new_files[rel] = data
                            if os.access(full, os.X_OK):
                                new_executables.append(rel)

            cut = [p for p in closure if cut_output(p)]
            for store_path in closure:
                if not cut_output(store_path):
                    add_tree(store_path)
            print(f"#846 cut: {len(cut)} -doc/-man/-dev store paths left out of the zip", file=sys.stderr)

            # the profile generation itself becomes the DEFAULT profile
            new_symlinks.append(f"{generation}←{profile_link}")

            # ── #640: and /usr/bin/env resolves through that same profile ───
            env_in_profile = os.path.join(generation, "bin", "env")
            if not os.path.islink(env_in_profile) and not os.path.exists(env_in_profile):
                print(f"FAIL: the realized profile has no bin/env ({env_in_profile}) -- "
                      f"coreutils is missing from default_packages.attrs ({attrs})", file=sys.stderr)
                return 1
            if ENV_SYMLINK in existing:
                print(f"FAIL: the input zip already carries {ENV_SYMLINK}; a second entry for it "
                      "would make which one wins depend on extraction order", file=sys.stderr)
                return 1
            why = env_target_unreachable(
                os.path.realpath(env_in_profile).lstrip("/"), existing, new_files, new_executables)
            if why:
                print(f"FAIL: /{ENV_SYMLINK} would point at something unrunnable: {why}", file=sys.stderr)
                return 1
            new_symlinks.append(env_symlink_line(profile_link))

            # ── #641: and the login shell login-inner now points at really ships
            shell_in_profile = os.path.join(generation, "bin", login_shell)
            if not os.path.islink(shell_in_profile) and not os.path.exists(shell_in_profile):
                print(f"FAIL: the realized profile has no bin/{login_shell} ({shell_in_profile}) -- "
                      f"login-inner would name a login shell that is not in the store", file=sys.stderr)
                return 1
            why = env_target_unreachable(
                os.path.realpath(shell_in_profile).lstrip("/"), existing, new_files, new_executables)
            if why:
                print(f"FAIL: the {login_shell} login shell would not be runnable: {why}", file=sys.stderr)
                return 1

            # ── #644: every tool the store declares must really be in the profile
            # The store links $HOME/.cloud-store/current/bin/<tool> at profile
            # bin/<tool> for each name in default_packages.binaries. A name in
            # that list with no binary behind it is a link that dangles on every
            # phone -- the #638/#640/#641 shape, one layer up -- and the list is
            # maintained beside attrs and provides, so it CAN drift from them.
            # Proving it here is what makes "adding a tool is a data-only edit"
            # true rather than merely intended.
            store_missing = profile_missing(generation, store_tools)
            if store_missing:
                print(f"FAIL: default_packages.binaries names {store_missing}, which the realized "
                      f"profile does not provide -- the link store would ship dangling links. "
                      f"Add the attr that provides them, or drop the names.", file=sys.stderr)
                return 1

            for name, (data, executable) in store_files.items():
                rel = f"{store_dir}/{name}"
                if rel in existing:
                    print(f"FAIL: the input zip already carries {rel}; a second entry would make "
                          "which one wins depend on extraction order", file=sys.stderr)
                    return 1
                new_files[rel] = data
                if executable:
                    new_executables.append(rel)
            print(f"#644 store: {len(store_files)} files at {store_dir}, "
                  f"{len(store_tools)} declared tools", file=sys.stderr)

            # ── store.json::linux_tools: the pinned linux-store / linux-account CLIs and the fish
            # greeting, at the same absolute paths the termux tree gets them (build-rootfs.sh).
            # startup-step: linux_tools_install
            try:
                linux_tools = load_linux_tools().fetch()
            except (ValueError, OSError) as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1
            for rel, (data, mode) in sorted(linux_tools.items()):
                if rel in existing or rel in new_files:
                    print(f"FAIL: the input zip already carries {rel}; a second entry would make "
                          "which one wins depend on extraction order", file=sys.stderr)
                    return 1
                new_files[rel] = data
                if mode & 0o111:
                    new_executables.append(rel)
            print(f"linux_tools: {len(linux_tools)} files baked ({', '.join(sorted(linux_tools))})", file=sys.stderr)

            fallback_body = (
                "# #595 -- baked default tooling, sourced by usr/lib/login-inner when\n"
                "# $HOME/.nix-profile does not exist (the wizard that would normally\n"
                "# create it never ran, because the packages are already installed).\n"
                f'export PATH="/{profile_link}/bin:$PATH"\n'
                "# Android host tools (getprop, pm, cmd, ...), last so they never shadow nix ones.\n"
                f'export PATH="$PATH:/{flakes_decl["android_host"]["bin_dir"].strip("/")}"\n'
            )
            new_files[fallback_script] = fallback_body.encode()

            # ── ~/flakes/{system,hm} templates, seed.sh and the android-host wrappers
            for rel, (data, executable) in flake_files.items():
                if rel in existing or rel in new_files:
                    print(f"FAIL: the input zip already carries {rel}", file=sys.stderr)
                    return 1
                new_files[rel] = data
                if executable:
                    new_executables.append(rel)
            print(f"flakes: {len(flake_files)} files baked", file=sys.stderr)

            # ── #771: the shared system gitconfig, and the noexec #! shim preloaded into every
            # glibc process of the session, by the profile path (never a /nix/store hash).
            shim = os.path.join(generation, preload)
            why = None if os.path.exists(shim) else f"the realized profile has no {preload}"
            why = why or env_target_unreachable(
                os.path.realpath(shim).lstrip("/"), existing, new_files, new_executables)
            if why:
                print(f"FAIL: the noexec shim would not load: {why}", file=sys.stderr)
                return 1
            for entry in (GITCONFIG_ENTRY, PRELOAD_ENTRY):
                if entry in existing:
                    print(f"FAIL: the input zip already carries {entry}", file=sys.stderr)
                    return 1
            new_files[GITCONFIG_ENTRY] = (STORE_SRC / "gitconfig").read_bytes()
            new_files[PRELOAD_ENTRY] = f"/{profile_link}/{preload}\n".encode()
            new_files[dns_resolv_conf] = dns_resolv_body.encode()

            # #846 the input bootstrap's own entries under a cut output go too.
            symlinks_txt = "".join(l for l in symlinks_txt.splitlines(True)
                                   if not cut_output(l.rstrip("\n").split("←")[-1]))
            executables_txt = "".join(l for l in executables_txt.splitlines(True)
                                      if not cut_output(l.rstrip("\n")))
            if not symlinks_txt.endswith("\n"):
                symlinks_txt += "\n"
            symlinks_txt += "\n".join(new_symlinks) + "\n"
            if not executables_txt.endswith("\n"):
                executables_txt += "\n"
            executables_txt += "\n".join(new_executables) + "\n"

            print(f"adding {len(new_files)} files, {len(new_symlinks)} symlinks, "
                  f"{len(new_executables)} new executables", file=sys.stderr)

            with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zout:
                for info in zin.infolist():
                    name = info.filename
                    if name == DROPPED_ENTRY or cut_output(name):
                        continue
                    if name == "usr/lib/login-inner":
                        zout.writestr(info, login_inner)
                    elif name == "bin/login":
                        zout.writestr(info, bin_login)
                    elif name == "SYMLINKS.txt":
                        zout.writestr(info, symlinks_txt)
                    elif name == "EXECUTABLES.txt":
                        zout.writestr(info, executables_txt)
                    elif name == ETC_PROFILE_ENTRY:
                        zout.writestr(info, etc_profile)
                    elif name == ETC_ZSHENV_ENTRY and etc_zshenv is not None:
                        zout.writestr(info, etc_zshenv)
                    elif name == PROOT_ENTRY:
                        zout.writestr(info, proot_bytes)
                    else:
                        zout.writestr(info, zin.read(name))
                for rel, data in new_files.items():
                    zout.writestr(rel, data)

    print(f"OK: {output_zip} carries {attrs} baked into {profile_link}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
