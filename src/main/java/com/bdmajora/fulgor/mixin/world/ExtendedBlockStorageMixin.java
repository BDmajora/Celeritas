package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.api.SectionLightInfo;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

// Folds lighting into isEmpty() so a fully carved-out section with real light data still reaches the client (MC-116690, otherwise the interior lights as open sky); cached since the packet path asks repeatedly over 2048-byte arrays, invalidated by the four setters, see SectionLightInfo for the renderer's original question
@Mixin(ExtendedBlockStorage.class)
public abstract class ExtendedBlockStorageMixin implements SectionLightInfo {
    @Shadow
    private int blockRefCount;

    @Shadow
    private NibbleArray blockLight;

    @Shadow
    private NibbleArray skyLight;

    // Cached verdict on the light arrays: -1 unknown, 0 trivial, 1 worth sending; not a reference count despite the Phosphor-inherited name
    @Unique
    private int fulgor$lightRefCount = -1;

    // Overwrite: writes through and marks the section's light dirty
    @Overwrite
    public void setSkyLight(int x, int y, int z, int value) {
        this.skyLight.set(x, y, z, value);
        this.fulgor$lightRefCount = -1;
    }

    // Overwrite: writes through and marks the section's light dirty
    @Overwrite
    public void setBlockLight(int x, int y, int z, int value) {
        this.blockLight.set(x, y, z, value);
        this.fulgor$lightRefCount = -1;
    }

    // Overwrite: replaces the array and marks dirty
    @Overwrite
    public void setSkyLight(NibbleArray array) {
        this.skyLight = array;
        this.fulgor$lightRefCount = -1;
    }

    // Overwrite: replaces the array and marks dirty
    @Overwrite
    public void setBlockLight(NibbleArray array) {
        this.blockLight = array;
        this.fulgor$lightRefCount = -1;
    }

    // Overwrite: a section with pending light work is not empty, or its light would never propagate
    @Overwrite
    public boolean isEmpty() {
        if (this.blockRefCount != 0) {
            return false;
        }

        if (this.fulgor$lightRefCount == -1) {
            // Full skylight and no block light is exactly what a client assumes for a missing section, so a section holding only that carries no information
            this.fulgor$lightRefCount = fulgor$isUniform(this.skyLight, (byte) 0xFF)
                    && fulgor$isUniform(this.blockLight, (byte) 0x00) ? 0 : 1;
        }

        return this.fulgor$lightRefCount == 0;
    }

    @Override
    public boolean fulgor$hasNoBlocks() {
        return this.blockRefCount == 0;
    }

    // Compares a whole byte rather than a nibble: both nibbles must match, and callers only ask about 0x00 and 0xFF, which are the same in both halves
    @Unique
    private static boolean fulgor$isUniform(NibbleArray array, byte value) {
        if (array == null) {
            return true;
        }

        for (byte b : array.getData()) {
            if (b != value) {
                return false;
            }
        }

        return true;
    }
}
