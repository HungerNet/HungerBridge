version = "hb-${stonecutter.current.loader}-${stonecutter.current.minecraftVersion}+${stonecutter.current.version}"

plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23" apply false
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList())
        resources.setSrcDirs(emptyList())
    }
}
