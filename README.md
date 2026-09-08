# HungerBridge

HungerBridge is a unified **Fabric + Paper/Purpur** backend used by
**HungerLib** to execute commands and write logs inside a Minecraft server
without using RCON.

It exposes a small, secure HTTP API:

 - `POST /server/run` — execute a command as console (with optional silent mode)
 - `POST /server/log` — write raw text to the server console
 - `GET /ping` — health check

HungerBridge works identically on:

- **Paper/Purpur 1.21.11**
- **Fabric 1.21.11**

---

## Configuration

Generated automatically on first run.

```yaml
port: 1913

players:
  max-list: 50
```

### Streaming server logs

Use the SSE stream to receive Minecraft log lines in real time:

```bash
curl -N \
  -H "X-Auth-Token-Id: bridge-client" \
  -H "X-Auth-Timestamp: $(date +%s)" \
  -H "X-Auth-Nonce: $(openssl rand -hex 16)" \
  -H "X-Auth-Signature: <hmac-signature>" \
  http://localhost:1913/server/stream
```

The server sends each line as an SSE event:

```text
data:[00:00:00 INFO]: Server started!

```

## Token management (HMAC tokens)

HungerBridge supports per-client HMAC-signed tokens. Tokens provide ACLs
(whitelist/blacklist), expiry, and replay protection.

<!-- admin endpoints removed -->

Using tokens from the Python client

The `hungerlib` client accepts a token string in the format `id:secret` and
will sign every request (including SSE connection headers) automatically.

```python
from hungerlib.bridgeclient import BridgeClient
client = BridgeClient('http://localhost:1913', 'abcd1234:<secret>')
print(client.runCommand('say hello'))
```

Storage and example files
- Example runtime policies: `config/HungerBridge/policies.yaml`
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
{"timestamp":"2026-09-02T12:34:56Z","token_id":"abcd1234","ip":"192.0.2.1","action":"run","result":"allowed","path":"/server/run","method":"POST"}
```

HungerBridge is a unified **Fabric + Paper/Purpur** backend used by
**HungerLib** to execute commands and write logs inside a Minecraft server
without using RCON.

It exposes a small, secure HTTP API and an in-game admin command set (`/hungerbridge`, alias `/hb`).

Core HTTP API endpoints

 - `POST /run` — execute a command as console (JSON `{command, silent, show_console}`)
 - `POST /log` — write raw text to the server console (JSON `{level, message}`)
 - `GET  /ping` — health check
 - `GET  /server/info` — server and bridge metadata
 - `GET  /server/status` — runtime status (ok)
 - `GET  /world/tps` — TPS and tick time metrics
 - `GET  /players/list` — players count/list
 - `GET  /server/stream` — SSE stream of console logs (supports signed headers)

<!-- admin endpoints removed -->

Supported platforms

- **Paper/Purpur** (plugin.yml registered) — in-game `/hungerbridge` command available when enabled
- **Fabric** (Brigadier registration) — in-game `/hungerbridge` command available when enabled

---

## Configuration

Generated automatically on first run in `config/HungerBridge`.

`config.yaml` (core) — minimal example

```yaml
port: 1913

players:
  max-list: 50
```

`security.yaml` — security and rate-limit settings

```yaml
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

`policies.yaml` — default token policies

```yaml
policies:
  - id: moderator
    default_expiry: 0
    max_skew: 300
    permissions: ["ping", "server.log", "server.run"]
```

`storage/` files (managed by the server)

- `config/HungerBridge/storage/tokens.json` — tokens metadata (secrets are not published)
- `config/HungerBridge/storage/sessions.json` — nonce/session cache

## Streaming server logs (SSE)

Use the SSE stream to receive Minecraft log lines in real time. The client may
provide a header provider callable to sign the SSE connection when using
HMAC tokens.

```bash
curl -N -H "X-Auth-Token-Id: admin" -H "X-Auth-Timestamp: $(date +%s)" -H "X-Auth-Nonce: $(openssl rand -hex 16)" -H "X-Auth-Signature: <hmac-signature>" http://localhost:1913/server/stream
```

Each SSE `data:` event contains a single raw console line.

## Token management (HMAC tokens)

HungerBridge supports per-client HMAC-signed tokens. Tokens provide policy
permissions, expiry, and replay protection.

Tokens are provisioned out-of-band by operators; the server does not expose HTTP admin endpoints in this build.

## Audit logging and rotation

Security events are logged as JSON-lines in `config/HungerBridge/logs/`.
Files are rotated daily and named `YYYY-MM-DD.audit.log`. The server can
prune old audit files according to `audit_retention_days` in
`security.yaml` (default 14 days). Example entry:

```json
{"timestamp":"2026-09-02T12:34:56Z","token_id":"abcd1234","ip":"192.0.2.1","action":"run","result":"allowed","path":"/server/run","method":"POST"}
```

<!-- in-game admin command removed -->

## Rate limiting

Rate limits are configurable via `security.yaml` (`rate_limits`). The server exposes runtime endpoints to report configured limits and per-token/per-IP runtime settings.

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
resp = client.create_token(policy_id='moderator', token_id='bridge-client', expiry=3600, permissions=['server.log', 'server.run'])
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
