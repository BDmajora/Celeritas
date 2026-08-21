package com.bdmajora.equilibrium.mixin.world.tick_scheduler;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Swaps the pending-tick set for an open-addressed one.
 *
 * <p>Every scheduled block tick — every redstone component that retriggers, every flowing liquid,
 * every growing crop — is a {@link NextTickListEntry} that gets looked up in this set to reject
 * duplicates, added to it, and removed from it a moment later. {@link java.util.HashSet} allocates a
 * node for each of those and puts the entry behind a pointer, so the duplicate check that runs before
 * every schedule is a cache miss.
 *
 * <p>An {@link ObjectOpenHashSet} stores the entries in one array and compares them in place. The
 * semantics are unchanged: {@code NextTickListEntry} defines {@code equals} and {@code hashCode} on
 * its position and block, and both sets honour them identically.
 *
 * <p>Deliberately not touched: the {@code TreeSet} that orders the pending ticks. Its ordering is
 * defined by {@code compareTo} over scheduled time, priority and a monotonically increasing entry id,
 * and that tie-break by insertion order is what makes redstone deterministic. Packing those three
 * fields into a long — which is what Lithium does on modern versions — is possible here too, but it
 * would have to reproduce the ordering exactly to be worth doing, and the entry id alone is a 64-bit
 * counter. The allocation this removes is the part that was actually costing anything.
 */
@Mixin(WorldServer.class)
public class WorldServerMixin {
    @Shadow
    @Final
    @Mutable
    private Set<NextTickListEntry> pendingTickListEntriesHashSet;

    /**
     * Replaced at the end of the constructor rather than by redirecting {@code Sets.newHashSet()},
     * so that a Forge patch moving that call cannot silently turn this into a no-op. Nothing is
     * scheduled during world construction.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$useFastutilSet(CallbackInfo ci) {
        this.pendingTickListEntriesHashSet = new ObjectOpenHashSet<>();
    }
}
