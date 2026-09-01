// Artifact version derived from Stonecutter
version = "hungerbridge-${'$'}{stonecutter.current.minecraftVersion}-${'$'}{stonecutter.current.loader}+${'$'}{stonecutter.current.version}"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

// Versioned projects are thin wrappers; the root project compiles the shared
// `paper/src` sources when Paper is active so compilation happens with the
// dev-bundle and `:common` on the classpath.
