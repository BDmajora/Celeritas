package com.bdmajora.coartatio.mixin.world;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops chunk sections that hold nothing.
 *
 * <p>Hydrogen's {@code MixinWorldChunk}, retargeted. {@code AnvilChunkLoader.readChunkFromNBT}
 * allocates an {@code ExtendedBlockStorage} for every section present on disk, including ones whose
 * blocks have all been mined out since — {@code removeInvalidBlocks()} resets the reference count
 * but keeps the storage. That storage is a 4096-entry block state container plus two nibble arrays,
 * on the order of ten kilobytes, for a section that is entirely air.
 *
 * <p>{@code Chunk} already treats a null section as air: {@code NULL_BLOCK_STORAGE} <i>is</i>
 * {@code null}, {@code getBlockState} returns air for it, and {@code setBlockState} allocates on
 * demand. So the fix is simply to null them out.
 *
 * <h2>Why the light check</h2>
 *
 * <p>{@code isEmpty()} only counts blocks; it says nothing about light. With no section,
 * {@code getLightFor} falls back to {@code canSeeSky() ? default : 0}, so nulling a section whose
 * stored light is not reproducible by that rule would darken or brighten it until the next relight —
 * a visible bug in caves and under overhangs.
 *
 * <p>Hydrogen nulls on emptiness alone. Here a section is only dropped when its light is uniform and
 * matches what the fallback would produce anyway: block light all zero, and sky light either absent,
 * all zero, or all fifteen. That keeps every ambiguous section and still catches the dominant cases —
 * air above the terrain, and mined-out volumes below it.
 */
@Mixin(Chunk.class)
public abstract class ChunkMixin {
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

    private static boolean coartatio$hasReproducibleLight(ExtendedBlockStorage section) {
        if (!coartatio$isUniform(section.getBlockLight(), 0)) {
            return false;
        }

        NibbleArray skyLight = section.getSkyLight();

        return skyLight == null
                || coartatio$isUniform(skyLight, 0)
                || coartatio$isUniform(skyLight, 15);
    }

    /** True if every nibble in the array equals {@code value}. */
    private static boolean coartatio$isUniform(NibbleArray array, int value) {
        if (array == null) {
            return true;
        }

        byte expected = (byte) ((value & 0xF) | ((value & 0xF) << 4));

        for (byte b : array.getData()) {
            if (b != expected) {
                return false;
            }
        }

        return true;
    }
}
