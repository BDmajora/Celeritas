package com.bdmajora.coartatio.mixin.core;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.registry.RegistrySimple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

// Gives every RegistrySimple a compact backing map, from LoliASM's optimizeRegistries
// Vanilla builds one HashMap per registry through the createUnderlyingMap hook, which exists precisely so it
// can be overridden — so this needs no field shadow and no constructor injection, just a different return value
// Registries are numerous in a modded instance (blocks, items, sounds, biomes, potions, plus one per mod-added
// registry) and several are large. An open-addressed map removes the per-entry Node allocation from all of them
@Mixin(RegistrySimple.class)
public abstract class RegistrySimpleMixin<K, V> {
    // Cancelled at HEAD so vanilla's HashMap is never constructed at all
    @Inject(method = "createUnderlyingMap", at = @At("HEAD"), cancellable = true)
    private void coartatio$compactBackingMap(CallbackInfoReturnable<Map<K, V>> cir) {
        cir.setReturnValue(new Object2ObjectOpenHashMap<>());
    }
}
