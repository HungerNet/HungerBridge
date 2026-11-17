# HungerBridge HTTP API — protocol v1

Base URL: `http://<bind-address>:<port>` (default `http://127.0.0.1:1913`). Every response
carries `X-HungerBridge-Protocol: 1`. Python examples use
[HungerBridgePy](https://github.com/HungerNet/HungerBridgePy) (`pip install hungerbridge`).

## Conventions

**Success** — a flat JSON object with `"ok": true` plus endpoint fields (there is no `data`
envelope):

```json
{ "ok": true, "tps": 20.0, "tps_1m": 19.9, "tps_5m": 19.8, "tps_15m": 19.8 }
```

**Error** — `{"ok": false, "error": "<code>", "message": "<text>", "reason": "<optional>"}`

| HTTP | `error` | `reason` (when present) |
|---|---|---|
| 400 | `bad_request` | body is not a JSON object / field has the wrong type / required field missing |
| 401 | `unauthorized` | `missing_credentials`, `invalid_credentials` (unknown token *or* bad signature — deliberately indistinguishable), `timestamp_out_of_range` (clock skew > `skew-seconds`), `nonce_replay`, `token_revoked`, `token_expired` |
| 403 | `forbidden` | token lacks the endpoint's permission node; `ip_not_allowed` (security.yaml `ips`) |
| 404 | `not_found` | unknown endpoint or pickup |
| 405 | `method_not_allowed` | (`Allow` header names the method) |
| 413 | `request_too_large` | body > 1 MiB |
| 429 | `rate_limited` | `Retry-After` header (seconds) is always sent |
| 500 | `internal_error` | |
| 503 | `too_many_clients` / `unavailable` | SSE client limit (64) reached |

## Authentication (HMAC-SHA256)

Headers: `X-Auth-Id`, `X-Auth-Timestamp` (unix seconds), `X-Auth-Nonce` (unique per
request), `X-Auth-Signature` = lower-case hex `HMAC-SHA256(secret, string_to_sign)`:

```
string_to_sign = METHOD "\n" path "\n" timestamp "\n" nonce "\n" canonical_body
```

* `path`: request path without query/fragment, leading `/`, no trailing `/`.
* `canonical_body`: empty for GET; otherwise the JSON body with object keys sorted (UTF-16
  order), no whitespace, `"` `\` and `\b \f \n \r \t` escaped as usual, and every other
  character below U+0020 or above U+007F written as a lower-case `\uXXXX` escape
  (characters outside the BMP as a UTF-16 surrogate pair). Send exactly this canonical text
  as the body.
* `secret`: the 64-hex-character token secret from the pickup (decode hex → 32-byte key).

Checks, in order: rate limit → timestamp within `skew-seconds` (default 300) → token +
signature → nonce not seen before (only authentic requests are recorded) → revocation /
expiry → permission node. Golden test vectors: `common/src/test/resources/protocol-vectors.json`.

### Tokens

```
/hb token create <tokenId> <policyId> [expirySeconds]    (server console only)
  → Pickup passkey: <hex>
  → Token created. Retrieve it at: /pickup/<uuid>?passkey=<hex>
```

`GET /pickup/<uuid>?passkey=<hex>` (unauthenticated, single use, expires after 5 minutes)
→ `{"ok": true, "token_id": "...", "token_secret": "<64 hex>"}`.

```python
client = BridgeClient.fromPickup("http://127.0.0.1:1913/pickup/<uuid>?passkey=<hex>")
```

### Permission nodes

`server.ping`, `server.meta`, `server.run`, `server.stop`, `server.log`, `server.stream`,
`players.list`, `world.tps`, `world.mspt`, `world.time`, `world.weather`, `world.chunks`,
`world.entities`, `system.uptime`, `system.cpu`, `system.memory`, `system.gc`,
`system.threads`, `system.network`. Wildcards: `server.*`, `world.*`, `system.*`, `*`.
`/auth/check` needs no node. Legacy names from older default policies are still accepted
(with a warning): `server.info` → `server.meta`+`server.ping`, `player.list` →
`players.list`, `world.read` → `world.*`, `ping` → `server.ping`.

### Rate limits

Token buckets from `security.yaml` (`per-ip`, `per-token`, `pickup`), applied once per
request *before* signature verification, so a throttled request can be retried unchanged.

---

## Endpoints

| Endpoint | Node | Response fields | Python |
|---|---|---|---|
| `GET /ping` | `server.ping` | `server_time` (epoch ms) | `isOk()`, `getServerTime()`, `getPing()` |
| `GET /auth/check` | — | `tokenId`, `policyId`, `permissions` (effective), `expiry` | `getTokenInfo()` |
| `GET /server/meta` | `server.meta` | `platform` (`paper`/`folia`/`fabric`), `minecraft_version`, `bridge_version`, `port`, `protocol_version` | `getServerMeta()`, `getPlatform()`, `getProtocolVersion()` … |
| `POST /server/run` | `server.run` | `output` (list of captured lines; omitted when `silent`) | `runCommand(cmd, showConsole, silent, normalize)` |
| `POST /server/log` | `server.log` | — | `log(message, level, thread)` |
| `POST /server/stop` | `server.stop` | `stopped`, `force` | `stopServer()` |
| `GET /server/stream` | `server.stream` | SSE, see below | `streamLogs()`, `client.stream`, `openStream()` |
| `GET /players/list` | `players.list` | `count` (online), `max` (slots), `players` (names, capped by `players.max-list`) | `getPlayers('count'\|'list')`, `getMaxPlayers()` |
| `GET /world/tps` | `world.tps` | `tps`, `tps_1m`, `tps_5m`, `tps_15m` | `getTPS(mode)` |
| `GET /world/mspt` | `world.mspt` | `mspt` | `getMSPT()` |
| `GET /world/time` | `world.time` | `time` (tick of day 0–23999), `full_time`, `day`, `server_time` (epoch s) | `getWorldTime()`, `getWorldTimeInfo()` |
| `GET /world/weather` | `world.weather` | `weather`: `clear`/`rain`/`thunder` | `getWorldWeather()` |
| `GET /world/chunks` | `world.chunks` | `total`, `world`, `world_nether`, `world_the_end` | `getLoadedChunks()` |
| `GET /world/entities` | `world.entities` | same keys | `getLoadedEntities()` |
| `GET /system/uptime` | `system.uptime` | `uptime_ms` | `getUptime()` |
| `GET /system/cpu` | `system.cpu` | `cpu_load` (process, 0–1), `processors` | `getCPUStats()` |
| `GET /system/memory` | `system.memory` | `heap_*_bytes`, `nonheap_*_bytes` (`nonheap_max_bytes` null when unbounded), `jvm_*_bytes`, `process_used_bytes`, `process_virtual_bytes`; legacy `used_bytes`, `total_bytes`, `free_bytes`, `max_bytes` | `getMemoryStats(unit)` |
| `GET /system/gc` | `system.gc` | `gc_type` (G1/ZGC/Shenandoah/Parallel/Serial), `gc_count`, `gc_time_ms`, `last_gc_pause_ms`, `avg_gc_pause_ms` | `getGCStats()` |
| `GET /system/threads` | `system.threads` | `current`, `peak`, `daemon` | `getThreadStats()` |
| `GET /system/network` | `system.network` | `bytes_in_per_sec`, `bytes_out_per_sec`, `total_bytes_in`, `total_bytes_out` (Linux `/proc/net/dev`, loopback excluded; 0 elsewhere) | `getNetworkStats()` |
| `GET /pickup/<id>?passkey=` | — | `token_id`, `token_secret` | `BridgeClient.redeemPickup(url)` |

Notes:

* Dimension keys are by environment: the overworld is always `world`, regardless of
  `level-name`; extra worlds/dimensions appear under their own name/id and count towards
  `total`. A world that could not be sampled reports `-1` (excluded from `total`).
* TPS on Folia is the TPS of the region owning the overworld spawn. Metrics that are not
  available report `-1`.
* `/server/run` executes as the console on the main thread (Paper/Fabric) or the global
  region (Folia) and waits up to 5 s for the result.

### Request bodies

```jsonc
// POST /server/run
{ "command": "say hi", "silent": false, "show_console": false }
// POST /server/log — level: any builtin (INFO, WARN, ERROR, DEBUG, TRACE) or custom name
{ "message": "hello", "level": "BRIDGE", "thread": "my-bot" }
// POST /server/stop
{ }
```

### `GET /server/stream[?history=N]` (Server-Sent Events)

```
data:[12:00:01 INFO]: <oldest of the last N lines>        (history, N ≤ 200)
data:[12:00:02 INFO]: ...
:history-end                                              (always sent; also flushes headers)
data:[12:00:05 WARN]: <live line>
:keepalive                                                (every 15 s)
```

Each event is one console log event formatted `[HH:mm:ss LEVEL]: message` (server time zone);
stack traces follow on further lines. Line breaks inside an event are escaped as the two
characters `\n` / `\r`. Pickup passkeys are redacted. Clients that predate `:history-end`
ignore it (SSE comment).

```python
# blocking iterator
for line in client.streamLogs(history=50, reconnect=True, sanitize=True):
    print(line)

# or callbacks in a background thread
client.stream.connect(history=50, reconnect=True)
```

Errors opening the stream use the same status codes and `reason`s as other endpoints;
HungerBridgePy raises them as typed errors and does not reconnect after 401, 403 or 404.

[CalagoAPI](https://github.com/HungerNet/CalagoAPI)'s `calagoapi-minecraft` extension builds
`MinecraftServer.sendCommand()` (`/server/run`) and `streamConsole()` (this stream) on these
HungerBridgePy calls.

## Removed / never registered

`/server/info`, `/server/status`, `/server/restart`, `/tokens` and the `/v2` prefix are not
part of the API (their handler classes were dead code and have been deleted).
