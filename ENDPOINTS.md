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

`/server/stop` — graceful stop

```bash
curl -sS -X POST http://localhost:1913/server/stop -H "Content-Type: application/json" -d '{}'
```

```python
client.stopServer()   # returns ok boolean
```

`/server/restart` — restart

```bash
curl -sS -X POST http://localhost:1913/server/restart -H "Content-Type: application/json" -d '{}'
```

```python
client.restartServer()  # returns ok boolean
```

`/server/log` — emit bridge log

```bash
curl -sS -X POST http://localhost:1913/server/log -H "Content-Type: application/json" -d '{"level":"info","message":"hello"}'
```

```python
client.log('hello', level='info')  # returns ok boolean
```

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

`/system/memory` — memory metrics

```bash
curl -sS http://localhost:1913/system/memory
```

```python
client.getSystemMemory('total')
```

`/system/disk` — disk metrics

```bash
curl -sS http://localhost:1913/system/disk
```

```python
client.getSystemDisk('total')
```

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

`/world/chunks` — chunk stats

```bash
curl -sS http://localhost:1913/world/chunks
```

```python
client.getLoadedChunks()
```

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
