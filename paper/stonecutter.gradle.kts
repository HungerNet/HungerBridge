plugins {
    id("dev.kikugie.stonecutter")
    id("gg.meza.stonecraft")
}

// Allow overriding the active Paper target via environment variable
// Priority: STONECUTTER_ACTIVE_PAPER > STONECUTTER_ACTIVE > default
val activeTarget: String = System.getenv("STONECUTTER_ACTIVE_PAPER") ?: System.getenv("STONECUTTER_ACTIVE") ?: "1.21.11-paper"

stonecutter {
    active(activeTarget)
}
