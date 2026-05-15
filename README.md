# pasquet.backend

A Clojure web service built with [Biff](https://biffweb.com).

## Development

Start the app locally:

```
clj -M:dev dev
```

This starts the app on http://localhost:8080 with an nREPL server on port 7888. Files are re-evaluated on save.

## Deployment

Deploy code and restart the app:

```
clj -M:dev deploy
```

Deploy without downtime (hot-reloads changed files):

```
clj -M:dev soft-deploy
```

Watch for changes and continuously deploy + tail logs + open nREPL tunnel:

```
clj -M:dev prod-dev
```

## Server

Tail application logs:

```
clj -M:dev logs
```

Restart the app:

```
clj -M:dev restart
```

Connect to the production nREPL:

```
clj -M:dev prod-repl
```

## Other commands

```
clj -M:dev css              # Generate CSS
clj -M:dev uberjar          # Build an uberjar
clj -M:dev generate-secrets # Generate new secrets for config.env
clj -M:dev --help           # List all available commands
```

## Server setup

The server is configured via the NixOS module in `nixos/module.nix`. Import it into your NixOS configuration:

```nix
services.pasquet-backend = {
  enable = true;
  domain = "backend.pasquet.co";
  authorizedKeys = [ "ssh-ed25519 ..." ];
};
```

Then `nixos-rebuild switch`. Point your nginx at `localhost:8080`.
