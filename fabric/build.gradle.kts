plugins {
    id("java")
}

version = "hb-${stonecutter.current.loader}-${stonecutter.current.minecraftVersion}+${stonecutter.current.version}"

// Version-specific Fabric configuration is defined under fabric/versions/*.
// Keep the root project as a thin wrapper to avoid applying Loom twice.
