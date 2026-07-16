plugins {
    `kotlin-dsl`
    `java-library`
    id("java-gradle-plugin") // so we can assign and ID to our plugin
}

dependencies {
    implementation("com.google.guava:guava:33.1.0-jre")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.3.0")
    implementation("dev.kikugie:stonecutter:0.7.11")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") }
        filter {
            includeGroupAndSubgroups("net.fabricmc")
        }
    }
    exclusiveContent {
        forRepository { maven("https://maven.kikugie.dev/releases") }
        filter {
            includeGroup("dev.kikugie")
        }
    }
}

gradlePlugin {
    plugins {
        register("impetus-fabric-remapper") {
            id = "impetus-fabric-remapper"
            implementationClass = "com.bdmajora.impetus.engine.gradle.fabric.remapper.RemapperPlugin"
        }
        // here we register our plugin with an ID
        register("impetus-fabric-module-finder") {
            id = "impetus-fabric-module-finder"
            implementationClass = "com.bdmajora.impetus.engine.gradle.fabric.remapper.ModuleFinderPlugin"
        }
    }
}
