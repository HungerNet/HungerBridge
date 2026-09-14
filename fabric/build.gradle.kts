plugins {
    id("java")
}

// tasks.named<Jar>("jar") {
//     from(project(":common").sourceSets["main"].output)
// }

version = "hb-${stonecutter.current.loader}-${stonecutter.current.minecraftVersion}+${stonecutter.current.version}"

// Version-specific Fabric configuration is defined under fabric/versions/*.
// Keep the root project as a thin wrapper to avoid applying Loom twice.
