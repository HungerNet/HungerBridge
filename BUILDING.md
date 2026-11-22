# Building and validating HungerBridge

## Layout

```
common/                         loader-independent core + tests
  http/        endpoint handlers (protocol v2)
  stream/      LogEvent, LogDistributor (ring, epoch/seq, subscriptions), StreamConnection
  log/         BridgeLogger (Log4j, all loaders), LineFormat, MessageNormalizer, PlatformLogAppender
  message/     BridgeMessage: the /hb output model rendered by each loader
paper/src/main/java             Paper/Purpur/Folia plugin (CapturingSender via Bukkit.createCommandSender,
                                PaperMessages, PlatformScheduler for Folia)
paper/versions/<mc>-paper/      per-version build (paperweight userdev dev bundle) + plugin.yml
fabric/src/main/java            Fabric mod (CapturingCommandSource, FabricMessages, mixin hooks)
fabric/compat/mc1_21|mc26/java  tiny per-era seam (FabricCompat) for API differences
fabric/versions/<mc>-fabric/    per-version build (Loom) + fabric.mod.json
autogen/HungerBridge/           config templates bundled into the jar; copied only when missing
ci/                             boot_test.py (real servers), publish_modrinth.py (releases)
```

Targets: `1.21.11`, `26.1.2`, `26.2`, `26.3` × `paper`, `fabric` (8 jars). The Paper jar is
also the Purpur and Folia jar. Jars are named `HungerBridge-<loader>-<minecraft>-<version>.jar`;
nothing depends on that name (releases are selected by Modrinth metadata).

## Requirements

* JDK 25 to run Gradle (wrapper 9.7.0) and to compile 26.x targets.
* JDK 21 for `common` and the 1.21.11 targets. Gradle must find both JDKs (standard
  locations, or `-Porg.gradle.java.installations.paths=<jdk21>,<jdk25>`; CI uses
  `-Porg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME_25_X64`).
* On Windows, keep the Gradle user home on a short path (paperweight exceeds `MAX_PATH`
  otherwise) and the checkout out of OneDrive (it locks `build/` files).

## Selecting a target

One Minecraft target is configured per Gradle invocation (Loom and paperweight are heavy).
Select it with `HB_TARGET=<mc>-<loader>`; without it only `:common` is configured.

```bash
./gradlew :common:test                                   # unit + in-process HTTP tests

HB_TARGET=26.3-paper ./gradlew :paper:versions:26.3-paper:build     # → paper/versions/26.3-paper/build/libs/
HB_TARGET=1.21.11-fabric ./gradlew :fabric:versions:1.21.11-fabric:build
```

## CI

`.github/workflows/build-and-release.yml`:

| Job | What it runs |
|---|---|
| `test` | `./gradlew :common:test` |
| `protocol` | starts `./gradlew :common:standaloneBridge` (the real HTTP stack with a fake game), runs HungerBridgePy's unit tests and its live suite against it |
| `build` | the 8 targets; uploads each jar with `target.json` (`{"loader", "minecraft"}`) |
| `boot` | Paper, Purpur, Folia and Fabric × 4 Minecraft versions: `ci/boot_test.py` downloads the real server, installs the jar, boots it and checks the console and the full live suite (below) |
| `publish` | on push to `main` with `run: true` in `version.yaml`: `ci/publish_modrinth.py` publishes one Modrinth version per jar (needs the `MODRINTH_TOKEN` secret); versions that exist already are skipped |

## Runtime validation

`ci/boot_test.py` is the acceptance test for a server, and runs locally too:

```bash
pip install -e ../HungerBridgePy
python ci/boot_test.py --loader fabric --minecraft 26.3 \
    --jar fabric/versions/26.3-fabric/build/libs/HungerBridge-fabric-26.3-5-alpha.1.jar \
    --java /path/to/jdk25/bin/java --hungerbridgepy ../HungerBridgePy
```

On the real server console it verifies that `runCommand("seed", False)` keeps the feedback
off the console, that `show_console=True` echoes it, and that
`log(level="CUSTOMLEVEL", thread="CustomThread", logger="CustomLogger")` produces the
loader's console line with `[CustomLogger]`. Then it runs HungerBridgePy's live suite
(`tests/test_live.py`): authentication (replay, clock skew, tampering), every endpoint,
command feedback, suppression observed through the stream, custom loggers, normalization,
stream resume after a disconnect, error handling and rate limiting.

Without a Minecraft server, use the standalone bridge:

```bash
./gradlew :common:standaloneBridge -Phb.standalone.port=19130    # prints PICKUP_URL=...
HB_PICKUP_URL=... HB_EXPECT_PLATFORM=standalone pytest -m live tests/test_live.py   # in HungerBridgePy
```

## Adding a Minecraft version

1. Copy the newest `paper/versions/<mc>-paper` and `fabric/versions/<mc>-fabric`, update
   the dev bundle / `minecraft(...)` coordinates, `api-version` and the `"minecraft"` range
   in `fabric.mod.json`.
2. Add the targets to the `build` matrix and the version to the `boot` matrix.
3. Build. Put Minecraft API differences behind `FabricCompat` (`fabric/compat/...`); the
   mixin targets `MinecraftServer#tickChildren`, `stopServer` and `reloadResources`, and
   mixin failures only show up at runtime, which the `boot` job catches.
