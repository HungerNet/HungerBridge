# HungerBridge HTTP API — protocol v2

Base URL: `http://<bind-address>:<port>` (default `http://127.0.0.1:1913`). Every response
carries `X-HungerBridge-Protocol: 2`; clients must reject other versions (HungerBridgePy
raises `HungerBridgeProtocolError`). HungerBridge 5.x speaks protocol 2. Python examples use
[HungerBridgePy](https://github.com/HungerNet/HungerBridgePy) 2.x.

## Conventions

* JSON keys are `snake_case`.
* **Success**: a flat JSON object with `"ok": true` plus endpoint fields.
* **Error**: `{"ok": false, "error": "<code>", "message": "<text>", "reason": "<optional>"}`

| HTTP | `error` | `reason` / notes |
|---|---|---|
| 400 | `bad_request` | body is not a JSON object, a field has the wrong type or an invalid value, a required field is missing |
| 401 | `unauthorized` | `missing_credentials`, `invalid_credentials` (unknown token *or* bad signature — deliberately indistinguishable), `timestamp_out_of_range`, `nonce_replay`, `token_revoked`, `token_expired` |
| 403 | `forbidden` | token lacks the endpoint's permission node; `ip_not_allowed` (security.yaml `ips`) |
| 404 | `not_found` | unknown endpoint or pickup |
| 405 | `method_not_allowed` | `Allow` header names the method |
| 413 | `request_too_large` | body > 1 MiB |
| 429 | `rate_limited` | `Retry-After` header (seconds) is always sent |
| 500 | `internal_error` | |
| 503 | `too_many_clients` / `unavailable` | stream limit (64) reached / token manager not ready |
| 504 | `command_timeout` | `/server/run`: the server thread did not run the command within 5 s (it may still run) |

## Authentication (HMAC-SHA256)

Headers: `X-Auth-Id` (token id), `X-Auth-Timestamp` (unix seconds), `X-Auth-Nonce` (unique
per request), `X-Auth-Signature` = lower-case hex `HMAC-SHA256(secret, string_to_sign)`:

```
string_to_sign = METHOD "\n" path "\n" timestamp "\n" nonce "\n" canonical_body
```

* `path`: request path without query/fragment, leading `/`, no trailing `/`.
* `canonical_body`: empty for GET; otherwise the JSON body with object keys sorted (UTF-16
  order), no whitespace, `"` `\` and `\b \f \n \r \t` escaped as usual, and every other
  character below U+0020 or above U+007F written as a lower-case `\uXXXX` escape
  (characters outside the BMP as a UTF-16 surrogate pair). Send exactly this text as the body.
* `secret`: the token secret from the pickup, 64 hex characters (decode → 32-byte key).
  Clients take the token id and the secret as two separate values.

Checks, in order: rate limit → timestamp within `skew-seconds` (default 300) → token +
signature → nonce not seen before (only authentic requests are recorded) → revocation /
expiry → permission node. Golden test vectors: `common/src/test/resources/protocol-vectors.json`
(shared with HungerBridgePy).

### Tokens

`/hb token create <tokenId> <policyId> [expirySeconds]` (server console only) prints a
pickup URL. `GET /pickup/<uuid>?passkey=<hex>` (unauthenticated, single use, expires after
5 minutes) → `{"ok": true, "token_id": "...", "token_secret": "<64 hex>"}`.

```python
client = BridgeClient.fromPickup("http://127.0.0.1:1913/pickup/<uuid>?passkey=<hex>")
```

### Permission nodes

`server.ping`, `server.meta`, `server.run`, `server.stop`, `server.log`, `server.stream`,
`players.list`, `world.tps`, `world.mspt`, `world.time`, `world.weather`, `world.chunks`,
`world.entities`, `system.uptime`, `system.cpu`, `system.memory`, `system.gc`,
`system.threads`, `system.network`. Wildcards: `server.*`, `world.*`, `system.*`, `*`.
`/auth/check` needs no node.

### Rate limits

Token buckets from `security.yaml` (`per-ip`, `per-token`, `pickup`), applied once per
request *before* signature verification, so a throttled request has no side effect and
can be retried (with a fresh nonce).

---

## Endpoints

| Endpoint | Node | Response fields | HungerBridgePy |
|---|---|---|---|
| `GET /ping` | `server.ping` | `server_time` (epoch ms) | `isOk()`, `getServerTime()`, `getPing()` |
| `GET /auth/check` | — | `token_id`, `policy_id`, `permissions` (effective), `expiry` (unix s, 0 = never) | `getTokenInfo()` |
| `GET /server/meta` | `server.meta` | `platform` (`paper`/`folia`/`fabric`), `minecraft_version`, `bridge_version`, `port`, `protocol_version` | `getServerMeta()`, `getPlatform()`, `getMinecraftVersion()`, `getBridgeVersion()` |
| `POST /server/run` | `server.run` | `output` (feedback lines) | `runCommand(command, show_console=False)` |
| `POST /server/log` | `server.log` | `message` (as logged, after normalization) | `log(message, level, thread, logger, normalize)` |
| `POST /server/stop` | `server.stop` | `stopped` | `stopServer()` |
| `GET /server/stream` | `server.stream` | SSE, see below | `streamLogs()`, `openStream()` |
| `GET /players/list` | `players.list` | `count` (online), `max` (slots), `players` (names, capped by `players.max-list`) | `getPlayers()`, `getPlayerCount()`, `getMaxPlayers()`, `getPlayerStats()` |
| `GET /world/tps` | `world.tps` | `tps`, `tps_1m`, `tps_5m`, `tps_15m` | `getTPS(window)`, `getTPSStats()` |
| `GET /world/mspt` | `world.mspt` | `mspt` | `getMSPT()` |
| `GET /world/time` | `world.time` | `time` (tick of day 0–23999), `full_time`, `day`, `server_time` (epoch s) | `getWorldTime()`, `getWorldTimeInfo()` |
| `GET /world/weather` | `world.weather` | `weather`: `clear`/`rain`/`thunder` | `getWorldWeather()` |
| `GET /world/chunks` | `world.chunks` | `total`, `world`, `world_nether`, `world_the_end` (+ other dimensions) | `getLoadedChunks()` |
| `GET /world/entities` | `world.entities` | same keys | `getLoadedEntities()` |
| `GET /system/uptime` | `system.uptime` | `uptime_ms` | `getUptime()` |
| `GET /system/cpu` | `system.cpu` | `cpu_load` (process, 0–1), `processors` | `getCPUStats()` |
| `GET /system/memory` | `system.memory` | `heap_*_bytes`, `nonheap_*_bytes` (`nonheap_max_bytes` null when unbounded), `jvm_*_bytes`, `process_used_bytes`, `process_virtual_bytes` | `getMemoryStats(unit)` |
| `GET /system/gc` | `system.gc` | `gc_type`, `gc_count`, `gc_time_ms`, `last_gc_pause_ms`, `avg_gc_pause_ms` | `getGCStats()` |
| `GET /system/threads` | `system.threads` | `current`, `peak`, `daemon` | `getThreadStats()` |
| `GET /system/network` | `system.network` | `bytes_in_per_sec`, `bytes_out_per_sec`, `total_bytes_in`, `total_bytes_out` (Linux, loopback excluded; 0 elsewhere) | `getNetworkStats()` |
| `GET /pickup/<id>?passkey=` | — | `token_id`, `token_secret` | `redeemPickup(url)`, `BridgeClient.fromPickup(url)` |

Notes: dimension keys are by environment (the overworld is always `world`); a world that
could not be sampled reports `-1`. TPS on Folia is the TPS of the region owning the
overworld spawn. Unavailable metrics report `-1`.

### `POST /server/run`

```json
{ "command": "seed", "show_console": false }
→ { "ok": true, "output": ["Seed: [1234567890]"] }
```

Runs with console permissions on the main thread (Paper/Purpur), the global region (Folia)
or the server thread (Fabric). The output is the command's **feedback**, captured from a
dedicated command source (Paper: `Bukkit.createCommandSender`, Fabric: a `CommandSource`
on the console's `CommandSourceStack`):

* `show_console: false` (default): the feedback is returned and **not** written to the
  server console.
* `show_console: true`: each feedback line is also sent to the console, exactly as the
  console's own commands print it.

Effects the game logs by itself are not feedback and are logged either way (for example the
chat broadcast of `/say`). Operators are not sent `[Server: …]` admin notices for bridge
commands. A timeout is a 504 `command_timeout`.

### `POST /server/log`

```json
{ "message": "example message", "level": "CUSTOMLEVEL", "thread": "CustomThread",
  "logger": "CustomLogger", "normalize": false }
→ { "ok": true, "message": "example message" }
```

| Field | Default | Rules |
|---|---|---|
| `message` | required | no control characters except tab and newline |
| `level` | `INFO` | built-in (`INFO`, `WARN`, `ERROR`, `DEBUG`, `TRACE`) or custom: `[A-Za-z][A-Za-z0-9_]{0,31}`, upper-cased; at most 64 custom levels per server run |
| `thread` | `HungerBridge` | 1–64 characters, no control characters |
| `logger` | `HungerBridge` | 1–64 characters, no control characters |
| `normalize` | `false` | reduce the message to plain text first (below) |

Rendered as `[10:53:54] [CustomThread/CUSTOMLEVEL]: [CustomLogger] example message` in the
stream on every loader and on the Fabric console; Paper's console layout shows
`[10:53:54 CUSTOMLEVEL]: [CustomLogger] example message`. Custom levels rank between INFO
and WARN, so they are always shown.

**Normalization** (`"normalize": true`), identical on every loader:

1. a JSON text component (`{"text":…}`, `[…]`, `"…"`) is flattened to its text (`text`,
   `translate` → its `fallback`, `keybind`, `selector`, then `extra`);
2. ANSI escapes and `§` formatting codes are removed;
3. leading chat-channel decorations are removed, repeatedly: `System chat:`, `[System]`,
   `[CHAT]`, `[Not Secure]`, `[Server]`, `[Rcon]` (rules are declared per Minecraft version
   range; all apply to every supported version);
4. if nothing is left, the original message is kept.

`"System chat: Hello"` → `"Hello"`. The response's `message` is what was logged.

### `GET /server/stream` (Server-Sent Events)

Start point: `Last-Event-ID: <epoch>.<seq>` (resume after that event) or, on a first
connect, `?history=<N>` / `?history=all` (replay the N newest events; default 0). The server
keeps the last 1000 events.

```
retry: 2000

id: 9f1c2b7a.41
event: log
data: {"seq":41,"time":1763650434123,"level":"INFO","thread":"Server thread","logger":"net.minecraft.server.MinecraftServer","message":"Steve joined the game","line":"[10:53:54] [Server thread/INFO]: Steve joined the game"}

event: ready
data: {"epoch":"9f1c2b7a","seq":41,"replayed":1,"gap":false}

: keepalive
```

| `log` field | Meaning |
|---|---|
| `seq` | position in this server run's event sequence (strictly increasing from 1) |
| `time` | epoch milliseconds |
| `level`, `thread`, `logger` | as logged (Minecraft's own loggers are `net.minecraft.*`) |
| `message` | the message as logged (passkeys redacted) |
| `throwable` | stack trace text, only when the event has one |
| `line` | canonical plain-text rendering: `[HH:mm:ss] [thread/LEVEL]: [logger] message` (logger omitted for Minecraft's own loggers), stack trace on following lines, ANSI/`§` codes removed, server time zone |

* Replayed events come first, then exactly one `ready` event, then live events. Every event
  is delivered once and in order; there is no overlap between replay and live events.
* `epoch` changes when the server restarts. A `Last-Event-ID` from another epoch replays
  everything still buffered. `gap: true` means events after the resume point are no longer
  buffered and were lost.
* A client that falls 1024 events behind is disconnected (not silently thinned); it
  reconnects with `Last-Event-ID` and gets exactly what it missed.
* Keepalive comments every 15 s; clients should treat ~45 s of silence as a dead connection.
* Malformed `history` or `Last-Event-ID` values are a 400.

```python
for event in client.streamLogs(history=50):          # reconnects and resumes by default
    print(event.level, event.logger, event.message)
```

## Removed in protocol v2

`silent` on `/server/run`; `force` on `/server/stop`; camelCase `tokenId`/`policyId` on
`/auth/check`; `used_bytes`, `total_bytes`, `free_bytes`, `max_bytes` on `/system/memory`;
the text-line SSE format and `:history-end`; the legacy permission names `server.info`,
`player.list`, `world.read`, `ping`. See [docs/migrating-to-5.md](docs/migrating-to-5.md).
