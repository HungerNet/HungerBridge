# 02 — Features

## Summary

Today HungerBridge is a remote console: run a command, read some numbers, tail the log.
The six features below turn it into an automation API that Discord bots, dashboards and
panel tooling can build on without scraping console text. Each part ships independently.

| # | Feature | What users get | Effort | Repo |
|---|---|---|---|---|
| 1 | **Structured event stream** | Typed JSON events (join, leave, chat, death, advancement, server lifecycle, low-TPS alert) over SSE | M–L | both |
| 2 | **Player management** | Player info, kick, ban/pardon, whitelist, direct message, with structured results | M | both |
| 3 | **Prometheus `/metrics`** | Grafana dashboards and alerting with no custom code | S–M | HungerBridge |
| 4 | **Async Python client** | `async`/`await` API for Discord bots and other asyncio apps | M | HungerBridgePy |
| 5 | **`hungerbridge` CLI** | `hb ping`, `hb run`, `hb tail`, token redemption from the shell | S–M | HungerBridgePy |
| 6 | **Safe restart** | One call to warn players, save, stop, start through the panel, and wait until healthy | M | HungerBridgePy (+ HungerLib adapter) |

**Decisions needed**
- [ ] **Fabric events without Fabric API.** The recommendation is to keep the "no Fabric
      API" property and add mixins (§1.4). The alternative is an *optional* Fabric API
      integration, which is less code but means a second code path.
- [ ] **Should chat events include message text by default?** It's privacy-relevant.
      Recommendation: yes, behind its own permission node `events.chat`.
- [ ] **Ban storage.** Use vanilla ban lists (recommended, interoperates with
      `/ban`/`/pardon`) or a HungerBridge-specific list.
- [ ] **Metrics auth model** (§3.3). Recommendation: an opt-in bearer token.
- [ ] **Where the Pterodactyl restart adapter lives** (§6): HungerLib (recommended) or
      HungerBridgePy.

---

## §1 Structured event stream

### 1.1 Wire protocol

New endpoint: **`GET /server/events?types=<csv>&since=<id>`**, with permission node
`events.read`. Chat events also need `events.chat`. The existing `/server/stream` is
unchanged.

SSE frames use the standard `event:` and `id:` fields, which `/server/stream` does not use:

```
id: 1042
event: player_join
data: {"v":1,"ts":1762300000123,"type":"player_join","player":{"name":"Steve","uuid":"…"},"online":3}

: keepalive
```

- `id` is a monotonically increasing `long` per server run.
- With `?since=<id>`, the server replays buffered events with `id > since` from a ring
  buffer of 1,000 events. That is the resume-after-reconnect mechanism. If `since` is older
  than the buffer, the server emits `event: gap` with `{"missed_from":…,"missed_to":…}`.
  Clients can also send the standard `Last-Event-ID` header instead of `since`.
- `types` filters on the server side. When absent, every type the token may see is sent.
- Every payload has `v` (payload schema version, 1), `ts` (epoch ms) and `type`.

### 1.2 Event catalogue (v1)

| type | payload fields | source |
|---|---|---|
| `server_started` | `platform`, `minecraft_version` | bridge start (after `Done`) |
| `server_stopping` | `reason` (`api`, `console`, `unknown`) | stop hook |
| `player_join` | `player{name,uuid}`, `online` | platform |
| `player_leave` | `player`, `online`, `reason` (`quit`, `kick`, `ban`, `timeout`, `unknown`) | platform |
| `player_chat` | `player`, `message` (plain text) | platform (`events.chat`) |
| `player_death` | `player`, `message` (vanilla death message, plain text), `killer` (`{name,uuid}` or null) | platform |
| `player_advancement` | `player`, `advancement` (id, e.g. `minecraft:story/mine_stone`), `title` | platform; only advancements with a display, excluding recipes |
| `tps_alert` | `state` (`degraded`/`recovered`), `tps_1m`, `threshold` | bridge watcher (§1.5) |
| `command_executed` | `token_id`, `command` (redacted like the audit log) | `RunHandler` (`events.audit`) |
| `gap` | `missed_from`, `missed_to` | stream |

Player name and UUID come from the platform. Offline-mode servers have offline UUIDs,
documented as such.

### 1.3 Common module (`com.hungerbridge.common.events`)

```java
public record BridgeEvent(long id, long ts, String type, JsonObject payload) {}

public final class EventBus {              // singleton like LogDistributor
    public static EventBus get();
    public void publish(String type, JsonObject payload);     // thread-safe, non-blocking
    public Subscription subscribe(Set<String> types, long sinceId); // registers + replays
    // ring buffer: ArrayDeque<BridgeEvent>(1000) under a lock; ids from AtomicLong
}
public final class Subscription implements AutoCloseable {   // BlockingQueue<BridgeEvent>(512), drop-oldest
    BridgeEvent poll(long timeout, TimeUnit unit);
}
public final class Events {                // typed helpers so platforms don't hand-build JSON
    static void playerJoin(String name, UUID uuid, int online) { … }
    …
}
```

`EventStreamHandler extends ApiHandler` (path `/server/events`, `GET`, node `events.read`):
1. Parse `types` and `since` (or `Last-Event-ID`).
2. Drop `player_chat` unless the token has `events.chat`, and `command_executed` unless it
   has `events.audit`.
3. Subscribe before `sendResponseHeaders`. Run the same loop and keepalive as
   `StreamLogsHandler`. Extract the shared SSE writer into `http/SseWriter`.

Register the new nodes in `Permissions.KNOWN`: `events.read`, `events.chat`, `events.audit`.

### 1.4 Platform hooks

**Paper / Folia.** Add a new class `paper/.../PaperEventListener implements Listener`,
registered in `onEnable`.

| Bukkit event | priority | notes |
|---|---|---|
| `PlayerJoinEvent` | MONITOR | `Bukkit.getOnlinePlayers().size()` |
| `PlayerQuitEvent` | MONITOR | `reason` via `getReason()` (`PlayerQuitEvent.QuitReason`) |
| `io.papermc.paper.event.player.AsyncChatEvent` | MONITOR, `ignoreCancelled = true` | `PlainTextComponentSerializer.plainText().serialize(e.message())` |
| `PlayerDeathEvent` | MONITOR | `deathMessage()` → plain text; killer via `getEntity().getKiller()` |
| `PlayerAdvancementDoneEvent` | MONITOR | skip if `getAdvancement().getDisplay() == null` |

On Folia these fire on region threads. `EventBus.publish` is thread-safe and does no game
access, so no scheduling is needed. Never call world APIs inside the listeners.

**Fabric (no Fabric API).** Add mixins, plus a `FabricEvents` bridge class. These are the
mojmap targets on 26.x. **Verify each one per version with `javap` against Loom's
`minecraft-merged.jar`** (the method used during the 2026-11 pass) and add them to
`hungerbridge.mixins.json`.

| Mixin target | Injection | Event |
|---|---|---|
| `PlayerList#placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` | `TAIL` | `player_join` |
| `PlayerList#remove(ServerPlayer)` | `HEAD` | `player_leave` (reason from a `ThreadLocal` set by the kick/ban paths, defaulting to `quit`) |
| `PlayerList#broadcastChatMessage(PlayerChatMessage, ServerPlayer, ChatType$Bound)` | `HEAD` | `player_chat` (`message.signedContent()`) |
| `ServerPlayer#die(DamageSource)` | `HEAD` | `player_death` (`getCombatTracker().getDeathMessage()`) |
| `PlayerAdvancements#award(AdvancementHolder, String)` | `RETURN`, only when `cir.getReturnValue()` is true and the advancement is complete with a display | `player_advancement` |

- Signatures that differ between 1.21.11 and 26.x go in per-era mixin classes under
  `fabric/compat/{mc1_21,mc26}/java/.../mixin/` with per-era mixin configs. Each version
  build adds its era's `resources` dir.
- Use `require = 1` (the existing `defaultRequire`), so a renamed target fails the boot
  test rather than silently dropping events.

**Lifecycle.**
- `server_started` is published at the end of `onEnable` (Paper) and `onServerStarted`
  (Fabric).
- `server_stopping` is published from `onDisable` and `onServerStopping`. The API stop
  sets `reason = api` through a flag on `BridgeServer` that `ServerStopHandler` sets before
  calling the stop action.

### 1.5 TPS watcher

- A `ScheduledExecutorService` in `BridgeServer` (daemon) polls `executor.getTps1m()`
  every 10 s.
- When the value drops below `events.tps-alert-threshold` (`config.yaml`, default `15.0`,
  `0` disables) for 3 consecutive samples, it emits `degraded`. It emits `recovered` once
  the value is ≥ threshold + 1 for 3 samples.
- No game-thread work beyond what `getTps1m()` already does: a 2 s bounded hop.

### 1.6 HungerBridgePy

- `parse_sse` gains the `event` and `id` fields. Change it to yield an `SseEvent(kind,
  data, event, id)` namedtuple. `Stream` keeps using only `data` and comments, and the old
  tuple shape stays available through `parse_sse_legacy` for one release.
- New `hungerbridge/events.py`:

```python
@dataclass(frozen=True)
class Event:
    id: int; ts: int; type: str; data: dict      # data = full payload

class EventStream:  # same lifecycle as Stream: connect(reconnect=True) / disconnect / wait_until_connected
    def __init__(self, transport, types: Iterable[str] | None = None, handler: Callable[[Event], None] | None = None): ...
    def on(self, type: str) -> Callable[[F], F]: ...   # decorator registration
    last_id: int | None                               # sent as ?since= on reconnect
```

- `BridgeClient.events(types=None) -> EventStream`.
- On reconnect, resume from `last_id`. A `gap` event is delivered to handlers like any
  other event.

### 1.7 Tests

- **Java.** `EventBus` replay, the `since` gap, ring overflow, type filtering, and
  permission filtering of chat and audit events. `EventStreamHandler` in-process with
  `SseReader` (from `BridgeServerIntegrationTest`).
- **Python.** Parser with `event`/`id`; `EventStream` resume via the fake server.
- **Live.**
  - `server_started` is in the replay buffer at connect with `since=0`.
  - `command_executed` appears after `runCommand` (token has `*`).
  - `tps_alert`: set the threshold to 25 in the boot-test config so it fires
    deterministically.
  - Player events need a connected player. They're covered by an *optional* bot test,
    gated behind `HB_BOT_TESTS=1`, using a headless protocol client (`mineflayer` in Node
    is the most version-current choice). It joins (offline mode), chats, runs `/kill`,
    and leaves. Without the bot, the default CI only proves the hooks are installed:
    with `require = 1` a missing mixin target fails the boot test.

### 1.8 Tasks

1. `EventBus`, `Subscription`, `SseWriter` refactor, `EventStreamHandler`, nodes, unit tests.
2. Paper listener; live test with lifecycle and command events.
3. Fabric mixins per era; verify targets with `javap` for all 4 versions; boot tests.
4. TPS watcher.
5. HungerBridgePy `EventStream`; docs (`ENDPOINTS.md` event catalogue, Python README).
6. Optional: a bot-join test harness.

---

## §2 Player management

### 2.1 Endpoints

| Method | Path | Node | Body / query | Result |
|---|---|---|---|---|
| GET | `/players/info?player=<name\|uuid>` | `players.info` | — | `{name, uuid, online, op, whitelisted, banned, world, x, y, z, health, food, level, ping_ms, gamemode, first_played, last_seen}`; fields not available for offline players are null |
| POST | `/players/kick` | `players.kick` | `{player, reason?}` | `{kicked: bool}`; 404 `player_not_online` |
| POST | `/players/ban` | `players.ban` | `{player, reason?, duration_seconds?}` (null = permanent) | `{banned: true, expires: epoch_s\|null}` |
| POST | `/players/pardon` | `players.ban` | `{player}` | `{pardoned: bool}` |
| GET | `/players/whitelist` | `players.whitelist` | — | `{enabled, players:[{name,uuid}]}` |
| POST | `/players/whitelist` | `players.whitelist` | `{action: add\|remove\|on\|off, player?}` | `{ok, enabled, changed: bool}` |
| POST | `/players/message` | `players.message` | `{player, message}` (plain text; ≤ 256 chars) | `{delivered: bool}` |

- `player` accepts a name (case-insensitive) or a UUID. Name resolution for offline
  players uses the server's user cache only. **No Mojang lookups.**
- Error codes: 404 `player_not_found`, 404 `player_not_online`, 409 `already_banned`.
  409 only applies with `"strict": true`; by default the call is idempotent and returns
  `changed: false`.

### 2.2 Implementation

Extend the `CommandExecutor` interface. Rename it to `PlatformAdapter` in a later release;
for now add default methods that return `Optional.empty()` for "unsupported", which maps
to 501.

```java
default Optional<PlayerInfo> playerInfo(String nameOrUuid)              { return Optional.empty(); }
default PlayerActionResult kick(String player, String reason)           { … }
default PlayerActionResult ban(String player, String reason, Long durationSeconds) { … }
default PlayerActionResult pardon(String player)                        { … }
default WhitelistState whitelist(String action, String player)          { … }
default PlayerActionResult message(String player, String text)          { … }
```

`PlayerInfo`, `PlayerActionResult` and `WhitelistState` are records in
`common/.../players`.

| Operation | Paper / Purpur | Folia | Fabric |
|---|---|---|---|
| lookup | `Bukkit.getPlayerExact` / `getPlayer(UUID)`; offline: `Bukkit.getOfflinePlayerIfCached(name)` | same; **position/health read via `player.getScheduler()`** (entity scheduler, owning region) | `server.getPlayerList().getPlayerByName/getPlayer(uuid)`; offline: `server.services().nameToIdCache()`; verify the accessor name per version |
| kick | `player.kick(Component.text(reason), PlayerKickEvent.Cause.PLUGIN)` on the main thread | `player.getScheduler().run(plugin, t -> player.kick(...), null)` | `player.connection.disconnect(Component.literal(reason))` on the server thread |
| ban | `Bukkit.getBanList(BanListType.PROFILE).addBan(profile, reason, expires, "HungerBridge")`, then kick if online | ban on global, kick on entity scheduler | `server.getPlayerList().getBans().add(new UserBanListEntry(profile, created, "HungerBridge", expires, reason))`, then disconnect |
| whitelist | `Bukkit.setWhitelist`, `OfflinePlayer.setWhitelisted` | global scheduler | `PlayerList.getWhiteList()`, `server.setEnforceWhitelist` |
| message | `player.sendMessage(Component.text(text))` | entity scheduler | `player.sendSystemMessage(Component.literal(text))` |

- **Plain text only.** Never parse JSON/MiniMessage from API input. That rules out
  injection of click events and the like.
- Every action is recorded through `AuditLogger` and emits a `command_executed`-style
  event, `player_action`. Add it to the §1 catalogue.

### 2.3 Python client

```python
client.getPlayerInfo("Steve") -> dict
client.kickPlayer(player, reason=None) -> bool
client.banPlayer(player, reason=None, duration=None) -> dict   # duration: seconds | timedelta
client.pardonPlayer(player) -> bool
client.getWhitelist() -> dict; client.whitelist(action, player=None) -> dict
client.messagePlayer(player, text) -> bool
```

Add an exception `HungerBridgeNotFound` subclass, `PlayerNotFound`, carrying
`code in {"player_not_found", "player_not_online"}`.

### 2.4 Tests

- **Live without a player.**
  - `getPlayerInfo` for an unknown name returns 404.
  - Ban an offline name already in the user cache, then pardon it. Seed the cache through
    `whitelist add` of a fake offline-mode name: offline mode allows arbitrary names, and
    the UUID is derived.
  - Whitelist add/remove/on/off round-trip, with state checked via `GET`.
  - `kick` of an offline player returns 404 `player_not_online`.
- **Live with the optional bot** (§1.7): info fields populated, kick disconnects, message
  delivered.

---

## §3 Prometheus `/metrics`

### 3.1 Output (text exposition format 0.0.4)

```
# HELP hungerbridge_tps Ticks per second (window: 1m|5m|15m|instant)
# TYPE hungerbridge_tps gauge
hungerbridge_tps{window="1m"} 19.9
hungerbridge_mspt 1.25
hungerbridge_players_online 3
hungerbridge_players_max 20
hungerbridge_chunks_loaded{world="world"} 441
hungerbridge_entities{world="world"} 120
hungerbridge_world_day_time_ticks 6000
hungerbridge_jvm_memory_bytes{area="heap",kind="used|committed|max"} …
hungerbridge_jvm_gc_collections_total 12
hungerbridge_jvm_gc_seconds_total 0.84
hungerbridge_jvm_threads{state="current|daemon|peak"} …
hungerbridge_process_cpu_ratio 0.07
hungerbridge_uptime_seconds 3600
hungerbridge_http_requests_total{endpoint="/ping",status="200"} 42
hungerbridge_http_rate_limited_total 3
hungerbridge_auth_failures_total{reason="invalid_credentials"} 1
hungerbridge_sse_clients{stream="logs|events"} 2
hungerbridge_info{platform="paper",minecraft="26.3",bridge="4-alpha.93",protocol="1"} 1
```

### 3.2 Implementation

- `common/.../metrics/MetricsHandler` builds the text with a small `PrometheusWriter`
  (no new dependency).
- Values come from the existing executor and system calls, cached for
  `metrics.cache-seconds` (default 5), so a 1 s scrape interval cannot hammer the game
  thread.
- HTTP counters are a `ConcurrentHashMap<String, LongAdder>`, incremented in
  `AuditFilter`, which already sees the path and status.
- Bound the endpoint label cardinality: known paths only, otherwise `other`.

### 3.3 Auth

Prometheus cannot compute HMAC signatures. Add a config block:

```yaml
metrics:
  enabled: false
  auth: bearer        # bearer | none (none = rely on ips whitelist only)
  bearer-token-file: metrics.token   # created with 32 random bytes (hex) on first enable, owner-only perms
```

- `MetricsHandler` is not an `ApiHandler`. It does its own checks, in this order: the IP
  filter (which already applies to every context), then a constant-time comparison of
  `Authorization: Bearer …`, then rate limiting.
- Response type: `text/plain; version=0.0.4`.

### 3.4 Tests

- **Format test.** Parse the output with a minimal parser in the test and assert there
  are no duplicate series and every name matches `[a-zA-Z_:][a-zA-Z0-9_:]*`.
- **Auth test.** Disabled → 404, missing bearer → 401, correct bearer → 200.
- **Live:** scrape twice; `hungerbridge_http_requests_total` increases.
- Provide `docs/grafana/hungerbridge.json`: a dashboard with TPS, MSPT, players, memory,
  GC and HTTP errors.

---

## §4 Async Python client

### Design

- Optional dependency: `pip install hungerbridge[async]` → `httpx>=0.27`.
- Package `hungerbridge/aio/`:
  - `transport.py`: `AsyncTransport`. It mirrors `Transport` but uses
    `httpx.AsyncClient`, reusing `auth.build_auth_headers`, `canonical`, the error mapping
    (refactor `Transport._error_for` into a module function `error_for(status, text,
    json)` usable by both) and the `Retry-After` handling with `asyncio.sleep`.
  - `client.py`: `AsyncBridgeClient`. Generate its methods from one shared table so the
    two clients cannot drift:

    ```python
    # hungerbridge/_endpoints.py
    ENDPOINTS = {
      "getTPS": Endpoint("GET", "world/tps", modes={"current":"tps","1m":"tps_1m",...}),
      "getMSPT": Endpoint("GET", "world/mspt", field="mspt"),
      ...
    }
    ```

    Simple getters are table-driven. Complex methods (`getMemoryStats`, `runCommand`,
    `log`, `broadcast`) are hand-written once in a mixin that is parameterized by a
    `_get`/`_post` that's sync or async. Simplest approach: write the async versions by
    hand and add a test asserting that both clients expose identical public method names
    and signatures.
  - `stream.py`: `AsyncStream` / `AsyncEventStream` using `client.stream("GET", …)` and
    `aiter_bytes()`, with the same `_split_lines`/`parse_sse` (they're pure functions) and
    the same history-marker logic. Cancellation via task cancel means none of the socket
    shutdown workaround is needed.
- Usage:

  ```python
  async with AsyncBridgeClient(url, "id:secret") as hb:
      print(await hb.getTPS("1m"))
      async for ev in hb.events(types=["player_join"]):
          ...
  ```

### Tests

- Run the existing fake-server tests parametrized over sync and async with
  `pytest-asyncio`.
- Add a method-parity test.
- Run the live suite in both modes by adding a `HB_ASYNC=1` switch.

---

## §5 CLI

### Design

- Console script: `[project.scripts] hungerbridge = "hungerbridge.cli:main"`, with an
  `hb` alias.
- Uses `argparse` only, no new dependency.
- **Credentials**, resolved in order:
  1. `--url` and `--token`;
  2. env `HB_URL` and `HB_TOKEN`;
  3. a profile file `~/.config/hungerbridge/profiles.toml` (Windows:
     `%APPDATA%\hungerbridge\profiles.toml`), selected with `--profile name` (default
     `default`). Read with `tomllib` on 3.11+, otherwise `tomli` as an optional fallback.
     Write with a minimal hand-written serializer for the two keys.
- Commands:

```
hb login <pickup-url> [--profile NAME]      # redeem pickup, store id:secret (file perms 600 / owner-only ACL)
hb ping | hb meta | hb whoami
hb run "<command>" [--silent] [--json]
hb log "<message>" [--level L] [--thread T]
hb tail [--history N] [--events TYPES]      # follow /server/stream (or /server/events)
hb stats [--json]                            # tps, mspt, players, memory one-screen summary
hb players [--json] | hb kick/ban/pardon/msg …   (after §2)
hb stop [--yes]
```

- **Exit codes:** 0 ok, 1 API error (message from the server's `error`/`reason`),
  2 usage, 3 connection error, 4 auth (401/403).
- `--json` prints the raw response for scripting.

### Tests

`tests/test_cli.py` drives `main([...])` against the fake server and asserts stdout and
exit codes. A profile round-trip uses a `tmp_path` HOME.

---

## §6 Safe restart

### Problem

A server cannot reliably restart itself: the reverted `/server/restart` endpoint showed
that. The panel (Pterodactyl) owns the process lifecycle, and HungerLib already talks to
Pterodactyl. *HungerLib's internals were not inspected during the 2026-11 pass. Confirm its
power-action API before implementing the adapter.*

### Design (HungerBridgePy, `hungerbridge/ops.py`)

```python
class Starter(Protocol):
    def start(self) -> None: ...                 # e.g. Pterodactyl power action "start"
    def is_running(self) -> bool | None: ...     # optional; None = unknown

def safe_restart(
    client: BridgeClient,
    starter: Starter,
    *,
    countdown: Sequence[int] = (60, 30, 10, 5, 4, 3, 2, 1),
    message: str = "Server restarting in {s}s",
    save: bool = True,
    offline_timeout: float = 120,
    online_timeout: float = 300,
    on_progress: Callable[[str], None] | None = None,
) -> RestartResult: ...
```

**Steps**
1. Pre-flight `getServerMeta()`, and record `bridge_version`/`minecraft_version`.
2. Countdown: `broadcast()` at each mark, sleeping between marks.
3. If `save`, run `runCommand("save-all flush")`.
4. Call `stopServer()`. If the §1 event stream is available, also wait for
   `server_stopping`.
5. Wait until `/ping` raises `HungerBridgeConnectionError` (offline), up to
   `offline_timeout`.
6. Call `starter.start()`.
7. Poll `/ping` with back-off until `isOk()`, up to `online_timeout`. The token survives
   restarts, because tokens persist in `storage/tokens.json`.
8. Return `RestartResult(ok, downtime_s, phases)`. Every phase timeout raises
   `RestartError(phase=…)`.

- **HungerLib side:** a `PterodactylStarter` that wraps its existing power-action call.
- **CLI:** `hb restart --countdown 60,30,10 --starter pterodactyl` (only if the HungerLib
  adapter is importable).

### Tests

- Unit test with a fake client and a fake starter, checking phase order and timeouts.
- Live (optional): the standalone bridge exits on stop, and the test's starter relaunches
  it with `subprocess`. That gives an end-to-end restart with no panel involved.
