pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "HungerBridge"

// One Minecraft target is configured per invocation (each applies Loom or paperweight,
// which are heavy). Select it with STONECUTTER_ACTIVE=<mc>-<loader>, e.g. 26.3-paper or
// 26.3-fabric (the variable name is historical; Stonecutter itself is not used).
// A bare version ("26.3") means Paper. Without a target only :common is configured,
// which is enough for `./gradlew :common:test`.
val raw = (System.getenv("STONECUTTER_ACTIVE") ?: "").trim()
val mc = raw.filter { it.isDigit() || it == '.' }.trim('.')
val active = when {
    mc.isEmpty() -> null
    raw.contains("fabric") -> "$mc-fabric"
    raw.contains("paper") || raw.all { it.isDigit() || it == '.' } -> "$mc-paper"
    else -> null
}

include("common")

if (active != null) {
    val loader = active.substringAfterLast('-')
    val dir = file("$loader/versions/$active")
    require(dir.isDirectory) {
        "Unknown target '$active' (from STONECUTTER_ACTIVE='$raw'). Available: " +
            listOf("paper", "fabric").flatMap { l -> file("$l/versions").list()?.toList() ?: emptyList() }.sorted()
    }
    println("HungerBridge target: $active")
    include(loader)
    include("$loader:versions:$active")
} else {
    println("HungerBridge target: none (common only)")
}
