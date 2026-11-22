# HungerBridge

HungerBridge is a server-side **Paper / Purpur / Folia** plugin and **Fabric** mod that exposes
a small, authenticated HTTP API for running console commands (with their output), writing
to the server log, streaming the console and reading server, world and JVM metrics, without
RCON.

* Download: **[Modrinth](https://modrinth.com/project/hungerbridge)**, the only release channel.
* Python client: [HungerBridgePy](https://github.com/HungerNet/HungerBridgePy) (`pip install hungerbridge`).
* API reference: [ENDPOINTS.md](ENDPOINTS.md) (protocol v2). Upgrading from 4.x:
  [docs/migrating-to-5.md](docs/migrating-to-5.md).

## Supported platforms

| Minecraft | Paper / Purpur | Folia | Fabric (Loader ≥ 0.19.2, no Fabric API needed) | Java |
|---|---|---|---|---|
| 1.21.11 | ✅ | ✅ | ✅ | 21+ |
| 26.1.2 | ✅ | ✅ | ✅ | 25+ |
| 26.2 | ✅ | ✅ (Folia beta) | ✅ | 25+ |
| 26.3 | ✅ | ✅ (Folia alpha) | ✅ | 25+ |

One jar per Minecraft version and loader family: the Paper jar runs on Paper, Purpur and
Folia (the threading model is detected at runtime). Every cell is boot-tested in CI on a
real server (see [BUILDING.md](BUILDING.md#ci)). Forge and NeoForge are not supported.

## Quick start

1. Download the jar for your Minecraft version and loader from Modrinth, put it into
   `plugins/` (Paper/Purpur/Folia) or `mods/` (Fabric) and start the server. Configuration
   is generated in `plugins/HungerBridge/` or `config/HungerBridge/`.
2. Add a policy to `policies.yaml` if the shipped read-only `default` policy is not enough,
   then run `/hb reload`:

   ```yaml
   policies:
     - id: admin
       default-expiry: 0
       permissions: ["*"]
   ```

3. From the **server console**: `/hb token create my-bot admin`

   ```text
   HungerBridge » Token created
     ✔ my-bot uses policy admin
     • Pickup URL  /pickup/<uuid>?passkey=<hex>
     ▲ The pickup URL works once and expires in 5 minutes
   ```

4. Redeem it from your client:

   ```python
   from hungerbridge import BridgeClient

   client = BridgeClient.fromPickup("http://127.0.0.1:1913/pickup/<uuid>?passkey=<hex>")
   print(client.runCommand("list"))                  # output is returned, not printed on the console
   client.log("backup done", level="BACKUP", logger="NightlyBot")
   for event in client.streamLogs(history=20):
       print(event.line)
   ```

   Later runs use the token directly:
   `BridgeClient("http://127.0.0.1:1913", token_id="my-bot", token_secret="<64 hex>")`.

### From CalagoAPI (Calagopus panel)

The `calagoapi-minecraft` extension of [CalagoAPI](https://github.com/HungerNet/CalagoAPI)
combines the panel API with HungerBridge (through HungerBridgePy):

```python
server = panel.minecraft.getServer('abc123', bridge_url='http://10.0.0.5:1913')
server.installBridge(minecraft_version='1.21.11', loader='paper')   # from Modrinth
server.createBridgeToken()          # runs /hb token create on the console and redeems the pickup
print(server.sendCommand('list'))
```

## Console output

HungerBridge writes its own messages as the `HungerBridge` logger, so every loader shows
`[HungerBridge] …`. Log lines written through the API carry the caller's level, thread and
logger. On Fabric (vanilla console layout):

```text
[10:53:54] [Server thread/INFO]: [HungerBridge] HTTP API listening on 127.0.0.1:1913 (protocol v2, 21 endpoints)
[10:53:54] [CustomThread/CUSTOMLEVEL]: [CustomLogger] example message
```

Paper prints the same messages in its own layout (`[10:53:54 CUSTOMLEVEL]: [CustomLogger] example message`).
The log stream (`GET /server/stream`) uses the first format on every loader.

`/hb` output uses one visual language on all loaders: a `HungerBridge »` header, then
`✔` success, `•` key/value details, `▲` warnings, `✖` errors and `›` usage lines. It reads
the same without colours (Fabric's dedicated console prints plain text).

## Configuration

`config.yaml`

```yaml
port: 1913
bind-address: 127.0.0.1   # keep it local unless you put TLS in front of it
debug: false              # HMAC diagnostics in the log (level HBDEBUG); never in production
players:
  max-list: 50            # cap on names returned by /players/list
```

`security.yaml` — `skew-seconds` (timestamp window, default 300), `rate-limits` (`per-ip`,
`per-token`, `pickup` token buckets) and `ips` (`whitelist`/`blacklist`, CIDR supported;
default: localhost only).

`policies.yaml` — named permission sets (see ENDPOINTS.md → *Permission nodes*). Nodes no
endpoint checks are reported in the log at load time.

`/hb reload` re-reads all three files; `port` and `bind-address` changes need a restart
(the log says so). Runtime files are created only when missing, edits are never
overwritten. Secrets live in `storage/` (`master.key`, `tokens.json`, `pickups.json`,
`sessions.json`, owner-only permissions where supported); audit logs in `logs/<date>.audit.log`.

## Console commands (console only)

```
/hb help
/hb reload
/hb token list
/hb token create <id> <policy> [expirySeconds]
/hb token rotate <id>      (new secret, printed as a new single-use pickup URL)
/hb token revoke <id>
/hb token remove <id>
```

`/hb` is short for `/hungerbridge`.

## Building

See [BUILDING.md](BUILDING.md). Design and status of the current modernization:
[PLAN.md](PLAN.md); changes: [CHANGELOG.md](CHANGELOG.md).
