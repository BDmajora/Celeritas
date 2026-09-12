package com.bdmajora.coarctatio.mixin.world;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Nulls out chunk sections holding nothing (Hydrogen's MixinWorldChunk): readChunkFromNBT allocates ~10 KB of storage even for mined-out all-air sections, and Chunk already treats a null section as air
@Mixin(Chunk.class)
public abstract class ChunkMixin {
    // At RETURN so vanilla has finished assigning the array and the sections are fully populated
    @Inject(method = "setStorageArrays", at = @At("RETURN"))
    private void coarctatio$dropEmptySections(ExtendedBlockStorage[] sections, CallbackInfo ci) {
        if (sections == null) {
            return;
        }

        for (int i = 0; i < sections.length; i++) {
            ExtendedBlockStorage section = sections[i];

            if (section != null && section.isEmpty() && coarctatio$hasReproducibleLight(section)) {
                sections[i] = Chunk.NULL_BLOCK_STORAGE;
            }
        }
    }

    // isEmpty() says nothing about light, and without a section getLightFor falls back to canSeeSky() ? default : 0; so unlike Hydrogen a section is dropped only when its light is uniform AND matches that fallback (block light zero, sky absent/zero/fifteen)
    private static boolean coarctatio$hasReproducibleLight(ExtendedBlockStorage section) {
        if (!coarctatio$isUniform(section.getBlockLight(), 0)) {
            return false;
        }

        NibbleArray skyLight = section.getSkyLight();

        return skyLight == null
                || coarctatio$isUniform(skyLight, 0)
                || coarctatio$isUniform(skyLight, 15);
    }

    // True when every nibble equals value; a null array counts as uniform since no stored light contradicts the fallback
    private static boolean coarctatio$isUniform(NibbleArray array, int value) {
        if (array == null) {
            return true;
        }

        // Two nibbles per byte, so the value is duplicated into both halves and the array is compared a byte at a time
        byte expected = (byte) ((value & 0xF) | ((value & 0xF) << 4));

        for (byte b : array.getData()) {
            if (b != expected) {
                return false;
            }
        }

        return true;
    }
}
