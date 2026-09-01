plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-releases/")
    maven("https://repo.papermc.io/repository/maven-snapshots/")
}

dependencies {
    // NEW Paperweight 2.x DSL — correct for 26.x
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

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
