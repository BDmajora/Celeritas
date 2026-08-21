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

/**
 * Swaps the AI task sets for open-addressed ones.
 *
 * <p>Every mob has two of these — one selector for goals and one for targeting — and each holds two
 * sets: all its tasks, and the subset currently running. {@code onUpdateTasks} iterates the running
 * set every tick and the full set every third tick, for every mob within simulation range. A
 * {@link java.util.LinkedHashSet} puts each task behind its own node with forward and backward links,
 * so walking twenty mobs' worth of goals is a walk through a hundred scattered objects.
 *
 * <p>{@link ObjectLinkedOpenHashSet} keeps the same insertion-ordered iteration — which matters, as
 * task priority in 1.12.2 is expressed by insertion order into {@code taskEntries} and reordering it
 * would change which goal wins a tie — while storing the entries in one array.
 *
 * <p>This is not what Lithium's {@code ai} package does, because it cannot be: that package rewrites
 * the brain-and-sensor system introduced in 1.14, which does not exist here. 1.12.2's selector is
 * already frugal by comparison — it re-evaluates on a three-tick cadence and keeps its own running
 * set — so what is left to take is the allocation, not the algorithm.
 */
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
