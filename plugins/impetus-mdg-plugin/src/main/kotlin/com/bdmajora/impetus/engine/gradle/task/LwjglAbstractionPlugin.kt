package com.bdmajora.impetus.engine.gradle.task

import org.gradle.api.Plugin
import org.gradle.api.Project

class LwjglAbstractionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("generateLWJGLAbstraction", GenerateLWJGLAbstraction::class.java)
    }
}
