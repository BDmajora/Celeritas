package com.bdmajora.coarctatio.mixin.core;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.registry.RegistrySimple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

// Gives every RegistrySimple a compact backing map (LoliASM's optimizeRegistries) by overriding the createUnderlyingMap hook, dropping per-entry Nodes across every modded registry
@Mixin(RegistrySimple.class)
public abstract class RegistrySimpleMixin<K, V> {
    // Cancelled at HEAD so vanilla's HashMap is never constructed at all
    @Inject(method = "createUnderlyingMap", at = @At("HEAD"), cancellable = true)
    private void coarctatio$compactBackingMap(CallbackInfoReturnable<Map<K, V>> cir) {
        cir.setReturnValue(new Object2ObjectOpenHashMap<>());
    }
}
