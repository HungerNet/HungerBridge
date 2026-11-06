# HungerBridge

HungerBridge is a server-side **Paper / Purpur / Folia** plugin and **Fabric** mod that exposes
a small, authenticated HTTP API for running console commands, writing to the server log,
streaming the console (SSE) and reading server/world/JVM metrics — without RCON.

The Python client is [HungerBridgePy](https://github.com/HungerNet/HungerBridgePy)
(`pip install hungerbridge`).

## Supported platforms

| Minecraft | Paper / Purpur | Folia | Fabric (Loader ≥ 0.19.2, **no Fabric API needed**) | Java |
|---|---|---|---|---|
| 1.21.11 | ✅ | ✅ | ✅ | 21+ |
| 26.1.2 | ✅ | ✅ | ✅ | 25+ |
| 26.2 | ✅ | ✅ (Folia beta) | ✅ | 25+ |
| 26.3 | ✅ | ✅ (Folia alpha) | ✅ | 25+ |

Every cell was boot-tested on a real server and passed the HungerBridgePy live protocol
suite (see BUILDING.md → *Runtime validation*). One jar per Minecraft version: the Paper jar
runs on Paper, Purpur and Folia (the threading model is detected at runtime).

## Quick start

1. Drop the jar into `plugins/` (Paper/Purpur/Folia) or `mods/` (Fabric) and start the server.
   Configuration is generated in `plugins/HungerBridge/` or `config/HungerBridge/`.
2. Add a policy to `policies.yaml` if the shipped read-only `default` policy is not enough:

   ```yaml
   policies:
     - id: admin
       default-expiry: 0
       permissions: ["*"]
   ```

   and run `/hb reload`.
3. From the **server console**: `/hb token create my-bot admin` — it prints a single-use
   pickup URL (`/pickup/<uuid>?passkey=<hex>`, valid 5 minutes).
4. Redeem it from your client:

   ```python
   from hungerbridge import BridgeClient
   client = BridgeClient.fromPickup("http://127.0.0.1:1913/pickup/<uuid>?passkey=<hex>")
   print(client.getServerMeta(), client.getTPS("1m"))
   print(client.runCommand("list"))
   ```

The full API (endpoints, authentication, error codes, SSE format) is in [ENDPOINTS.md](ENDPOINTS.md).

## Configuration

`config.yaml`

```yaml
port: 1913
bind-address: 127.0.0.1   # keep it local unless you put TLS in front of it
debug: false              # HMAC diagnostics in the log — never in production
players:
  max-list: 50            # cap on names returned by /players/list
```

`security.yaml` — `skew-seconds` (timestamp window, default 300), `rate-limits` (`per-ip`,
`per-token`, `pickup` token buckets) and `ips` (`whitelist`/`blacklist`, CIDR supported;
default: localhost only).

`policies.yaml` — named permission sets (see ENDPOINTS.md → *Permission nodes*). Unknown
or legacy node names are reported in the log at load time.

Runtime files are created only when missing — edits are never overwritten. Secrets live in
`storage/` (`master.key`, `tokens.json`, `pickups.json`, `sessions.json`, owner-only file
permissions where the filesystem supports them); audit logs in `logs/<date>.audit.log`.

## Console commands (console only)

```
/hb help
/hb reload
/hb token list
/hb token create <id> <policyId> [expirySeconds]
/hb token rotate <id>      (new secret, printed as a new single-use pickup URL)
/hb token revoke <id>
/hb token remove <id>
```

## Building

See [BUILDING.md](BUILDING.md). Architecture and the 2026-11 release-readiness work are
documented in [PLAN.md](PLAN.md) and [CHANGELOG.md](CHANGELOG.md); proposed next steps are in
[docs/design/](docs/design/README.md).
