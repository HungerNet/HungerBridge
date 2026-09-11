plugins {
    id("net.fabricmc.fabric-loom") version "1.18.0-alpha.19"
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
    implementation("org.yaml:snakeyaml:2.2")
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
    include(project(":common"))
}

tasks.jar {
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.contains("snakeyaml") }
            .map { zipTree(it) }
    })
}

tasks.processResources {
    from(".") {
        include("fabric.mod.json")
        include("hungerbridge.mixins.json")
    }
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }

    // Ensure autogen runtime templates from the repository root are packaged
    // into the final plugin JAR under `/autogen/*` so the running plugin can
    // seed runtime config from them.
    from(rootProject.file("autogen/HungerBridge")) {
        into("autogen")
    }
}

sourceSets {
    main {
        java.srcDir("../../src/main/java")
        resources.srcDir("../../src/main/resources")
    }
}
