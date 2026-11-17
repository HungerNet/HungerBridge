# HungerBridge / HungerBridgePy — Final Report (2026-11-04)

## Executive summary

Both repositories are in a verified, release-ready state, **not yet committed or pushed**.

| Area | Before | After (verified) |
|---|---|---|
| HungerBridgePy | couldn't be imported; PyPI name clashed with HungerLib; 0 tests | 9 focused modules, 65 tests passing on Python 3.9 and 3.14, wheel builds |
| Builds | 6/6 built, but 2 shipped defects nobody saw | 8/8 build (26.3 added); artifacts inspected |
| Runtime | never tested; Fabric wouldn't start; Folia refused the plugin | 12/12 real servers: boot, 20/20 live protocol tests, clean API shutdown |
| Java tests | 21 tests, 1 broken, never run in CI | 55 tests, all pass, CI runs them |
| Protocol | 7 client/server mismatches | all fixed; golden vectors shared by both test suites |

## PLAN.md

Written after investigation and before implementation, then kept current. It contains the
findings, the refactor plan, the protocol analysis, the compatibility matrix (baseline →
final), Folia and 26.3 analysis, validation plans, risks, a completed checklist, and a
status log of discoveries.

## Architecture changes

**HungerBridge (Java)**
- Added:
  - `http/ApiHandler` (one path for method → body → rate limit + auth → ACL → handler, plus JSON 400/404/405/413/500);
  - `http/RequestState` (per-request state);
  - `security/Permissions`;
  - `log/LogLineFormatter`;
  - `v2/WorldHandlers`, `v2/SystemHandlers`, `v2/NotFoundHandler`;
  - `paper/scheduler/{PlatformScheduler, BukkitPlatformScheduler, FoliaPlatformScheduler}`;
  - `fabric/compat/{mc1_21,mc26}`;
  - 26.3 version projects;
  - tests: `BridgeServerIntegrationTest`, `ProtocolVectorsTest`, `RegressionTest`, `testing/*`, including `StandaloneBridge`;
  - `CHANGELOG.md`, `PLAN.md`.
- Removed dead code:
  - 6 unregistered handlers, including `/tokens`, which had no ACL;
  - 4 `Platform*Bridge` interfaces, 4 providers, 3 response helpers;
  - duplicate `PaperBridgeAdapter` and the per-platform log appenders;
  - all dead `build.gradle.kts` and `stonecutter.gradle.kts` files.
- Rewritten:
  - `HttpUtil`, `BridgeServer`, `LogDistributor`, all handlers;
  - the Paper executor and plugin, the Fabric executor, entry point and registrar;
  - the version build files, `settings.gradle.kts`, and CI.

**HungerBridgePy:** `bridge.py` (594 lines) split as follows; `bridge.py` remains as a compatibility re-export.

| Module | Extracted from `bridge.py` |
|---|---|
| `canonical.py` | `_canonicalize_json_body`, `_normalize_path` (fixed non-BMP escaping, UTF-16 key order, NaN rejection) |
| `auth.py` | `_build_auth_headers` → `Credentials` (`"id:secret"` support), `string_to_sign`, `sign`, `build_auth_headers` |
| `transport.py` | `_get`/`_post` (two duplicated 40-line retry loops), `_throttle` → `Transport` (session, timeouts, fresh nonce per retry, `Retry-After`, typed errors, protocol check) |
| `stream.py` | `Stream` (WHATWG SSE parser, history marker with legacy fallback, reconnect/back-off/dedupe, bounded `disconnect`) |
| `client.py` | all endpoint wrappers; signatures unchanged; plus `fromPickup`, `getProtocolVersion`, `getWorldTimeInfo` |
| `exceptions.py`, `units.py`, `protocol.py` | the missing `utils` modules, rebuilt from HungerLib's semantics; typed error subclasses |

## Protocol findings (all fixed; both sides)

1. **Non-BMP characters** (emoji) were escaped as `ὠ0` → signature failure plus a corrupted payload.
2. **Double rate limiting.** The second check ran *after* the nonce was consumed, so client retries after a 429 always failed. There was also no `Retry-After` header.
3. **Client reused the nonce** on retries, and had no timeouts.
4. **HMAC check order** leaked token existence (via stale timestamps and expiry), and forged requests caused disk writes. Now: timestamp → signature → nonce → state. 401s carry a `reason`.
5. **SSE**:
   - the client stripped indentation;
   - early lines were held as "history" for 15s;
   - lines were split on Unicode separators;
   - the server never flushed headers;
   - a full server sent 503 after already sending 200.

   The server now sends a `:history-end` marker.
6. **Line format:** the server streamed bare messages while the docs and client expected `[HH:mm:ss LEVEL]` lines; the server now uses the documented format.
7. **Versioning:** none existed. Now an `X-HungerBridge-Protocol: 1` header and `protocol_version` in `/server/meta`.
8. **Semantics:**
   - `/world/time` returned the wall clock;
   - `/auth/check` returned 200 on failure;
   - `max-list` was ignored;
   - Fabric metrics were broken;
   - GC fields were wrong.

## Compatibility matrix (final, all runtime-verified)

| MC | Paper | Folia | Fabric | Java |
|---|---|---|---|---|
| 1.21.11 | ✅ 20/20 | ✅ 20/20 | ✅ 20/20 | 21 |
| 26.1.2 | ✅ 20/20 | ✅ 20/20 | ✅ 20/20 | 25 |
| 26.2 | ✅ 20/20 | ✅ 20/20 (Folia beta) | ✅ 20/20 | 25 |
| 26.3 | ✅ 20/20 | ✅ 20/20 (Folia alpha) | ✅ 20/20 | 25 |

Servers tested: Paper builds 132/74/135/242, Folia 14/8/7/6, Fabric Loader 0.20.1.

## Folia

One jar serves Paper, Purpur and Folia. The threading model is detected at runtime via `RegionizedServer`.

| Folia work | Runs on |
|---|---|
| console commands, `shutdown`, world time and weather | global region scheduler |
| chunks, entities, TPS, MSPT | region scheduler at the world's spawn chunk |

- Every cross-thread hop has a 5s (commands) or 2s (metrics) timeout with a fallback value.
- `BukkitScheduler` is never touched on Folia.
- `plugin.yml` declares `folia-supported: true`.

Rationale: the threaded-region APIs are present in paper-api from 1.21.11 through 26.3, so a separate Folia build or `folia-api` dependency would add maintenance without benefit.

## Minecraft 26.3

- Added Paper (dev bundle `26.3.build.+`) and Fabric (non-remapping Loom, unobfuscated) targets.
- API change found by compiling: 26.1+ replaced `getDayTime()` with world clocks (`getOverworldClockTime()`), so I added the `FabricCompat` seam.
- The mixin targets are unchanged; boot tests confirm them.
- `api-version: 26.3`, `"minecraft": "~26.3"`, and added to the CI matrix.

## GitHub Actions validation

**Workflows inspected:** `build-and-release.yml`, `generate-wrapper.yml`, HungerBridgePy `release.yml`.

**Reproduced locally** with Temurin 25.0.5 + 21.0.13 on Windows (Docker's daemon was not running):
- the same env vars, `-Xmx6g`, and `gradlew :<loader>:versions:<target>:build --no-daemon --stacktrace`;
- Python: version injection from `version.yaml`, `python -m build` → `hungerbridge-1.0.dev1` wheel + sdist.

Deviation: final builds redirected `build/` outside OneDrive with an init script, because OneDrive locks broke Gradle's cleanup. Tasks and commands were otherwise identical.

**CI changes:**
- jobs `test` (`:common:test`) and `protocol` (standalone bridge × HungerBridgePy live suite);
- 26.3 matrix rows;
- explicit JDK 21 + 25;
- the release job's version regex now accepts unquoted values;
- the wrapper workflow no longer downgrades Gradle to 8.10.2;
- HungerBridgePy got `tests.yml` and a test step in `release.yml`.

## Build results

| Run | Result |
|---|---|
| Baseline (original code) | 6/6 exit 0, but artifact inspection showed Fabric 1.21.11 = Java 25 bytecode and no Folia flag |
| Final, clean | 8/8 exit 0; 1.21.11 jars are class version 65 (Java 21), 26.x jars 69; no `fabric-api` dependency; SnakeYAML shaded into Fabric only; `folia-supported: true` |

## Runtime validation

The final matrix above: every server started, HungerBridge enabled, the live suite passed 20/20, `/server/stop` shut the server down (exit 0), with **0 HungerBridge warnings and 0 error lines**.

Baseline evidence:
- the original Fabric jar refuses to start without Fabric API, and with it crashes with `UnsupportedClassVersionError (class file version 69.0)` on Java 21;
- the original Paper jar on Folia 26.2: "not marked as supporting Folia".

## Integration validation

HungerBridgePy's live suite against real servers and against `:common:standaloneBridge`: 20/20 everywhere. It covers:
- auth check;
- replay, clock-skew and tampered-body rejection (with the correct `reason`);
- every endpoint;
- `/server/run` with emoji (proves the canonicalization fix end to end);
- log → SSE delivery with history marker and timestamp;
- JSON 404 and 400;
- the protocol header on errors;
- the rate limit with `Retry-After`, recovered by the retrying client;
- `/server/stop`.

## Testing results

| Suite | Result |
|---|---|
| Java | 55 tests: unit, in-process HTTP integration, protocol vectors, regressions (incl. 200 concurrent admin/reader ACL requests). All pass. The previously broken `debugModeRoutesHmacDiagnosticsToDebugLevel` now passes. |
| Python | 65 unit + protocol tests on 3.9.25 and 3.14.7, plus the 20-test live suite. A stress loop (15×) caught and fixed the `disconnect()` hang. |
| Shared vectors | `protocol-vectors.json` (identical SHA-256 in both repos) is verified by both languages. |

## Bugs fixed (highlights beyond the protocol list)

- **Security:**
  - per-request auth state lived in `HttpExchange` attributes, which the JDK shares per endpoint (verified in `ExchangeImpl` bytecode). The ACL race is latent: not reproduced in 3×2000 attempts. The **audit log corruption was reproduced: 3971/4000 records carried another request's params**.
  - Paper `/hb` was usable by in-game ops, even though console-only was the intent of commit #7.
- **Config:** the shipped default policy granted only nodes no endpoint checks, so its tokens were useless. Fixed, plus aliases with warnings.
- **Commands:**
  - `/hb token rotate` permanently disabled the token (no pickup for the new secret);
  - errors were never shown to the sender;
  - Fabric lacked the `[expiry]` argument.
- **Robustness:** 4 SSE clients could starve the whole API (4-thread pool with a queue); the pool now grows on demand. `security.yaml` was re-parsed on every request. The SSE scheduler died after stop/start.
- **Python:** `broadcast()` built invalid JSON; `getTimestamped()` never matched anything; the 3.9 claim was false.

## Remaining issues / owner actions

1. **PyPI:** the distribution is renamed `hungerlib` → `hungerbridge`. Configure trusted publishing for `hungerbridge` before merging, or the release job will fail.
2. **Behaviour changes** for consumers (listed in `CHANGELOG.md`): the meaning of `/world/time.time`, `/auth/check` returning 401, the `max-list` cap.
3. Folia 26.3 is upstream **alpha** and 26.2 **beta**. They pass, but upstream may still change.
4. `show_console=false` does not suppress console output (this was already the case; documented, not changed).
5. On Windows, `Stream.disconnect()` returns within its timeout, but the daemon reader can linger until the server's next keepalive (≤15s).
6. CI on GitHub itself hasn't run. The cross-repo `protocol` job assumes `HungerNet/HungerBridgePy` is readable by the workflow.
7. Nothing is committed. `version.yaml` in both repos has `run: true`, so pushing to main will publish releases.

## Evidence locations (session scratchpad)

`logs/baseline`, `logs/final` (build logs and summaries), `servers/<target>/{console.log, pytest-live.txt, result.json}`, `race/RaceRepro.java`, `logs/matrix-final.txt`.
