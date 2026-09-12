package com.bdmajora.coarctatio.mixin.core;

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

// Compacts the per-section entity lookup (FoamFix's FoamyClassInheritanceMultiMap): sixteen per chunk, each eagerly allocating a HashMap and HashSet; Class keys are canonical so reference hashing is correct and faster. Unrelated to Impetus' own forEach mixin on the same target
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

    // At RETURN so vanilla has populated both collections; the copy constructors carry the contents across and the originals become garbage immediately
    @Inject(method = "<init>", at = @At("RETURN"))
    private void coarctatio$compactLookups(CallbackInfo ci) {
        this.map = new Reference2ObjectOpenHashMap<>(this.map);
        this.knownKeys = new ReferenceOpenHashSet<>(this.knownKeys);
    }
}
