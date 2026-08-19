package com.bdmajora.coartatio.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.audio.SoundRegistry;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * The one registry {@link com.bdmajora.coartatio.mixin.core.RegistrySimpleMixin} does not reach.
 *
 * <p>From LoliASM. {@code SoundRegistry} extends {@code RegistrySimple} but overrides
 * {@code createUnderlyingMap} with its own {@code HashMap}, so compacting the base class has no
 * effect on it — easy to miss, since every other registry in the game is covered by the one mixin.
 *
 * <p>It is also one of the larger registries in a modded instance: one entry per sound event, and
 * sound-heavy packs run to tens of thousands.
 */
@Mixin(SoundRegistry.class)
public abstract class SoundRegistryMixin {
    @Inject(method = "createUnderlyingMap", at = @At("HEAD"), cancellable = true)
    private void coartatio$compactSoundMap(CallbackInfoReturnable<Map<ResourceLocation, SoundEventAccessor>> cir) {
        cir.setReturnValue(new Object2ObjectOpenHashMap<>());
    }
}
