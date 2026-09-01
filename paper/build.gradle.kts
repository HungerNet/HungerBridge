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

// Normalize STONECUTTER_ACTIVE the same way as settings.gradle.kts so the
// root project can apply the correct dev-bundle when Paper is active.
val raw = System.getenv("STONECUTTER_ACTIVE") ?: "__none__"

val active = when {
    raw.contains("paper") -> {
        val mc = raw.filter { it.isDigit() || it == '.' }
        if (mc.isNotBlank()) "${'$'}mc-paper" else "__none__"
    }
    raw.contains("fabric") -> {
        val mc = raw.filter { it.isDigit() || it == '.' }
        if (mc.isNotBlank()) "${'$'}mc-fabric" else "__none__"
    }
    raw.all { it.isDigit() || it == '.' } -> "${'$'}raw-paper"
    else -> "__none__"
}

if (active.endsWith("-paper")) {
    apply(plugin = "io.papermc.paperweight.userdev")

    // Map normalized active keys to the correct dev-bundle coordinates present
    // in this repository's versioned builds.
    val devBundle = when {
        active.startsWith("1.21.11") -> "io.papermc.paper:dev-bundle:1.21.11-R0.1-20260511.115010-91@zip"
        active.startsWith("26.1.2") -> "io.papermc.paper:dev-bundle:26.1.2.build.74-stable"
        active.startsWith("26.2") -> "io.papermc.paper:dev-bundle:26.2.build.119-stable"
        else -> throw GradleException("Unknown Paper active version: ${'$'}active")
    }

    dependencies {
        paperweightDevelopmentBundle(devBundle)
        implementation(project(":common"))
        compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")
    }

    // Compile the shared `paper/src` sources inside the root project when
    // Paper is active so sources see the dev-bundle on the classpath.
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
} else {
    // When Paper is not active, keep the root project with no sources so it
    // doesn't compile without the dev-bundle.
    sourceSets {
        named("main") {
            java.setSrcDirs(emptyList())
            resources.setSrcDirs(emptyList())
        }
    }
}
