{
  description = "Discord bot that verifies members of unofficial TCS @ UT Discord servers";

  nixConfig = {
    extra-substituters = [
      "https://nix-community.cachix.org"
      "https://cache.garnix.io"
      "https://attic.bartoostveen.nl/tcs-bot"
    ];
    extra-trusted-public-keys = [
      "nix-community.cachix.org-1:mB9FSh9qf2dCimDSUo8Zy7bkq5CX+/rkCWyvRCYg3Fs="
      "cache.garnix.io:CTFPyKSLcx5RMJKfLo5EEPUObbA78b0YQ2DTCJXqr9g="
      "tcs-bot:cUYt7f0r3vvOriZybjYHTKK+jFuJPdOrPII4aXBi+1Q="
    ];
    always-allow-substitutes = true;
  };

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";

    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    build-gradle-application = {
      url = "github:raphiz/buildGradleApplication";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs@{ self, flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
        "x86_64-darwin"
      ];

      imports = [
        inputs.treefmt-nix.flakeModule
      ];

      perSystem =
        { system, ... }:

        let
          pkgs = import inputs.nixpkgs {
            inherit system;
            allowUnfree = true;
            overlays = [
              inputs.build-gradle-application.overlays.default
              (final: _prev: {
                jdk = final.jdk25_headless;
                gradle = final.gradle_9;
              })
            ];
          };
          version = self.shortRev or "dirty";
          env = {
            JAVA_HOME = pkgs.jdk.home;
            GRADLE_JAVA_HOME = pkgs.jdk.home;
          };
        in
        rec {
          _module.args.pkgs = pkgs;

          treefmt = {
            programs.nixfmt.enable = true;
          };

          devShells.default = pkgs.mkShell {
            packages = with pkgs; [
              gradle
              jdk
              python314
              updateVerificationMetadata
            ];

            inherit env;

            shellHook = ''
              echo
              echo "Run ./gradle_generate_metadata.sh after adding/removing dependencies"
              echo "Use ./gradle_resolve_missing_metadata.py if you get stuck"
              echo "Use update-verification-metadata as a last resort"
              echo
            '';
          };

          packages.default = (pkgs.callPackage ./package.nix { inherit version; }).overrideAttrs {
            inherit env;
          };

          packages.cache =
            with pkgs;
            writeShellApplication {
              name = "cache";

              runtimeInputs = [
                attic-client
              ];

              text = ''
                if [[ -v ATTIC_TOKEN ]]; then
                  attic login tcs-bot-cache https://attic.bartoostveen.nl "$ATTIC_TOKEN"
                  attic push tcs-bot-cache:tcs-bot ${packages.default}
                else
                  attic push tcs-bot ${packages.default}
                fi
              '';
            };
        };
    };
}
