# #771 -- what the nix terminal bakes that a nixpkgs attr cannot give it: goose and hermes at
# the versions the termux terminal ships, and the noexec #! shim both terminals preload.
#
# ONE declaration of goose and hermes: ac_cloud-termux/rootfs/rootfs.json (tarballs.goose,
# pip_venvs.hermes-agent) -- the version, the release url and its per-arch names are read from
# it, never restated. nixpkgs is not that declaration: at the pinned nixpkgs goose-cli is a
# different release (1.47.0, built from source with an X11 closure) and hermes-agent is not
# packaged at all. What this terminal needs on top is the one thing a fixed-output fetch must
# have and rootfs.json does not carry: the content hash, pinned in build.json
# default_packages.extras.pins with the version it was taken for. A rootfs.json bump without a
# re-pin stops evaluation here, by name, instead of fetching bytes nobody checked.
#
# Called by bake_default_packages.py as `(import <this> { pkgs = <pinned nixpkgs for the
# target system>; }).<attr>` for each default_packages.extras.attrs entry, into the same
# profile the nixpkgs attrs go to.
{ pkgs }:
let
  inherit (pkgs) lib;
  rootfs = builtins.fromJSON (builtins.readFile ../../../../../ac_cloud-termux/rootfs/rootfs.json);
  extras = (builtins.fromJSON (builtins.readFile ../../../../build.json)).forks.nixdroid.bootstrap.default_packages.extras;
  pinned = name: version:
    let p = extras.pins.${name}; in
    if p.version == version then p
    else throw ("build.json default_packages.extras.pins.${name} is for ${p.version}, but "
      + "ac_cloud-termux/rootfs/rootfs.json declares ${version}: re-pin its hash for ${version}");

  goose = rootfs.tarballs.goose;
  hermes = rootfs.pip_venvs."hermes-agent";
  agy = rootfs.tarballs.agy;
  # rootfs.json keys its arch_names by docker arch, which is Go's GOARCH (amd64, arm64).
  arch = pkgs.stdenv.hostPlatform.go.GOARCH;
  fill = s: builtins.replaceStrings [ "{version}" "{arch}" ] [ goose.version goose.arch_names.${arch} ] s;
in
{
  # The same glibc release tarball the termux rootfs installs, given nix's loader and libstdc++.
  goose = pkgs.stdenv.mkDerivation {
    pname = "goose";
    inherit (goose) version;
    src = pkgs.fetchurl {
      url = fill goose.url;
      hash = (pinned "goose" goose.version).hash.${arch};
    };
    unpackPhase = "tar -xzf $src";
    nativeBuildInputs = [ pkgs.autoPatchelfHook ];
    buildInputs = [ pkgs.stdenv.cc.cc.lib ];
    # #846 upstream ships it unstripped and stdenv's default strip is debug-only (-S):
    # strip ALL symbols, ~47 MB unpacked / ~7.8 MB of the zip per ABI. Backtraces lose names.
    stripAllList = [ "bin" ];
    installPhase = ''
      install -Dm755 ${lib.optionalString (goose ? extracted_dir) (fill goose.extracted_dir + "/")}${goose.extracted_bin} $out/bin/goose
    '';
    doInstallCheck = true;
    nativeInstallCheckInputs = [ pkgs.versionCheckHook pkgs.writableTmpDirAsHomeHook ];
    versionCheckKeepEnvironment = [ "HOME" ];
    meta.mainProgram = "goose";
  };

  # Antigravity CLI (`agy`), the same pinned glibc release tarball the termux rootfs installs
  # (rootfs.json tarballs.agy: version, url, arch names; hashes in build.json extras.pins.agy).
  # No nixpkgs attr and no npm package carry it, so this is the declarative path instead of the
  # vendor's `curl | bash`. The archive holds one self-contained glibc ELF named `antigravity`
  # (needs only libc, hence autoPatchelf to nix's loader). Auth is a runtime login, never baked.
  agy = pkgs.stdenv.mkDerivation {
    pname = "agy";
    inherit (agy) version;
    src = pkgs.fetchurl {
      url = builtins.replaceStrings [ "{version}" "{arch}" ] [ agy.version agy.arch_names.${arch} ] agy.url;
      hash = (pinned "agy" agy.version).hash.${arch};
    };
    unpackPhase = "tar -xzf $src";
    nativeBuildInputs = [ pkgs.autoPatchelfHook ];
    buildInputs = [ pkgs.stdenv.cc.cc.lib ];
    # The ELF is stripped already; a second strip/patchelf shrink pass is not wanted on 200 MB.
    dontStrip = true;
    # The 207 MB ELF is not exec'd through its own PT_INTERP: patchelf grows its program-header
    # table (6 -> 9 LOAD segments, PT_INTERP moved to the file end), which the phone's proot
    # loader could not start (ENOENT on a file that exists). bin/agy is a tiny launcher that
    # runs nix's own ld.so on the real ELF (libexec/agy.real) with an explicit library path, so
    # proot only maps the launcher shell and ld.so (the goose-proven path) and ld.so maps the ELF.
    # Loader and libs are interpolated store paths, so they are in the runtime closure.
    installPhase = ''
      install -Dm755 ${agy.extracted_bin} $out/libexec/agy.real
      mkdir -p $out/bin
      cat > $out/bin/agy <<EOF
      #!${pkgs.runtimeShell}
      exec ${pkgs.stdenv.cc.bintools.dynamicLinker} --library-path ${pkgs.stdenv.cc.libc}/lib:${pkgs.stdenv.cc.cc.lib}/lib $out/libexec/agy.real "\$@"
      EOF
      chmod 755 $out/bin/agy
    '';
    doInstallCheck = true;
    nativeInstallCheckInputs = [ pkgs.versionCheckHook pkgs.writableTmpDirAsHomeHook ];
    versionCheckKeepEnvironment = [ "HOME" ];
    meta.mainProgram = "agy";
  };

  # The same PyPI release the termux rootfs pip-installs into its venv. Its dependencies come
  # from the pinned nixpkgs (Hydra-built, so the arm64 bake substitutes instead of compiling);
  # their exact pins are relaxed, and the runtime-deps check still fails the build if any
  # Requires-Dist name has nothing behind it. The wheel caps Python at <3.14 only because uv
  # found no cp314 wheels for pydantic-core then (its pyproject says so); nixpkgs builds those
  # for its own 3.14, so the default python3Packages is used.
  hermes = pkgs.python3Packages.buildPythonApplication {
    pname = "hermes-agent";
    inherit (hermes) version;
    format = "wheel";
    src = pkgs.python3Packages.fetchPypi {
      pname = "hermes_agent";
      inherit (hermes) version;
      format = "wheel";
      python = "py3";
      dist = "py3";
      hash = (pinned "hermes-agent" hermes.version).hash;
    };
    dependencies =
      with pkgs.python3Packages;
      [
        openai certifi python-dotenv fire httpx rich tenacity pyyaml ruamel-yaml requests jinja2
        pydantic prompt-toolkit croniter packaging markdown pyjwt urllib3 cryptography psutil
        websockets pathspec fastapi uvicorn python-multipart ptyprocess pillow
      ]
      ++ httpx.optional-dependencies.socks
      ++ uvicorn.optional-dependencies.standard
      ++ pyjwt.optional-dependencies.crypto;
    pythonRelaxDeps = true;
    # #788 upstream bug in 0.19.0 (reproduced on 3.13 and 3.14, not a packaging difference):
    # the Termux pre-import `--version` shortcut prints PROJECT_ROOT, which main.py only
    # defines ~100 lines later, so on a phone `hermes --version` printed one line and died
    # with NameError. It runs only when TERMUX_VERSION or a com.termux PREFIX is set: the nix
    # terminal's login keeps TERMUX_VERSION, the termux one enters its rootfs with `env -i`
    # (ac_cloud-termux/rootfs/enter.sh), which is why only this terminal hit it. Both lines
    # that print it get the same path computed with `os` (imported above the shortcut) instead.
    postInstall = ''
      main=$out/${pkgs.python3Packages.python.sitePackages}/hermes_cli/main.py
      substituteInPlace $main --replace-fail \
        '{PROJECT_ROOT}")' '{os.path.dirname(os.path.dirname(os.path.realpath(__file__)))}")'
      ${pkgs.python3Packages.python.interpreter} -m compileall -q -f -o 0 -o 1 $main
    '';
    pythonImportsCheck = [ "hermes_cli.main" ];
    doInstallCheck = true;
    nativeInstallCheckInputs = [ pkgs.versionCheckHook pkgs.writableTmpDirAsHomeHook ];
    versionCheckKeepEnvironment = [ "HOME" ];
    versionCheckProgram = "${placeholder "out"}/bin/hermes";
    # versionCheckHook ignores the exit code (`|| true`) and only looks for the version string,
    # and its sandbox has no TERMUX_VERSION, so it never took the crashing path and would have
    # passed it anyway (the line before the NameError already says v0.19.0). Run both paths
    # the device can take, the Termux shortcut and the full CLI, under errexit: each must be 0.
    postInstallCheck = ''
      TERMUX_VERSION=1 $out/bin/hermes --version
      env -u TERMUX_VERSION -u PREFIX $out/bin/hermes --version
    '';
    meta.mainProgram = "hermes";
  };

  # ab_cloud-terminal-store/noexec-shebang.c, the source the termux rootfs compiles too.
  noexec-shebang = pkgs.stdenv.mkDerivation {
    pname = "cloud-noexec-shebang";
    version = "1";
    src = ../../../../../ab_cloud-terminal-store/noexec-shebang.c;
    dontUnpack = true;
    buildPhase = "$CC -std=gnu11 -O2 -Wall -shared -fPIC -o libcloud-noexec-shebang.so $src";
    installPhase = "install -Dm755 libcloud-noexec-shebang.so $out/${extras.preload}";
    doInstallCheck = true;
    # It is preloaded into the bootstrap's own /bin/sh too, whose glibc is 2.37: a symbol
    # version newer than that would make ld.so refuse it in every such process.
    installCheckPhase = ''
      newer="$(objdump -T $out/${extras.preload} | grep -oE 'GLIBC_2\.(3[89]|[4-9][0-9])' | sort -u || true)"
      [ -z "$newer" ] || { echo "the shim needs $newer, newer than the bootstrap's glibc 2.37"; exit 1; }
    '';
  };

  # Option 37.1 -- fleet-webserver, the terminal's own-disk server (build.json::runtime.webserver,
  # started by bin/login). The ONE shared fleet web server, ab_cloud-libs-shared/libs/fleet-webserver
  # (the same crate c3-webserver :8002 and the termux terminal run), built here from that source:
  # std-only, so no crates and no hash to pin, and its Linux frontend is bundled into the binary
  # from the lib's vendor/ mirror (its build.rs refuses a vendor/ that does not match
  # VENDOR.sha256). Nothing is fetched at build or at runtime beyond the pinned nixpkgs rustc.
  # In ~/flakes/system the same source sits beside this file as ./fleet-webserver.
  fleet-webserver =
    let src = ../../../../../ab_cloud-libs-shared/libs/fleet-webserver; in
    pkgs.rustPlatform.buildRustPackage {
      pname = "fleet-webserver";
      version = (builtins.fromTOML (builtins.readFile (src + "/Cargo.toml"))).package.version;
      src = lib.cleanSourceWith {
        inherit src;
        filter = path: type: baseNameOf path != "target";
      };
      cargoLock.lockFile = src + "/Cargo.lock";
      # The lib's cargo tests need node and a cloud-u-linux checkout; CI's
      # ab_cloud-terminal-store/webserver-ui-check.py starts the real binary instead.
      doCheck = false;
      meta.mainProgram = "fleet-webserver";
    };
}
