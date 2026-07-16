package com.bdmajora.impetus.engine.gradle.build.conventions;

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Project;
import org.gradle.jvm.tasks.Jar;

import java.util.List;

public class ShadowHelper {
    public static void createShadowRemapJar(Project project) {
        createShadowRemapJar(project, "remapJar");
    }

    public static void createShadowRemapJar(Project project, String remapJarTaskName) {
        project.getTasks().named("shadowJar").configure(task -> {
            if (task instanceof ShadowJar shadowJar) {
                shadowJar.getConfigurations().set(List.of());
            }
        });
        project.getTasks().register("shadowRemapJar", ShadowJar.class).configure(shadowJar -> {
            shadowJar.getArchiveClassifier().set("");
            shadowJar.getConfigurations().set(List.of(project.getConfigurations().getByName("shadow")));
            shadowJar.from(project.zipTree(((Jar)project.getTasks().named(remapJarTaskName).get()).getArchiveFile()));
            shadowJar.getManifest().inheritFrom(((Jar)project.getTasks().getByName("jar")).getManifest());
            shadowJar.relocate("org.joml", "com.bdmajora.impetus.engine.impl.shadow.joml");
            shadowJar.mergeServiceFiles();

            shadowJar.from("COPYING", "COPYING.LESSER", "README.md");
        });
    }
}
