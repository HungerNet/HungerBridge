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

- `X-Auth-Token-Id`
- `X-Auth-Timestamp`
- `X-Auth-Nonce`
- `X-Auth-Signature`

The server verifies the request method, path, body, and timestamp skew using the configured token manager. Requests without valid auth return `401` with `{"ok":false,...}`. Permission failures are `403`.

For admin routes, the token must be allowed for the relevant ACL action. Generic token permission metadata is available from:

- `GET /auth/check`

---

## 3. Common examples

### Basic curl example

```bash
TOKEN_ID="admin"
TOKEN_SECRET="<secret>"
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
MSG="GET\n/ping\n${TS}\n${NONCE}\n"
SIG=$(printf '%s' "$MSG" | openssl dgst -sha256 -hmac "$TOKEN_SECRET" -binary | xxd -p -c 256)

curl -sS \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "X-Auth-Token-Id: $TOKEN_ID" \
  -H "X-Auth-Timestamp: $TS" \
  -H "X-Auth-Nonce: $NONCE" \
  -H "X-Auth-Signature: $SIG" \
  http://localhost:1913/ping
```

### Python BridgeClient example

```python
from hungerlib.bridgeclient import BridgeClient

client = BridgeClient(
    "http://localhost:1913",
    token_id="admin",
    token_secret="<secret>",
)

print(client.ping())
print(client.auth_check())
```

---

## 4. Endpoint catalog

<details>
<summary><b>/ping</b> — basic health check</summary>

- Method: `GET`
- Auth: required
- Purpose: low-cost liveness check for infrastructure and monitoring.
- Success example:

```bash
curl -sS -H "X-Auth-Token-Id: admin" -H "X-Auth-Timestamp: $(date +%s)" -H "X-Auth-Nonce: $(openssl rand -hex 16)" -H "X-Auth-Signature: <sig>" http://localhost:1913/ping
```

```python
client.ping()
```

- Typical response:

```json
{ "ok": true, "timestamp": 1712345678 }
```

</details>

<details>
<summary><b>/auth/check</b> — inspect token permissions</summary>

- Method: `GET`
- Auth: required
- Permission/action: token metadata is returned directly after successful authentication.
- Purpose: validate the active token, its policy, expiry, and whitelist/blacklist.

```bash
curl -sS -H "X-Auth-Token-Id: admin" -H "X-Auth-Timestamp: $(date +%s)" -H "X-Auth-Nonce: $(openssl rand -hex 16)" -H "X-Auth-Signature: <sig>" http://localhost:1913/auth/check
```

```python
client.auth_check()
```

- Response includes `permissions` with the token ID, policy, and allowed/blocked actions.

</details>

<details>
<summary><b>/server/run</b> — run a console command</summary>

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

</details>

<details>
<summary><b>/server/run-batch</b> — run multiple commands in one request</summary>

- Method: `POST`
- Auth: required
- Action: `run`
- Purpose: submit a batch of commands for processing with one authenticated request.

```bash
curl -sS -X POST http://localhost:1913/server/run-batch \
  -H "Content-Type: application/json" \
  -d '{"commands":["say batch 1","say batch 2"]}'
```

```python
# Mirror the underlying request with a raw POST if needed:
client._post('server/run-batch', {"commands": ["say batch 1", "say batch 2"]})
```

</details>

<details>
<summary><b>/server/stop</b> — stop the server</summary>

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

</details>

<details>
<summary><b>/server/restart</b> — restart the server</summary>

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

</details>

<details>
<summary><b>/server/log</b> — write a log entry</summary>

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

</details>

<details>
<summary><b>/server/meta</b> — bridge metadata</summary>

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

</details>

<details>
<summary><b>/server/stream</b> — live server log stream</summary>

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

- The client also keeps a legacy fallback for `/server/stream/logs` if the server is older or a compatibility endpoint is needed.

</details>

<details>
<summary><b>/system/uptime</b> — system uptime</summary>

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

</details>

<details>
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

</details>

<details>
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

</details>

<details>
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

</details>

<details>
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

</details>

<details>
<summary><b>/players/kick</b> — kick a player</summary>

- Method: `POST`
- Auth: required
- Action: `players.kick`

```bash
curl -sS -X POST http://localhost:1913/players/kick \
  -H "Content-Type: application/json" \
  -d '{"player":"Steve","reason":"Maintenance"}'
```

```python
client.player_kick("Steve", reason="Maintenance")
```

</details>

<details>
<summary><b>/players/ban</b> — ban a player</summary>

- Method: `POST`
- Auth: required
- Action: `players.ban`

```bash
curl -sS -X POST http://localhost:1913/players/ban \
  -H "Content-Type: application/json" \
  -d '{"player":"Steve","reason":"Rule break","duration":3600}'
```

```python
client.player_ban("Steve", reason="Rule break", duration=3600)
```

</details>

<details>
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

</details>

<details>
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

</details>

<details>
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

</details>

<details>
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

</details>

<details>
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

</details>

<details>
<summary><b>/world/events/join</b> — player join events</summary>

- Method: `GET`
- Auth: required
- Action: `world.events.join`

```bash
curl -sS http://localhost:1913/world/events/join
```

```python
client.world_event_join()
```

</details>

<details>
<summary><b>/world/events/leave</b> — player leave events</summary>

- Method: `GET`
- Auth: required
- Action: `world.events.leave`

```bash
curl -sS http://localhost:1913/world/events/leave
```

```python
client.world_event_leave()
```

</details>

<details>
<summary><b>/world/events/chat</b> — chat events</summary>

- Method: `GET`
- Auth: required
- Action: `world.events.chat`

```bash
curl -sS http://localhost:1913/world/events/chat
```

```python
client.world_event_chat()
```

</details>

<details>
<summary><b>/admin/status</b> — admin status overview</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token
- Purpose: read rate limits, ACL state, and service status.

```bash
curl -sS http://localhost:1913/admin/status
```

```python
client.admin_status()
```

</details>

<details>
<summary><b>/admin/reload</b> — reload config</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token
- Purpose: reload configuration without restarting the service.

```bash
curl -sS -X POST http://localhost:1913/admin/reload -H "Content-Type: application/json" -d '{}'
```

```python
client.reload_config()
```

</details>

<details>
<summary><b>/admin/audit?n=&lt;N&gt;</b> — audit summary</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token
- Purpose: return the last `N` audit entries or a summary of recent actions.

```bash
curl -sS "http://localhost:1913/admin/audit?n=20"
```

```python
client.admin_audit(n=20)
```

</details>

<details>
<summary><b>/admin/audit/purge</b> — purge audit log</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token
- Purpose: clear sensitive or obsolete audit entries.

```bash
curl -sS -X POST http://localhost:1913/admin/audit/purge -H "Content-Type: application/json" -d '{}'
```

```python
client.purge_audit()
```

</details>

<details>
<summary><b>/admin/config/get/main</b> — read main config</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS http://localhost:1913/admin/config/get/main
```

```python
client.config_get("main")
```

</details>

<details>
<summary><b>/admin/config/get/security</b> — read security config</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS http://localhost:1913/admin/config/get/security
```

```python
client.config_get("security")
```

</details>

<details>
<summary><b>/admin/config/get/tokens</b> — read token config</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS http://localhost:1913/admin/config/get/tokens
```

```python
client.config_get("tokens")
```

</details>

<details>
<summary><b>/admin/config/update/main</b> — update main config</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS -X POST http://localhost:1913/admin/config/update/main \
  -H "Content-Type: application/json" \
  -d '{"key":"value"}'
```

```python
client.config_update("main", {"key": "value"})
```

</details>

<details>
<summary><b>/admin/config/update/security</b> — update security config</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS -X POST http://localhost:1913/admin/config/update/security \
  -H "Content-Type: application/json" \
  -d '{"key":"value"}'
```

```python
client.config_update("security", {"key": "value"})
```

</details>

<details>
<summary><b>/admin/config/update/tokens</b> — update token config</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS -X POST http://localhost:1913/admin/config/update/tokens \
  -H "Content-Type: application/json" \
  -d '{"key":"value"}'
```

```python
client.config_update("tokens", {"key": "value"})
```

</details>

<details>
<summary><b>/admin/token/list</b> — list token metadata</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS http://localhost:1913/admin/token/list
```

```python
client.list_tokens()
```

</details>

<details>
<summary><b>/admin/token/create</b> — create a token</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token
- Body: `policyId`, `tokenId`, optional `expiry`, `whitelist`, `blacklist`

```bash
curl -sS -X POST http://localhost:1913/admin/token/create \
  -H "Content-Type: application/json" \
  -d '{"policyId":"default","tokenId":"worker-1","expiry":3600}'
```

```python
client.create_token(
    policy_id="default",
    token_id="worker-1",
    expiry=3600,
    whitelist=["run", "log"],
    blacklist=[]
)
```

</details>

<details>
<summary><b>/admin/token/revoke</b> — revoke a token</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS -X POST http://localhost:1913/admin/token/revoke \
  -H "Content-Type: application/json" \
  -d '{"id":"worker-1"}'
```

```python
client.revoke_token("worker-1")
```

</details>

<details>
<summary><b>/admin/token/remove</b> — permanently remove</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS -X POST http://localhost:1913/admin/token/remove \
  -H "Content-Type: application/json" \
  -d '{"id":"worker-1"}'
```

```python
client.remove_token("worker-1")
```

</details>

<details>
<summary><b>/admin/token/rotate</b> — rotate a secret</summary>

- Method: `POST`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS -X POST http://localhost:1913/admin/token/rotate \
  -H "Content-Type: application/json" \
  -d '{"id":"worker-1"}'
```

```python
client.rotate_token("worker-1")
```

</details>

<details>
<summary><b>/admin/token/meta</b> — read token metadata summary</summary>

- Method: `GET`
- Auth: required
- ACL: admin-capable token

```bash
curl -sS http://localhost:1913/admin/token/meta
```

```python
client.token_meta()
```

</details>

---

## 5. Compatibility notes

- Older v2 routes continue to work when they still exist, so older clients are not intentionally broken.
- The canonical v3 tree is the list above. The client prefers the v3 contract and falls back to legacy URLs when needed.
- The stream endpoint remains compatible with the older `/server/stream/logs` route to avoid abrupt client breakage.

## 6. Error behavior

Expected failure patterns:

- `401 unauthorized` — invalid or missing signed auth headers
- `403 forbidden` — token is valid but missing ACL permission
- `404 not_found` — unknown action or resource
- `405 method_not_allowed` — wrong HTTP verb
- `429 rate_limited` — rate limiter rejected the request
- `500 internal` — backend failure or unexpected error

The Python client raises `HungerBridgeError` on those HTTP failures so callers can react consistently.
