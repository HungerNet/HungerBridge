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