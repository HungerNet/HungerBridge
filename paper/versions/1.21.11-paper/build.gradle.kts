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
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:1.21.11-R0.1-20260511.115010-91@zip")
    implementation(project(":common"))
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
