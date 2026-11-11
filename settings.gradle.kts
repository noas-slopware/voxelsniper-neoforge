rootProject.name = "VoxelSniper"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}

include("VoxelSniperCore")
include("VoxelSniperSpigot")
include("VoxelSniperNeoForge")
//include("VoxelSniperFabric")
