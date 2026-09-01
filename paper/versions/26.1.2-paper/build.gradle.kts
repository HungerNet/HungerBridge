plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

version = rootProject.version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-releases/")
}

dependencies {
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:26.1.2.build.74-stable@zip")
    implementation(project(":common"))
}

sourceSets {
    named("main") {
        java.srcDir(rootProject.file("paper/src/main/java"))
        resources.srcDir(rootProject.file("paper/src/main/resources"))
    }
}
