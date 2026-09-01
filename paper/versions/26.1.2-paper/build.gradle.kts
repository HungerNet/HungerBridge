plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
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
