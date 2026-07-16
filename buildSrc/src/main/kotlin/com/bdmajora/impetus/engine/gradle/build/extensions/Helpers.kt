package com.bdmajora.impetus.engine.gradle.build.extensions

import bs.ModLoader
import org.gradle.api.Project

fun Project.versionedProperty(name: String): String? {
    return rootProject.properties["${name}_${ModLoader.getMinecraftVersion(project).replace('.', '_')}"]?.toString()
}