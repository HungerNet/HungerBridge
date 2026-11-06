# 01 — Engineering foundations

## Summary

**Problem.** The 2026-11 pass found two shipped defects that compiled cleanly and failed
only when a real server started: the Fabric jar demanded Fabric API, and the 1.21.11 jar
was built for the wrong Java version. CI still only compiles, so the same class of bug can
return with any new Minecraft version or mixin. Builds also use dynamic dev-bundle versions,
so the same commit can produce different jars on different days. The API docs had drifted
far from the code.

**Proposal.**
1. **Boot tests in CI.** For every target, start a real Paper, Folia or Fabric server with
   the freshly built jar, run the HungerBridgePy live suite, and stop the server through
   the API.
2. **Reproducible builds.** Pin every version and let Renovate open PRs to bump them, so
   the boot tests gate each bump.
3. **An OpenAPI spec as the single source of truth for the HTTP API**, checked by tests on
   both sides so docs and code can't drift again.
4. **Housekeeping.** Rename the misleading `STONECUTTER_ACTIVE` variable.

**Effort:** M overall: §1 ≈ 1 day, §2 ≈ 0.5 day, §3 ≈ 1–2 days, §4 ≈ 1 hour.

**Decisions needed**
- [ ] CI minutes. 12 boot jobs × about 2 minutes each per push is fine on public repos. On
      private repos, run them only on PRs and main.
- [ ] Should Renovate PRs auto-merge when all checks pass? Recommended for patch-level dev
      bundles only.
- [ ] Should the spec live in HungerBridge (recommended) with HungerBridgePy fetching it, or
      be copied into both repos like `protocol-vectors.json`?

---

## §1 Boot tests in CI

### Design

Port the local PowerShell harness (`runtime-test.ps1`, used for REPORT.md) to a bash
script that's committed to the repo:

```
tools/boot-test/boot-test.sh <platform> <mc> <jar> [--expect-platform X]
tools/boot-test/server-jar.sh <platform> <mc>   # resolves + caches the official server jar
```

**`server-jar.sh`**
- Paper/Folia: `GET https://fill.papermc.io/v3/projects/{paper|folia}/versions/{mc}/builds`.
  Take the first build whose `channel` is in `${ALLOWED_CHANNELS:-STABLE,BETA,ALPHA}`, then
  `downloads."server:default".url`. Verify `checksums.sha256`.
- Fabric:
  `https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/{installer}/server/jar`, with
  `loader` and `installer` pinned in `tools/boot-test/versions.env` (see §2).
- Send `User-Agent: hungerbridge-ci (github.com/HungerNet/HungerBridge)`. The Fill API
  requires a descriptive UA.

**`boot-test.sh` steps** (mirrors the PowerShell harness, which is proven on 12 targets):
1. `mktemp -d` server dir. Write `eula.txt`, and a `server.properties` with:
   `online-mode=false`, a flat preset with explicit `generator-settings`, `view-distance=4`,
   `simulation-distance=4`, a random free game port, and `max-players=20`.
2. Pre-seed the HungerBridge config dir (`plugins/HungerBridge` or `config/HungerBridge`):
   `config.yaml` with a random free bridge port, and `policies.yaml` with `admin: ["*"]`.
3. Copy the jar into `plugins/` or `mods/`.
4. Start the server with `mkfifo` stdin:
   `java -Xms1G -Xmx2G -jar server.jar --nogui < fifo > console.log 2>&1 &`, holding the
   fifo open with `exec 3>fifo`. Java is `$JAVA_HOME_21_X64` for 1.21.11 and
   `$JAVA_HOME_25_X64` for 26.x.
5. Wait up to 300 s for `Done (`. Fail fast if the process exits.
6. Run `echo "hb token create ci admin" >&3`, then grep
   `/pickup/[0-9a-f-]+\?passkey=[0-9a-f]+` within 30 s.
7. Run `HB_PICKUP_URL=… HB_EXPECT_PLATFORM=… HB_EXPECT_MC=… HB_ALLOW_STOP=1 pytest -m live
   --junitxml=…`.
8. Expect the process to exit within 90 s (the stop test). Otherwise send `stop` and fail.
9. Log gate: fail if `console.log` contains `UnsupportedClassVersionError`,
   `Mixin apply failed`, `Could not load plugin`, `Incompatible mods`, or any `ERROR` line
   that mentions `hungerbridge`. Write the log as a job artifact.

**Workflow change** (`build-and-release.yml`):

```yaml
  boot-test:
    needs: build-matrix
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        include:   # 12 rows: {platform, mc, artifact, java}
          - { platform: paper,  mc: 1.21.11, artifact: paper-1.21.11,  java: 21 }
          - { platform: folia,  mc: 1.21.11, artifact: paper-1.21.11,  java: 21 }
          - { platform: fabric, mc: 1.21.11, artifact: fabric-1.21.11, java: 21 }
          # … 26.1.2, 26.2, 26.3 (java: 25)
    steps:
      - uses: actions/checkout@v4
      - uses: actions/checkout@v4
        with: { repository: HungerNet/HungerBridgePy, path: HungerBridgePy }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21\n25" }
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - uses: actions/download-artifact@v4
        with: { name: "${{ matrix.artifact }}", path: jar/ }
      - uses: actions/cache@v4      # server jars + Fabric's downloaded vanilla jar/libraries
        with: { path: ~/.cache/hb-servers, key: "server-${{ matrix.platform }}-${{ matrix.mc }}" }
      - run: pip install -e HungerBridgePy[test]
      - run: tools/boot-test/boot-test.sh ${{ matrix.platform }} ${{ matrix.mc }} jar/*.jar
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: "boot-${{ matrix.platform }}-${{ matrix.mc }}", path: boot-out/ }
```

`release` adds `boot-test` to `needs`. Folia alpha builds are allowed to fail without
blocking a release only if the owner decides so; implement that with
`continue-on-error: ${{ matrix.platform == 'folia' && matrix.mc == '26.3' }}`.

### Acceptance criteria

- [ ] Reintroducing either historical bug makes the matrix red. Two checks: (a) set the
      1.21.11-fabric toolchain to 25; (b) add `"fabric-api": "*"` to `fabric.mod.json`.
- [ ] A green run uploads 12 `console.log` + `pytest-live.xml` artifacts.
- [ ] The full matrix takes ≤ 15 minutes of wall time in parallel.

### Tasks

1. `tools/boot-test/{server-jar.sh,boot-test.sh,versions.env}` and a README.
2. Run locally on Linux (WSL or a container) against 2 targets.
3. Add the workflow job; seed the cache.
4. Run the bug-reintroduction checks on a throwaway branch.

---

## §2 Reproducible versions + Renovate

### Design

Move every moving version into `gradle/libs.versions.toml`, so Renovate can parse it and
humans have one file to read:

```toml
[versions]
paperweight = "2.0.0-beta.23"
loom = "1.18.0-alpha.19"
fabric-loader = "0.19.2"
snakeyaml = "2.3"
gson = "2.10.1"
log4j = "2.25.1"
junit = "5.10.2"
paper-devbundle-1_21_11 = "1.21.11-R0.1-20260511.115010-91"
paper-devbundle-26_1_2 = "26.1.2.build.74-stable"
paper-devbundle-26_2 = "26.2.build.135-stable"
paper-devbundle-26_3 = "26.3.build.242-stable"
```

- Version build files use `libs.versions.paper.devbundle.x26.x3.get()` and so on, in
  place of `26.3.build.+`.
- `tools/boot-test/versions.env` pins the Fabric loader and installer used for boot tests,
  and optionally the Paper/Folia server builds.

**Renovate (`renovate.json`)**
- Use the `gradle` manager for the TOML catalog.
- Use a `customManagers` regex manager for `versions.env`.
- Group all `paper-devbundle-*` bumps into one PR per week.
- `packageRules` auto-merge `patch` updates of dev bundles when all checks pass, including
  §1.
- Dev-bundle versions live in a Maven repo (`repo.papermc.io/repository/maven-public`).
  Add it as a `registryUrls` entry.

### Acceptance criteria

- [ ] Running `./gradlew :paper:versions:26.3-paper:build` twice a week apart, without a
      Renovate PR in between, produces byte-identical class files. Jar timestamps differ
      unless `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true`
      (also add those).
- [ ] Renovate opens a PR when a new 26.3 dev bundle appears, and §1 runs on it.

---

## §3 OpenAPI contract

### Design

- **Spec file:** `api/openapi.yaml` (OpenAPI 3.1) in HungerBridge. Each operation lists
  its permission node in `x-hb-permission`. The security scheme is `hmac` (`type: apiKey`
  for the four headers, with the scheme spelled out in its description). Response schemas
  are JSON Schema matching the current flat responses; shared `ErrorBody` and
  `components.headers.Protocol`.
- **Java contract test.** `OpenApiContractTest` loads the YAML with SnakeYAML and asserts:
  - the set of `paths` equals the set of registered `ApiHandler.path()` values plus
    `/pickup/{id}`;
  - each operation's HTTP method equals the handler's method;
  - `x-hb-permission` equals the handler's permission node.

  `ApiHandler` gains a `permission()` getter, and `BridgeServer` exposes
  `List<ApiHandler> handlers()`.
- **Response-shape test (Python).** `test_live.py` gains a fixture that validates every
  JSON response against the matching schema. It uses `jsonschema` as a test-only
  dependency, and loads `HB_OPENAPI` (path to the spec), skipping if unset. CI points it
  at the checked-out HungerBridge spec.
- **Docs.** `ENDPOINTS.md` stays the human guide. The CI `protocol` job additionally runs
  the live suite with schema validation.
- **Later (optional).** Generate `TypedDict` response models into
  `hungerbridge/models.py` with a small script rather than a full generator, to avoid a
  heavy dependency.

### Acceptance criteria

- [ ] Adding a handler without a spec entry fails `OpenApiContractTest`.
- [ ] Renaming a response field without updating the spec fails the live suite on CI.

---

## §4 Rename `STONECUTTER_ACTIVE`

The Stonecutter plugin isn't used, so the variable name misleads people.

- `settings.gradle.kts` reads `HB_TARGET` first, then falls back to `STONECUTTER_ACTIVE`
  and prints a deprecation line when it does.
- Update the CI, `BUILDING.md` and the boot-test scripts.
- Remove the fallback one release later.
