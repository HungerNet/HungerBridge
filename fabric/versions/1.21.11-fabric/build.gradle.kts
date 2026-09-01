plugins {
    id("fabric-loom") version "1.18.0-alpha.19"
}

version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
    include(project(":common"))
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
