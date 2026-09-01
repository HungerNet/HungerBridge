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

include("common")

val active = System.getenv("STONECUTTER_ACTIVE") ?: "__none__"

if (active.endsWith("-fabric")) {
    include("fabric")
    include("fabric:versions:$active")
}

if (active.endsWith("-paper")) {
    include("paper")
    include("paper:versions:$active")
}
