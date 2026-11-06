plugins {
    id("net.fabricmc.fabric-loom") version "1.18.0-alpha.19"
}

// Minecraft 26.3 (unobfuscated) — Java 25.
version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
    // SnakeYAML is not shipped by Minecraft: it is shaded into the mod jar (see below).
    implementation("org.yaml:snakeyaml:2.3")
}

sourceSets {
    main {
        java.srcDir("../../src/main/java")
        java.srcDir("../../compat/mc26/java") // version-specific seam (FabricCompat)
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