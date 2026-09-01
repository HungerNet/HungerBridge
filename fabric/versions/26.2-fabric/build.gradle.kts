plugins {
    id("fabric-loom") version "1.18.0-alpha.19"
}

version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
}
