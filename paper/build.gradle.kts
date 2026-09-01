version = "hungerbridge-${stonecutter.current.minecraftVersion}-${stonecutter.current.loader}+${stonecutter.current.version}"

plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23" apply false
}

// Version-specific Paper configuration is defined under paper/versions/*.

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

// The root project is a thin wrapper; versioned subprojects apply Paperweight
// and configure the dev-bundle and `:common` dependency. Keep no sources here
// to avoid compiling without the dev-bundle.
sourceSets {
    named("main") {
        java.setSrcDirs(emptyList())
        resources.setSrcDirs(emptyList())
    }
}
