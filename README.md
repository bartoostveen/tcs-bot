# `tcs-bot`

Simple Discord bot that verifies members of unofficial TCS @ UT Discord servers.

![Screenshot of the verification embed in Discord](/screenshot.png)

## License

This code is released under the GPLv3 license. A copy is located at [LICENSE](LICENSE).

## Building

```shell
$ nix build .#
```

or, for the non-purists:

```shell
$ ./gradlew installDist
```

(and if you are on Windows: good luck)

## Environment variables

These variables are automatically loaded from the current working directory's `.env` file.

- `CANVAS_ACCESS_TOKEN`: Canvas access token (**required**)
- `CANVAS_BASE_URL`: (default: `https://canvas.utwente.nl`)
- `CANVAS_CA_BUNDLE`: ca bundle pointing to recent ca bundle (set of globally trusted certificates) (recommended: Canvas
  uses fairly new root certificates that are not yet bundled with a lot of JDKs) (example:
  `/etc/ssl/certs/ca-bundle.crt`)


- `REDIS_CONNECTION_STRING`: connection string for the Redis/Valkey cache (default: `localhost:6379`)
- `DATABASE_CONNECTION_STRING`: connection string for the PostgreSQL database (**required**)
- `DATABASE_USERNAME`: database username (default: `null`)
- `DATABASE_PASSWORD`: database username (default: `null`)


- `MICROSOFT_CLIENT_ID`: client id used for Microsoft authentication (**required**)
- `MICROSOFT_CLIENT_SECRET`: client secret used for Microsoft authentication (**required**)
- `MICROSOFT_AUTH_ENDPOINT`: authentication endpoint used for Microsoft authentication (**required**)


- `HOST`: host the http server listens on (default: `0.0.0.0`)
- `PORT`: port the http server listens on (default: `6969`)
- `HOSTNAME`: the hostname used for (for example) redirect URI's (**required**)
- `ENVIRONMENT`: either `DEVELOPMENT` or `PRODUCTION` (default: `PRODUCTION`)
- `METRICS_PREFIX`: ip address prefix Prometheus metrics are available on (doesn't affect `DEVELOPMENT` environment) (
  default: null)


- `DISCORD_ACCESS_TOKEN`: Discord Bot token (**required**)
- `DISCORD_DEPLOYER_ID`: list of users with bot management permissions (comma-separated) (**required**)
