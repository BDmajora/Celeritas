package com.bdmajora.coarctatio.mixin.core;

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

// Compacts the synced-data map every entity carries (FoamFix's FoamyArrayBackedDataManagerMap); Int2ObjectOpenHashMap still implements Map<Integer, V> but drops boxing, Nodes and the eager table
@Mixin(EntityDataManager.class)
public abstract class EntityDataManagerMixin {
    // Declared as Map<Integer, ...>, which the primitive-keyed replacement still satisfies
    @Mutable
    @Shadow
    @Final
    private Map<Integer, EntityDataManager.DataEntry<?>> entries;

    // Replaced rather than copied: vanilla's constructor leaves the map empty, so there is nothing to carry over
    @Inject(method = "<init>", at = @At("RETURN"))
    private void coarctatio$compactEntries(CallbackInfo ci) {
        this.entries = new Int2ObjectOpenHashMap<>();
    }
}
