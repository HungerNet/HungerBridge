# Migrating to HungerBridge 5 (protocol v2)

HungerBridge 5 changes the wire protocol (v1 → v2). Use it with **HungerBridgePy 2** and
**calagoapi-minecraft 2**; older clients are rejected (they would misread the stream).
Backward compatibility was deliberately not kept. Releases come from
[Modrinth](https://modrinth.com/project/hungerbridge) only.

## Server operators

1. Replace the jar (Modrinth, or `server.installBridge(minecraft_version=..., loader=...)`
   from CalagoAPI). Configuration and tokens carry over.
2. `policies.yaml`: the legacy names `server.info`, `player.list`, `world.read` and `ping`
   no longer grant anything (they are logged as unknown nodes). Use `server.meta` +
   `server.ping`, `players.list`, `world.*` and `server.ping`.
3. `/hb tokens` is gone; use `/hb token`. `/hb` output has a new layout; the pickup line
   still contains `/pickup/<id>?passkey=<hex>`.
4. `/hb reload` now also applies `players.max-list`; `port` and `bind-address` still need a
   restart (and the log says so).
5. Debug diagnostics (`debug: true`) are logged at the level `HBDEBUG`.

## API clients

| Protocol v1 | Protocol v2 |
|---|---|
| `X-HungerBridge-Protocol: 1` | `2` (reject anything else) |
| `/server/run` `{"command", "silent", "show_console"}` (`show_console` had no effect) | `{"command", "show_console"}`; `false` keeps the output off the console; output always returned; 504 `command_timeout` on timeout |
| `/server/log` `{"message", "level", "thread"}` | adds `logger` and `normalize`; names are validated (400); response has the logged `message` |
| `/auth/check` `tokenId`, `policyId` | `token_id`, `policy_id` |
| `/server/stop` `{"force"}` → `stopped`, `force` | `{}` → `stopped` |
| `/system/memory` `used_bytes`, `total_bytes`, `free_bytes`, `max_bytes` | `heap_used_bytes`, `heap_committed_bytes`, `heap_max_bytes` |
| SSE `data:[HH:mm:ss LEVEL]: message` lines, `\n` escaped as two characters, `:history-end` comment, history ≤ 200 | `event: log` with a JSON payload and `id: <epoch>.<seq>`, an `event: ready` after the replay, `Last-Event-ID` resume, `history=N\|all` (ring of 1000) |

Stream lines are now `[HH:mm:ss] [thread/LEVEL]: [logger] message` (the `line` field).

## Developers

* `CommandExecutor.run(command, showConsole)` replaces `execute`/`executeWithOutput`;
  `CommandCapture` (log scraping) is removed.
* `Logger.log(LogEntry)` + `info/warn/error/debug` replace `log(level, [thread,] message)`;
  `BridgeLogger` replaces the Paper and Fabric logger adapters.
* `HB_TARGET` replaces `STONECUTTER_ACTIVE`.
