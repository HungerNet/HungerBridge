plugins {
    id("fabric-loom") version "1.7.4"
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":common"))
}
