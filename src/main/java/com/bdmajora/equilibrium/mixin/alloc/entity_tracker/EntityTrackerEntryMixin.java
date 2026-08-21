package com.bdmajora.equilibrium.mixin.alloc.entity_tracker;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Swaps the tracked-player set for an open-addressed one.
 *
 * <p>There is one of these per tracked entity, and the set is iterated on every tick the entity moves
 * and probed on every tick it does not. {@link java.util.HashSet} allocates a node per entry and
 * scatters those nodes across the heap, so iterating even a handful of players is a handful of cache
 * misses; an open-addressed set keeps them in one array.
 *
 * <p>{@link ReferenceOpenHashSet} rather than {@code ObjectOpenHashSet} because
 * {@link EntityPlayerMP} does not override {@code equals} — identity already <em>is</em> equality
 * here, and saying so lets the set compare references rather than call through to {@code Object}.
 *
 * <p>The field is replaced at the end of the constructor rather than by redirecting the
 * {@code Sets.newHashSet()} call, so a Forge patch reordering that call site cannot silently turn
 * this into a no-op. Nothing is added to the set during construction.
 */
@Mixin(EntityTrackerEntry.class)
public class EntityTrackerEntryMixin {
    @Shadow
    @Final
    @Mutable
    public Set<EntityPlayerMP> trackingPlayers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$useFastutilSet(CallbackInfo ci) {
        this.trackingPlayers = new ReferenceOpenHashSet<>();
    }
}
