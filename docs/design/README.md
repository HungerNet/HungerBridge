# HungerBridge design documents

These are design proposals for work that builds on the 2026-11 release-readiness pass. Each document opens with a plain-language summary
covering the problem, the proposal, the effort, and the decisions an owner needs to make.
After that comes an implementation spec precise enough to build from directly: files,
formats, tests, acceptance criteria and an ordered task list.

| Doc | Scope | Repos | Effort | Status |
|---|---|---|---|---|
| [01 — Engineering foundations](01-engineering-foundations.md) | Boot tests in CI, reproducible versions + Renovate, OpenAPI contract, target-variable rename | HungerBridge, HungerBridgePy | M | §1 boot tests and §4 `HB_TARGET`: **done in 5-alpha.1**; rest proposed |
| [02 — Features](02-features.md) | Structured event stream, player management, Prometheus metrics, async client, CLI, safe restart | both | L (split into 6 shippable parts) | §1: structured *log* events **done in 5-alpha.1** (protocol v2 stream); typed game events and the rest proposed |
| [03 — Security hardening](03-security-hardening.md) | Signed query strings (protocol v3), TLS / reverse proxy, per-token IP binding + rate limits, `show_console` | both | M | §4 `show_console`: **fixed in 5-alpha.1** (native capture, not the appender proposal); rest proposed |
| [04 — Bigger bets](04-bigger-bets.md) | Velocity proxy plugin, NeoForge, web dashboard | HungerBridge (+ new repo for dashboard) | XL, spike first | Exploratory |

Effort scale: **S** ≈ under a day, **M** ≈ 1–3 days, **L** ≈ 1–2 weeks, **XL** ≈ multi-week.

## Recommended order

1. **01 §1 boot tests in CI**. Every later change, especially new mixins and new event
   hooks, depends on runtime regressions being caught automatically.
2. **01 §2 pinned versions**, so a CI failure means the code changed and not that the
   dev bundle moved.
3. **02 §1 event stream**, then **02 §2 player endpoints**. These two deliver the most
   value to users.
4. **03 §1 query signing** before anything is exposed beyond localhost. **03 §3** comes
   with 02 §2, because new write endpoints make per-token limits matter more.
5. Everything else, depending on demand.

## Conventions used in every doc

- **Protocol compatibility.** New server features must not break HungerBridgePy 2 (protocol v2). New behaviour is opt-in, either through a new endpoint or a field old
  clients ignore. Breaking changes require a new protocol version (see 03 §1).
- **Thread rules.** All game-state access goes through `PlatformScheduler` (Paper/Folia) or
  `FabricCommandExecutor.onServerThread` (Fabric), with timeouts.
- **Permissions.** Every new endpoint gets its own node, registered in
  `common/.../security/Permissions.KNOWN` and documented in `ENDPOINTS.md`.
- **Tests.** Each feature ships:
  - Java unit or in-process tests in `BridgeServerIntegrationTest` style;
  - HungerBridgePy unit tests against the fake server;
  - new cases in `tests/test_live.py`, which run against all 12 real servers through 01 §1.
