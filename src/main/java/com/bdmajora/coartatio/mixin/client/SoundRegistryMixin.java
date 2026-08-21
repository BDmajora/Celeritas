package com.bdmajora.coartatio.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.audio.SoundRegistry;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * The one registry {@link com.bdmajora.coartatio.mixin.core.RegistrySimpleMixin} does not reach.
 *
 * <p>From LoliASM. {@code SoundRegistry} extends {@code RegistrySimple} but overrides
 * {@code createUnderlyingMap} with its own {@code HashMap}, so compacting the base class has no
 * effect on it — easy to miss, since every other registry in the game is covered by the one mixin.
 *
 * <p>It is also one of the larger registries in a modded instance: one entry per sound event, and
 * sound-heavy packs run to tens of thousands.
 *
 * <h2>Why this injects at RETURN and not HEAD</h2>
 *
 * <p>{@code RegistrySimple}'s version is a bare {@code return Maps.newHashMap()}, so cancelling it at
 * {@code HEAD} is harmless. This override is not — it keeps a <b>second reference</b> to the map it
 * creates:
 *
 * <pre>
 * protected Map&lt;...&gt; createUnderlyingMap() {
 *     this.soundRegistry = Maps.newHashMap();
 *     return this.soundRegistry;
 * }
 * public void clearMap() { this.soundRegistry.clear(); }
 * </pre>
 *
 * <p>Cancelling at {@code HEAD} hands a compact map to the caller but never runs that assignment,
 * leaving {@code soundRegistry} null. {@code clearMap} is called on the first resource reload, so the
 * game died with a {@code NullPointerException} before reaching the main menu.
 *
 * <p>Injecting at {@code RETURN} and rewriting both the field and the return value keeps the two
 * references pointing at the same map, which is the invariant the class is built on. The
 * {@code HashMap} vanilla allocated a moment earlier is garbage immediately, which costs one
 * short-lived object per registry — the price of not having to reimplement the method.
 */
@Mixin(SoundRegistry.class)
public abstract class SoundRegistryMixin {
    @Shadow
    private Map<ResourceLocation, SoundEventAccessor> soundRegistry;

    @Inject(method = "createUnderlyingMap", at = @At("RETURN"), cancellable = true)
    private void coartatio$compactSoundMap(CallbackInfoReturnable<Map<ResourceLocation, SoundEventAccessor>> cir) {
        Map<ResourceLocation, SoundEventAccessor> compact = new Object2ObjectOpenHashMap<>();

        this.soundRegistry = compact;
        cir.setReturnValue(compact);
    }
}
