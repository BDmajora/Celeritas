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

// Swaps NBTTagCompound's HashMap for TagMap
// NBT is the most numerous object graph in a modded save: every item stack, tile entity, entity and packet
// payload. Vanilla gives each compound a HashMap — a 16-entry table allocated eagerly plus a Node per key —
// where nearly all of them hold four or five entries whose keys come from a tiny shared vocabulary
@Mixin(NBTTagCompound.class)
public class NBTTagCompoundMixin {
    @Mutable
    @Shadow
    @Final
    private Map<String, NBTBase> tagMap;

    // The field is assigned at its declaration, so the replacement lands at the end of the implicit no-arg
    // constructor and vanilla's map is garbage immediately. That wastes one HashMap allocation per compound,
    // which is the price of not rewriting the constructor with a @Redirect every other NBT mod would collide with
    // The descriptor pins this to the no-arg constructor specifically; the (Map) constructor is left alone
    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void coartatio$compactBackingMap(CallbackInfo ci) {
        this.tagMap = new TagMap();
    }
}
