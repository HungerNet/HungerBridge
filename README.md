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
  -H "X-Auth-Id: bridge-client" \
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

HungerBridge supports per-client HMAC-signed tokens. Tokens are provisioned via the server CLI (`hb token create <tokenId> <policyId>`).

- The CLI prints a pickup URL: `/pickup/{UUID}`. Visit that URL once to retrieve the plaintext secret; pickups are single-use and expire automatically.

Storage and example files
- Example runtime policies: `config/HungerBridge/policies.yaml`
- Token storage: `autogen/HungerBridge/tokens.json`
- Pickup storage: `autogen/HungerBridge/pickups.json`

Do NOT commit production secrets to source control. Ensure `config/HungerBridge`
is protected by filesystem permissions in your deployment.

## Streaming server logs (SSE)

Use the SSE stream to receive Minecraft log lines in real time:

```bash
curl -N \
  -H "X-Auth-Id: bridge-client" \
  -H "X-Auth-Timestamp: $(date +%s)" \
  -H "X-Auth-Nonce: $(openssl rand -hex 16)" \
  -H "X-Auth-Signature: <hmac-signature>" \
  http://localhost:1913/server/stream
```

## Python client (`hungerlib`)

The `hungerlib` Python client `BridgeClient` signs requests using the canonical HMAC scheme (headers: `X-Auth-Id`, `X-Auth-Timestamp`, `X-Auth-Nonce`, `X-Auth-Signature`). It also provides an SSE `Stream` helper.

Example usage:

```python
from hungerlib.bridgeclient import BridgeClient

# token is id:secret
client = BridgeClient('http://localhost:1913', 'abcd1234:<secret>')

# run a command
print(client.runCommand('say hello'))

# Stream logs with signed headers
stream = client.stream
stream.connect(history=50)
```
