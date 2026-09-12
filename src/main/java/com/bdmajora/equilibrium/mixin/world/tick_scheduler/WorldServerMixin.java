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

// Swaps the pending-tick dedup set for an open-addressed one, hot for redstone/liquids/crops; the ordered TreeSet is left alone since its insertion-order tie-break keeps redstone deterministic
@Mixin(WorldServer.class)
public class WorldServerMixin {
    @Shadow
    @Final
    @Mutable
    private Set<NextTickListEntry> pendingTickListEntriesHashSet;

    // Injected at ctor return rather than redirecting Sets.newHashSet() so a Forge patch reordering that call can't turn this into a no-op
    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$useFastutilSet(CallbackInfo ci) {
        this.pendingTickListEntriesHashSet = new ObjectOpenHashSet<>();
    }
}
