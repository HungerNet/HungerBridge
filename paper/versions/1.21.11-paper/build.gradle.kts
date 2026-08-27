plugins {
    id("io.papermc.paperweight.userdev") version "1.7.1"
}

repositories {
    mavenCentral()
}

dependencies {
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:1.21.11-R0.1-20260511.115010-91")
    implementation(project(":common"))
}
