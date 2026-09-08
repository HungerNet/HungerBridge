# HungerBridge API v3 Endpoint Reference

This is the canonical HTTP API for HungerBridge. The list below reflects the authoritative v3 tree and the current route behavior in the server and the Python client.

> Scope: this document covers the mounted v3 API surface and the compatibility layer that keeps older v2 endpoints working where they still exist.

## 1. Response envelope

All successful and failed HTTP responses follow a JSON envelope shaped like this:

```json
{
  "ok": true,
  "message": "optional human readable message",
  "data": { "...": "..." },
  "error": "optional_error_code",
  "details": { "...": "..." }
}
```

Common patterns:

- Success: `"ok": true`
- Error: `"ok": false` with `"error"` and `"message"`
- Some endpoints return primitive payloads or arrays under `"data"`, `"output"`, `"permissions"`, `"tokens"`, etc.

The server-side utilities implement the same contract through the shared `JsonResponse` and `HttpUtil` helpers.

---

## 2. Auth and permissions

Most endpoints require signed HMAC headers:
 `X-Auth-Id`
- `X-Auth-Signature`

The server verifies the request method, path, body, and timestamp skew using the configured token manager. Requests without valid auth return `401` with `{"ok":false,...}`. Permission failures are `403`.

<!-- /world/events/* removed from v3 API -->

</details>
<!-- admin endpoints removed -->
- Method: `GET`
- Auth: required
- Purpose: low-cost liveness check for infrastructure and monitoring.
- Success example:

```bash
curl -sS -H "X-Auth-Id: admin" -H "X-Auth-Timestamp: $(date +%s)" -H "X-Auth-Nonce: $(openssl rand -hex 16)" -H "X-Auth-Signature: <sig>" http://localhost:1913/ping
```
```python
client.ping()
```

- Typical response:

```json
{ "ok": true, "timestamp": 1712345678 }
```


- Method: `GET`
- Auth: required
- Permission/action: token metadata is returned directly after successful authentication.
- Purpose: validate the active token, its policy, expiry, and effective permissions.

```bash
curl -sS -H "X-Auth-Id: admin" -H "X-Auth-Timestamp: $(date +%s)" -H "X-Auth-Nonce: $(openssl rand -hex 16)" -H "X-Auth-Signature: <sig>" http://localhost:1913/auth/check
```
```python
client.auth_check()
```

- Response includes `permissions` with the token ID, policy, and allowed/blocked actions.


- Method: `POST`
- Auth: required
- Action: `run`
- Purpose: execute a command on the underlying server process.

```bash
curl -sS -X POST http://localhost:1913/server/run \
  -H "Content-Type: application/json" \
  -d '{"command":"say hello from HungerBridge","silent":false,"show_console":true}'
```
```python
client.runCommand("say hello from HungerBridge", show_console=True, silent=False)
```

- Response envelope typically includes `"output"` as a list when not silent.


- Method: `POST`
- Auth: required
- Action: `run`
- Purpose: submit a batch of commands for processing with one authenticated request.

<!-- /server/run-batch removed from v3 API -->


- Method: `POST`
- Auth: required
- Action: `server.stop`
- Purpose: gracefully stop the server process, when allowed by policy.

```bash
curl -sS -X POST http://localhost:1913/server/stop \
  -H "Content-Type: application/json" \
  -d '{}'
```

```python
client.stop_server()
```


- Method: `POST`
- Auth: required
- Action: `server.restart`
- Purpose: trigger a server restart through the bridge logic.

```bash
curl -sS -X POST http://localhost:1913/server/restart \
  -H "Content-Type: application/json" \
  -d '{}'
```

```python
client.restart_server()
```


- Method: `POST`
- Auth: required
- Action: `log`
- Purpose: emit a message to the server log stream or bridge logger.

```bash
curl -sS -X POST http://localhost:1913/server/log \
  -H "Content-Type: application/json" \
  -d '{"level":"info","message":"hello from bridge"}'
```

```python
client.log("hello from bridge", level="info")
```


- Method: `GET`
- Auth: required
- Action: `meta`
- Purpose: return bridge/server metadata without touching the actual runtime state.

```bash
curl -sS http://localhost:1913/server/meta
```

```python
client.server_meta()
```

- Method: `GET`
- Auth: required
- Action: `stream`
- Purpose: open a live SSE stream of logs from the server.
- Optional query: `?history=N`

```bash
curl -N "http://localhost:1913/server/stream?history=50"
```

```python
client.stream.connect(history=50)
```
</details>

- Method: `GET`
- Auth: required
- Action: `system.uptime`
- Purpose: report application or runtime uptime.

```bash
curl -sS http://localhost:1913/system/uptime
```

```python
client.system_uptime()
```

<summary><b>/system/cpu</b> — CPU stats</summary>

- Method: `GET`
- Auth: required
- Action: `system.cpu`

```bash
curl -sS http://localhost:1913/system/cpu
```

```python
client.system_cpu()
```

<summary><b>/system/memory</b> — memory stats</summary>

- Method: `GET`
- Auth: required
- Action: `system.memory`

```bash
curl -sS http://localhost:1913/system/memory
```

```python
client.system_memory()
```

<summary><b>/system/disk</b> — disk stats</summary>

- Method: `GET`
- Auth: required
- Action: `system.disk`

```bash
curl -sS http://localhost:1913/system/disk
```

```python
client.system_disk()
```

<summary><b>/players/list</b> — list online players</summary>

- Method: `GET`
- Auth: required
- Action: `players.list`

```bash
curl -sS http://localhost:1913/players/list
```

```python
client.players_list()
```

<!-- /players/kick and /players/ban removed from v3 API -->

<summary><b>/world/tps</b> — tick performance</summary>

- Method: `GET`
- Auth: required
- Action: `world.tps`

```bash
curl -sS http://localhost:1913/world/tps
```

```python
client.world_tps()
```

<summary><b>/world/mspt</b> — mspt summary</summary>

- Method: `GET`
- Auth: required
- Action: `world.mspt`

```bash
curl -sS http://localhost:1913/world/mspt
```

```python
client.world_mspt()
```

<summary><b>/world/chunks</b> — chunk stats</summary>

- Method: `GET`
- Auth: required
- Action: `world.chunks`

```bash
curl -sS http://localhost:1913/world/chunks
```

```python
client.world_chunks()
```

<summary><b>/world/time</b> — world time</summary>

- Method: `GET`
- Auth: required
- Action: `world.time`

```bash
curl -sS http://localhost:1913/world/time
```

```python
client.world_time()
```

<summary><b>/world/weather</b> — world weather state</summary>

- Method: `GET`
- Auth: required
- Action: `world.weather`

```bash
curl -sS http://localhost:1913/world/weather
```

```python
client.world_weather()
```

<!-- /world/events/* removed from v3 API -->

</details>
<!-- admin endpoints removed -->
