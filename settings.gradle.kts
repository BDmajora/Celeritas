pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        exclusiveContent {
            forRepository { maven("https://maven.neoforged.net/releases") }
            filter {
                includeGroup("net.neoforged")
            }
        }
        exclusiveContent {
            forRepository { maven("https://maven.fabricmc.net/") }
            filter {
                includeGroupAndSubgroups("net.fabricmc")
                includeGroup("fabric-loom")
            }
        }
        exclusiveContent {
            forRepository { maven("https://maven.taumc.org/releases") }
            filter {
                includeGroupAndSubgroups("org.taumc")
            }
        }
        exclusiveContent {
            forRepository { maven("https://nexus.gtnewhorizons.com/repository/public/") }
            filter {
                includeGroupAndSubgroups("com.gtnewhorizons")
            }
        }
        maven("https://maven.minecraftforge.net/") {
            content {
                includeGroupAndSubgroups("net.minecraftforge")
            }
        }
        maven {
            name = "sponge"
            url = uri("https://repo.spongepowered.org/maven-public/")
            content {
                includeGroupAndSubgroups("org.spongepowered")
            }
        }
    }

    plugins {
        id("org.taumc.gradle.versioning") version(extra["taugradle_version"].toString())
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
}

rootProject.name = "impetus"

includeBuild("plugins/impetus-mdg-plugin")
include("common")
