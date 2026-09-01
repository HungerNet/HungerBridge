pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "HungerBridge"

val raw = System.getenv("STONECUTTER_ACTIVE") ?: "__none__"

val active = when {
    raw.contains("paper") -> {
        val mc = raw.filter { it.isDigit() || it == '.' }
        if (mc.isNotBlank()) "${mc}-paper" else "__none__"
    }
    raw.contains("fabric") -> {
        val mc = raw.filter { it.isDigit() || it == '.' }
        if (mc.isNotBlank()) "${mc}-fabric" else "__none__"
    }
    raw.all { it.isDigit() || it == '.' } -> "${raw}-paper"
    else -> "__none__"
}

println("Normalized STONECUTTER_ACTIVE = ${active}")

if (active.endsWith("-fabric")) {
    include("common")
    include("fabric")
    include("fabric:versions:${active}")
}

if (active.endsWith("-paper")) {
    include("common")
    include("paper")
    include("paper:versions:${active}")
}
