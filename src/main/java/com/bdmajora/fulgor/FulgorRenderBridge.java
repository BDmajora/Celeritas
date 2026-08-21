package com.bdmajora.fulgor;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import com.bdmajora.fulgor.api.SectionLightInfo;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * The two places Impetus' terrain renderer has to know that Fulgor exists.
 *
 * <p>Both stem from the same thing: the renderer does not read the world through the interfaces the
 * rest of the game uses. It copies block states and light arrays out of chunk sections wholesale so a
 * worker thread can mesh them, which means it bypasses every hook Fulgor relies on.
 *
 * <p>Kept here rather than inlined at the call sites so that the renderer depends on one named seam
 * instead of on the lighting subsystem's internals, and so both call sites degrade the same way when
 * Fulgor is switched off.
 */
public final class FulgorRenderBridge {
    private FulgorRenderBridge() {
    }

    /**
     * Resolves anything the world still owes before its light arrays are copied.
     *
     * <p>A normal light read goes through {@code Chunk.getLightFor}, which flushes first. Copying a
     * {@code NibbleArray} out of a section does not, and nothing about the copy would reveal that it
     * captured a stale batch — the chunk would simply render with the light it had a moment ago and
     * keep it until something else forced a rebuild.
     *
     * <p>Cheap when there is nothing to do, which is nearly always: two volatile reads and a return.
     */
    public static void flushPendingLightUpdates(World world) {
        if (world instanceof LightingEngineProvider) {
            ((LightingEngineProvider) world).fulgor$getLightingEngine().processLightUpdates();
        }
    }

    /**
     * Whether a section contains no blocks, and therefore nothing to mesh.
     *
     * <p>Not the same question as {@code ExtendedBlockStorage.isEmpty()} once Fulgor is loaded. That
     * method also answers "should this be sent to the client", and Fulgor widens it so that a blockless
     * section holding real light data still gets sent — otherwise the client relights the inside of
     * carved-out terrain as open sky. The renderer wants the narrow question, or it would queue build
     * tasks that can only ever produce empty geometry.
     *
     * @see SectionLightInfo
     */
    public static boolean isEmptyOfBlocks(ExtendedBlockStorage section) {
        if (section instanceof SectionLightInfo) {
            return ((SectionLightInfo) section).fulgor$hasNoBlocks();
        }

        return section.isEmpty();
    }
}
