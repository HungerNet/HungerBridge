plugins {
    id("dev.kikugie.stonecutter")
    id("gg.meza.stonecraft")
}

// Allow overriding the active Fabric target via environment variable
// e.g. `STONECUTTER_ACTIVE=26.2-fabric ./gradlew <task>`
val activeTarget: String = System.getenv("STONECUTTER_ACTIVE") ?: "1.21.11-fabric"

stonecutter {
    active(activeTarget)
}
