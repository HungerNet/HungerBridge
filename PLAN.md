# HungerBridge Ecosystem — Refactor, Compatibility & Release-Readiness Plan

Scope: `HungerBridge` (Java, Paper + Fabric, this repo) and `HungerBridgePy`
(Python client, sibling repo `../HungerBridgePy`). This plan was written after a
full read of both repositories and **before** implementation. It is updated as
work proceeds (see "Status log" at the bottom).

---

## Executive Summary

| Area | Assessment |
|---|---|
| HungerBridgePy | **Not importable.** `__init__.py` imports a non-existent `HungerBridge` symbol, and `bridge.py` imports a non-existent `hungerbridge.utils` package (`exceptions`, `convert`). The PyPI distribution name is `hungerlib`, which is an *existing, unrelated* PyPI project (HungerLib 4.24.2), so the release workflow would publish into the wrong project. Zero tests. |
| Protocol | The HMAC scheme matches on both sides for ASCII/BMP payloads, but there are real mismatches: non-BMP characters (emoji) are escaped differently (Python `ὠ0` vs Java UTF-16 surrogates) → signature failure *and* corrupted payloads; server rate-limits **twice** per request and the second 429 fires *after* the nonce is consumed, so the client's retry (which reuses the same nonce) is always rejected as a replay; no `Retry-After` header although the client reads one; SSE `history` detection on the client is purely time-based. |
| Authorization | **The shipped default `policies.yaml` grants permission nodes that no endpoint checks** (`server.info`, `player.list`, `world.read`). A token created with the default policy cannot call any endpoint. `TokensConfig.defaults()` grants `ping` (dotless → never matches `server.ping`). |
| Paper | Builds rely on paperweight userdev and one NMS call (`MinecraftServer.getServer()` for max players). All scheduling uses `BukkitScheduler` / `callSyncMethod`. |
| Folia | **Unsupported.** No `folia-supported: true` in `plugin.yml` (Folia refuses to load it) and every scheduler call throws `UnsupportedOperationException` on Folia. |
| Fabric | `fabric.mod.json` hard-depends on `fabric-api` although no Fabric API class is used. The 1.21.11 target is compiled with a **Java 25** toolchain while declaring `"java": ">=21"` → `UnsupportedClassVersionError` on the Java 21 runtime that 1.21.11 servers use. Dimension/chunk/entity metrics use reflection with names that do not exist at runtime → all dimensions collapse onto key `world`, counts are 0. World state is read from HTTP threads (not thread-safe). |
| Minecraft 26.3 | Released 2026-09-15. Paper (build 241, STABLE), Folia (build 6, ALPHA), Fabric (loader 0.20.1) and the Paper 26.3 dev bundle all exist. Not targeted yet. |
| CI | Only builds per-target jars; `:common:test` **never runs in CI**, and one existing unit test (`debugModeRoutesHmacDiagnosticsToDebugLevel`) asserts a level the code never emits. Release job parses `version.yaml` with a regex that only works for double-quoted values. Python repo has no test job. |

---

## Findings

### HungerBridge (Java)

* **Build system:** Gradle wrapper 9.7.0, Groovy root `build.gradle` (reads `version.yaml`
  via SnakeYAML, applies Java toolchain 21 to all subprojects). `settings.gradle.kts`
  includes exactly **one** target per invocation, selected from `STONECUTTER_ACTIVE`
  (`<mc>-paper` / `<mc>-fabric`). Despite the naming, the Stonecutter plugin is **not used**;
  `stonecutter.gradle.kts` files are comment-only stubs.
* **Duplicate build scripts:** `common/`, `paper/` and `fabric/` each contain *both*
  `build.gradle` and `build.gradle.kts`. Gradle selects `build.gradle`; the `.kts`
  files are dead (and would not compile — they reference a `stonecutter` extension that
  does not exist). They mislead readers and must be removed.
* **Modules:**
  * `common` — loader-agnostic core: JDK `HttpServer` bridge (`BridgeServer`), 21 handlers
    under `http/v2`, HMAC token auth (`TokenManager`), config (`Config`, `TokensConfig`),
    SSE log fan-out (`LogDistributor`), audit log, command output capture.
  * `paper` — Bukkit/Paper plugin sources shared by all `paper/versions/<mc>-paper`
    projects (each applies paperweight userdev with its own dev bundle + `plugin.yml`).
  * `fabric` — Fabric mod sources shared by `fabric/versions/<mc>-fabric` (Loom 1.18.0-alpha.19;
    `fabric-loom` (remapping) for obfuscated 1.21.11, `net.fabricmc.fabric-loom`
    (non-remapping) for unobfuscated 26.x). One mixin into `MinecraftServer`.
* **Packaging:** Each versioned jar inlines `common` classes. Fabric additionally unpacks
  SnakeYAML into the jar (Minecraft does not ship it). Gson and log4j are provided by
  both platforms.
* **Supported targets claimed:** BUILDING.md + CI: Paper & Fabric 1.21.11 / 26.1.2 / 26.2.
  README claims only "Paper/Purpur 1.21.11, Fabric 1.21.11". No Folia claim anywhere.
* **Tests:** 5 JUnit classes in `common` (config, token manager, policy semantics,
  messages). No HTTP-level tests, no protocol tests, no platform tests.

### HungerBridgePy (Python)

* Single module `src/hungerbridge/bridge.py` (594 lines): canonical JSON + path
  normalization (protocol), SSE `Stream`, `BridgeClient` (transport, retry, throttle,
  HMAC signing, ~30 endpoint wrappers, unit conversion).
* Imports `.utils.exceptions` and `.utils.convert` which do not exist → `ImportError`.
* `__init__` exports `HungerBridge` which does not exist → `ImportError`.
* `pyproject.toml`: name `hungerlib` (taken on PyPI by a different project),
  `requires-python >=3.9` but code uses PEP 604 annotations evaluated at runtime
  (`str | None`) → `TypeError` on 3.9; `package-data` references `hungerlib.email`
  (copy-paste from HungerLib).
* README describes HungerLib/Pterodactyl, not this package; examples use
  `hungerlib.bridgeclient`, which does not exist.
* No HTTP timeouts anywhere (a dead server hangs callers forever).

### CI/CD

* `HungerBridge/.github/workflows/build-and-release.yml` — matrix of 6 targets on
  `ubuntu-latest`, Temurin JDK 25, `./gradlew :<project>:versions:<target>:build --no-daemon --stacktrace`
  with `JAVA_TOOL_OPTIONS=-Xmx6g`; uploads `build/libs/*.jar`; `release` job creates a
  GitHub release `v<version>` with all jars on every push to main.
* `HungerBridge/.github/workflows/generate-wrapper.yml` — manual; regenerates the wrapper
  with Gradle **8.10.2** (the committed wrapper is 9.7.0 → running it would downgrade).
* `HungerBridgePy/.github/workflows/release.yml` — on push to main: parse `version.yaml`
  with yq, inject version into `pyproject.toml`, `python -m build`, GitHub release, PyPI
  trusted publish. No tests.

---

## HungerBridgePy Refactor Plan

`bridge.py` is decomposed by responsibility. No speculative abstraction layers: each
module is something that is independently testable and maps to a protocol concern.

| File | Responsibility | Public API | Depends on | Migration notes |
|---|---|---|---|---|
| `canonical.py` | Protocol-critical canonical JSON + path normalization, byte-for-byte compatible with `TokenManager.canonicalizeJson` / `normalizePath`. | `canonical_json(value) -> str`, `normalize_path(path) -> str` | stdlib | Replaces private `_canonicalize_json_body` / `_normalize_path` (re-exported from `bridge.py` under old names). Fixes non-BMP escaping. |
| `auth.py` | HMAC-SHA256 request signing. | `Credentials` (parses `id`/`secret` or `"id:secret"`), `sign_request(...)`, `build_auth_headers(...)`, header-name constants | `canonical` | Signing logic moved verbatim; nonce/timestamp generated per call (per retry). |
| `exceptions.py` | Exception hierarchy. | `HungerBridgeError` (base, now carries `status`, `code`, `message`, `reason`), `HungerBridgeRateLimit`, `InvalidModeError`, `HungerBridgeAuthError`, `HungerBridgeForbidden`, `HungerBridgeNotFound`, `HungerBridgeConnectionError` | `requests` | Creates the module the old code imported but never shipped. All new types subclass `HungerBridgeError`, so existing `except HungerBridgeError` keeps working. |
| `units.py` | Byte unit conversion. | `convert_bytes(value, unit)`, `convert.byte(value, from, to)` (compat object) | stdlib | Replaces the missing `utils.convert`. |
| `transport.py` | HTTP: pooled `requests.Session`, timeouts, throttle, 429 retry/back-off honoring `Retry-After`, error-envelope → exception mapping, protocol-version check. | `Transport(base_url, credentials, ...)`, `.get(path)`, `.post(path, payload)` | `auth`, `canonical`, `exceptions` | Behavior of `max_retries`, `retry_backoff_sec`, `swallow_rate_limit`, `static_delay` preserved. New: `timeout`. |
| `stream.py` | SSE client for `/server/stream`: spec-correct field parsing, history/live split via server `:history-end` marker (time-based fallback for older servers), optional auto-reconnect with back-off and history de-duplication, read timeout for dead-connection detection. | `Stream` (same public methods: `connect`, `disconnect`, `isConnected`, `getRaw`, `getSanitized`, `getTimestamped`, `sanitize`, `extractTimestamp`), `parse_sse_lines()` | `auth` (via header provider), `requests` | Constructor signature preserved. `connect(keepalive, history)` preserved; adds `reconnect=False`. |
| `client.py` | `BridgeClient` — thin endpoint wrappers only. | All existing camelCase methods, unchanged signatures; new `getWorldInfo`, `getProtocolVersion`, `redeemPickup` (static). | `transport`, `stream`, `units`, `exceptions` | `broadcast()` now builds the tellraw component with `json.dumps` (was string-escaped and broke on `\`/newlines). |
| `bridge.py` | **Compatibility shim** — re-exports `BridgeClient`, `Stream`, `_canonicalize_json_body`, `_normalize_path`. | as before | all | Keeps `from hungerbridge.bridge import BridgeClient` working. |
| `__init__.py` | Package exports. | `BridgeClient`, `Stream`, exceptions, `HungerBridge` (alias of `BridgeClient`, the name the old `__init__` tried to export), `__version__`, `PROTOCOL_VERSION` | all | Fixes the import error. |

Packaging: rename distribution to **`hungerbridge`** (free on PyPI; matches the import
package). Fix `requires-python` (`from __future__ import annotations` keeps 3.9 working),
remove bogus `package-data`, add `py.typed`, add test extras, rewrite README for this
package. **Owner action:** a PyPI trusted publisher must be configured for the new name.

---

## Protocol Compatibility Analysis

**Transport:** HTTP/1.1, JSON request/response bodies (UTF-8), SSE for logs. No
compression on either side (none negotiated; `requests` sends `Accept-Encoding: gzip`
but the JDK `HttpServer` never compresses — compatible). Default bind `127.0.0.1:1913`.

**Message formats.** Success: flat JSON object with `"ok": true` plus endpoint fields
(e.g. `/world/tps` → `{"ok":true,"tps":..,"tps_1m":..,...}`). Error:
`{"ok":false,"error":"<code>","message":"<text>"}` with matching HTTP status.
(ENDPOINTS.md wrongly documents a `{ok,message,data,error}` envelope — to be fixed.)

**Authentication (HMAC "HKIM").** Headers `X-Auth-Id`, `X-Auth-Timestamp` (unix seconds),
`X-Auth-Nonce`, `X-Auth-Signature`. Signature = hex(HMAC-SHA256(key, M)),
`M = METHOD \n normalized_path \n timestamp \n nonce \n canonical_body`, key = 32-byte
HKDF-SHA256(master.key, salt, token_id), handed to the client once as 64 hex chars via
`/pickup/<id>?passkey=<hex>`. Canonical body = keys sorted, no whitespace, non-ASCII as
`\uXXXX`, empty for GET. Server checks: timestamp skew (default 300 s), nonce replay
cache (persisted to `storage/sessions.json`), signature, expiry, revocation, ACL.

| Concern | Java (server) | Python (client) | Status |
|---|---|---|---|
| Method/path normalization | upper-case; strip query/fragment/trailing `/` | same | ✅ match |
| Canonical JSON, ASCII/BMP | sorted keys, escapes, `\u%04x` for `<0x20` and `>0x7f` | same | ✅ match |
| Canonical JSON, **non-BMP** | iterates UTF-16 → `😀` | iterates code points → `ὠ0` (5 hex digits) | ❌ **mismatch** — signature fails; the body Python *sends* is also wrong (`ὠ`+`0`) |
| Numbers | original lexeme (Gson `LazilyParsedNumber`) | `json.dumps` (body sent = canonical form) | ✅ consistent; NaN/Infinity must be rejected client-side |
| Secret decoding | hex | `bytes.fromhex` | ✅ |
| Nonce per retry | single-use | **reused across retries** | ❌ retry after 429 → `NONCE_REPLAY` |
| Rate limiting | `auth()` limits (pre-verify) **and** each handler limits again (post-verify, after nonce consumed); 429 + then a second response write attempt | expects 429 + optional `Retry-After` | ❌ double consumption, no `Retry-After`, nonce burned |
| Verification order | expiry checked before signature (leaks token existence), nonce recorded + **disk write** before signature (unauthenticated disk writes) | n/a | ❌ fix order: timestamp → signature → nonce → expiry |
| 401 detail | none | n/a | add machine-readable `reason` (`timestamp_out_of_range`, `nonce_replay`, `token_expired`, `invalid_credentials`) without enabling token enumeration |
| `/auth/check` | 200 + `ok:false` for unknown/revoked tokens; lists only policy *or* token permissions | expects `tokenId/policyId/permissions` | ❌ make it 401 on failure and return merged permissions |
| Handler exceptions | uncaught → connection dropped, no response | `requests` error | ❌ central 500 JSON |
| Unknown paths | JDK HTML 404 | — | return JSON 404 |
| Versioning | none | none | ➕ add `X-HungerBridge-Protocol: 1` response header + `protocol_version` in `/server/meta`; client warns on major mismatch |
| SSE framing | `data:<line>\n\n`, `\n`/`\r` escaped as `\\n`/`\\r`, `:keepalive` every 15 s, history first | strips **all** leading whitespace after `data:` (destroys stack-trace indentation); history = whatever arrives in first 1 s (or 15 s when no history requested, delaying all early lines) | ❌ server sends `:history-end` comment after history (ignored by old clients); client strips at most one space per SSE spec, uses marker, falls back to timer |
| SSE headers flush | headers only flushed with first chunk (≤15 s delay) | blocks on headers | ❌ flush immediately (marker does this) |
| Stream registration | registers client **after** sending 200 → on "too many clients" tries to send 503 after 200 | — | ❌ register first |
| Reconnect | — | none; thread dies silently | ➕ optional auto-reconnect with back-off, history de-dup |
| Timeouts | `/server/run` waits ≤5 s for main thread | **no timeouts** | ➕ client default `(5, 30)` s; stream read timeout > keepalive |
| `/world/time` | returns **wall-clock epoch seconds** as `time` | `getWorldTime()` | ❌ return world day-time ticks (`time`), plus `full_time`, `day`, `server_time` |
| `/world/*` dimension keys (Fabric) | broken reflection → all `world` | expects `world`, `world_nether`, `world_the_end` | ❌ fix on Fabric |
| `/players/list` | ignores `players.max-list` (documented limit) | | ❌ apply limit; `count` stays total |
| `/world/tps` Fabric 15m | returns 5 m value | | ❌ real 15 m EMA |
| Default policy | grants non-existent nodes | | ❌ fix template; accept legacy node aliases with a warning |

**Connection lifecycle:** stateless request/response (keep-alive pooled via
`requests.Session` after refactor); SSE stream is long-lived, server-side keepalive comment
every 15 s, server drops clients whose queue stalls for 30 s, max 64 stream clients.

**Error handling / retry:** client retries only on 429, up to `max_retries`, sleeping
`max(backoff*(attempt+1), Retry-After)`; regenerates signature per attempt (fixed).

---

## Version / Loader Compatibility Matrix

Baseline (before this work) → final. Runtime = real server boot + HungerBridgePy live suite.

| Minecraft Version | Loader | Build (baseline → final) | Runtime (baseline → final) | Issues found / fixed |
|---|---|---|---|---|
| 1.21.11 | Paper | ✅ → ✅ | untested → ✅ 20/20 | NMS removed; scheduler abstraction |
| 26.1.2 | Paper | ✅ → ✅ | untested → ✅ 20/20 | same |
| 26.2 | Paper | ✅ → ✅ | untested → ✅ 20/20 | same |
| 26.3 | Paper | not targeted → ✅ | — → ✅ 20/20 | new target |
| 1.21.11 | Folia | (Paper jar) → ✅ | refuses to load → ✅ 20/20 | `folia-supported`, Folia scheduler |
| 26.1.2 | Folia | (Paper jar) → ✅ | refuses to load → ✅ 20/20 | same |
| 26.2 | Folia (beta) | (Paper jar) → ✅ | **refuses to load (verified)** → ✅ 20/20 | same |
| 26.3 | Folia (alpha) | — → ✅ | — → ✅ 20/20 | new |
| 1.21.11 | Fabric | ✅ (wrong bytecode) → ✅ | **won't start without Fabric API; crashes on Java 21 (verified)** → ✅ 20/20 | Java 21 toolchain, no Fabric API |
| 26.1.2 | Fabric | ✅ → ✅ | won't start without Fabric API → ✅ 20/20 | dependency, metrics, day-time API |
| 26.2 | Fabric | ✅ → ✅ | same → ✅ 20/20 | same |
| 26.3 | Fabric | not targeted → ✅ | — → ✅ 20/20 | new target |

---

## Folia Analysis

* **Scheduler usage today:** `BukkitScheduler.runTask` (command dispatch), `callSyncMethod`
  (TPS, tick times, chunk/entity counts, max players), `runTaskAsynchronously`
  (unused async helpers). All throw `UnsupportedOperationException` on Folia.
* **Thread assumptions:** "main thread" = owner of all world state; HTTP worker threads
  block on main-thread futures with no timeout (`callSyncMethod(..).get()`), which can hang
  HTTP workers forever if the server stalls.
* **Unsafe API usage:** `World#getEntities()` / `getLoadedChunks()` called via
  `callSyncMethod` (fine on Paper, illegal off-region on Folia); `getWeather()` reads world
  state directly from an HTTP thread (unsafe on both). `Server#getTPS()` /
  `getTickTimes()` / `getAverageTickTime()` are global-tick concepts that Folia does not
  provide in the same way. NMS `MinecraftServer.getServer()` (unnecessary).
* **Required abstraction:** `PlatformScheduler` (in `paper` sources):
  `runGlobal(Runnable)`, `callGlobal(Callable, timeout)`, `callForWorld(World, Callable)`,
  plus `isFolia()`. Two implementations:
  * `BukkitPlatformScheduler` — Paper/Purpur: `BukkitScheduler` (unchanged semantics).
  * `FoliaPlatformScheduler` — `GlobalRegionScheduler` for console commands/global state;
    `RegionScheduler` (at a world's spawn chunk) for per-world reads; never touches
    `BukkitScheduler`.
  Selection at runtime by probing `io.papermc.paper.threadedregions.RegionizedServer`.
  One jar serves Paper, Purpur and Folia; Folia classes are only touched on Folia.
* **Metrics on Folia:** TPS/MSPT from `Server#getTPS()`/`getAverageTickTime()` when
  supported, else region TPS (`Bukkit.getRegionTPS(...)`) at the overworld spawn region;
  entity/chunk counts best-effort via region tasks with timeouts — values that Folia cannot
  provide globally are reported as `-1` rather than throwing.
* **Plugin descriptor:** add `folia-supported: true`.
* Validation: run real Folia servers (1.21.11, 26.1.2, 26.2, 26.3) with the built jar and
  drive every endpoint through HungerBridgePy.

---

## Minecraft 26.3 Analysis

* **Mappings:** 26.x is shipped unobfuscated by Mojang → no intermediary/reobf step. Fabric
  uses the non-remapping `net.fabricmc.fabric-loom` plugin (as for 26.1.2/26.2); Paper's dev
  bundle `26.3.build.<n>-stable` exists.
* **API changes to check (compile + runtime):** Paper API deprecations (e.g.
  `JavaPlugin#getDescription`), `Server#getTPS`/`getTickTimes`, Bukkit `api-version: 26.3`;
  Fabric mixin targets `MinecraftServer#tickChildren`, `stopServer`, `reloadResources`,
  `ServerChunkCache#getLoadedChunksCount`, `ServerLevel#getAllEntities`, `getDayTime`.
  The mixin uses `defaultRequire: 1`, so a renamed target crashes at startup → runtime test
  required, compile alone is not evidence.
* **Strategy:** add `paper/versions/26.3-paper` and `fabric/versions/26.3-fabric` cloned
  from 26.2, pin dev bundle/loader, add CI matrix rows, compile, then boot real 26.3 Paper,
  Folia and Fabric servers and exercise the API.
* **Java:** 26.x requires Java 25 (toolchain 25, `"java": ">=25"`).

---

## Build Validation Plan

* **Workflows:** `build-and-release.yml` (authoritative), `generate-wrapper.yml` (manual),
  `HungerBridgePy/release.yml`.
* **Matrix:** `{paper,fabric} × {1.21.11, 26.1.2, 26.2}` (+ `26.3` after this work), JDK 25.
* **Reproduction:** a local script that mirrors each matrix step exactly — `STONECUTTER_ACTIVE`
  / `STONECUTTER_ACTIVE_PAPER` env, `JAVA_TOOL_OPTIONS=-Xmx6g`,
  `GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx6g`,
  `gradlew :<project>:versions:<target>:build --no-daemon --stacktrace`, Temurin JDK 25 as
  `JAVA_HOME`; JDK 21 made discoverable to Gradle toolchains (ubuntu-latest has it in
  `/usr/lib/jvm`). Docker is installed but its daemon is not running, so builds run
  natively on Windows with the same JDK vendor/version family.
* **Remapping/packaging:** `build` → `remapJar` (Fabric 1.21.11), `jar` (Fabric 26.x),
  paperweight jar (Paper). Inspect every artifact: class-file major version, bundled
  `common` classes, `plugin.yml`/`fabric.mod.json` expansion, SnakeYAML presence on Fabric.
* **Tests:** add `:common:test` to CI and run it locally.
* **Python:** `python -m build` exactly as in `release.yml` (incl. the version-injection
  step), plus `pytest` on 3.9 and 3.14.

## Runtime Validation Plan

* For each Paper/Folia/Fabric × MC version: create a throw-away server dir in the
  scratchpad, download the official server jar (PaperMC Fill API / Fabric meta), accept the
  EULA, set `online-mode=false`, small view distance, `level-type=flat`, unique port,
  install the built artifact, start with the correct JDK (21 for 1.21.11, 25 for 26.x),
  wait for `Done (`, issue `hb token create`, redeem the pickup, run the protocol test
  suite, `stop`, collect logs, grep for `ERROR`/`Exception`/HungerBridge warnings.
* **Protocol/integration:** a pytest integration suite (skipped unless
  `HB_URL`/`HB_TOKEN` are set) that drives every endpoint through HungerBridgePy: auth
  check, ping, meta, players, tps/mspt/time/weather/chunks/entities, system metrics,
  run (with emoji/escaping payloads), log, SSE (history marker + live line), 401/403/404
  paths, replay rejection, rate-limit + retry, disconnect/reconnect.

## Testing Strategy

* **Unit (Python):** canonical JSON vs. golden vectors generated by the Java implementation,
  signing, path normalization, transport retry/timeout/error mapping (mocked HTTP), SSE
  parser, stream history/marker/reconnect logic (local fake SSE server), units.
* **Unit (Java):** existing tests (fix the wrong one), plus rate-limit single-consumption,
  verification order, legacy permission aliases, default policy usefulness, canonicalization
  golden vectors (shared with Python).
* **Integration (Java):** start `BridgeServer` in-process with a fake `CommandExecutor`
  and hit it over real HTTP (auth, 401 reasons, 404 JSON, Retry-After, SSE marker).
* **Protocol (cross-language):** a shared golden-vector file
  (`protocol/canonical-vectors.json`) consumed by both test suites; plus live Python ↔ Java
  tests against the in-process server and against real Minecraft servers.
* **Compatibility:** CI matrix incl. 26.3; artifact inspection; runtime boot matrix.
* **Regression:** one test per bug fixed above.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| paperweight dev-bundle setup fails/slow locally (RAM: 15 GB host) | med | run targets sequentially, mirror CI memory flags |
| Folia API differences per version (region TPS, entity access) | high | all Folia calls guarded, timeouts, `-1` fallbacks; runtime-tested per version |
| Mixin target renamed in 26.3 | med | runtime boot test; compile against 26.3 |
| Behavior change of `/world/time` value | low | documented; old value was a bug |
| Permission alias widening | low | aliases only map the three shipped-but-broken node names to their obviously intended read-only nodes; warning logged |
| PyPI rename | med | documented owner action; nothing is published from here |
| `/auth/check` returning 401 instead of 200 `ok:false` | low | client raises `HungerBridgeAuthError` (subclass of `HungerBridgeError`) |

## Implementation Order

1. [x] Baseline: reproduce CI matrix as-is; record results.
2. [x] Python: decompose `bridge.py`, fix imports/packaging, protocol fixes, unit tests.
3. [x] Java protocol/auth fixes (rate limit, verification order, Retry-After, auth reasons,
       `/auth/check`, safe handler wrapper, JSON 404, protocol header, SSE marker/flush,
       world time, players limit, cached security settings, default policies + aliases).
4. [x] Java tests: fix broken test, add regression + in-process HTTP integration tests,
       shared golden vectors.
5. [x] Paper: remove NMS, `PlatformScheduler` abstraction, thread-safe metrics, timeouts.
6. [x] Folia: `FoliaPlatformScheduler`, `folia-supported: true`.
7. [x] Fabric: drop `fabric-api` dependency, Java 21 toolchain for 1.21.11, direct
       (non-reflective) metrics on the server thread, 15 m TPS, command parity.
8. [x] 26.3: Paper + Fabric version projects, CI matrix.
9. [x] Build hygiene: remove dead `.kts`/stonecutter stubs, CI runs `:common:test`,
       release job version parsing, wrapper workflow version, Python CI test job.
10. [x] Re-run the full CI reproduction (8 targets) + Python build.
11. [x] Runtime validation: Paper ×4, Folia ×4, Fabric ×4 + integration suite.
12. [x] Documentation (README, ENDPOINTS, BUILDING, Python README, CHANGELOGs).
13. [x] Final report.

---

## Status log

* 2026-11-04 — Investigation complete; plan written. Baseline CI reproduction running.
* Baseline CI reproduction: all 6 targets build (exit 0) — but artifact inspection shows the
  Fabric 1.21.11 jar contains Java 25 (major 69) classes, and no Paper jar declares Folia
  support. Runtime confirmation on real servers:
  * baseline Fabric 1.21.11: refuses to start — *"requires any version of fabric-api, which
    is missing"*; with Fabric API added it crashes on Java 21 with
    `UnsupportedClassVersionError … class file version 69.0`;
  * baseline Paper jar on Folia 26.2: *"Could not load plugin … not marked as supporting Folia"*.
* Discovery: JDK `HttpExchange` attributes are backed by the shared per-context map
  (verified in `sun.net.httpserver.ExchangeImpl` bytecode, JDK 21 and 25). The original code
  passed the authenticated token and parsed body through them. Consequences: latent ACL race
  (not reproduced in 3×2000 concurrent attempts — nanosecond window) and wrong audit records
  (reproduced: 3971/4000 `/server/run` audit lines carried another request's parameters).
  Fixed with a thread-confined `RequestState`; regression test with 200 concurrent
  admin/reader requests.
* Discovery: Minecraft 26.x replaced `Level#getDayTime()` with world clocks
  (`getOverworldClockTime()`) → `fabric/compat/{mc1_21,mc26}` seam.
* Discovery: modern versions keep no spawn chunks loaded with no players online (0 is valid);
  the live test force-loads a chunk to verify chunk counting.
* Discovery: `/hb token rotate` made tokens unusable (no pickup for the new secret); fixed.
* Discovery: `/system/gc` `last_gc_pause_ms`/`gc_type` were semantically wrong; fixed.
* Python: `Stream.disconnect()` could block for the read timeout (buffered-reader lock);
  fixed via socket shutdown + bounded join; stream honours `Retry-After` when reconnecting.
* Runtime matrix (12 servers): all boot, load HungerBridge, pass the 20-test live suite,
  and shut down via `/server/stop` (see final report for per-target evidence).

## Folia implementation decisions (final)

* One jar for Paper, Purpur and Folia; `PlatformScheduler.create()` probes
  `io.papermc.paper.threadedregions.RegionizedServer` at runtime. Only the Paper API is used
  (the threaded-region scheduler interfaces exist in paper-api 1.21.11 … 26.3), so no
  separate Folia build or `folia-api` dependency is needed.
* Console commands, `Bukkit.shutdown()`, world time and weather → global region scheduler.
* Chunk/entity counts, TPS and MSPT → region scheduler at the world's spawn chunk (TPS is the
  TPS of that region on Folia).
* Every hop has a timeout (5 s commands, 2 s metrics) and a fallback value, so a stalled
  region cannot hang HTTP worker threads.
* `BukkitScheduler` is never touched on Folia; Folia classes are never touched on Paper.
