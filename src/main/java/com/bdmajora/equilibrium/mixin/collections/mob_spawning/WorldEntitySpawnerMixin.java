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

/**
 * Swaps the eligible-chunk set for an open-addressed one.
 *
 * <p>Mob spawning rebuilds this set every spawn cycle by walking a 17×17 block of chunks around each
 * player, and probes it once per candidate chunk on the way. With several players online that is
 * hundreds of {@code contains} and {@code add} calls per cycle, each of which allocates or chases a
 * {@link java.util.HashSet} node.
 *
 * <p>{@code ChunkPos} defines {@code equals} and {@code hashCode}, and {@link ObjectOpenHashSet}
 * honours both identically. Iteration order differs from {@code HashSet}, but the spawner copies the
 * set into a list and shuffles it before use, so nothing downstream depends on it.
 */
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
