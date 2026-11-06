# Changelog

## Unreleased — compatibility, protocol and release-readiness pass (2026-11)

### Platforms
* **Minecraft 26.3** support for Paper and Fabric (new `26.3-paper` / `26.3-fabric` targets).
* **Folia** support (1.21.11, 26.1.2, 26.2, 26.3) with the existing Paper jars:
  `folia-supported: true` and a `PlatformScheduler` abstraction (Bukkit scheduler on
  Paper/Purpur; global/region schedulers on Folia). `/server/meta` reports `folia`.
* Fabric jars no longer require **Fabric API** (it was declared but never used).
* Fabric **1.21.11** jar is compiled for Java 21 (was Java 25 bytecode → crash on Java 21).
* Paper code no longer touches server internals (NMS); world reads run on the owning thread
  with timeouts.

### Security fixes
* Per-request auth state was stored in `HttpExchange` attributes, which the JDK shares across
  all concurrent requests to the same endpoint: the ACL check could read another request's
  token (latent race), and audit-log entries recorded other requests' parameters
  (reproduced: 3971/4000 `/server/run` audit lines were wrong). State is now per request.
* HMAC verification order: timestamp → signature → nonce → revocation/expiry. Unknown tokens
  can no longer be distinguished via stale timestamps or expiry; forged requests no longer
  write the nonce cache to disk.
* Paper `/hb` is now console-only like on Fabric (token pickups print a passkey).

### Protocol (v1) — see ENDPOINTS.md
* New: `X-HungerBridge-Protocol: 1` header on every response; `protocol_version` in `/server/meta`.
* New: machine-readable `reason` on 401 (`missing_credentials`, `invalid_credentials`,
  `timestamp_out_of_range`, `nonce_replay`, `token_revoked`, `token_expired`).
* Fixed: requests were rate-limited twice (half the configured burst) and the second check
  ran after the nonce was consumed, so client retries after a 429 always failed. Now one check,
  before verification, and 429 responses carry `Retry-After`.
* Fixed: malformed bodies / wrong field types produced dropped connections; now 400. Unknown
  paths return JSON 404 (also `/ping/extra`); wrong method returns 405 + `Allow`.
* SSE: `:history-end` comment after the history (always sent, flushes headers immediately);
  lines are now `[HH:mm:ss LEVEL]: message` (as documented) with stack traces; a full server
  answers 503 instead of a broken 200; streams no longer starve other requests (the HTTP pool
  grows on demand instead of queueing behind 4 threads).
* Behaviour changes:
  * `/world/time` → `time` is now the **tick of day** (0–23999) — it used to be wall-clock
    epoch seconds by mistake; `full_time`, `day`, `server_time` added.
  * `/auth/check` → failures are 401 (was 200 with `ok:false`); `permissions` lists the
    effective permissions (token + policy, aliases expanded).
  * `/players/list` → `players` is capped by `players.max-list` (as documented); `count` is
    still the full online count.
  * `/world/chunks`, `/world/entities` on Fabric now report real per-dimension values (all
    dimensions previously collapsed into `world` and counts were 0); keys are assigned by
    environment on Paper too, independent of `level-name`.
  * `/world/tps` `tps_15m` on Fabric is a real 15-minute average (was the 5-minute value).
  * `/system/gc` → `gc_type` is the collector family (G1, ZGC, …), `last_gc_pause_ms` is the
    last collection's duration (was the largest cumulative time).
  * `/system/network` excludes the loopback interface.

### Configuration
* The shipped `policies.yaml` granted `server.info`, `player.list`, `world.read` — nodes no
  endpoint checks, so tokens from the default policy could not call anything. The template
  now grants real read-only nodes, and the three legacy names (plus `ping`) are accepted as
  aliases with a warning. Policies granting unknown nodes are reported at load time.
* `security.yaml` rate limits are parsed once at load/reload (were re-read on every request).
* `/hb token rotate` now prints a pickup URL for the new secret (rotation previously made the
  token unusable). Command errors are shown to the sender (they were only logged).

### Build / CI
* CI runs `:common:test` and a cross-language protocol job against HungerBridgePy, builds
  8 targets, and installs JDK 21 + 25 explicitly; `version.yaml` parsing accepts unquoted
  values; the wrapper workflow no longer downgrades Gradle to 8.10.2.
* Removed dead `build.gradle.kts` / `stonecutter.gradle.kts` duplicates and unused classes
  (six unregistered handlers incl. an unauthenticated-ACL `/tokens` handler, unused
  platform-bridge interfaces and providers).

### Migration notes
* Replace legacy nodes in `policies.yaml` (`server.info` → `server.meta` + `server.ping`,
  `player.list` → `players.list`, `world.read` → `world.*`). Existing files keep working.
* Clients reading `/world/time` `time` as a timestamp should use `server_time`.
* HungerBridgePy ≥ this release is recommended (older clients keep working against this
  server; they just do not use the new protocol features).
