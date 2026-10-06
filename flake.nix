{
  description = "mboard - minimal, offline Android keyboard (EN/AR)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
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
            buildToolsVersions = [buildToolsVersion];
            platformVersions = ["37.0"];

            # Device testing happens on real hardware over adb: no emulator.
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          }).androidsdk;

        sdkRoot = "${androidSdk}/libexec/android-sdk";
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21 # AGP 9.x / Gradle 9.x
            gradle # only to bootstrap ./gradlew; use the wrapper afterwards
            androidSdk # platform 37, build-tools, platform-tools (adb)
            ktlint
          ];

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
