package com.bdmajora.extras.mixin.particle;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.extras.client.particle.ParticleClassRegistry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.IParticleFactory;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The catch-all particle gate (block-break/hit switches, master switch, per-class filter); every particle reaches addEffect, which is what makes per-class toggles work for mods, while id-keyed switches live in RenderGlobalParticleMixin
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    // Records which mod registered each factory while its container is active, the only reliable attribution for lambda/anonymous factories
    @Inject(method = "registerParticle", at = @At("HEAD"))
    private void impetus$captureFactoryMod(int id, IParticleFactory particleFactory, CallbackInfo ci) {
        if (particleFactory == null) {
            return;
        }

        String modId = null;
        try {
            ModContainer container = Loader.instance().activeModContainer();
            if (container != null) {
                modId = container.getModId();
            }
        } catch (Throwable t) {
            // Attribution is a nicety; never let it break particle registration.
        }

        ParticleClassRegistry.getInstance().registerFactoryMod(particleFactory, modId);
    }

    @Inject(method = "addBlockDestroyEffects", at = @At("HEAD"), cancellable = true)
    private void impetus$blockDestroyEffects(BlockPos pos, IBlockState state, CallbackInfo ci) {
        ExtrasConfig.ParticleSettings settings = Extras.options().particle;
        if (!settings.all || !settings.blockBreak) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addBlockHitEffects(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$blockHitEffects(BlockPos pos, EnumFacing side, CallbackInfo ci) {
        ExtrasConfig.ParticleSettings settings = Extras.options().particle;
        if (!settings.all || !settings.blockBreaking) {
            ci.cancel();
        }
    }

    // Records the class and applies the master and per-class filters; recordClass is identity-guarded and isEmptyDisabled short-circuits the no-filter case
    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void impetus$filterEffect(Particle effect, CallbackInfo ci) {
        if (effect == null) {
            return;
        }

        ParticleClassRegistry registry = ParticleClassRegistry.getInstance();
        registry.recordClass(effect.getClass());

        if (!Extras.options().particle.all) {
            ci.cancel();
            return;
        }

        if (!registry.isEmptyDisabled() && registry.isClassDisabled(effect.getClass().getName())) {
            ci.cancel();
        }
    }
}
