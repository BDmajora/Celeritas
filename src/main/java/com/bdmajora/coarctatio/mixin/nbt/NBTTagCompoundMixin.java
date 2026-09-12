package com.bdmajora.coarctatio.mixin.nbt;

import com.bdmajora.coarctatio.nbt.TagMap;
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

// Swaps NBTTagCompound's HashMap for TagMap; NBT is the most numerous object graph in a modded save and vanilla eagerly allocates a 16-entry table plus a Node per key for four-entry compounds
@Mixin(NBTTagCompound.class)
public class NBTTagCompoundMixin {
    // Replaced wholesale; TagMap keeps the same Map contract so every vanilla caller is unaffected
    @Mutable
    @Shadow
    @Final
    private Map<String, NBTBase> tagMap;

    // The field is assigned at declaration so vanilla's map is allocated then discarded; that waste buys not fighting other NBT mods over a constructor @Redirect, and the descriptor pins the no-arg ctor
    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void coarctatio$compactBackingMap(CallbackInfo ci) {
        this.tagMap = new TagMap();
    }
}
