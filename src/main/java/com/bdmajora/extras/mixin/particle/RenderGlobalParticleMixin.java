package com.bdmajora.extras.mixin.particle;

import com.bdmajora.extras.client.particle.ParticleFilters;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OptiFine's named particle switches, applied where OptiFine applies them: at the point the particle
 * id is still known and before the particle object exists.
 *
 * <p>This has to be id-keyed rather than class-keyed. {@code ParticleSuspendedTown} alone backs
 * {@code SUSPENDED_DEPTH} (the void particles), {@code TOWN_AURA} and {@code VILLAGER_HAPPY}, so
 * filtering by class would silently take the villagers' happy particles out with the void ones. The
 * class-keyed filter in {@link ParticleManagerMixin} still runs afterwards for modded particles,
 * which have no id worth reasoning about.
 *
 * <p>{@code spawnParticle0} is overloaded — the three-boolean form is the one that actually builds
 * the particle; the shorter one delegates to it — so the descriptor is spelled out in full.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalParticleMixin {
    @Inject(
            method = "spawnParticle0(IZZDDDDDD[I)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$filterParticle(int particleId, boolean ignoreRange, boolean minimal,
                                        double x, double y, double z,
                                        double xSpeed, double ySpeed, double zSpeed,
                                        int[] parameters, CallbackInfoReturnable<Particle> cir) {
        if (!ParticleFilters.isAllowed(particleId)) {
            // Vanilla's own "not spawned" answer; every caller already handles null.
            cir.setReturnValue(null);
        }
    }
}
