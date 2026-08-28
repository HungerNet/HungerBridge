plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT"
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:26.2.build.119-stable")
    implementation(project(":common"))
}
