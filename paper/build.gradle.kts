plugins {
    id("java")
}

// Version-specific Paper configuration is defined under paper/versions/*.
// Keep the root project as a thin wrapper to avoid applying Paperweight twice.

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")
}

// The actual Paper sources are compiled inside the versioned subprojects
// so that they inherit the Paper dev-bundle and `:common` on their classpath.
// Keep the root project with no sources to avoid compiling without the
// dev-bundle (CI activates versioned projects via Stonecutter).
sourceSets {
    named("main") {
        java.setSrcDirs(emptyList())
        resources.setSrcDirs(emptyList())
    }
}
