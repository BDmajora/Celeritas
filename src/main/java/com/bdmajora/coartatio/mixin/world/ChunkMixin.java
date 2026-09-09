package com.bdmajora.coartatio.mixin.world;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Drops chunk sections that hold nothing. Hydrogen's MixinWorldChunk, retargeted
// AnvilChunkLoader.readChunkFromNBT allocates an ExtendedBlockStorage for every section present on disk,
// including ones whose blocks have all since been mined out — removeInvalidBlocks() resets the reference count
// but keeps the storage. That storage is a 4096-entry block state container plus two nibble arrays, on the
// order of ten kilobytes, for a section that is entirely air
// Chunk already treats a null section as air: NULL_BLOCK_STORAGE IS null, getBlockState returns air for it, and
// setBlockState allocates one on demand, so the fix is just to null them out
@Mixin(Chunk.class)
public abstract class ChunkMixin {
    // At RETURN so vanilla has finished assigning the array and the sections are fully populated
    @Inject(method = "setStorageArrays", at = @At("RETURN"))
    private void coartatio$dropEmptySections(ExtendedBlockStorage[] sections, CallbackInfo ci) {
        if (sections == null) {
            return;
        }

        for (int i = 0; i < sections.length; i++) {
            ExtendedBlockStorage section = sections[i];

            if (section != null && section.isEmpty() && coartatio$hasReproducibleLight(section)) {
                sections[i] = Chunk.NULL_BLOCK_STORAGE;
            }
        }
    }

    // isEmpty() only counts blocks and says nothing about light, which is why this extra check exists
    // With no section, getLightFor falls back to canSeeSky() ? default : 0, so nulling a section whose stored
    // light that rule cannot reproduce would darken or brighten it until the next relight — a visible bug in
    // caves and under overhangs
    // Hydrogen nulls on emptiness alone. Here a section is dropped only when its light is uniform AND matches
    // what the fallback would produce anyway: block light all zero, sky light absent, all zero, or all fifteen
    // That keeps every ambiguous section and still catches the dominant cases — air above the terrain, and
    // mined-out volumes below it
    private static boolean coartatio$hasReproducibleLight(ExtendedBlockStorage section) {
        if (!coartatio$isUniform(section.getBlockLight(), 0)) {
            return false;
        }

        NibbleArray skyLight = section.getSkyLight();

        return skyLight == null
                || coartatio$isUniform(skyLight, 0)
                || coartatio$isUniform(skyLight, 15);
    }

    // True when every nibble in the array equals value; a null array counts as uniform, since there is no stored
    // light to contradict the fallback
    private static boolean coartatio$isUniform(NibbleArray array, int value) {
        if (array == null) {
            return true;
        }

        // Two nibbles per byte, so the target value is duplicated into both halves and the whole array can be
        // compared a byte at a time instead of unpacking each nibble
        byte expected = (byte) ((value & 0xF) | ((value & 0xF) << 4));

        for (byte b : array.getData()) {
            if (b != expected) {
                return false;
            }
        }

        return true;
    }
}
