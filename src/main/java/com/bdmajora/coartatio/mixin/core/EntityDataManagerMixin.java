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

/**
 * Compacts the synced-data map every entity carries.
 *
 * <p>From FoamFix's {@code FoamyArrayBackedDataManagerMap}. Vanilla gives each
 * {@code EntityDataManager} a {@code HashMap<Integer, DataEntry>} — boxed integer keys, a
 * {@code Node} per entry, a table allocated eagerly — for what is usually a handful of parameters.
 *
 * <p>{@code Int2ObjectOpenHashMap} implements {@code Map<Integer, V>} so the field type is
 * unchanged, but it stores keys as primitives in a flat array. There is one of these per entity in
 * the world, so the saving scales with entity count rather than with anything the player controls.
 */
@Mixin(EntityDataManager.class)
public abstract class EntityDataManagerMixin {
    @Mutable
    @Shadow
    @Final
    private Map<Integer, EntityDataManager.DataEntry<?>> entries;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactEntries(CallbackInfo ci) {
        this.entries = new Int2ObjectOpenHashMap<>();
    }
}
