# PLAN: HungerBridge ecosystem modernization (protocol v2)

Scope: three repositories, with **HungerBridge as the source of truth**.

| Repo | Path (local) | Role | Version before → after |
|---|---|---|---|
| HungerBridge | `HungerBridge-1/` (this repo) | Paper / Purpur / Folia plugin + Fabric mod: the HTTP API inside the game | `4-alpha.97` → `5-alpha.1` |
| HungerBridgePy | `../HungerBridgePy/` | Python client (`pip install hungerbridge`) | `1.0-dev.7` → `2.0-dev.1` |
| CalagoAPI | `../CalagoAPI/` | Calagopus SDK; `calagoapi-minecraft` extension uses HungerBridgePy | core unchanged; `calagoapi-minecraft` `1.0-dev.2` → `2.0-dev.1` |

This file was written after reading all three repositories in full and **before** any code
change. It is updated during implementation (see [§17 Status log](#17-status-log)). The
earlier plans (`PLAN.md` of the 2026-11 release-readiness pass here, CalagoAPI's 2.0 design
record) and `REPORT.md` are superseded and removed; git history keeps them.

Backward compatibility is **out of scope** by decision of the owner. Everything below that
says "removed" is removed, not deprecated.

Contents

1. [Architecture review](#1-architecture-review)
2. [Runtime data flow](#2-runtime-data-flow)
3. [Public API inventory](#3-public-api-inventory)
4. [Transport and streaming review](#4-transport-and-streaming-review)
5. [Release-resolution review](#5-release-resolution-review)
6. [Logging review](#6-logging-review)
7. [Command execution investigation (`runCommand("text", false)`)](#7-command-execution-investigation)
8. [Naming convention audit](#8-naming-convention-audit)
9. [Technical debt, duplicates and legacy layers](#9-technical-debt-duplicates-and-legacy-layers)
10. [Bug inventory](#10-bug-inventory)
11. [Documentation and tutorial audit](#11-documentation-and-tutorial-audit)
12. [Proposed architecture](#12-proposed-architecture)
13. [Repository-by-repository implementation plan](#13-repository-by-repository-implementation-plan)
14. [Breaking change inventory](#14-breaking-change-inventory)
15. [Risk assessment](#15-risk-assessment)
16. [Testing and documentation strategy](#16-testing-and-documentation-strategy)
17. [Status log](#17-status-log)

---

## 1. Architecture review

### 1.1 Repository relationships

```
                    ┌──────────────────────── Calagopus panel ───────────────────────┐
                    │ REST API (files, power, console websocket)   Version Changer ext.│ (retired)
                    └───────────▲─────────────────────────────────────────▲──────────┘
                                │ calagoapi core                          │ calagoapi_minecraft.versions
┌──────────────┐   uses   ┌─────┴──────────────────┐   uses   ┌───────────┴─────────┐
│ user scripts ├─────────►│ CalagoAPI              ├─────────►│ HungerBridgePy      │
└──────────────┘          │  calagoapi (core)      │          │  BridgeClient       │
                          │  calagoapi-minecraft   │          │  Stream (SSE)       │
                          └────────────────────────┘          │  releases (GitHub)  │──► api.github.com (removed)
                                                              └─────────┬───────────┘
                                                      signed HTTP + SSE │ protocol v1
                                                              ┌─────────▼───────────┐
                                                              │ HungerBridge        │
                                                              │  common: HTTP API,  │
                                                              │   auth, SSE, config │
                                                              │  paper: Paper/      │
                                                              │   Purpur/Folia      │
                                                              │  fabric: Fabric     │
                                                              └─────────────────────┘
```

* **HungerBridge** — Gradle multi-project. `common/` (loader independent: JDK `HttpServer`,
  HMAC auth in `TokenManager`, `LogDistributor` SSE fan-out, YAML config, endpoint handlers),
  `paper/src` (one source tree, per-version builds in `paper/versions/<mc>-paper`, runtime
  Folia detection through `PlatformScheduler`), `fabric/src` (+ `fabric/compat/<era>` seam,
  per-version Loom builds). 8 jars: {1.21.11, 26.1.2, 26.2, 26.3} × {paper, fabric}.
  Forge and NeoForge are **not** supported targets (no module exists; `docs/design/04` lists
  NeoForge as exploratory) and stay out of scope.
* **HungerBridgePy** — `client.py` (endpoint wrappers), `transport.py` (signing, retry, error
  mapping), `auth.py`, `canonical.py` (wire canonicalization, golden vectors shared with
  Java), `stream.py` (SSE), `releases.py` (GitHub release discovery), `bridge.py`
  (compatibility re-exports), `units.py`, `exceptions.py`, `protocol.py`.
* **CalagoAPI** — game-agnostic core (`calagoapi`) plus first-party extension
  `calagoapi-minecraft`: `MinecraftServer(Server)` combining panel calls with
  `server.bridge` (a HungerBridgePy `BridgeClient`), `installBridge()` (HungerBridgePy
  release finder + panel `files.pullFile`), `createBridgeToken()` (panel console websocket +
  pickup), Version Changer wrappers (`server.versions`, `panel.minecraft.versionChanger`).

### 1.2 Architectural inconsistencies

| # | Inconsistency | Where |
|---|---|---|
| A1 | The same data is exposed under different names in two Python layers (`getMemoryStats` vs `getMemoryInfo`, `getCPUStats` vs `getProcessorInfo`, `getGCStats` vs `getGarbageCollectorInfo`, `log` vs `writeLog`, `getServerMeta` vs `getBridgeInfo`, `getWorldWeather` vs `getWeather`, …): ~25 MinecraftServer methods are pure renamed forwards of HungerBridgePy | CalagoAPI `server.py` |
| A2 | Console *line format* is decided in three places: Java `LogLineFormatter` (`[HH:mm:ss LEVEL]: msg`), Python `Stream.sanitize`/`extractTimestamp` (re-parses it), CalagoAPI docs (re-documents it) | all |
| A3 | Logger adapters differ per loader: Paper uses the *thread* name as the Log4j *logger* name, Fabric renames the thread — the `thread` argument means different things per loader | `PaperLoggerAdapter`, `FabricLoggerAdapter` |
| A4 | Command output is captured by scraping the root logger (needs the console to print it), so capture and console output are coupled; `show_console` cannot work by construction (§7) | `CommandCapture` |
| A5 | Release selection depends on GitHub asset *file names* (two naming schemes), while the project is published on Modrinth with structured loader/game-version metadata | `releases.py`, root `build.gradle` |
| A6 | `installBridge()` infers loader/version from four sources (Version Changer, running bridge, egg name, startup variables) — implicit, order-dependent, untestable without mocks | `calagoapi_minecraft/bridge.py` |
| A7 | Two credential formats (`"id:secret"` and `(id, secret)`) with parsing in HungerBridgePy *and* validation in CalagoAPI | `auth.Credentials.parse`, `config.checkToken` |
| A8 | Chat styling by *string prefix* (`"Error:"`, `"Success:"` …) repeated in Paper `HbCommand.style` and Fabric `FabricCommandRegistrar.styleFor`; `/hb` without arguments behaves differently on Paper (header only) and Fabric (usage) | platform modules |
| A9 | History cap (200) hard-coded on both sides (`LogDistributor.HISTORY_MAX`, `protocol.MAX_HISTORY`) | both |
| A10 | Wire keys mostly snake_case, but `/auth/check` returns `tokenId`/`policyId` | `AuthCheckHandler` |

### 1.3 Duplicate implementations

* Paper/Fabric logger adapters (95% identical) → one common Log4j logger.
* Paper/Fabric chat styling (`style` / `styleFor`) → one message model, two thin renderers.
* `CommandMessages.helpLines()` handled in `HbCommand` *and* `CommonCommandHandler`.
* Python stream line clean-up (`sanitize`) duplicated in HungerBridgePy and CalagoAPI
  (`onConsoleLine` re-sanitizes), and ANSI regexes in both packages.
* Credential validation: HungerBridgePy `Credentials.parse` + CalagoAPI `checkToken`.
* Release lookup: HungerBridgePy `find_release` + CalagoAPI `findBridgeRelease` wrapper +
  CalagoAPI config keys `release/repository/github_api` mirroring its arguments.
* `BridgeClient.redeemPickup` (static) duplicates module function `redeem_pickup`.

### 1.4 Legacy compatibility layers

| Layer | Repo | Decision |
|---|---|---|
| `hungerbridge.bridge` re-export module, `HungerBridge` alias, `_canonicalize_json_body` | Py | remove |
| `Credentials` non-hex secret → UTF-8 fallback | Py | remove (secrets are always 64 hex) |
| `"id:secret"` combined tokens | Py, CalagoAPI | remove (requirement) |
| SSE legacy time-window heuristic for servers without `history-end` | Py | remove |
| `level=None` backspace hack (`"\b" * 50`) to hide the level tag | Py | remove |
| `units.convert` SimpleNamespace (`convert.byte(...)`, HungerLib compatibility) | Py | remove |
| GitHub old asset names `<mc>-<loader>-<version>.jar` | Py | remove with GitHub support |
| Legacy permission aliases `server.info`, `player.list`, `world.read`, `ping` | Java | remove (unknown nodes keep warning) |
| `/system/memory` legacy `used_bytes`, `total_bytes`, `free_bytes`, `max_bytes` | Java | remove |
| `token`/`tokens` subcommand alias (Paper only, Fabric never registered `tokens`) | Java | remove `tokens` |
| `CommandExecutor.executeWithOutput(String)` default overload, `execute(String)` | Java | replace by one `run()` |
| `Logger.log(level, message)` "backwards-compatible" default | Java | replaced by the new logger API |
| `Config` reading `../../version.yaml` relative to the config dir | Java | remove (platforms set the version) |
| `STONECUTTER_ACTIVE` (misnamed; Stonecutter is not used), `fabric/versions/26.2-fabric/stonecutter.gradle.kts`, `init-no-project.gradle`, `settings-wrapper.gradle` | Java | rename to `HB_TARGET` / remove |
| Version Changer integration (`versions.py`, `server.versions`, `panel.minecraft.versionChanger`, `auto_backup`, detection) | CalagoAPI | remove (requirement) |

---

## 2. Runtime data flow

### 2.1 Request (today, protocol v1)

```
BridgeClient.runCommand("list", showConsole=False)
 └ Transport.request("POST", "/server/run", {"command","silent","show_console"})
    ├ canonical_body(payload)  ──►  body bytes = canonical JSON (ASCII, sorted keys)
    ├ build_auth_headers: HMAC-SHA256(secret, METHOD\npath\nts\nnonce\nbody)
    └ requests.Session.request(timeout=(5,30)); 429 → retry with fresh nonce + Retry-After
HungerBridge HttpServer (pool, up to 128 workers)
 └ filters: RequestState → ProtocolHeader → RemoteIp → Audit
    └ ApiHandler: path → method → body (≤1 MiB, JSON object) → rate limit → HMAC → ACL
       └ RunHandler → CommandExecutor.executeWithOutput(cmd, showConsole)
          ├ Fabric: server.submit(...) on the server thread, 5 s timeout
          │   CommandCapture: root-logger appender ON, performPrefixedCommand(console), OFF
          └ Paper/Folia: PlatformScheduler.callGlobal(...), 5 s timeout
              CommandCapture: same, Bukkit.dispatchCommand(ConsoleSender)
       ← {"ok":true,"output":[...]}       (timeout → silently {"ok":true,"output":[]})
```

### 2.2 Log stream (today)

```
any thread ─ Log4j event ─► PlatformLogAppender (root logger)
   └ LogLineFormatter "[HH:mm:ss LEVEL]: msg" ─► LogDistributor.publish(line)
        ├ history deque (200, redacted)
        └ for each StreamConnection: queue.offer (full → drop oldest, silently)
GET /server/stream?history=N
   register() → sendHistory(snapshot) → ":history-end" → loop poll(5s) → write+flush
   keepalive ":keepalive" every 15 s (scheduler); "stalled" = no enqueue for 60 s
HungerBridgePy Stream (thread) ─ parse "data:" lines ─ unescape "\n" ─ handlers / lists
   reconnect: exponential back-off; de-duplicate replayed history by *string* match
CalagoAPI streamConsole() = streamLogs(sanitize=True)
```

### 2.3 Install and token bootstrap (today)

```
installBridge(mc=None, loader=None)
  detectPlatform: Version Changer build → bridge /server/meta → egg name → startup vars
  find_release: GET api.github.com/repos/HungerNet/HungerBridge/releases/latest
                parse asset file names → pick jar
  panel files.pullFile(url, "/plugins" | "/mods")
createBridgeToken(): panel console websocket "hb token create ..." → regex "/pickup/…"
                     → GET /pickup/<id>?passkey=… → "id:secret"
```

---

## 3. Public API inventory

### 3.1 HungerBridge HTTP API (v1 → v2)

| Endpoint | Node | v1 | v2 change |
|---|---|---|---|
| `GET /ping` | `server.ping` | `server_time` | — |
| `GET /auth/check` | — | `tokenId`, `policyId`, `permissions`, `expiry` | keys `token_id`, `policy_id` |
| `GET /server/meta` | `server.meta` | `platform`, `minecraft_version`, `bridge_version`, `port`, `protocol_version` | `protocol_version` = 2 |
| `POST /server/run` | `server.run` | `{command, silent, show_console}` → `output` | `{command, show_console}`; `show_console` honoured; 504 `command_timeout` |
| `POST /server/log` | `server.log` | `{message, level, thread}` | `+ logger`, `+ normalize`; validated names |
| `POST /server/stop` | `server.stop` | `{force}` (ignored) → `stopped`, `force` | no body fields → `stopped` |
| `GET /server/stream` | `server.stream` | SSE text lines | SSE JSON `log` events with ids, `ready` event, `Last-Event-ID` resume, `history=N\|all` |
| `GET /players/list` | `players.list` | `count`, `max`, `players` | — |
| `GET /world/{tps,mspt,time,weather,chunks,entities}` | `world.*` | | — |
| `GET /system/{uptime,cpu,memory,gc,threads,network}` | `system.*` | | memory legacy aliases removed |
| `GET /pickup/<id>?passkey=` | — | `token_id`, `token_secret` | — |

Console commands (`/hungerbridge`, alias `/hb`, console only): `help`, `reload`,
`token list|create|rotate|revoke|remove`.

### 3.2 HungerBridgePy (top level)

`BridgeClient` (≈35 methods), `Stream`, `Credentials`, `redeem_pickup`, `find_release`,
`ReleaseAsset`, `PROTOCOL_VERSION`, `MAX_HISTORY`, 7 exception classes, `HungerBridge`
alias; modules `releases` (`list_release_assets`, `parse_asset_name`, `loader_for`,
`LOADERS`, `LOADER_DIRECTORIES`, `GITHUB_API`, `DEFAULT_REPOSITORY`), `units`
(`convert`, `convert_bytes`, `convert_time`), `stream` (`parse_sse`), `auth`
(`build_auth_headers`, `string_to_sign`, `sign`, header constants).

### 3.3 calagoapi-minecraft

`MinecraftServer` (≈70 methods), `MinecraftManager` (`getServer`, `getServers`,
`fromServer`, `versionChanger`), `MinecraftExtension`, `AsyncMinecraftServer`,
`VersionManager`, `VersionChangerAdmin`, `VersionChangerAPI`, `PANEL_EXTENSION`,
`MinecraftError`, `BridgeNotConfiguredError`, `DEFAULT_CONFIG`, `settings()`.

---

## 4. Transport and streaming review

### 4.1 Transport (HTTP)

| # | Finding | Severity | Fix |
|---|---|---|---|
| T1 | `swallow_rate_limit=True` turns a 429 into `None`; getters then return dicts of `None` (hidden failure) | medium | remove the option; 429 always raises after retries |
| T2 | Protocol mismatch only *warns*; a v1 client against a v2 server misparses the stream silently | high (after v2) | mismatch raises `HungerBridgeProtocolError` on the first response |
| T3 | Throttle (`static_delay`) uses wall-clock `time.time()` (jumps with NTP) | low | monotonic clock, rename `min_interval` |
| T4 | Error text is `"HungerBridge error 401: <raw JSON body>"` | low | `"HungerBridge 401 unauthorized (invalid_credentials): Authentication required"` |
| T5 | `redeem_pickup` bypasses `Transport` (own `requests.get`, no protocol check) | low | goes through the same error/protocol mapping |
| T6 | `/server/run` timeouts are reported as success with empty output | high | server: 504 `command_timeout`; client raises |
| T7 | Request signing, canonical JSON, retry-with-fresh-nonce | ok | unchanged (golden vectors stay valid) |

### 4.2 Streaming

| # | Finding | Effect | Fix (v2) |
|---|---|---|---|
| S1 | `register()` then history *snapshot*: a line published in between is queued live **and** included in the snapshot, ahead of the history | duplicated + reordered lines on every busy connect | subscribe atomically under the publish lock |
| S2 | Full per-client queue drops the oldest chunk silently | event loss with no signal | overflow closes the connection; client resumes by id, gaps are reported |
| S3 | Reconnect de-duplicates by searching the last seen *line text* in the replayed history | repeated lines (`Saving…`, `There are 0 players…`) are dropped or duplicated | monotonic sequence ids + `Last-Event-ID` |
| S4 | `\n`/`\r` are escaped as two characters, and the client un-escapes every backslash-n | a literal `C:\new` arrives as `C:` + newline + `ew` | JSON `data:` payload (no custom escaping) |
| S5 | "Stalled" means "nothing *enqueued* for 60 s"; keepalives enqueue every 15 s, so it never fires; a client that stops reading blocks a worker thread in `write()` | thread held under slow/dead clients | *Revised during implementation:* the JDK `HttpServer` API cannot interrupt a writer blocked on a peer that stopped reading (closing the exchange from another thread would itself try to write the final chunk). The useless stall check is removed; a full queue disconnects the client as soon as `write()` returns; the thread is released when TCP fails; the 64-stream cap (< 128 workers) bounds the impact. Documented in `StreamConnection`. |
| S6 | `:history-end` is an SSE *comment*; proxies may drop comments | client never leaves "history" mode behind such proxies | typed `ready` event |
| S7 | Default `Stream` handlers append every line to three unbounded lists | memory grows without bound in long-running bots | no implicit accumulation; handlers or iterator only |
| S8 | Legacy time-window heuristic (1 s) for servers without a protocol header | dead code after v2 | removed |
| S9 | `connect()` check-then-start is not atomic | two threads can start two workers | lock |
| S10 | History cap duplicated (200 in Java and Python) | drift | server owns it (`history=all`); ring 1000 events |
| S11 | Server restart: client resumes counting from old lines | wrong replay | stream `epoch` (per JVM): id = `<epoch>.<seq>`; a new epoch replays the whole ring |
| S12 | Lines carry neither thread nor logger | logger support impossible to stream | structured event: `seq, time, level, thread, logger, message, line` |

### 4.3 v2 stream contract (HungerBridge is the source of truth)

```
GET /server/stream?history=<N|all>          (first connect; default 0)
Last-Event-ID: <epoch>.<seq>                (resume; replaces history)

retry: 2000

id: 9f1c2b.41
event: log
data: {"seq":41,"time":1763650434123,"level":"INFO","thread":"Server thread","logger":"HungerBridge","message":"…","line":"[10:53:54] [Server thread/INFO]: [HungerBridge] …"}

event: ready
data: {"epoch":"9f1c2b","seq":41,"replayed":12,"gap":false}

: keepalive                                  (every 15 s)
```

* `seq` is per JVM, strictly increasing; `epoch` is random per JVM start.
* `ready` is sent once per connection after the replay; `gap: true` when the resume point
  is older than the ring (events were lost and cannot be replayed).
* `line` is the canonical rendering (§6.3), plain text (ANSI and `§` codes removed,
  passkeys redacted). `message` is the raw message (redacted).
* Overflowing clients are disconnected, not silently thinned.

---

## 5. Release-resolution review

* Today: GitHub Releases API (`releases/latest` or a tag), jar chosen by **parsing file
  names** (`HungerBridge-<loader>-<mc>-<version>.jar` and a legacy scheme). Loader aliases
  (`purpur`→`paper`, `quilt`→`fabric`, …) are a hard-coded table, `"latest"` Minecraft
  version picks the highest parsed number.
* The project is published on Modrinth (`https://modrinth.com/project/hungerbridge`, id
  `LYkoD1S0`). Live metadata (2026-11-20): loaders `fabric, folia, paper, purpur, quilt`;
  game versions `1.21 … 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2`; 10 versions, newest
  `3.0-alpha` (one "Plugin" version for `paper,purpur,folia`, one "Mod" version for
  `fabric`), each with one primary file. **No protocol-v2 (5.x) build is published yet**,
  and 26.3 is not published.
* Modrinth answers exactly the question we need with structured filters:
  `GET /v2/project/hungerbridge/version?loaders=["paper"]&game_versions=["1.21.11"]`,
  newest first; the primary file carries `url`, `filename`, `size`, `hashes.sha512`.

**Decision:** HungerBridgePy resolves releases **only** through Modrinth:

1. `GET /v2/project/hungerbridge` → validate `loader` ∈ project `loaders` and
   `minecraft_version` ∈ project `game_versions` (clear error listing what exists).
2. `GET /v2/project/hungerbridge/version?loaders=[loader]&game_versions=[mc]` (Modrinth
   filters by metadata, nothing is parsed from file names).
3. Keep versions whose `version_number` major equals `BRIDGE_MAJOR` (5 ↔ protocol 2), and
   `status == "listed"`; pick the newest, or the exact `version` requested.
4. Return `BridgeRelease` (version id/number, type, loaders, game versions, primary file
   name/url/size/sha512, `directory` = `/plugins` for plugin loaders, `/mods` for mod loaders).

The HungerBridge release workflow **publishes to Modrinth** (one Modrinth version per jar,
loaders and game version taken from the build matrix, not from file names) and stops
creating GitHub releases. Requires a `MODRINTH_TOKEN` repository secret (owner action).

---

## 6. Logging review

### 6.1 Findings

| # | Finding |
|---|---|
| L1 | Paper adapter logs through a Log4j logger *named after the thread* (Paper prints it as `[thread]`), Fabric renames the thread; same API call, different console output |
| L2 | No logger-name concept anywhere in the protocol or clients |
| L3 | Any string becomes a custom Log4j level (`Level.forName(name, 350)`), unvalidated: unbounded global level registry, control characters in console |
| L4 | `level=None` sends 50 backspaces to "hide" the level tag |
| L5 | SSE lines use a Paper-like layout without thread; differs from the Fabric console and from the required format |
| L6 | Bridge system messages have no consistent prefix/wording (`"HungerBridge started (fabric, …)"`, `"Registered endpoints: …"`, `"Copied runtime config …"`) |
| L7 | Chat output: prefix-driven colours; `"Success:"` is never emitted (all successes render grey); `createdToken`/`rotatedToken` ANSI helpers and `formatTable/formatList/formatKeyValues/formatStatus/formatJsonPreview` are dead code kept alive only by tests |
| L8 | Audit log redacts any value containing `token` (e.g. `hb token list`) — conservative, kept |

### 6.2 Custom logger support (requirement)

```python
client.log(message="example message", level="CUSTOMLEVEL", thread="CustomThread", logger="CustomLogger")
```

renders as

```
[10:53:54] [CustomThread/CUSTOMLEVEL]: [CustomLogger] example message
```

* **Server console**: Fabric's (vanilla) layout is `[%d{HH:mm:ss}] [%t/%level]: %msg`, so
  the Fabric logger embeds `[logger] ` in the message → exactly the line above. Paper's
  layout prints `[%d{HH:mm:ss} %level]: [%logger] %msg` for plugin loggers, so the Paper
  logger logs through a Log4j logger named `CustomLogger` → `[10:53:54 CUSTOMLEVEL]:
  [CustomLogger] example message` (Paper's own layout; HungerBridge does not rewrite server
  log configuration).
* **Stream, history, replay**: the event carries `thread`, `logger`, `level`, `message`
  structurally; `line` is the exact required format on **every** loader.
* **Defaults**: `thread` = `HungerBridge`, `logger` = `HungerBridge`.
* **Validation** (400 `bad_request`): `level` `^[A-Za-z][A-Za-z0-9_]{0,31}$` (upper-cased),
  `thread`/`logger` 1–64 printable characters, no control characters.

### 6.3 Canonical line format

```
[HH:mm:ss] [<thread>/<LEVEL>]: [<logger>] <message>
```

* `[<logger>] ` is omitted for Minecraft-internal loggers (`net.minecraft.*`, `com.mojang.*`,
  `Minecraft`, root) and when the message already starts with it.
* Time in the server's time zone (same as the console); `time` (epoch ms) is in the event.
* Stack traces follow on further lines (`line` and `message` contain `\n`; JSON-escaped on
  the wire).

### 6.4 Message normalization (requirement)

`client.log(..., normalize=True)`: `"System chat: Hello"` → `"Hello"`. Implemented **once,
server-side** (`common/log/MessageNormalizer`), so every client and loader behaves
identically and the server knows its Minecraft version:

1. **Structured first**: a message that is a JSON text component (`{"text":…}`, `[…]`,
   `"…"`) is parsed with Gson and flattened (text, `extra`, `translate` → `fallback`/key)
   — the documented JSON text-component format, the same on every loader.
2. Legacy `§x` formatting codes and ANSI escapes are removed.
3. Leading chat-channel decorations are removed by a declared rule table (regex only here):
   `System chat:`, `[System]`, `[CHAT]`, `[Not Secure]`, `[Server]`, `[Rcon]`. Each rule
   carries the Minecraft version range it applies to (all current rules: every supported
   version), so version-specific decorations are explicit when they appear.
4. Whitespace is trimmed; an empty result keeps the original message.

*Why not the platform component API:* Fabric's `Component.Serializer.fromJson` signature
changed in 1.20.5 and again with the codec rewrite; Paper uses Adventure. A per-loader path
would behave differently per loader and cannot be unit-tested without a game; the JSON text
component format itself is stable.

### 6.5 Visual language (chat and console output)

One message model in `common` (`BridgeMessage`: kind + text + optional key/value),
rendered by Paper (Adventure `NamedTextColor`) and Fabric (`ChatFormatting`). The layout
reads well **without** colour (Fabric's dedicated console prints plain text):

```
HungerBridge » Token created                         header   brand: dark aqua bold, title: white
  ✔ my-bot uses policy admin                         success  green glyph, white text
  • Pickup URL   /pickup/<id>?passkey=<hex>          detail   grey key, aqua value
  ▲ The pickup expires in 5 minutes                  warning  gold
  ✖ Unknown policy id: admn                          error    red
  › /hb token create <id> <policy> [expirySeconds]   usage    grey glyph, yellow command
```

Glyphs are BMP characters that render in Paper's JLine console, Fabric's console, the
Calagopus/xterm web console and Minecraft chat. Log output of HungerBridge itself goes
through the `HungerBridge` logger (§6.2), so every loader prints `[HungerBridge] …`.

---

## 7. Command execution investigation

Report: `runCommand("text", false)` still shows the command's output in the server
console on "Fabric 1.26.3+" (Minecraft has no 1.26.x; since 2026 versions are numbered
26.x, so this is 26.3).

### 7.1 Execution-path audit

| Stage | Code | Behaviour |
|---|---|---|
| Invocation | `BridgeClient.runCommand(command, showConsole=False)` | positional `False` binds to `showConsole` |
| Transport | `Transport.post("/server/run", {"command":…, "silent":False, "show_console":False})` | flag is signed and delivered; verified in tests (`test_run_command_normalization`) |
| Parsing | `RunHandler.optBoolean(body, "show_console", false)` | flag reaches `executor.executeWithOutput(cmd, false)` |
| Execution (Fabric) | `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd)` on the server thread | the source is the **`MinecraftServer` itself** |
| Execution (Paper/Folia) | `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)` on main/global thread | the source is the **console sender** |
| Feedback | vanilla: `CommandSourceStack.sendSuccess` → `MinecraftServer.sendSystemMessage` → `LOGGER.info(text)`; Bukkit console sender → console logger | feedback **is** a console log event |
| Suppression | `CommandCapture.capture(action, showConsole)` | **`showConsole` is never read.** No suppression code exists anywhere |
| Capture | temporary root-logger appender, filtered to the calling thread | reads what the console prints; output must reach the console to be captured |

### 7.2 Root cause

Two defects, both in the design rather than in a version-specific detail:

1. The suppression flag was **never implemented** (`CommandCapture` ignores it; the 2026-11
   report recorded "show_console=false does not suppress console output").
2. Capture works by **listening to the console log**. The command runs as the console, so
   its feedback is a console log line by definition; capture cannot exist without the
   console printing it. Any suppression bolted onto this (e.g. muting console appenders
   for the duration, as `docs/design/03 §4` proposed) is a symptom fix: it is global (other
   threads' logs during the window vanish), loader-specific (appender names differ:
   `TerminalConsole`, `SysOut`, `ServerGuiConsole`, …) and racy with async appenders.

It shows on every loader and version; it is merely most visible on Fabric, whose vanilla
console prints every feedback line with `[Server thread/INFO]`. Side effect of the same
design: log-scraping also captures unrelated lines logged on the server thread during the
command and misses feedback delivered asynchronously.

### 7.3 Fix (native feedback capture)

* **Fabric**: run with `createCommandSourceStack().withSource(capture)` where `capture` is
  a dedicated `CommandSource` (`sendSystemMessage` → collect `Component.getString()`,
  `acceptsSuccess/acceptsFailure` → true, `shouldInformAdmins` → false). With
  `show_console=true` each collected line is also sent to `server.sendSystemMessage` (the
  exact vanilla console path).
* **Paper / Purpur / Folia**: `Bukkit.createCommandSender(feedback)` (Paper API; vanilla
  and plugin feedback is delivered as Adventure components, serialized as plain text);
  dispatched on the main thread / global region as before; `show_console=true` forwards each
  component to `Bukkit.getConsoleSender()`.
* `CommandCapture` (log scraping) is deleted. `CommandExecutor.run(command, showConsole)`
  returns the lines or throws `CommandTimeoutException` → HTTP 504 `command_timeout`.
* Scope of suppression: command **feedback**. Game side effects that the server itself logs
  (e.g. `/say` is a chat broadcast and is logged by the chat system) are not feedback and
  still appear, exactly as when a player runs them. Admin broadcasts
  (`[Server: Set the time …]`) are not sent for bridge commands on any loader.
* **Local execution** (`/hb` typed in the console) is unaffected: it is a console command.
  **Remote execution** is the HTTP path above. HungerBridge is server-only
  (`"environment": "server"`); there is no client-side execution path.

### 7.4 Regression tests

* Java (common): `RunHandler` passes `show_console` through; timeout → 504; the fake
  executor records the flag.
* HungerBridgePy unit: wire body `{"command","show_console"}`; 504 → `HungerBridgeError`
  with `code == "command_timeout"`.
* Live suite: `runCommand("seed", show_console=False)` returns `Seed: [...]`.
* Boot tests (CI, real servers, every loader × version): the console log must **not**
  contain the `seed` feedback after `show_console=False`, and must contain it after
  `show_console=True`.

---

## 8. Naming convention audit

**Rule set (final):**

| Element | Convention | Examples |
|---|---|---|
| Methods and functions (Python, Java) | camelCase | `runCommand`, `findRelease`, `redeemPickup`, `waitUntilConnected` |
| Python parameters, attributes, dict keys | snake_case | `token_id`, `show_console`, `minecraft_version` (as in the required APIs) |
| JSON wire keys, YAML keys | snake_case / kebab-case (YAML, unchanged) | `token_id`, `policy_id`, `show_console`; `bind-address` |
| Classes | PascalCase | `BridgeClient`, `LogEvent`, `BridgeRelease` |
| Constants | UPPER_SNAKE | `PROTOCOL_VERSION`, `BRIDGE_MAJOR` |
| Acronyms in names | upper case as today | `getTPS`, `getMSPT`, `getCPUStats`, `getGCStats` |
| Collections | plural nouns | `players`, `permissions`, `game_versions` |

**Irregularities fixed:**

| Before | After | Why |
|---|---|---|
| `/auth/check` `tokenId`, `policyId` | `token_id`, `policy_id` | only camelCase wire keys |
| `runCommand(…, showConsole=…)`, CalagoAPI `sendCommand(showConsole=)` | `show_console=` | parameter convention |
| `runCommand(…, normalize=)` (= "join output") | removed; always returns text | collided with `log(normalize=)` |
| `silent` (= "don't return output") next to `show_console` | removed | reads as "don't print" |
| `redeem_pickup`, `find_release`, `list_release_assets`, `parse_asset_name`, `loader_for`, `build_auth_headers`, `string_to_sign`, `canonical_json`, `canonical_body`, `normalize_path`, `quote_string`, `convert_bytes`, `parse_sse`, `parse_retry_after`, `wait_until_connected` | camelCase equivalents (`redeemPickup`, `findRelease`, …) or removed | function convention |
| `BridgeClient.redeemPickup` (static) + module `redeem_pickup` | module `redeemPickup` + `BridgeClient.fromPickup` | duplicate |
| `history_handler`, `new_log_handler`, `close_handler` | `on_history`, `on_event`, `on_close` | callbacks named by event |
| `retry_backoff_sec`, `static_delay` | `retry_backoff`, `min_interval` (seconds) | units consistent with `timeout` |
| `Credentials.secret` | `Credentials.token_secret` | one term for one thing |
| `ReleaseAsset`, `release=` (GitHub tag) | `BridgeRelease`, `version=` (Modrinth version number) | Modrinth terminology |
| `getPlayers('count'\|'list')` mode strings | `getPlayers()`, `getPlayerCount()` | explicit methods |
| `getTPS(mode)` | `getTPS(window)` | names the concept |
| `InvalidModeError` | `ValueError` | no special type for argument errors |
| CalagoAPI forwards (`getMemoryInfo`, `getProcessorInfo`, `writeLog`, `getBridgeInfo`, …) | removed; `server.bridge.<HungerBridgePy name>` | one name per concept |
| `bridge_token` | `bridge_token_id` + `bridge_token_secret` | requirement |
| `/hb tokens` | `/hb token` only | singular, as registered on Fabric |
| `STONECUTTER_ACTIVE` | `HB_TARGET` | misnomer |
| Java `executeWithOutput`, `CommandCapture` | `run`, native sources | describes behaviour |

---

## 9. Technical debt, duplicates and legacy layers

| # | Debt | Repo | Action |
|---|---|---|---|
| D1 | Build outputs are committed (`build/`, `common/build/`, `fabric/versions/*/build/`, `paper/versions/*/build/`) although `.gitignore` lists `build/` | HungerBridge | untrack |
| D2 | `__pycache__/*.pyc` (3 Python versions) and `src/hungerbridge.egg-info/` committed | HungerBridgePy | untrack, extend `.gitignore` |
| D3 | `.gitignore` of HungerBridgePy is a copy of the Gradle one (`*.class`, `.gradle/`) | HungerBridgePy | Python ignore list |
| D4 | `Config.load` mixes YAML parsing, defaults and seeding; `reload` duplicates parsing and does not reload `players.max-list` | HungerBridge | single parse path; reload applies everything that can change at runtime, reports the rest as "restart required" |
| D5 | `CommandMessages` dead formatting helpers + tests | HungerBridge | remove (§6.5) |
| D6 | `ServerStopHandler` accepts `force` and ignores it | HungerBridge | remove |
| D7 | `BridgeClient._extract` dotted-path helper, `_convert` leniency (unknown unit → raw bytes) | HungerBridgePy | plain `dict.get`; unknown unit → `ValueError` |
| D8 | `client.stream` created eagerly in every `BridgeClient.__init__` | HungerBridgePy | removed; `openStream()` / `streamLogs()` |
| D9 | `calagoapi_minecraft/bridge.py` detection helpers, `VERSION_VARIABLES` | CalagoAPI | removed with inference |
| D10 | Two release workflows publish artifacts to GitHub (HungerBridge) | HungerBridge | Modrinth only |
| D11 | Version strings: `version.yaml` descriptions are changelog stubs ("Bump version") | all | real changelog entries |
| D12 | Thread pool uses `CallerRunsPolicy`: beyond 128 concurrent requests the dispatcher thread would run a handler (a stream would block accepts) | HungerBridge | documented accepted risk: SSE capped at 64 < 128 workers, per-IP/token rate limits, every hop has a timeout |

---

## 10. Bug inventory

| # | Bug | Repo | Fix |
|---|---|---|---|
| B1 | `show_console=false` never suppresses console output (§7) | HB | native capture |
| B2 | `/server/run` timeout returns `ok` with empty output | HB | 504 `command_timeout` |
| B3 | Stream history/live race duplicates and reorders lines (S1) | HB | atomic subscribe |
| B4 | Slow stream clients silently lose lines (S2) | HB | disconnect + resume |
| B5 | Dead/non-reading stream clients hold a worker thread (S5) | HB | bounded and documented (see S5; no interruptible API exists) |
| B6 | Literal backslash-n in log messages is turned into a newline (S4) | Py | JSON events |
| B7 | Reconnect drops/duplicates repeated lines (S3) | Py | sequence ids |
| B8 | Unbounded in-memory line lists in default `Stream` handlers (S7) | Py | removed |
| B9 | `"Success:"` styling never applied; Paper `/hb` without args differs from Fabric | HB | message model |
| B10 | Unvalidated custom level names grow Log4j's global level registry; control characters reach the console | HB | validation |
| B11 | `thread` argument means "logger name" on Paper and "thread name" on Fabric | HB | one logger |
| B12 | `swallow_rate_limit` hides 429s as `None` values | Py | removed |
| B13 | `Config.reload` ignores `players.max-list`; `bind-address` changes are claimed reloaded but never rebind | HB | reload semantics |
| B14 | `installBridge()` can pick a jar for the wrong loader/version from inference (e.g. egg named "Paper" running Purpur is fine, but an egg named "Fabric API test" on Paper picks Fabric) | CalagoAPI | explicit parameters |
| B15 | Release finder may select a jar that speaks an incompatible protocol (any GitHub release) | Py | major-version filter |
| B16 | `Stream.connect()` race can start two workers (S9) | Py | lock |
| B17 | `/hb tokens` works on Paper only | HB | removed |

---

## 11. Documentation and tutorial audit

| Document | Repo | Status | Action |
|---|---|---|---|
| `README.md` | HB | describes v1, GitHub release names, CalagoAPI `installBridge()` without args | rewrite |
| `ENDPOINTS.md` | HB | v1 wire format | rewrite for v2 |
| `BUILDING.md` | HB | `STONECUTTER_ACTIVE`, GitHub release job | update |
| `CHANGELOG.md` | HB | — | new 5-alpha.1 entry |
| `PLAN.md`, `REPORT.md` | HB | previous pass, completed | replaced / removed |
| `docs/design/*` | HB | proposals; 03 §4 proposes appender muting; 01 §4 rename | mark implemented items, drop superseded proposal text |
| `README.md`, `CHANGELOG.md` | Py | `"id:secret"`, GitHub releases, `showConsole`, `normalize`, `client.stream` | rewrite; add migration guide |
| `README.md` (root), `docs/README.md`, `docs/architecture.md`, `docs/extensions.md` | CalagoAPI | Version Changer, `bridge_token` | update |
| `docs/migrating-from-hungerlib.md`, `docs/migrating-to-2.0.md` | CalagoAPI | point to `server.versions` | update; new `docs/migrating-minecraft-2.0.md` |
| `docs/design/extension-system.md` | CalagoAPI | historical design record mentioning the Version Changer | label as historical, note the removal |
| `PROJECT_NOTES.md` | CalagoAPI | Version Changer decision row | update |
| `src/extensions/calagoapi-minecraft/README.md` | CalagoAPI | full API reference incl. Version Changer | rewrite |
| Tutorial 11 Minecraft setup | CalagoAPI | `installBridge()` detection, `bridge_token` | rewrite steps 2–3 |
| Tutorial 12 Commands and console | CalagoAPI | `silent`, `showConsole`, `sanitize` | rewrite |
| Tutorial 13 Monitoring | CalagoAPI | forwards (`getMemoryInfo`, `getTPS` on server) | rewrite on `server.bridge` |
| Tutorial 14 Versions and installs | CalagoAPI | built on the Version Changer | **replace** with "Installing and upgrading HungerBridge" (Modrinth, explicit) |
| Tutorial 15 Community bot | CalagoAPI | `bridge_token`, forwards | update |
| `examples/minecraft/*`, `_env.py` | CalagoAPI | `HB_TOKEN` combined | `HB_TOKEN_ID` + `HB_TOKEN_SECRET`; new APIs; executed by tests |
| New tutorial "Logging from your bot" | CalagoAPI | — | custom levels, threads, loggers, normalization, reading them back from the stream |

Every example that appears in a tutorial is a file under `examples/` executed by the test
suite against the fake panel, the fake bridge and a fake Modrinth.

---

## 12. Proposed architecture

```
CalagoAPI calagoapi-minecraft 2.0                HungerBridgePy 2.0                  HungerBridge 5 (protocol v2)
────────────────────────────────                ──────────────────                  ────────────────────────────
MinecraftServer(Server)                          BridgeClient(url, *, token_id,      common/
 ├ panel: power, files, properties, EULA          token_secret)                        http/  handlers (v2)
 ├ sendCommand → bridge.runCommand                ├ runCommand(cmd, show_console)       stream/ LogEvent, LogDistributor
 ├ streamConsole/onConsoleLine → event.line       ├ log(message, level, thread,           (ring, epoch, seq, subscribe,
 ├ installBridge(minecraft_version, loader) ──────┤   logger, normalize)                   watchdog), StreamHandler
 │    └ hungerbridge.releases.findRelease ────────┼─► Modrinth API v2                    log/  BridgeLogger (Log4j),
 ├ createBridgeToken() → Credentials              ├ streamLogs() → Iterator[LogEvent]         LineFormat, MessageNormalizer,
 └ bridge  ── the BridgeClient (all bridge APIs)  ├ openStream(on_event, …) → Stream          PlatformLogAppender
                                                  ├ Stream: SSE v2, Last-Event-ID,       command/ CommandExecutor.run,
                                                  │   epoch, gaps, back-off + jitter            CommandTimeoutException
                                                  └ Transport: signing, retries,         message/ BridgeMessage (+ kinds)
                                                      protocol check, typed errors     paper/  CapturingSender (Paper API),
                                                                                              PaperMessages (Adventure)
                                                                                       fabric/ CapturingSource (CommandSource),
                                                                                              FabricMessages (ChatFormatting)
```

Principles: one implementation per behaviour, at the layer that owns the knowledge;
explicit parameters; typed errors; observable failures (Python `logging` logger
`hungerbridge`, Java bridge logger `HungerBridge`).

---

## 13. Repository-by-repository implementation plan

### 13.1 HungerBridge (order: common → platforms → build/CI → docs)

1. `PROTOCOL_VERSION = 2`; version `5-alpha.1`.
2. **Commands**: `CommandExecutor.run(String, boolean) throws CommandTimeoutException`
   (metrics hooks unchanged); delete `CommandCapture`; `RunHandler` (no `silent`, 504);
   Fabric `CapturingCommandSource`; Paper `Bukkit.createCommandSender`.
3. **Logging**: `Logger` interface → `log(LogEntry)` + `info/warn/error/debug`;
   `common/log/BridgeLogger` (Log4j, per-call thread/logger/level, marker so the appender
   does not double-publish, `embedLoggerName` for Fabric); delete Paper/Fabric adapters;
   `LineFormat`; `MessageNormalizer`; validation in `LogHandler`.
4. **Stream**: `LogEvent` record, `LogDistributor` rewrite (ring 1000, epoch/seq,
   atomic subscribe, overflow disconnect, flush watchdog, keepalive), `StreamHandler` v2.
5. **Protocol clean-up**: `/auth/check` keys, `/server/stop` without `force`,
   `/system/memory` without aliases, permission aliases removed.
6. **Messages/UI**: `BridgeMessage` model; `CommonCommandHandler` returns messages;
   Paper/Fabric renderers; `/hb` (no args) = help on both loaders; remove `tokens`.
7. **Config**: one parser for load and reload; reload reports restart-only keys.
8. **Build**: `HB_TARGET`; delete leftovers; untrack build outputs.
9. **CI**: `test`, `protocol` (HungerBridgePy live suite vs. standalone bridge), `build`
   (8 jars), `boot` (real servers: Paper, Purpur, Folia, Fabric × 4 versions running the
   live suite + console assertions), `publish` (Modrinth, on `main`, `run: true`).
10. Docs: README, ENDPOINTS (v2), BUILDING, CHANGELOG, `docs/migrating-to-5.md`, design index.

### 13.2 HungerBridgePy

1. `auth.Credentials(token_id, token_secret)` validated (non-empty id, 64 hex secret).
2. `BridgeClient(url, *, token_id, token_secret, max_retries=2, retry_backoff=1.0,
   min_interval=0.0, timeout=(5, 30), session=None)`; `fromPickup(url, **options)`.
3. Endpoint methods per §8; `log(message, level="INFO", thread=None, logger=None,
   normalize=False)`; `runCommand(command, show_console=False) -> str`.
4. `transport`: protocol check (raise), monotonic throttle, error messages, 504 mapping.
5. `stream`: v2 SSE parser (`id`, `event`, `data`), `LogEvent`, resume, gap logging,
   back-off with jitter, permanent errors, `waitUntilConnected`, no implicit buffers.
6. `releases`: Modrinth-only `findRelease(minecraft_version, loader, *, version=None)`.
7. Remove `bridge.py`, `units.convert`, aliases; `logging.getLogger("hungerbridge")`.
8. Tests: unit (fake HTTP server, fake Modrinth), live suite updated (suppression,
   logger, normalization, reconnect/resume, gaps); CI runs live suite vs. standalone bridge.
9. Docs: README, CHANGELOG, `MIGRATING.md`.

### 13.3 CalagoAPI

1. Delete `calagoapi_minecraft/versions.py` and every Version Changer reference;
   contract test lists the Version Changer endpoints under `NOT_WRAPPED` (retired).
2. Config: `bridge_token_id`, `bridge_token_secret` (secret masked), validated through
   HungerBridgePy `Credentials`; remove `auto_backup`, `backup_*`, `release`,
   `repository`, `github_api`.
3. `MinecraftServer`: keep panel features, connection management, `sendCommand`,
   `sendCommands`, `sendPanelCommand`, `streamConsole`, `onConsoleLine`, chat/admin command
   helpers, `installBridge(minecraft_version, loader, *, version=None, replace=True)`,
   properties/EULA; remove renamed forwards (§1.2 A1) and `findBridgeRelease`.
4. `createBridgeToken()` returns `hungerbridge.Credentials`.
5. Async wrapper, tests, examples, tutorials (§11), migration guide.

---

## 14. Breaking change inventory

### HungerBridge 5 / protocol v2
* Protocol header and `/server/meta.protocol_version` are `2`; v1 clients are rejected by
  HungerBridgePy 2 and misparse v2 streams.
* `/server/stream` sends JSON `log` events + `ready` event; text lines, `:history-end`
  and `\n` escaping are gone; `history` max 1000 (`all`).
* `/server/run`: `silent` removed (unknown fields are ignored); `show_console` now really
  controls console output (default **off**); timeout is HTTP 504.
* `/server/log`: names validated (400 on invalid), `logger`/`normalize` added, default
  logger `HungerBridge`.
* `/auth/check`: `token_id`, `policy_id`.
* `/server/stop`: no `force` in the response.
* `/system/memory`: `used_bytes`, `total_bytes`, `free_bytes`, `max_bytes` removed.
* `policies.yaml`: `server.info`, `player.list`, `world.read`, `ping` no longer granted.
* `/hb tokens` removed; console output styling changed (the pickup URL line still
  contains `/pickup/<id>?passkey=<hex>`).
* Build: `STONECUTTER_ACTIVE` → `HB_TARGET`. Releases: Modrinth only.

### HungerBridgePy 2
* `BridgeClient(url, *, token_id, token_secret, …)` keyword-only, required; `"id:secret"`
  gone; options renamed (`retry_backoff`, `min_interval`), `swallow_rate_limit`,
  `history_handler`, `new_log_handler` and `client.stream` removed.
* `runCommand(command, show_console=False) -> str`; `silent`/`normalize` removed.
* `log(..., logger=None, normalize=False)`; `level=None` removed.
* `getPlayers()` returns names; `getPlayerCount()`; `getTPS(window)`; `InvalidModeError`
  removed; `getMemoryStats()` alias keys `used/total/free/max` → `heap_free` added.
* `streamLogs()` yields `LogEvent`; `openStream(on_event, on_history, on_close)`;
  `Stream.sanitize`, `extractTimestamp`, `getRaw/getSanitized/getTimestamped`,
  `MAX_HISTORY` removed.
* `find_release` → `findRelease` (Modrinth); `ReleaseAsset` → `BridgeRelease`;
  `list_release_assets`, `parse_asset_name`, `loader_for` removed.
* snake_case functions renamed to camelCase; `hungerbridge.bridge` removed; `HungerBridge`
  alias removed; protocol mismatch raises.

### calagoapi-minecraft 2
* Version Changer removed (`server.versions`, `panel.minecraft.versionChanger`,
  `VersionManager`, `VersionChangerAdmin`, `VersionChangerAPI`, `PANEL_EXTENSION`).
* `bridge_token` → `bridge_token_id` + `bridge_token_secret` (config, constructor,
  `connectBridge(token_id=, token_secret=)`); `createBridgeToken()` returns `Credentials`.
* `installBridge(minecraft_version, loader, *, version=None, replace=True)`: both
  required, no detection; returns `BridgeRelease`; `findBridgeRelease` removed.
* `sendCommand(command, *, show_console=False)`; `silent` removed.
* `streamConsole(history=0, timeout=None, *, reconnect=None)`, `onConsoleLine(callback,
  history=0, *, reconnect=None)`: `sanitize` removed (lines are always plain).
* Forwarding methods removed: `getBridgeInfo`, `getBridgeTokenInfo`, `getPlatform`,
  `getMinecraftVersion`, `writeLog`, `broadcast`, `getPlayers`, `getPlayerCount`,
  `getMaxPlayers`, `getTPS`, `getMSPT`, `getPerformance`, `getWorldTime`,
  `getWorldTimeInfo`, `getWeather`, `getLoadedChunks`, `getLoadedEntities`,
  `getMemoryInfo`, `getProcessInfo`, `getProcessorInfo`, `getThreadInfo`,
  `getGarbageCollectorInfo`, `getNetworkInfo`, `getJVMUptime` → use `server.bridge.*`.

---

## 15. Risk assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Minecraft/Paper API names used by the new capture code differ on a target | medium | build break | compile all 8 targets locally (JDK 21 + 25 downloaded to the scratchpad) and in CI |
| Runtime behaviour differs from compile-time expectations (feedback delivery, console echo) | medium | wrong output | boot-test CI job on every loader × version with console assertions |
| No 5.x build on Modrinth yet → `installBridge()` finds nothing compatible until the first publish | certain (until release) | install fails with a clear error | the publish job; error message names the requirement |
| `MODRINTH_TOKEN` secret missing | high | publish job fails | job fails loudly; documented owner action |
| Thread-rename for custom thread names (proven in production) vs. hand-built Log4j events | low | — | keep the proven approach, isolated in `BridgeLogger` |
| Paper prints logger names with its own layout (`[HH:mm:ss LEVEL]: [logger] msg`) | certain | console differs from Fabric | documented; the stream `line` is identical everywhere |
| Normalizer strips something a user wanted | low | message altered | opt-in (`normalize=False` default), rules listed in docs, tests |
| Breaking changes across three packages | certain | user migration | migration guides per repo, protocol check fails fast |
| Folia threading for the new sender | low | exception | same `callGlobal` path as before; boot-tested |

---

## 16. Testing and documentation strategy

### 16.1 Test layers

| Layer | Where | What |
|---|---|---|
| Java unit | `common/src/test` | normalizer rules, line format, level/name validation, stream ring/seq/epoch/subscribe atomicity, overflow, watchdog, message model rendering, config reload |
| Java in-process HTTP | `BridgeServerIntegrationTest` | every endpoint v2, auth, SSE v2 (history, resume, gap, ready, overflow), run timeout 504, log validation |
| Cross-language protocol | HungerBridgePy `tests/test_live.py` vs. `:common:standaloneBridge` | the real HTTP stack + HungerBridgePy; run locally and in both repos' CI |
| Python unit | HungerBridgePy `tests/` | transport, auth vectors, client wire bodies, SSE v2 parser, resume/gaps, Modrinth resolution (fake API) |
| SDK offline | CalagoAPI `pytest` | contract-checked fake panel + fake HungerBridge (v2) + fake Modrinth; examples executed |
| Boot (real Minecraft) | HungerBridge CI `boot` job (`ci/boot_test.py`) | download Paper/Purpur/Folia/Fabric for each version, install the built jar, start, create a token on the console, run the live suite, assert console suppression/echo, custom logger line, normalization, stream resume across a restart |
| Live release resolution | HungerBridgePy CI (non-blocking job) | `findRelease` against the real Modrinth API |

### 16.2 Documentation

* API references live next to the code they describe (ENDPOINTS.md, HungerBridgePy
  README, calagoapi-minecraft README) and are checked by tests where possible (CalagoAPI
  docs-method check, examples executed).
* Each repo gets a migration guide; changelogs list every breaking change of §14.
* Tutorials are rewritten against the final API; every code block is backed by an
  executed example file.

---

## 17. Status log

| Date | Step | Result |
|---|---|---|
| 2026-11-20 | Investigation of all three repos, Modrinth API probed | this plan |
| 2026-11-20 | Baseline `:common:test` (JDK 25 + 21 from scratchpad, build dir outside OneDrive because OneDrive locks `build/`) | pass |
| 2026-11-21 | HungerBridge common: protocol v2, BridgeLogger, LineFormat, MessageNormalizer, stream v2, BridgeMessage, Config, command API | `:common:test` 68 tests pass (9 classes; new: LogDistributorTest, LogFormatTest, v2 stream/run/log integration tests) |
| 2026-11-21 | Fabric capture source + renderer; Paper `createCommandSender` + Adventure renderer; scheduler refactor | Fabric 26.3 and 1.21.11, Paper 26.3 compile against the real APIs (`CommandSource` signature checked with `javap` on the 26.3 jar) |
| 2026-11-21 | HungerBridgePy 2.0 | 79 unit tests pass on Python 3.12 and 3.9; live suite 20/20 (stop skipped) against the real Java HTTP stack (`:common:standaloneBridge`), including suppression via the stream, the exact custom-logger line, normalization and resume; real Modrinth metadata test passes |
| 2026-11-21 | calagoapi-minecraft 2.0, examples 01–06, contract test | full CalagoAPI suite 155 pass |
| 2026-11-21 | CI: `ci/boot_test.py`, `ci/publish_modrinth.py` (dry run checked), workflows; docs and tutorials rewritten | see below |
| 2026-11-21 | Full `build` of all 8 targets (Paper/Fabric × 1.21.11, 26.1.2, 26.2, 26.3) | all OK; final `:common:test` 68 pass, live suite 20 pass |

### Decisions taken during implementation

* **Default logger/thread** for `/server/log`: `HungerBridge` for both, so `[HungerBridge]`
  appears identically on Paper (layout) and Fabric (embedded); callers name their own logger.
* **`/server/log` response** includes the logged `message`, so clients see the normalization
  result without reading the stream.
* **Debug diagnostics** (`debug: true`) use one custom level `HBDEBUG` instead of the two
  ad-hoc levels `HMAC-DEBUG` and `DBG`.
* **Custom levels** are capped at 64 per server run (Log4j levels are global and permanent).
* **Thread labels** keep the proven thread-rename technique (now in one place, `BridgeLogger`)
  rather than hand-built Log4j events, which could not be boot-tested locally.
* **`streamLogs()` reconnects by default**: with exact resume there is no duplicate risk, and
  reliability is the priority. Tests against fakes that ignore `Last-Event-ID` pass
  `reconnect=False` explicitly.
* **Local verification limits**: no Minecraft server was booted on this machine; runtime
  behaviour on real servers (feedback delivery, console layouts) is covered by the CI `boot`
  job, which has not run yet. `MODRINTH_TOKEN` must be added as a repository secret before
  the first publish.
