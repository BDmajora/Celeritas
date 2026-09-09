package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.api.SectionLightInfo;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

// teaches a chunk section that its lighting can be worth sending even when its blocks are not
// vanilla's isEmpty() counts non-air blocks, and SPacketChunkData uses it to decide which sections to
// put in the packet
// a section that has been fully carved out but still holds real light data - the inside of a large
// cave, a hollowed-out mountain - therefore never reaches the client, which falls back to "no section
// means sky above equals full daylight" and lights the interior as if it were open air: that is
// MC-116690
// the fix is to fold lighting into the emptiness test, so a section is empty only when it has no
// blocks *and* its light arrays hold exactly what the client's fallback would have assumed anyway
// the result is cached, since the packet path asks repeatedly and the arrays are 2048 bytes each, and
// the four setters invalidate it
// see SectionLightInfo for how the renderer keeps asking the original question
@Mixin(ExtendedBlockStorage.class)
public abstract class ExtendedBlockStorageMixin implements SectionLightInfo {
    @Shadow
    private int blockRefCount;

    @Shadow
    private NibbleArray blockLight;

    @Shadow
    private NibbleArray skyLight;

    // cached verdict on the light arrays: -1 unknown, 0 trivial, 1 worth sending
    // not a reference count despite the vanilla-adjacent name it inherits from Phosphor - there is
    // nothing to count, only a yes or no that is expensive enough to be worth remembering
    @Unique
    private int fulgor$lightRefCount = -1;

    /**
     * @reason Invalidate the cached light verdict.
     * @author Angeline (Phosphor)
     */
    @Overwrite
    public void setSkyLight(int x, int y, int z, int value) {
        this.skyLight.set(x, y, z, value);
        this.fulgor$lightRefCount = -1;
    }

    /**
     * @reason Invalidate the cached light verdict.
     * @author Angeline (Phosphor)
     */
    @Overwrite
    public void setBlockLight(int x, int y, int z, int value) {
        this.blockLight.set(x, y, z, value);
        this.fulgor$lightRefCount = -1;
    }

    /**
     * @reason Invalidate the cached light verdict.
     * @author Angeline (Phosphor)
     */
    @Overwrite
    public void setSkyLight(NibbleArray array) {
        this.skyLight = array;
        this.fulgor$lightRefCount = -1;
    }

    /**
     * @reason Invalidate the cached light verdict.
     * @author Angeline (Phosphor)
     */
    @Overwrite
    public void setBlockLight(NibbleArray array) {
        this.blockLight = array;
        this.fulgor$lightRefCount = -1;
    }

    /**
     * @reason A section with no blocks but non-trivial lighting still has to be sent to the client.
     * @author Angeline (Phosphor)
     */
    @Overwrite
    public boolean isEmpty() {
        if (this.blockRefCount != 0) {
            return false;
        }

        if (this.fulgor$lightRefCount == -1) {
            // Full skylight and no block light is exactly what a client assumes for a missing section,
            // so a section holding only that carries no information and can be left out.
            this.fulgor$lightRefCount = fulgor$isUniform(this.skyLight, (byte) 0xFF)
                    && fulgor$isUniform(this.blockLight, (byte) 0x00) ? 0 : 1;
        }

        return this.fulgor$lightRefCount == 0;
    }

    @Override
    public boolean fulgor$hasNoBlocks() {
        return this.blockRefCount == 0;
    }

    // compares against a whole byte rather than a nibble: both nibbles in a byte have to match, and
    // the callers only ever ask about 0x00 and 0xFF, which are the same in both halves
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
