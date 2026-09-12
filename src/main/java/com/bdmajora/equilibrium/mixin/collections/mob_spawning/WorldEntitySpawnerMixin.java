package com.bdmajora.equilibrium.mixin.collections.mob_spawning;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldEntitySpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

// Swaps the eligible-chunk set for an open-addressed one, rebuilt every spawn cycle over 17x17 chunks per player; iteration order differs but the spawner copies and shuffles before use
@Mixin(WorldEntitySpawner.class)
public class WorldEntitySpawnerMixin {
    @Shadow
    @Final
    @Mutable
    private Set<ChunkPos> eligibleChunksForSpawning;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$useFastutilSet(CallbackInfo ci) {
        this.eligibleChunksForSpawning = new ObjectOpenHashSet<>();
    }
}
