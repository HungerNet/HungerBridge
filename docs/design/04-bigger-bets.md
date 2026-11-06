# 04 — Bigger bets

## Summary

These three ideas widen what HungerBridge covers. Each is a multi-week effort with real
uncertainty, so each one **starts with a time-boxed spike** that answers specific
questions before anyone commits to a full build. Only pursue the ones there's actual demand
for.

| Bet | Value | Main risk | Spike (time-box) | Build if spike passes |
|---|---|---|---|---|
| **Velocity proxy plugin** | One API for a whole network: who is online where, move players between servers, network-wide broadcasts | Different API model (no worlds/TPS); fan-out to backend bridges | 1 day | L |
| **NeoForge support** | Reaches modded servers that aren't on Fabric | A third loader to keep current every Minecraft release | 1–2 days | L, plus ongoing maintenance |
| **Web dashboard** | A visual console: live log, events, metrics, player actions | Browser auth: an HMAC secret must never reach the browser | 1 day (design + prototype) | XL |

**Decisions needed**
- [ ] Which of the three has a real user today? If none, park this document.
- [ ] Velocity: is per-backend aggregation needed (proxy → backend bridges), or only
      proxy-native data?
- [ ] Dashboard: is it hosted by HungerNet (part of HungerLib's stack) or bundled into the
      plugin?

**Prerequisites:**
- 01 §1 (boot tests). Every bet adds platforms that need runtime testing.
- 02 §1 (event stream) for the dashboard and for Velocity aggregation.

---

## A. Velocity proxy plugin

### Shape

A new module, `velocity/`. It needs only one build (the Velocity API is stable across
Minecraft versions), compiles against `com.velocitypowered:velocity-api:3.x`
(annotation processor) on Java 21, and embeds `common` like the other jars.

| Concern | Approach |
|---|---|
| Entry point | `@Plugin(id = "hungerbridge") class HungerBridgeVelocity`; `@Subscribe ProxyInitializeEvent` → start `BridgeServer`; `ProxyShutdownEvent` → stop |
| Config dir | `@DataDirectory Path` |
| Logging / SSE | Velocity uses Log4j 2, so the existing `PlatformLogAppender` works unchanged (verify in the spike) |
| Commands | `/hb …` via `CommandManager` (`SimpleCommand`), console-only (`invocation.source() instanceof ConsoleCommandSource`) |
| Executor | `VelocityAdapter implements CommandExecutor`: `executeWithOutput` via `server.getCommandManager().executeAsync(console, cmd)` with capture; TPS/world methods keep their defaults (-1). Endpoints whose data doesn't exist return 501 `not_supported_on_platform` |

### New endpoints (`network.*` nodes; Velocity only)

| Endpoint | Result |
|---|---|
| `GET /network/servers` | `[{name, address, players, online}]`. `online` comes from a cached `RegisteredServer.ping()`, refreshed every 15 s |
| `GET /network/players` | `[{name, uuid, server, ping_ms}]` |
| `POST /network/send` | `{player, server}` → `player.createConnectionRequest(server).connect()` |
| `POST /network/broadcast` | `{message}` → every player |
| `POST /players/kick` etc. | reuse 02 §2 on the proxy (network-wide kick) |

Events (02 §1): `player_join`/`player_leave` at network level, and `player_switch_server`
(`{player, from, to}`).

### Optional aggregation (only if decided)

A `backends:` section in the Velocity `config.yaml` lists the backend bridges' URLs and
tokens. Then `GET /network/metrics` fans out to each backend's `/world/tps`, `/world/mspt`
and `/players/list` using a small Java HMAC client. Reuse the `SignedHttp` logic from the
tests, promoted to `common/.../client/BridgeHttpClient`. Short timeouts, cached results.
**Security:** this makes the proxy a holder of backend tokens, so use a dedicated
read-only policy on each backend.

### Spike questions (1 day)

1. Does `PlatformLogAppender` receive Velocity console output?
2. Does command capture work for proxy commands (`executeAsync` completes on another
   thread, so the `ThreadLocal` capture may miss it)? If not, capture via the
   `CommandSource` sender instead.
3. Build integration: is there a third `velocity/versions/...` entry, or a plain module
   outside `HB_TARGET` selection? Recommendation: a plain module, always included.
4. Can the boot test run Velocity plus one Paper backend in CI within 3 minutes?

---

## B. NeoForge support

### Key insight

From 26.1 on, Minecraft is unobfuscated, so NeoForge and Fabric compile against **the same
Minecraft class and method names**. Most of `FabricCommandExecutor` is plain Minecraft
code. Refactor it before adding a third loader:

```
vanilla/src/main/java      ← NEW: loader-neutral Minecraft code (mojmap)
  VanillaCommandExecutor   (from FabricCommandExecutor: commands, metrics, players, levelKey)
  VanillaCompat seam       (from fabric/compat; day-time etc.)
  TickStats                (EMA/TPS from HungerBridgeFabric)
fabric/src/main/java       ← Loom-specific: entry point, mixins → call into vanilla/
neoforge/src/main/java     ← NeoForge-specific: @Mod entry, event subscribers → call into vanilla/
```

Each loader's version build adds `vanilla/src/main/java` as a source dir, the same way it
adds `common` today.

### NeoForge mapping

| Need | NeoForge mechanism (no mixins needed) |
|---|---|
| Start/stop | `ServerStartedEvent`, `ServerStoppingEvent` on `NeoForge.EVENT_BUS` |
| Tick timing | `ServerTickEvent.Pre` / `Post` → `TickStats.record(nanos)` |
| Commands | `RegisterCommandsEvent` → the existing Brigadier tree (share `FabricCommandRegistrar`'s logic in `vanilla/` by passing a dispatcher) |
| Reload | `AddReloadListenerEvent` or `OnDatapackSyncEvent` |
| Player events (02 §1) | `PlayerEvent.PlayerLoggedInEvent` / `PlayerLoggedOutEvent`, `ServerChatEvent`, `LivingDeathEvent`, `AdvancementEvent.AdvancementEarnEvent` |
| Config dir | `FMLPaths.CONFIGDIR.get().resolve("HungerBridge")` |
| SnakeYAML | Shade it like Fabric does, or use jar-in-jar via `jarJar`. Check whether NeoForge already ships SnakeYAML |

Build: `neoforge/versions/<mc>-neoforge/build.gradle.kts` with the ModDevGradle plugin
(`net.neoforged.moddev`) and a `neoforge.mods.toml` template.

### Spike questions (1–2 days)

1. Which NeoForge versions exist for 26.1.2 / 26.2 / 26.3 (check `maven.neoforged.net`),
   and does a 1.21.11 target matter?
2. Does the `vanilla/` refactor compile for Fabric 1.21.11? That version is obfuscated, so
   `vanilla/` is limited to 26.x, or 1.21.11 keeps its own copy. Recommendation: `vanilla/`
   for 26.x only; 1.21.11 Fabric stays as it is until 1.21.11 support is dropped.
3. Does the NeoForge server launcher run headless in the boot-test harness
   (`run.sh`/`@libraries` args)?
4. What is the maintenance cost per Minecraft release, compared with Fabric (Loom bump
   only)? Write the result into `BUILDING.md` → "Adding a Minecraft version".

---

## C. Web dashboard

### Constraint that drives the design

HungerBridge tokens are HMAC secrets with long lifetimes. **They must never be sent to a
browser.** Two viable architectures:

| | A. Backend-for-frontend (recommended) | B. Bundled in the plugin |
|---|---|---|
| Where | A small web app (part of HungerLib's stack, or a new repo `HungerBridgeDashboard`) | Static files served by `BridgeServer` at `/dashboard/` |
| Auth | The dashboard has its own login (OIDC, or a password plus TOTP). Its backend holds HungerBridge tokens and calls the API with HungerBridgePy | A new short-lived **session** mechanism: `/hb dashboard login` prints a one-time code, which is exchanged for an `HttpOnly; Secure; SameSite=Strict` cookie scoped to a policy, with a 1 h expiry. Needs CSRF protection on POSTs and TLS (03 §2) |
| Multi-server | Natural: one dashboard, many bridges | One per server |
| New server code | None (uses 02 §1 events, 02 §3 metrics, 02 §2 player actions) | Session store, cookie auth path in `HttpUtil.authenticate`, static file handler, CSP headers |
| Risk | Another service to host | Expands the plugin's attack surface considerably |

### Option A sketch

- **Backend:** Python (FastAPI + HungerBridgePy async client from 02 §4).
  - `GET /api/servers`;
  - `GET /api/servers/{id}/events` (SSE proxy of `/server/events`, filtered by the
    dashboard user's role);
  - `POST /api/servers/{id}/run` (role-checked, audit-logged with the dashboard user
    name);
  - metrics come from Prometheus (02 §3) rather than the bridge directly.
- **Frontend:** a single-page app with:
  - a live console (`/server/stream`);
  - an event feed;
  - TPS/MSPT/players charts;
  - a player table with kick/ban/whitelist/message actions;
  - token and policy management (read-only at first).
- **Roles:** `viewer` (read endpoints and events), `moderator` (+ player actions),
  `admin` (+ run/stop/restart via 02 §6). Each role maps to a HungerBridge token, created
  with a matching policy.

### Spike (1 day)

1. Prototype the backend with two endpoints (servers, events proxy) plus a static page
   that shows the live event feed. Verify SSE passes through end to end with the
   proxy/flush settings from 03 §2.1.
2. Write the threat model in one page: who logs in, where the tokens live, what an XSS on
   the dashboard can do.
3. Decide A or B. Only build B if A is ruled out by hosting constraints, and then only
   after 03 §2 (TLS) has shipped.
