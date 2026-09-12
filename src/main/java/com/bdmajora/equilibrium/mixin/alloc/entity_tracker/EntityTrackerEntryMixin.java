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

// Swaps the tracked-player set for an open-addressed one, one per tracked entity iterated every tick it moves; ReferenceOpenHashSet since EntityPlayerMP does not override equals
@Mixin(EntityTrackerEntry.class)
public class EntityTrackerEntryMixin {
    @Shadow
    @Final
    @Mutable
    public Set<EntityPlayerMP> trackingPlayers;

    // Replaced at ctor return rather than redirecting Sets.newHashSet() so a Forge patch reordering that call can't silently no-op this
    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$useFastutilSet(CallbackInfo ci) {
        this.trackingPlayers = new ReferenceOpenHashSet<>();
    }
}
