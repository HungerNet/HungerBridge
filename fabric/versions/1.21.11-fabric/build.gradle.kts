plugins {
    id("fabric-loom") version "1.8.13"
}

// Artifact version derived from Stonecutter
version = "hungerbridge-${'$'}{stonecutter.current.minecraftVersion}-${'$'}{stonecutter.current.loader}+${'$'}{stonecutter.current.version}"

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
}
