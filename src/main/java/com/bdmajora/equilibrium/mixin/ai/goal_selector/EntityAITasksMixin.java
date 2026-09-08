package com.bdmajora.equilibrium.mixin.ai.goal_selector;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.entity.ai.EntityAITasks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

// Swaps the AI task/running sets for open-addressed ones; iterated every tick (running set) and every third tick (full set) per mob
// ObjectLinkedOpenHashSet preserves insertion order, which matters here since task priority is expressed by insertion order into taskEntries
@Mixin(EntityAITasks.class)
public class EntityAITasksMixin {
    @Shadow
    @Final
    @Mutable
    public Set<EntityAITasks.EntityAITaskEntry> taskEntries;

    @Shadow
    @Final
    @Mutable
    private Set<EntityAITasks.EntityAITaskEntry> executingTaskEntries;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$useFastutilSets(CallbackInfo ci) {
        this.taskEntries = new ObjectLinkedOpenHashSet<>();
        this.executingTaskEntries = new ObjectLinkedOpenHashSet<>();
    }
}
