plugins {
    `java-library`
    `kotlin-dsl` // GenerateLWJGLAbstraction is Kotlin
    id("java-gradle-plugin") // so we can assign and ID to our plugin
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("net.fabricmc:access-widener:2.1.0")
    implementation("net.fabricmc:tiny-remapper:0.11.0")
    implementation("net.fabricmc:mapping-io:0.7.1")
    implementation("net.fabricmc:mapping-io-extras:0.7.1")
    implementation("net.neoforged:srgutils:1.0.0")
    // GenerateLWJGLAbstraction reads the LWJGL jars to derive the abstraction layer
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.neoforged.net/releases") }
        filter {
            includeGroup("net.neoforged")
        }
    }
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") }
        filter {
            includeGroup("net.fabricmc")
        }
    }
}

gradlePlugin {
    plugins {
        register("impetus-mdg-remapper") {
            id = "impetus-mdg-remapper"
            implementationClass = "com.bdmajora.impetus.engine.gradle.mdg.remapper.MDGRemapperPlugin"
        }
        register("impetus-lwjgl-abstraction") {
            id = "impetus-lwjgl-abstraction"
            implementationClass = "com.bdmajora.impetus.engine.gradle.task.LwjglAbstractionPlugin"
        }
    }
}