package com.bdmajora.coartatio.mixin.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.datasync.EntityDataManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// Compacts the synced-data map every entity carries, from FoamFix's FoamyArrayBackedDataManagerMap
// Int2ObjectOpenHashMap still implements Map<Integer, V> so callers are unchanged, but drops the boxing,
// the per-entry Node and the eager table; one per entity, so it scales with entity count
@Mixin(EntityDataManager.class)
public abstract class EntityDataManagerMixin {
    // Declared as Map<Integer, ...>, which the primitive-keyed replacement still satisfies
    @Mutable
    @Shadow
    @Final
    private Map<Integer, EntityDataManager.DataEntry<?>> entries;

    // Replaced rather than copied: vanilla's constructor leaves the map empty, so there is nothing to carry over
    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactEntries(CallbackInfo ci) {
        this.entries = new Int2ObjectOpenHashMap<>();
    }
}
