package com.bdmajora.extras.mixin.particle;

import net.minecraft.client.particle.IParticleFactory;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes {@code ParticleManager}'s private factory registry so the per-class toggles can be built
 * from what is actually registered rather than a hard-coded list.
 */
@Mixin(ParticleManager.class)
public interface ParticleManagerAccessor {
    @Accessor("particleTypes")
    Map<Integer, IParticleFactory> impetus$getParticleTypes();
}
