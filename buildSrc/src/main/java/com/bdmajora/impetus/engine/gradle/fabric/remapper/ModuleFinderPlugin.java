package com.bdmajora.impetus.engine.gradle.fabric.remapper;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ModuleFinderPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().create("fabricApiModuleFinder", FabricApiModuleFinder.class);
    }
}
