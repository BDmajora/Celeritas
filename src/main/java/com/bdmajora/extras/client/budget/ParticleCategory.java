package com.bdmajora.extras.client.budget;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleDigging;
import net.minecraft.client.particle.ParticleRain;

import java.util.Locale;

// What the particle budget may refuse: only COSMETIC; weather is gameplay feedback and block particles tell you a block broke, so both always spawn
public enum ParticleCategory {
    COSMETIC,
    WEATHER,
    CRITICAL;

    // Vanilla classes are matched by type; modded ones by name tokens up the hierarchy, which is how Pretty Rain, FBP and the like are recognised without a dependency. Block tokens go first and weather tokens read the simple name only, so "TerrainParticle" is block-like and a package named after rain protects nothing
    static ParticleCategory classify(Class<? extends Particle> type) {
        if (ParticleRain.class.isAssignableFrom(type)) {
            return WEATHER;
        }
        if (ParticleDigging.class.isAssignableFrom(type)) {
            return CRITICAL;
        }

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String name = current.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("com.leclowndu93150.particlerain.particle.")) {
                return WEATHER;
            }
            if (name.startsWith("com.tominocz.fbp.") || name.startsWith("hantonik.fbp.") || hasBlockToken(name)) {
                return CRITICAL;
            }
        }

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String simple = current.getSimpleName().toLowerCase(Locale.ROOT);
            if (simple.contains("rain") || simple.contains("snow") || simple.contains("weather")) {
                return WEATHER;
            }
        }

        return COSMETIC;
    }

    private static boolean hasBlockToken(String name) {
        return name.contains("terrainparticle") || name.contains("blockparticle")
                || name.contains("blockdust") || name.contains("blockstate")
                || name.contains("blockbreak") || name.contains("fancyblock") || name.contains("digging");
    }
}
