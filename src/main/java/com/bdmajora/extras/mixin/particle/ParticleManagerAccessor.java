package com.bdmajora.extras.mixin.particle;

import net.minecraft.client.particle.IParticleFactory;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// Exposes ParticleManager's private factory registry so per-class toggles are built from what's actually registered, not a hard-coded list
@Mixin(ParticleManager.class)
public interface ParticleManagerAccessor {
    @Accessor("particleTypes")
    Map<Integer, IParticleFactory> impetus$getParticleTypes();
}
