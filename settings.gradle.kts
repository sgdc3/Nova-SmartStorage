rootProject.name = "smartstorage"

dependencyResolutionManagement {
    repositories {
        // Simple Upgrades, which xenondevs' own Maven still has at a version built against Nova ~0.19.
        // Scoped to its group so nothing else is ever looked up here.
        maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.xenondevs.xyz/releases/")
    }
    versionCatalogs {
        create("libs") {
            from("xyz.xenondevs.nova:catalog:0.24.0")
        }
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.xenondevs.xyz/releases/")
    }
}
