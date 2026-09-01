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
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
    include(project(":common"))
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.processResources {
    from(".") {
        include("fabric.mod.json")
        include("hungerbridge.mixins.json")
    }
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

sourceSets {
    main {
        java.srcDir("../../src/main/java")
        resources.srcDir("../../src/main/resources")
    }
}
