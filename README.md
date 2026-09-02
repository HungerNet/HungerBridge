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

auth:
  key: "CHANGE_ME"

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
curl -N -H "X-Auth-Key: CHANGE_ME" http://localhost:1913/stream/logs
```

The server sends each line as an SSE event:

```text
data:[00:00:00 INFO]: Server started!

```

## Token management (HMAC tokens)

HungerBridge supports per-client HMAC-signed tokens in addition to the
legacy `X-Auth-Key` root key. Tokens provide ACLs (whitelist/blacklist),
expiry, and replay protection.

Administration
- Create a token (requires `X-Auth-Key` root access):

```bash
curl -X POST \
  -H "X-Auth-Key: CHANGE_ME" \
  -H "Content-Type: application/json" \
  -d '{"ttl_seconds":3600, "whitelist":["run"]}' \
  http://localhost:1913/v2/tokens
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
{"timestamp":"2026-09-02T12:34:56Z","token_id":"abcd1234","ip":"192.0.2.1","action":"run","result":"allowed","path":"/v2/run","method":"POST"}
```

The audit log intentionally avoids storing token secrets. Ensure `config/HungerBridge/logs`
is protected and rotated externally (logrotate, systemd journald forwarding, or a centralized
log collector).

## Origin exposure self‑probe

If your deployment uses a reverse proxy, enable the self-probe to verify the
origin server is not directly reachable over plain HTTP. Create or edit
`config/HungerBridge/security.yaml` and set `self_probe: true` and
`public_base_url` to the proxy's public URL. On startup HungerBridge will
attempt to probe `http://<public_host>/v2/ping`; if the probe succeeds the
server fails to start to avoid bypassing the proxy.

Example file: `config/HungerBridge/security.yaml` (created by default in this repo)

## IP whitelist / blacklist

You can optionally restrict access by IP. Add `ip_whitelist` or
`ip_blacklist` entries to `security.yaml`. Patterns may be exact IPs
or CIDR ranges (IPv4). Example:

```yaml
ip_whitelist:
  - 10.0.0.0/8
  - 192.168.1.5
ip_blacklist:
  - 203.0.113.0/24
```

Whitelists are enforced after authentication: if any whitelist entries are
present only those IPs are allowed. Blacklists deny matching IPs.
