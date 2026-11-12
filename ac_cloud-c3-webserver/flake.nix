{
  description = "cloud-c3-webserver — Nix devShell for the native Android build (#1682: Kotlin / Compose over the Rust core): rustup (Android std targets), the Android SDK + NDK and JDK 17. The CI pins live in build.json::toolchain.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # ── Pin SDK/build-tools/NDK in one place. Mirrored in
        #    build.json::android.compile_sdk and ::toolchain.ndk. The NDK is
        #    needed now: build.sh links the Rust core with the NDK clang
        #    for the Android target with the NDK's clang.
        baseAndroidArgs = {
          toolsVersion        = "26.1.1";
          platformToolsVersion = "35.0.2";
          buildToolsVersions  = [ "36.0.0" ];
          platformVersions    = [ "36" ];
          includeNDK          = true;
          ndkVersions         = [ "27.2.12479018" ];
        };

        # Lean BUILD SDK — NO emulator, NO system images.
        androidEnv = pkgs.androidenv.composeAndroidPackages baseAndroidArgs;
        androidSdk = androidEnv.androidsdk;

        # Heavy EMULATOR SDK — only for `build.sh emulator` (full-fidelity
        # arm64 testing, no libhoudini). ABI/API match build.json::emulator.
        emulatorEnv = pkgs.androidenv.composeAndroidPackages (baseAndroidArgs // {
          includeEmulator     = true;
          includeSystemImages = true;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "arm64-v8a" ];
        });
        emulatorSdk = emulatorEnv.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "cloud-c3-webserver-devshell";
          buildInputs = with pkgs; [
            jdk17
            rustup            # the Android std targets build.json::native.targets names (rustup target add)
            androidSdk
            adb-sync
            android-tools     # adb, fastboot
            jq                # build.sh reads build.json
            oras              # OCI artifact push to ghcr (release.ghcr)
            gh                # GitHub Release attachment (release.gh_release)
            git               # for `git rev-parse --short` in build.sh
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          NDK_HOME = "${androidSdk}/libexec/android-sdk/ndk/27.2.12479018";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/36.0.0/aapt2";

          shellHook = ''
            echo "cloud-c3-webserver devShell"
            echo "  java=$(java -version 2>&1 | head -1)"
            echo "  android-sdk=$ANDROID_HOME ndk=$NDK_HOME"
            echo "Commands: ./build.sh {build|release|dev|test|clean|shell|ship|emulator}"
          '';
        };

        # Separate, heavy shell for `build.sh emulator` only.
        devShells.emulator = pkgs.mkShell {
          name = "cloud-c3-webserver-emulator-devshell";
          buildInputs = with pkgs; [
            jdk17
            emulatorSdk       # emulator + avdmanager + arm64 system image
            android-tools     # adb
            jq                # build.sh reads build.json::emulator
          ];
          ANDROID_HOME = "${emulatorSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${emulatorSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
        };

        packages.default = pkgs.runCommandLocal "cloud-c3-webserver-stub" {} ''
          mkdir -p $out
          echo "TODO: hermetic APK build needs gradle wrapper checked in + dependency lockfile" > $out/README
        '';
      });
}
