plugins {
    id("fabric-loom") version "1.18.0-alpha.19"
}

// Minecraft 1.21.11 (obfuscated, remapped via intermediary) — Java 21.
version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
    // SnakeYAML is not shipped by Minecraft: it is shaded into the mod jar (see below).
    implementation("org.yaml:snakeyaml:2.3")
}

sourceSets {
    main {
        java.srcDir("../../src/main/java")
        java.srcDir("../../compat/mc1_21/java") // version-specific seam (FabricCompat)
        resources.srcDir("../../src/main/resources")
    }
}

tasks.processResources {
    from(".") {
        include("fabric.mod.json")
    }
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.named<Jar>("jar") {
    // Embed the loader-independent core and SnakeYAML. Gson and Log4j come from Minecraft.
    from(project(":common").extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main").output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("snakeyaml") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/versions/**", "module-info.class")
    }
}