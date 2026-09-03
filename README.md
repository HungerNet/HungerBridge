# HungerBridge

HungerBridge is a unified **Fabric + Paper/Purpur** backend used by
**HungerLib** to execute commands and write logs inside a Minecraft server
without using RCON.

It exposes a small, secure HTTP API:

- `POST /run` — execute a command as console (with optional silent mode)
- `POST /log` — write raw text to the server console
- `GET /ping` — health check

HungerBridge works identically on:

- **Paper/Purpur 1.21.11**
- **Fabric 1.21.11**

---

## Configuration

Generated automatically on first run.

```yaml
port: 1913

enabled_endpoints:
  run: true
  log: true
  ping: true
  stream_logs: true
  info: true
  status: true
  tps: true
  players: true

players:
  max-list: 50
```

### Streaming server logs

Use the SSE stream to receive Minecraft log lines in real time:

```bash
curl -N \
  -H "X-Auth-Token-Id: admin" \
  -H "X-Auth-Timestamp: $(date +%s)" \
  -H "X-Auth-Nonce: $(openssl rand -hex 16)" \
  -H "X-Auth-Signature: <hmac-signature>" \
  http://localhost:1913/stream/logs
```

The server sends each line as an SSE event:

```text
data:[00:00:00 INFO]: Server started!

```

## Token management (HMAC tokens)

HungerBridge supports per-client HMAC-signed tokens. Tokens provide ACLs
(whitelist/blacklist), expiry, and replay protection.

Administration
- Create a token using an admin token:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token-Id: admin" \
  -H "X-Auth-Timestamp: $(date +%s)" \
  -H "X-Auth-Nonce: $(openssl rand -hex 16)" \
  -H "X-Auth-Signature: <hmac-signature>" \
  -d '{"id":"bridge-client","expiry":3600,"whitelist":["run"]}' \
  http://localhost:1913/admin/tokens/create
```

Sample successful response:

```json
{
  "ok": true,
  "id": "abcd1234...",
  "secret": "<secret-shown-once>",
  "expiry": 1620000000
}
```

Store the returned secret securely — it is only shown once.

Using tokens from the Python client

The `hungerlib` client accepts a token string in the format `id:secret` and
will sign every request (including SSE connection headers) automatically.

```python
from hungerlib.bridgeclient import BridgeClient
client = BridgeClient('http://localhost:1913', 'abcd1234:<secret>')
print(client.runCommand('say hello'))
```

Storage and example files
- Example tokens policy: `config/HungerBridge/tokens.yaml`
- Token storage: `config/HungerBridge/storage/tokens.json`
- Nonce/session cache: `config/HungerBridge/storage/sessions.json`

Do NOT commit production secrets to source control. Ensure `config/HungerBridge/storage`
is protected by filesystem permissions in your deployment.

## Rate limiting

HungerBridge enforces per-token and per-IP rate limits by default to mitigate
abuse. When a client exceeds a rate limit the server responds with HTTP 429:

```json
{ "ok": false, "error": "rate_limited", "message": "Rate limit exceeded" }
```

Defaults (configurable in future releases):
- per-token: 5 requests/sec, burst 10
- per-IP: 20 requests/sec, burst 40

Rate limits are applied after authentication and ACL checks. If you need to
raise limits for a client, consider creating a dedicated token for that
client and modify the server configuration.

## Audit logging

All security-relevant events are appended as JSON lines to `config/HungerBridge/logs/audit.log`.
Each line contains fields such as `timestamp`, `token_id`, `ip`, `action`, and `result`.

Sample audit line:

```json
{"timestamp":"2026-09-02T12:34:56Z","token_id":"abcd1234","ip":"192.0.2.1","action":"run","result":"allowed","path":"/run","method":"POST"}
```

HungerBridge is a unified **Fabric + Paper/Purpur** backend used by
**HungerLib** to execute commands and write logs inside a Minecraft server
without using RCON.

It exposes a small, secure HTTP API and an in-game admin command set (`/hungerbridge`, alias `/hb`).

Core HTTP API endpoints

 - `POST /run` — execute a command as console (JSON `{command, silent, show_console}`)
 - `POST /log` — write raw text to the server console (JSON `{level, message}`)
 - `GET  /ping` — health check
 - `GET  /info` — server and bridge metadata
 - `GET  /status` — runtime status (ok)
 - `GET  /tps` — TPS and tick time metrics
 - `GET  /players` — players count/list
 - `GET  /stream/logs` — SSE stream of console logs (supports signed headers)

Admin HTTP endpoints (require an admin-capable token)

 - `GET  /admin/tokens/list` — list tokens (no secrets)
 - `POST /admin/tokens/create` — create token (JSON: `id`, optional `expiry`, optional `whitelist`, optional `blacklist`) — returns `id` and `secret`
 - `POST /admin/tokens/revoke` — revoke token (JSON: `id`)
 - `POST /admin/tokens/rotate` — rotate token secret (JSON: `id`) — returns new `id` and `secret`
 - `GET  /admin/status` — rate limits, ACLs, probe status
 - `GET  /admin/probe` — perform manual self-probe and return result
 - `GET  /admin/ip` — show configured IP whitelist/blacklist
 - `GET  /admin/audit?n=<N>` — return last N audit entries
 - `POST /admin/reload` — reload `security.yaml` and `tokens.yaml`

Supported platforms

- **Paper/Purpur** (plugin.yml registered) — in-game `/hungerbridge` command available when enabled
- **Fabric** (Brigadier registration) — in-game `/hungerbridge` command available when enabled

---

## Configuration

Generated automatically on first run in `config/HungerBridge`.

`config.yaml` (core) — minimal example

```yaml
port: 1913

enabled_endpoints:
  run: true
  log: true
  ping: true
  stream_logs: true
  info: true
  status: true
  tps: true
  players: true

players:
  max-list: 50
```

`security.yaml` — security and rate-limit settings

```yaml
self_probe: true
public_base_url: "https://my-proxy.example.com"
probe_timeout_ms: 2000
ip_list:
  mode: blacklist
  list:
    - 10.0.0.0/8
    - 203.0.113.0/24
rate_limits:
  token_rps: 5
  token_burst: 10
  ip_rps: 20
  ip_burst: 40
audit_retention_days: 14
```

`tokens.yaml` — default token policy

```yaml
tokens:
  - id: admin
    default_expiry: 0
    max_skew: -1
    endpoints_mode: blacklist
    endpoints: []
    commands_mode: blacklist
    commands: []
```

`storage/` files (managed by the server)

- `config/HungerBridge/storage/tokens.json` — tokens metadata (secrets are not published)
- `config/HungerBridge/storage/sessions.json` — nonce/session cache

## Streaming server logs (SSE)

Use the SSE stream to receive Minecraft log lines in real time. The client may
provide a header provider callable to sign the SSE connection when using
HMAC tokens.

```bash
curl -N -H "X-Auth-Token-Id: admin" -H "X-Auth-Timestamp: $(date +%s)" -H "X-Auth-Nonce: $(openssl rand -hex 16)" -H "X-Auth-Signature: <hmac-signature>" http://localhost:1913/stream/logs
```

Each SSE `data:` event contains a single raw console line.

## Token management (HMAC tokens)

HungerBridge supports per-client HMAC-signed tokens. Tokens provide ACLs
(whitelist/blacklist), expiry, and replay protection.

Create a token using an admin-capable token:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token-Id: admin" \
  -H "X-Auth-Timestamp: $(date +%s)" \
  -H "X-Auth-Nonce: $(openssl rand -hex 16)" \
  -H "X-Auth-Signature: <hmac-signature>" \
  -d '{"id":"bridge-client","expiry":3600,"whitelist":["run"]}' \
  http://localhost:1913/admin/tokens/create
```

Sample successful response (admin responses use a uniform schema):

```json
{
  "ok": true,
  "data": {
    "id": "abcd1234...",
    "secret": "<secret-shown-once>"
  }
}
```

Rotate a token (invalidates the old secret and returns a new secret):

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token-Id: admin" \
  -H "X-Auth-Timestamp: $(date +%s)" \
  -H "X-Auth-Nonce: $(openssl rand -hex 16)" \
  -H "X-Auth-Signature: <hmac-signature>" \
  -d '{"id":"abcd1234"}' \
  http://localhost:1913/admin/tokens/rotate
```

Store returned secrets securely — they are only shown once.

## Audit logging and rotation

Security events are logged as JSON-lines in `config/HungerBridge/logs/`.
Files are rotated daily and named `YYYY-MM-DD.audit.log`. The server can
prune old audit files according to `audit_retention_days` in
`security.yaml` (default 14 days). Example entry:

```json
{"timestamp":"2026-09-02T12:34:56Z","token_id":"abcd1234","ip":"192.0.2.1","action":"run","result":"allowed","path":"/run","method":"POST"}
```

## In-game admin command `/hungerbridge`

HungerBridge exposes `/hungerbridge` inside the server (the short alias `/hb` is also available). Available subcommands:

- `/hungerbridge reload` — reload config files
- `/hungerbridge status` — show rate limits and probe status
- `/hungerbridge probe` — run the self-probe
- `/hungerbridge audit [N]` — print last N audit lines (default 20)
- `/hungerbridge token list` — list token ids
- `/hungerbridge token create <id> [expiry]` — create a token with an explicit id and optional expiry in seconds
- `/hungerbridge token revoke <id>` — revoke token
- `/hungerbridge token rotate <id>` — rotate token secret
- `/hungerbridge ip` — show IP whitelist/blacklist
- `/hungerbridge config` — show basic config/status

Commands are registered using Bukkit plugin.yml (Paper) or Brigadier (Fabric).

## Rate limiting

Rate limits are configurable via `security.yaml` (`rate_limits`). Admin
endpoint `GET /admin/status` reports current configured limits and
per-token/per-IP runtime settings.

## Python client (`hungerlib`)

The `hungerlib` Python client supports HMAC tokens and the new admin API.

Example usage:

```python
from hungerlib.bridgeclient import BridgeClient

# token is id:secret
client = BridgeClient('http://localhost:1913', 'abcd1234:<secret>')

# run a command
print(client.runCommand('say hello'))

# admin: create token (requires an admin-capable token)
resp = client.create_token(token_id='bridge-client', expiry=3600, whitelist=['run'])
print(resp)

# list tokens
print(client.list_tokens())

# rotate token
print(client.rotate_token('abcd1234'))

# read last 50 audit lines
print(client.get_audit(50))

# Stream logs with signed headers (bridgeclient Stream accepts header provider)
stream = client.stream
stream.connect(history=50)
```

---

If you want me to also generate example administration scripts or unit tests
for the new modules, say which you prefer and I will add them next.
