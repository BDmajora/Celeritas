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

// Swaps NBTTagCompound's HashMap for TagMap; NBT is the most numerous object graph in a modded save
// Vanilla eagerly allocates a 16-entry table plus a Node per key for compounds that hold four or five entries
@Mixin(NBTTagCompound.class)
public class NBTTagCompoundMixin {
    // Replaced wholesale; TagMap keeps the same Map contract so every vanilla caller is unaffected
    @Mutable
    @Shadow
    @Final
    private Map<String, NBTBase> tagMap;

    // Field is assigned at its declaration, so vanilla's map is allocated then immediately discarded
    // That waste buys not fighting every other NBT mod over a @Redirect on the constructor
    // The descriptor pins this to the no-arg constructor; the (Map) one is left alone
    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void coartatio$compactBackingMap(CallbackInfo ci) {
        this.tagMap = new TagMap();
    }
}
