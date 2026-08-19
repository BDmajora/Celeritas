package com.bdmajora.coartatio.mixin.nbt;

import com.bdmajora.coartatio.nbt.TagMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Swaps {@code NBTTagCompound}'s {@code HashMap} for {@link TagMap}.
 *
 * <p>NBT is the single most numerous object graph in a modded save: every item stack, every tile
 * entity, every entity, every packet payload. Vanilla gives each compound a {@code HashMap} — a
 * 16-entry table allocated eagerly plus a {@code Node} per key — where almost all of them hold four
 * or five entries whose keys are drawn from a tiny shared vocabulary.
 *
 * <p>The field is assigned at its declaration, so the replacement happens at the end of the
 * (implicit, no-arg) constructor and the original map is immediately garbage. That costs one wasted
 * {@code HashMap} allocation per compound, which is the price of not rewriting the constructor with
 * a {@code @Redirect} that every other NBT mod would then conflict with.
 */
@Mixin(NBTTagCompound.class)
public class NBTTagCompoundMixin {
    @Mutable
    @Shadow
    @Final
    private Map<String, NBTBase> tagMap;

    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void coartatio$compactBackingMap(CallbackInfo ci) {
        this.tagMap = new TagMap();
    }
}
