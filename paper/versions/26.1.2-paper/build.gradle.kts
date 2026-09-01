plugins {
    id("io.papermc.paperweight.experimental") version "2.0.0-beta.23"
}

// Artifact version derived from Stonecutter
version = "hungerbridge-${'$'}{stonecutter.current.minecraftVersion}-${'$'}{stonecutter.current.loader}+${'$'}{stonecutter.current.version}"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:26.1.2.build.74-stable")
    implementation(project(":common"))
}

// Versioned project compiles the shared root `paper/src` sources so they are
// compiled with the dev-bundle and `:common` on the classpath.
sourceSets {
    named("main") {
        java {
            srcDir(rootProject.file("paper/src/main/java"))
        }
        resources {
            srcDir(rootProject.file("paper/src/main/resources"))
        }
    }
}
