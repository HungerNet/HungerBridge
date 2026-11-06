# 03 — Security hardening

## Summary

HungerBridge is safe on its default setting: bound to `127.0.0.1` with a localhost-only IP
whitelist. People will still expose it to a bot on another machine or a panel on another
host, and some gaps matter once they do:

| # | Gap | Risk if exposed | Fix | Effort |
|---|---|---|---|---|
| 1 | The query string isn't covered by the signature | A man-in-the-middle can alter parameters such as `?history=` (and future ones like `?player=`) without breaking the signature | Protocol v2 signs the canonical query; v1 stays accepted | M |
| 2 | Plain HTTP | Command output, logs and pickup secrets travel in cleartext; a passive observer can read everything except token secrets | Documented reverse-proxy setup (now), optional built-in HTTPS (later), trusted-proxy handling for client IPs | S (doc) + M (built-in) |
| 3 | Tokens work from any allowed IP; rate limits are global | A leaked token works from anywhere the IP filter allows; one noisy integration starves others | Per-policy `allowed-ips` and per-policy rate limits | S–M |
| 4 | `show_console=false` does nothing | Silent bot commands still spam the console | Implement suppression on console appenders only, or remove the flag | S |

**Decisions needed**
- [ ] §1: the window for accepting v1 signatures after v2 ships. Recommendation: accept
      both indefinitely, with a `security.yaml` switch `min-auth-version: 1|2` that
      defaults to 1.
- [ ] §2: is built-in HTTPS wanted at all, or is "use a reverse proxy" the official
      answer? Recommendation: proxy docs now; built-in only if users ask.
- [ ] §4: should silent commands also stay out of `logs/latest.log`? Recommendation: no.
      Suppress only the interactive console, and keep the file log and the audit log.

---

## §1 Protocol v2: signed query strings

### Wire format

The client adds the header `X-Auth-Version: 2`. The string to sign gains one line:

```
METHOD \n path \n canonical_query \n timestamp \n nonce \n canonical_body      (v2)
METHOD \n path \n timestamp \n nonce \n canonical_body                          (v1, unchanged)
```

**`canonical_query`**
- Split the raw query on `&`. Split each item on the first `=`. Percent-decode both parts
  (UTF-8; `+` is a literal plus, not a space).
- Sort by key, then value, in UTF-16 code-unit order.
- Re-encode with RFC 3986 unreserved characters only (`A-Z a-z 0-9 - . _ ~`), uppercase
  hex.
- Join with `&`. An empty query gives an empty line.
- Example: `?history=50&b=x%20y` → `b=x%20y&history=50`.

### Server (`TokenManager` / `HttpUtil.authenticate`)

- `canonicalRequestV2(method, path, rawQuery, ts, nonce, body)` plus a
  `canonicalQuery(rawQuery)` static method.
- `authenticate`:
  - reads `X-Auth-Version` (absent means 1);
  - verifies with the matching string-to-sign;
  - rejects v1 when `min-auth-version: 2`, with 401 `reason: auth_version_too_old`.
- Advertise support in `/server/meta`: `"auth_versions": [1, 2]`. The protocol header
  stays `1`: v2 auth is additive.
- Use `ex.getRequestURI().getRawQuery()`, never the decoded `getQuery()`, so the
  canonicalization is the only decoder.

### Client (HungerBridgePy)

- `auth.string_to_sign_v2` and `canonical.canonical_query`.
- `Transport` uses v2 whenever the server advertises it. Cache that from the first
  `/server/meta`, or try v2 and fall back to v1 on 401 `auth_version_unsupported`
  (old servers ignore the header and fail the signature). Recommended approach: lazily
  fetch `/server/meta` once, signed with v1.
- `Stream` and `EventStream` sign `?history=` / `?since=` / `?types=` with v2.

### Tests

- Add a `query_signatures` section to `protocol-vectors.json` (generated, then verified by
  both languages). Cases: empty query, ordering, repeated keys, `%20` vs `+`, non-ASCII,
  and a value containing `=`.
- **Integration:**
  - a v2 request with a tampered query → 401;
  - a v1 request is still accepted;
  - with `min-auth-version: 2`, a v1 request → 401 `auth_version_too_old`.

---

## §2 Transport security

### 2.1 Reverse proxy (documentation; ships first)

`docs/deploy/reverse-proxy.md` covers:

- **Caddy:**
  ```
  bridge.example.com { reverse_proxy 127.0.0.1:1913 { flush_interval -1 } }
  ```
  `flush_interval -1` is needed for SSE.
- **nginx:**
  ```
  proxy_buffering off; proxy_read_timeout 1h; proxy_http_version 1.1; proxy_set_header Connection "";
  ```
  The bridge already sends `X-Accel-Buffering: no`.
- Keep `bind-address: 127.0.0.1` and terminate TLS at the proxy.

### 2.2 Trusted proxies (needed for 2.1 to keep IP rules meaningful)

Behind a proxy, every request comes from `127.0.0.1`. That breaks the `ips` whitelist and
the per-IP rate limits.

```yaml
# security.yaml
trusted-proxies: [127.0.0.1, ::1]     # default: [] (header ignored)
client-ip-header: X-Forwarded-For     # or X-Real-IP
```

- `HttpUtil.remoteIp(ex)` becomes the only source of the client IP. When the socket peer
  matches `trusted-proxies`, take the right-most address in the header that is not itself
  a trusted proxy. Otherwise use the socket peer.
- `RemoteIpFilter`, rate limiting, `AuditFilter` and `RunHandler`'s audit call already all
  go through `HttpUtil.remoteIp`, so changing that one method covers every consumer.
- **Tests:** spoofed `X-Forwarded-For` from an untrusted peer is ignored; a trusted peer's
  header is honoured.

### 2.3 Built-in HTTPS (optional, later)

```yaml
tls:
  enabled: false
  keystore: tls.p12            # PKCS#12; or:
  cert-pem: fullchain.pem
  key-pem: privkey.pem         # PKCS#8 PEM
  reload-on-change: true       # pick up renewed certs without restart
```

- Use `HttpsServer` with an `SSLContext` built from PKCS#12 or PEM (parse PEM with the
  JDK `KeyFactory` and `CertificateFactory`; no Bouncy Castle).
- TLS 1.2+ only.
- When enabled, the HTTP listener is not started. No downgrade.
- Python already works through `https://` URLs. Add a `verify=` passthrough for
  self-signed certificates.

---

## §3 Per-token IP binding and per-policy limits

### Config (`policies.yaml`, all keys optional)

```yaml
policies:
  - id: discord-bot
    permissions: ["server.ping", "players.list", "events.read"]
    allowed-ips: [10.0.0.5, 10.0.1.0/24]   # in addition to the global ips filter
    rate-limit: { burst: 30, rps: 10 }     # replaces security.yaml per-token for this policy
```

Optionally, a token can carry its own `allowed-ips`: `/hb token create <id> <policy>
[expiry] [--ips a,b]`, stored on `TokenManager.Token`.

### Server

- `TokensConfig.TokenPolicy` gains `List<String> allowedIps` and
  `BucketConfig rateLimit`.
- **Check order in `HttpUtil.authenticate`.** The per-token bucket check needs the token's
  policy, which is only trusted after the signature is verified. So:
  - the per-IP check stays before verification;
  - the per-token check happens after the token is resolved, but before the nonce is
    recorded.

  Implementation: `TokenManager.verifyHmacDetailed` gets a callback hook `preNonce(Token)`
  that returns a deny reason, or split verification into `verifySignature` and
  `commitNonce`. **The split is recommended**: it's clearer, and the existing order
  guarantees hold.
- An IP mismatch returns 403 `reason: ip_not_allowed_for_token`. Don't use 401: the
  credentials are valid.
- `/auth/check` reports `allowed_ips` and `rate_limit`.

### Tests

- A token bound to `10.0.0.5` gets 403 from `127.0.0.1`.
- A policy rate limit overrides the global one.
- A throttled request still does not burn its nonce: re-send the identical request and
  get 200 (this extends `rateLimitConsumesOneTokenPerRequestAndDoesNotBurnNonce`).

---

## §4 `show_console`

### Current state

`CommandExecutor.executeWithOutput(cmd, showConsole)` passes the flag to
`CommandCapture.capture`, which ignores it.

### Design (implement)

`CommandCapture.capture(action, showConsole=false)`:
1. Find the console appenders on the root `LoggerConfig`:
   - Paper/Folia: `TerminalConsole`, the class
     `net.minecrell.terminalconsole.TerminalConsoleAppender`;
   - Fabric/vanilla: `SysOut` / `ServerGuiConsole`, matched by appender name and class
     `ConsoleAppender`.
   - Never touch file appenders (`File`, `RollingFile`) or HungerBridge's own appenders.
2. For each one, `addFilter(SUPPRESS)`, where `SUPPRESS` is a singleton
   `AbstractFilter` returning `DENY` when `CAPTURE_SUPPRESS.get() == TRUE` (a
   `ThreadLocal`) and `NEUTRAL` otherwise. Add it once and keep it installed: idempotent,
   and cheap when the flag is off.
3. Set the `ThreadLocal` around `action.run()`. Only events logged synchronously on the
   capturing thread are suppressed, so other threads' output is untouched.
4. If Log4j async loggers are active, the appender runs on another thread and the
   `ThreadLocal` is invisible there. Detect `AsyncLoggerContext` and fall back to "no
   suppression", logging it once at DEBUG. Document this.

### Alternative (if the owner prefers)

Deprecate the flag: `show_console` is accepted and ignored, the docs say so, and the
Python `showConsole` argument is kept for compatibility.

### Tests

- Unit: a capture with `showConsole=false` produces the captured lines while a test
  `ListAppender` named like a console appender receives nothing.
- Live: `runCommand("list", showConsole=False)` and check that the console log
  (boot-test `console.log`) does not contain the output. That needs the boot-test harness
  to expose the log path, which 01 §1 does.
