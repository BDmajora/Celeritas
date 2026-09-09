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

// Compacts the per-chunk-section entity lookup, from FoamFix's FoamyClassInheritanceMultiMap
// Every chunk holds sixteen of these, one per section, and each allocates a HashMap keyed by Class plus a
// HashSet of known keys whether or not the section ever contains an entity. Across a loaded world that is
// thousands of maps, most of them empty or nearly so
// Both are keyed by Class, which is canonical per classloader, so reference hashing is correct here as well as
// faster than Class.hashCode(). The class's own lookups already assume this — initializeClassLookup walks the
// map looking for an assignable key
// Not to be confused with Impetus' own ClassInheritanceMultiMapMixin, which overrides forEach to avoid an
// iterator allocation: two mixins on one target, doing unrelated things
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

    // At RETURN rather than HEAD so vanilla has already populated both collections; the copy constructors then
    // carry the existing contents across, and the original HashMap/HashSet become garbage immediately
    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactLookups(CallbackInfo ci) {
        this.map = new Reference2ObjectOpenHashMap<>(this.map);
        this.knownKeys = new ReferenceOpenHashSet<>(this.knownKeys);
    }
}
