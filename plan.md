# Plan: Reshape HungerBridge to canonical spec

Goal
--
Reshape the Java server, Python client, and CLI to match the canonical HungerBridge design (HKIM+HMAC auth, policies.yaml ACL, autogen/ persistence, CLI token pickup flow, and listed endpoints). Reuse existing logic where possible.

Phases
--
1. Inspect & baseline
   - Audit current handlers, `TokenManager`, `HttpUtil`, and `autogen/` templates.
   - Identify current endpoint mappings to permission nodes.

2. Core auth model
   - Implement HKIM canonicalization identically in Java and Python.
   - Enforce HMAC-SHA256 verification on all endpoints (including `/auth/check`).
   - Disable replay protection: remove `sessions.json`, nonce persistence, and nonce checks.
   - Remove expiry checks entirely.

3. Policies & permissions
   - Use `autogen/HungerBridge/policies.yaml` as canonical policy file.
   - Implement policy lookup: token -> policyId -> list of permission nodes.
   - Enforce permission nodes per endpoint; return `403` when missing.

4. Token lifecycle & pickups
   - Persist tokens, pickups under `autogen/` (e.g., `autogen/tokens.json`, `autogen/pickups.json`).
   - CLI-only token creation: `hb token create <tokenId> <policyId>` creates token, stores hash, and creates a pickup UUID valid 5 minutes.
   - Implement `revoke` (mark revoked) and `remove` (delete) behaviors.
   - Ensure pickup endpoint returns plaintext secret once then deletes it.

5. Endpoint wiring
   - Keep and fix: `/ping`, `/auth/check`, `/server/run`, `/server/stop`, `/server/restart`, `/server/log`, `/server/meta`, `/server/stream`, `/system/uptime`, `/system/cpu`, `/system/memory`, `/system/disk`, `/players/list`, `/world/tps`, `/world/mspt`, `/world/chunks`, `/world/time`, `/world/weather`.
   - Remove entirely: `/server/run-batch`, `/players/kick`, `/players/ban`, `/world/events/join`, `/world/events/leave`, `/world/events/chat` (remove handlers, routes, and references).
   - Wire each kept endpoint to a permission node (e.g., `/server/run` -> `server.run`).

6. Java CLI (`hb`) and server behavior
   - Expose only `hb reload`, `hb audit [n]`, `hb token {create,revoke,remove,list}`.
   - Remove legacy CLI commands and handlers.

7. Python `BridgeClient`
   - Implement high-level camelCase methods mirroring endpoints.
   - Implement canonical signing (`json.dumps(sort_keys=True,separators=(",",":"),ensure_ascii=True)` equivalent) and header format.
   - Add `signForDebug` helper.

8. Persistence & autogen
   - Move any runtime-generated files to `autogen/` only.
   - On startup load `autogen/tokens.json`, `autogen/pickups.json`, `autogen/HungerBridge/policies.yaml`.
   - On shutdown persist tokens and pickups back into `autogen/`.

9. Tests & validation
   - Unit tests for canonicalization parity (Java vs Python sample vectors).
   - Integration: create token via CLI, pickup secret, call `/auth/check` and representative endpoints, validate status codes and permission enforcement.

Deliverables / Checkpoints
--
- `TokenManager` updated to spec (no replay/session persistence, token revoke/remove, pickup flow)
- `HttpUtil` enforces HKIM+HMAC and maps to permission nodes
- Removed deprecated endpoints and CLI commands
- `autogen/` contains `tokens.json`, `pickups.json`, `policies.yaml` templates and runtime persistence
- `hungerlib` `BridgeClient` updated to canonical signing and methods

Next actions (immediate)
--
1. Update todo list and start by updating `TokenManager` and `HttpUtil` to remove replay protection and ensure canonicalization functions are explicit.
2. Wire endpoints to permission nodes and remove listed deprecated routes.

