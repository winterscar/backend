{ config, lib, pkgs, ... }:

let
  cfg = config.services.pasquet-backend;
in
{
  options.services.pasquet-backend = {
    enable = lib.mkEnableOption "pasquet-backend Biff application";

    domain = lib.mkOption {
      type = lib.types.str;
      description = "Domain name for the application.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 8080;
      description = "Port the application listens on.";
    };

    nreplPort = lib.mkOption {
      type = lib.types.port;
      default = 7888;
      description = "nREPL server port.";
    };

    authorizedKeys = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [];
      description = "SSH public keys authorized for the app user.";
    };
  };

  config = lib.mkIf cfg.enable {
    # Packages needed on the server
    environment.systemPackages = with pkgs; [
      clojure
      jdk21
      rlwrap
      rsync
      git
    ];

    # Trenchman for soft-deploy (nREPL eval over CLI)
    # If not packaged in nixpkgs, we fetch the binary
    systemd.services.pasquet-backend-trench-install = {
      description = "Install trenchman binary";
      wantedBy = [ "multi-user.target" ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
      };
      path = with pkgs; [ curl gnutar gzip ];
      script = let
        version = "0.4.0";
        arch = if pkgs.stdenv.hostPlatform.isAarch64 then "arm64" else "amd64";
        file = "trenchman_${version}_linux_${arch}.tar.gz";
      in ''
        if [ ! -f /usr/local/bin/trench ]; then
          mkdir -p /usr/local/bin
          curl -sSLf "https://github.com/athos/trenchman/releases/download/v${version}/${file}" \
            | tar zxvfC - /usr/local/bin trench
        fi
      '';
    };

    # The app user - Biff tasks SSH in as app@server
    users.users.app = {
      isNormalUser = true;
      home = "/home/app";
      openssh.authorizedKeys.keys = cfg.authorizedKeys;
    };

    # Sudoers rules that Biff tasks expect
    security.sudo.extraRules = [
      {
        users = [ "app" ];
        commands = [
          { command = "/run/current-system/sw/bin/systemctl reset-failed app.service"; options = [ "NOPASSWD" ]; }
          { command = "/run/current-system/sw/bin/systemctl restart app"; options = [ "NOPASSWD" ]; }
        ];
      }
    ];

    # Git bare repo for git-based deploys (rsync is preferred but this is a fallback)
    systemd.services.pasquet-backend-git-setup = {
      description = "Set up git bare repo for app deploys";
      wantedBy = [ "multi-user.target" ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        User = "app";
      };
      script = ''
        if [ ! -d /home/app/repo.git ]; then
          mkdir -p /home/app/repo.git
          cd /home/app/repo.git
          ${pkgs.git}/bin/git init --bare
          cat > hooks/post-receive << 'EOF'
#!/usr/bin/env bash
git --work-tree=/home/app --git-dir=/home/app/repo.git checkout -f
EOF
          chmod +x hooks/post-receive
        fi
      '';
    };

    # The app systemd service - matches what Biff tasks expect
    systemd.services.app = {
      description = "pasquet.backend";
      after = [ "network.target" ];
      wantedBy = [ "multi-user.target" ];

      startLimitIntervalSec = 500;
      startLimitBurst = 5;

      serviceConfig = {
        User = "app";
        Restart = "on-failure";
        RestartSec = "5s";
        WorkingDirectory = "/home/app";
        ExecStart = "${pkgs.bash}/bin/bash -c 'mkdir -p target/resources; ${pkgs.clojure}/bin/clj -M:prod'";
      };

      environment = {
        BIFF_PROFILE = "prod";
        JAVA_HOME = "${pkgs.jdk21}";
      };
    };
  };
}
