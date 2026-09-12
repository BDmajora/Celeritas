package com.bdmajora.coartatio.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.audio.SoundRegistry;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

// SoundRegistry overrides createUnderlyingMap with its own HashMap, so RegistrySimpleMixin misses it
// Injected at RETURN because the override also assigns soundRegistry; cancelling at HEAD NPEs on reload
@Mixin(SoundRegistry.class)
public abstract class SoundRegistryMixin {
    // Read back by clearMap, so it has to point at the same instance that is returned
    @Shadow
    private Map<ResourceLocation, SoundEventAccessor> soundRegistry;

    // Replaces both the field and the return value so the two never diverge
    @Inject(method = "createUnderlyingMap", at = @At("RETURN"), cancellable = true)
    private void coartatio$compactSoundMap(CallbackInfoReturnable<Map<ResourceLocation, SoundEventAccessor>> cir) {
        Map<ResourceLocation, SoundEventAccessor> compact = new Object2ObjectOpenHashMap<>();

        this.soundRegistry = compact;
        cir.setReturnValue(compact);
    }
}
