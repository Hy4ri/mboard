{
  description = "mboard - minimal, offline Android keyboard (EN/AR)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        buildToolsVersion = "37.0.0";
        androidSdk =
          (pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "22.0";
            platformToolsVersion = "37.0.1";
            buildToolsVersions = [ buildToolsVersion ];
            platformVersions = [ "37.0" ];

            # Device testing happens on real hardware over adb: no emulator.
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          }).androidsdk;

        sdkRoot = "${androidSdk}/libexec/android-sdk";

        # Android CLI (agent tooling: Compose preview render, APK analysis,
        # device control). nixpkgs lags upstream, so pin the version here.
        # Upstream ships x86_64-linux and aarch64-darwin builds only.
        androidCliVersion = "1.1.16717390";
        androidCliHashes = {
          x86_64-linux = "sha256-s07Quu7/oqruJyDYJJG5dYNWluLlaEBEkHi+yIVDZWQ=";
          aarch64-darwin = "sha256-6cbhn17YFC4ol+C2i0duF2hjPldAqlS/wjR2fMJkAf4=";
        };
        androidCliPlatforms = {
          x86_64-linux = "linux_x86_64";
          aarch64-darwin = "darwin_arm64";
        };
        androidCli = pkgs.android-cli.overrideAttrs (old: {
          version = androidCliVersion;
          src = pkgs.fetchurl {
            url = "https://dl.google.com/android/cli/${androidCliVersion}/${androidCliPlatforms.${system}}/android-cli";
            hash = androidCliHashes.${system};
          };
          # `preview render` runs a bundled JRE that dlopens AWT's X11 libs.
          # NixOS has no global copies, so give them to the CLI only.
          nativeBuildInputs =
            (old.nativeBuildInputs or [ ])
            ++ pkgs.lib.optional pkgs.stdenv.hostPlatform.isLinux pkgs.makeWrapper;
          postInstall =
            (old.postInstall or "")
            + pkgs.lib.optionalString pkgs.stdenv.hostPlatform.isLinux ''
              wrapProgram $out/bin/android --prefix LD_LIBRARY_PATH : ${
                pkgs.lib.makeLibraryPath (
                  with pkgs;
                  [
                    libx11
                    libxext
                    libxi
                    libxrender
                    libxtst
                  ]
                )
              }
            '';
        });
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs =
            # android-cli first: cmdline-tools also ships an `android`
            # bootstrap that downloads an unpinned CLI at runtime.
            pkgs.lib.optional (androidCliHashes ? ${system}) androidCli
            ++ (with pkgs; [
              jdk21 # AGP 9.x / Gradle 9.x
              gradle # only to bootstrap ./gradlew; use the wrapper afterwards
              androidSdk # platform 37, build-tools, platform-tools (adb)
              ktlint
            ]);

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME = "${pkgs.jdk21}";

          # AGP's Maven aapt2 is a generic Linux binary that NixOS can't run;
          # use the Nix-patched one from the SDK instead.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

          shellHook = ''
            export PATH="$ANDROID_HOME/platform-tools:$PATH"
            alias logcat-ime='adb logcat --pid=$(adb shell pidof com.m57.mboard)'
            echo "mboard dev shell: $(java -version 2>&1 | head -1), adb $(adb version | head -1 | awk '{print $NF}')"
          '';
        };
      }
    );
}
