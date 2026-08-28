plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT"
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    // Use the exact timestamped dev-bundle and force the ZIP artifact to ensure
    // the configuration contains a single file (Paperweight expects one file).
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:1.21.11-R0.1-20260511.115010-91@zip")
    implementation(project(":common"))
}
