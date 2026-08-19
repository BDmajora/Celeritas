package com.bdmajora.coartatio.mixin.core;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ClassInheritanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compacts the per-chunk-section entity lookup.
 *
 * <p>From FoamFix's {@code FoamyClassInheritanceMultiMap}. Every chunk holds sixteen of these — one
 * per section — and each allocates a {@code HashMap} keyed by {@code Class} plus a {@code HashSet}
 * of known keys, whether or not the section ever contains an entity. Across a loaded world that is
 * thousands of maps that are usually empty or near-empty.
 *
 * <p>Both are keyed by {@code Class}, which is canonical per classloader, so reference hashing is
 * both correct and faster than {@code Class.hashCode()}. Note the class's own lookups already rely
 * on this: {@code initializeClassLookup} walks the map looking for an assignable key.
 *
 * <p>Distinct from Impetus' existing {@code ClassInheritanceMultiMapMixin}, which overrides
 * {@code forEach} to avoid an iterator allocation. Two mixins on one class, doing unrelated things.
 */
@Mixin(ClassInheritanceMultiMap.class)
public abstract class ClassInheritanceMultiMapMixin<T> {
    @Mutable
    @Shadow
    @Final
    private Map<Class<?>, List<T>> map;

    @Mutable
    @Shadow
    @Final
    private Set<Class<?>> knownKeys;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactLookups(CallbackInfo ci) {
        this.map = new Reference2ObjectOpenHashMap<>(this.map);
        this.knownKeys = new ReferenceOpenHashSet<>(this.knownKeys);
    }
}
