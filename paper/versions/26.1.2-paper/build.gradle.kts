plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

// Paper / Purpur / Folia 26.1.2 — Java 25. Uses only the Paper API (no NMS); the jar
// is mojang-mapped (paperweight-mappings-namespace: mojang).
version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-releases/")
    maven("https://repo.papermc.io/repository/maven-snapshots/")
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.+")
    implementation(project(":common"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

sourceSets {
    named("main") {
        java.srcDir(rootProject.file("paper/src/main/java"))
        resources.srcDir(rootProject.file("paper/src/main/resources"))
    }
}

tasks.named<Jar>("jar") {
    // Embed the loader-independent core. SnakeYAML, Gson and Log4j are provided by the server.
    from(project(":common").extensions.getByType<JavaPluginExtension>().sourceSets["main"].output)
}

tasks.processResources {
    from("plugin.yml")
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}