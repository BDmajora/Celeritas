plugins {
    `java-library`
    `kotlin-dsl` // GenerateLWJGLAbstraction is Kotlin
    id("java-gradle-plugin") // so we can assign an ID to our plugins
}

dependencies {
    // ReobfuscateCodeAndMixinsTask + TinyRemapperMappingsHelper
    implementation("net.fabricmc:tiny-remapper:0.11.0")
    implementation("net.fabricmc:mapping-io:0.7.1")
    implementation("net.fabricmc:mapping-io-extras:0.7.1")
    // GenerateLWJGLAbstraction reads the LWJGL jars to derive the abstraction layer
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") }
        filter {
            includeGroup("net.fabricmc")
        }
    }
}

gradlePlugin {
    plugins {
        // apply() is intentionally empty; applying the plugin is what puts
        // ReobfuscateCodeAndMixinsTask on the root build script's classpath.
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
