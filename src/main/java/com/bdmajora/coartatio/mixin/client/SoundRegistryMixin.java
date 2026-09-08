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

// SoundRegistry overrides RegistrySimple.createUnderlyingMap with its own HashMap, so
// RegistrySimpleMixin's compaction doesn't reach it - covered separately here.
// Must inject at RETURN, not HEAD: unlike RegistrySimple's bare "return new HashMap()", this
// override also assigns the map to the soundRegistry field, and clearMap() reads that field later.
// Cancelling at HEAD would skip the assignment and NPE on the first resource reload.
@Mixin(SoundRegistry.class)
public abstract class SoundRegistryMixin {
    @Shadow
    private Map<ResourceLocation, SoundEventAccessor> soundRegistry;

    @Inject(method = "createUnderlyingMap", at = @At("RETURN"), cancellable = true)
    private void coartatio$compactSoundMap(CallbackInfoReturnable<Map<ResourceLocation, SoundEventAccessor>> cir) {
        Map<ResourceLocation, SoundEventAccessor> compact = new Object2ObjectOpenHashMap<>();

        this.soundRegistry = compact;
        cir.setReturnValue(compact);
    }
}
