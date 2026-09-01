pluginManagement {
    repositories {
        gradlePluginPortal()

        // Fabric Loom plugin repository
        maven("https://maven.fabricmc.net/")

        // Paperweight plugin repository
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "HungerBridge"

val active = System.getenv("STONECUTTER_ACTIVE") ?: "__none__"

// Fabric loader
if (active.endsWith("-fabric")) {
    include("common")
    include("fabric")
    include("fabric:versions:$active")
}

// Paper loader
if (active.endsWith("-paper")) {
    include("common")
    include("paper")
    include("paper:versions:$active")
}
