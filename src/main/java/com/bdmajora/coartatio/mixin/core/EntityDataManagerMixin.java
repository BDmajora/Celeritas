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
// Vanilla gives each EntityDataManager a HashMap<Integer, DataEntry>: boxed integer keys, a Node per entry and
// an eagerly allocated table, for what is usually a handful of parameters
// Int2ObjectOpenHashMap still implements Map<Integer, V>, so the field type and every caller are unchanged, but
// it stores the keys as primitives in a flat array
// There is one per entity in the world, so the saving scales with entity count rather than with anything the
// player controls
@Mixin(EntityDataManager.class)
public abstract class EntityDataManagerMixin {
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
