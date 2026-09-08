package com.bdmajora.extras.mixin.particle;

import com.bdmajora.extras.client.particle.ParticleFilters;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// OptiFine's named particle switches, applied at the point the particle id is still known and before the particle object exists
// Must be id-keyed, not class-keyed: ParticleSuspendedTown alone backs SUSPENDED_DEPTH, TOWN_AURA and VILLAGER_HAPPY,
// so filtering by class would take villager happy particles out along with the void ones
// spawnParticle0 is overloaded; full descriptor targets the form that actually builds the particle
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
