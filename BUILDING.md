Building and validating multi-version HungerBridge

Supported targets
- Paper: 1.21.11, 26.1.2, 26.2
- Fabric: 1.21.11, 26.1.2, 26.2

Java requirements
- Building 26.x targets requires a JDK 25 runtime for Gradle. Use a distribution that exposes a Java version string Kotlin accepts (some Temurin builds include an additional suffix that can cause Kotlin parsing errors).
- Recommended: run builds in a Docker/CI image that has JDK 25 installed (BellSoft Liberica / Azul Zulu / official OpenJDK 25 images).

Environment variables
- `STONECUTTER_ACTIVE` — selects the active Fabric target (e.g. `26.2-fabric`). Defaults to `1.21.11-fabric`.
- `STONECUTTER_ACTIVE_PAPER` — selects the active Paper target (e.g. `26.2-paper`). Overrides `STONECUTTER_ACTIVE` for Paper.

Example local run (select Paper 26.2)

```bash
export JAVA_HOME=/path/to/jdk25
export PATH="$JAVA_HOME/bin:$PATH"
export TMPDIR=/home/$USER/.tmp
export GRADLE_USER_HOME=/home/$USER/.gradle
export JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=$TMPDIR'
export STONECUTTER_ACTIVE_PAPER=26.2-paper
./gradlew :paper:help --no-daemon
```

Build specific version (Paper 26.1.2)

```bash
export STONECUTTER_ACTIVE_PAPER=26.1.2-paper
./gradlew :paper:build --no-daemon
```

Build specific Fabric version (26.2)

```bash
export STONECUTTER_ACTIVE=26.2-fabric
./gradlew :fabric:build --no-daemon
```

CI recommendation
- Use a runner with JDK 25 available (Docker images `bellsoft/liberica-openjdk:25`, `azul/zulu:25`, or `openjdk:25` if available). Example GitHub Actions job step:

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin' # or 'zulu' / 'liberica'
          java-version: '25'
      - name: Build Paper 26.2
        env:
          STONECUTTER_ACTIVE_PAPER: 26.2-paper
        run: ./gradlew :paper:build --no-daemon --stacktrace
```

Notes
- I attempted to install Temurin JDK 25 locally; Kotlin's Java detection may reject certain `java.version` formats (e.g. `25.0.4.1`) and fail the Kotlin script compilation. If you see `IllegalArgumentException: 25.0.4.1` during Kotlin compile, try a different JDK 25 image (BellSoft Liberica or Azul Zulu) in CI or Docker.
- If you want, I can continue trying alternate JDK 25 distributions locally, but the fastest path to a validated build is to run CI in a JDK25 container and report back results.
