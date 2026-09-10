# HungerBridge API v3 Endpoint Reference

Canonical HTTP API for HungerBridge (v3). Each endpoint shows a minimal curl example and the polished Python SDK usage (camelCase). For getters that return multiple fields, the SDK accepts a `field` argument (supports dot-paths) and returns a single extracted value.

## Response envelope

All responses follow a JSON envelope like:

```json
{ "ok": true, "message": "...", "data": { ... }, "error": null }
```

---

## Endpoints

`/ping` — liveness

```bash
curl -sS http://localhost:1913/ping
```

```python
client.ping()               # server timestamp
client.getPing()            # client RTT (ms)
```

`/auth/check` — token metadata

```bash
curl -sS -H "X-Auth-Id:<id>" -H "X-Auth-Timestamp:$(date +%s)" -H "X-Auth-Nonce:..." -H "X-Auth-Signature:<sig>" http://localhost:1913/auth/check
```

```python
client.authCheck('permissions')   # or any top-level field from the response
```

`/server/info` — bridge metadata

```bash
curl -sS http://localhost:1913/server/info
```

```python
client.getBridge('version')        # e.g. 'version', 'platform', 'minecraft'
client.getVersion()
client.getPlatform()
client.getMinecraftVersion()
```

`/server/meta` — server metadata

```bash
curl -sS http://localhost:1913/server/meta
```

```python
client.getServerMeta('some_field')
```

`/server/status` — runtime status

```bash
curl -sS http://localhost:1913/server/status
```

```python
client.getStatus()   # boolean 'ok' value
```

`/server/run` — execute command

```bash
curl -sS -X POST http://localhost:1913/server/run -H "Content-Type: application/json" -d '{"command":"say hello","silent":false,"show_console":true}'
```

```python
output = client.runCommand('say hello', show_console=True, silent=False)
```

`/server/stop` — stop the Minecraft server

```bash
curl -sS -X POST http://localhost:1913/server/stop -H "Content-Type: application/json" -d '{}'
```

```python
client.stopServer()   # stops the Minecraft server; does not stop the HTTP bridge
```

> Restart is no longer supported. The `/server/restart` endpoint has been removed.

`/server/log` — emit bridge log

```bash
curl -sS -X POST http://localhost:1913/server/log -H "Content-Type: application/json" -d '{"level":"BRIDGE","thread":"bridge-thread","message":"hello"}'
```

```python
client.log('hello', level='BRIDGE', thread='bridge-thread')
```

The `level` field accepts any string, including builtins such as `INFO`, `WARN`, `ERROR`, `DEBUG`, and `TRACE`, and custom levels such as `BRIDGE`. The server resolves each level to a real Log4J2 level, creating a dynamic level when needed with a priority above `INFO`. The optional `thread` field is used as log metadata in the emitted event, but does not rename the JVM thread itself.

`/server/stream` — SSE log stream

```bash
curl -N "http://localhost:1913/server/stream?history=50"
```

```python
client.stream.connect(history=50)
# client.stream.getRaw(), getSanitized(), getTimestamped() are available
```

`/players/list` — list online players

```bash
curl -sS http://localhost:1913/players/list
```

```python
client.getPlayers('count')   # number
client.getPlayers('list')    # list of names
```

`/system/uptime` — uptime

```bash
curl -sS http://localhost:1913/system/uptime
```

```python
client.getSystemUptime()
```

`/system/cpu` — cpu metrics

```bash
curl -sS http://localhost:1913/system/cpu
```

```python
client.getSystemCpu('usage')    # e.g. 'usage', 'cores', etc.
```

`/system/memory` — heap, JVM, and process memory metrics

```bash
curl -sS http://localhost:1913/system/memory
```

```python
client.getMemoryStats()['heap_used']
```

Response fields:
- `heap_used_bytes`, `heap_committed_bytes`, `heap_max_bytes`
- `nonheap_used_bytes`, `nonheap_committed_bytes`, `nonheap_max_bytes` (may be `null` when unbounded)
- `jvm_used_bytes`, `jvm_committed_bytes`, `jvm_max_bytes`
- `process_used_bytes`, `process_virtual_bytes`
- legacy compatibility aliases: `used_bytes`, `total_bytes`, `free_bytes`, `max_bytes`

Semantics:
- `jvm_used_bytes = heap_used_bytes + nonheap_used_bytes`
- `jvm_committed_bytes = heap_committed_bytes + nonheap_committed_bytes`
- `jvm_max_bytes = heap_max_bytes + nonheap_max_bytes` unless `nonheap_max_bytes` is unbounded (`null` / `-1`), in which case it falls back to the heap max.
- `nonheap_max_bytes` is reported as `null` when the JVM exposes it as unbounded (`-1`).
- `process_used_bytes` and `process_virtual_bytes` are process-level values from the OS provider: Linux reads `/proc/self/status` or `/proc/self/statm`, Windows/macOS use JNA-backed OS APIs when available, and unsupported/failing providers return `0`.

`/system/gc` — GC statistics

```bash
curl -sS http://localhost:1913/system/gc
```

```python
client.getSystemGc()
```

Response fields:
- `gc_type` — selected GC implementation name (G1, ZGC, Shenandoah, etc.)
- `gc_count` — total collections across all GC beans
- `gc_time_ms` — cumulative GC time
- `last_gc_pause_ms` — latest pause duration record
- `avg_gc_pause_ms` — average pause time across tracked collections

`/system/threads` — JVM thread counts

```bash
curl -sS http://localhost:1913/system/threads
```

```python
client.getSystemThreads()
```

Response fields:
- `current` — current live thread count
- `peak` — highest thread count observed
- `daemon` — daemon thread count

`/system/network` — current network throughput snapshot

```bash
curl -sS http://localhost:1913/system/network
```

```python
client.getSystemNetwork()
```

Response fields:
- `bytes_in_per_sec`
- `bytes_out_per_sec`
- `total_bytes_in`
- `total_bytes_out`

`/system/disk` — disk metrics for the server working directory only

```bash
curl -sS http://localhost:1913/system/disk
```

```python
client.getDiskStats()['total']
```

This endpoint uses `new File(".")` and reports:
- `total_bytes` = current working directory total space
- `free_bytes` = current working directory free space
- `usable_bytes` = current working directory usable space
- `used_bytes` = `total_bytes - free_bytes`
It does not inspect the host root filesystem.

`/world/tps` — tick performance

```bash
curl -sS http://localhost:1913/world/tps
```

```python
client.getTPS('current')   # 'current', '1m', '5m', 'tick_time'
```

`/world/mspt` — mspt

```bash
curl -sS http://localhost:1913/world/mspt
```

```python
client.getMSPT()
```

`/world/chunks` — per-world chunk counts

```bash
curl -sS http://localhost:1913/world/chunks
```

```python
client.getWorldChunks()
```

Response fields:
- `total` — combined chunk count across all loaded worlds
- `world` — overworld chunk count
- `world_nether` — nether chunk count
- `world_the_end` — end chunk count

`/world/entities` — per-world entity counts

```bash
curl -sS http://localhost:1913/world/entities
```

```python
client.getWorldEntities()
```

Response fields:
- `total` — combined entity count across all loaded worlds
- `world` — overworld entity count
- `world_nether` — nether entity count
- `world_the_end` — end entity count

`/world/time` — world time

```bash
curl -sS http://localhost:1913/world/time
```

```python
client.getWorldTime()
```

`/world/weather` — weather

```bash
curl -sS http://localhost:1913/world/weather
```

```python
client.getWorldWeather()
```
