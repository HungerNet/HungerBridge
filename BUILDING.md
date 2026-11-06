# Building and validating HungerBridge

## Layout

```
common/                         loader-independent core (HTTP API, auth, config, SSE) + tests
paper/src/main/java             Paper/Purpur/Folia plugin sources (shared by all versions)
paper/versions/<mc>-paper/      per-version build (paperweight userdev dev bundle) + plugin.yml
fabric/src/main/java            Fabric mod sources (shared by all versions)
fabric/compat/mc1_21|mc26/java  tiny per-era seam (FabricCompat) for API differences
fabric/versions/<mc>-fabric/    per-version build (Loom) + fabric.mod.json
autogen/HungerBridge/           templates bundled into the jar; copied only when missing
```

Targets: `1.21.11`, `26.1.2`, `26.2`, `26.3` × `paper`, `fabric` (8 jars). The Paper jar
is also the Folia jar.

## Requirements

* JDK 25 to run Gradle (wrapper 9.7.0) and to compile 26.x targets.
* JDK 21 for the `common` module and the 1.21.11 targets (their toolchain is 21 so the jars
  run on Java 21). Gradle must be able to find both JDKs — auto-detected from standard
  locations, or pass `-Porg.gradle.java.installations.paths=<jdk21>,<jdk25>` (CI uses
  `-Porg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME_25_X64`).

## Selecting a target

Only one Minecraft target is configured per Gradle invocation (Loom/paperweight are heavy).
Select it with `STONECUTTER_ACTIVE=<mc>-<loader>` — the name is historical; the Stonecutter
plugin is not used. Without it, only `:common` is configured.

```bash
./gradlew :common:test                                  # unit + in-process HTTP integration tests

export STONECUTTER_ACTIVE=26.3-paper
./gradlew :paper:versions:26.3-paper:build --no-daemon  # → paper/versions/26.3-paper/build/libs/

export STONECUTTER_ACTIVE=1.21.11-fabric
./gradlew :fabric:versions:1.21.11-fabric:build --no-daemon
```

(`STONECUTTER_ACTIVE_PAPER`, mentioned by older docs, was never read by the build.)

## CI (`.github/workflows/build-and-release.yml`)

| Job | What it runs |
|---|---|
| `test` | `./gradlew :common:test` |
| `protocol` | starts `./gradlew :common:standaloneBridge` (real HTTP stack, fake game backend), checks out HungerBridgePy and runs its unit tests + live protocol suite against it |
| `build-matrix` | `./gradlew :<loader>:versions:<target>:build` for the 8 targets (Temurin 21 + 25, `-Xmx6g`) and uploads `build/libs/*.jar` |
| `release` | on push to main: GitHub release `v<version.yaml version>` with all 8 jars |

## Adding a Minecraft version

1. Copy the newest `paper/versions/<mc>-paper` and `fabric/versions/<mc>-fabric` directory,
   update the dev bundle / `minecraft(...)` coordinates, `api-version` and the
   `"minecraft"` range in `fabric.mod.json`.
2. Add both targets to the CI matrix.
3. Build. If a Minecraft API changed, put the difference behind `FabricCompat`
   (`fabric/compat/...`) — the mixin targets `MinecraftServer#tickChildren`, `stopServer`
   and `reloadResources`, and mixin failures only show up at runtime.
4. Boot-test Paper, Folia and Fabric with the HungerBridgePy live suite (below).

## Runtime validation

The live suite in HungerBridgePy (`tests/test_live.py`) is the acceptance test for a server:

```bash
# on the server console
hb token create hbtest admin           # with an "admin" policy granting "*"
# on the client
HB_PICKUP_URL='http://127.0.0.1:1913/pickup/<id>?passkey=<hex>' \
HB_EXPECT_PLATFORM=paper HB_EXPECT_MC=26.3 HB_ALLOW_STOP=1 \
pytest -m live tests/test_live.py
```

It checks auth (including replay, clock-skew and tamper rejection), every endpoint, command
output capture, log → SSE delivery, 404/400 handling, rate limiting with retry and finally
`/server/stop`. Without a Minecraft server, use `./gradlew :common:standaloneBridge` and
`HB_EXPECT_PLATFORM=standalone`.
