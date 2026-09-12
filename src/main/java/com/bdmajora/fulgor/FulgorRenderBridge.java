package com.bdmajora.fulgor;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import com.bdmajora.fulgor.api.SectionLightInfo;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

// The two places the terrain renderer has to know Fulgor exists: it copies chunk sections wholesale for the
// mesher, bypassing every hook Fulgor relies on. One named seam so both call sites degrade the same way when off
public final class FulgorRenderBridge {
    private FulgorRenderBridge() {
    }

    // resolves anything the world still owes before its light arrays are copied
    // a normal light read goes through Chunk.getLightFor, which flushes first; copying a NibbleArray
    // out of a section does not, and nothing about the copy would reveal that it captured a stale batch
    // - the chunk would simply render with the light it had a moment ago and keep it until something
    // else forced a rebuild
    // cheap when there is nothing to do, which is nearly always: two volatile reads and a return
    public static void flushPendingLightUpdates(World world) {
        if (world instanceof LightingEngineProvider) {
            ((LightingEngineProvider) world).fulgor$getLightingEngine().processLightUpdates();
        }
    }

    // whether a section contains no blocks, and therefore nothing to mesh
    // not the same question as ExtendedBlockStorage.isEmpty() once Fulgor is loaded: that method also
    // answers "should this be sent to the client", and Fulgor widens it so that a blockless section
    // holding real light data still gets sent - otherwise the client relights the inside of carved-out
    // terrain as open sky (see SectionLightInfo)
    // the renderer wants the narrow question, or it would queue build tasks that can only ever produce
    // empty geometry
    public static boolean isEmptyOfBlocks(ExtendedBlockStorage section) {
        if (section instanceof SectionLightInfo) {
            return ((SectionLightInfo) section).fulgor$hasNoBlocks();
        }

        return section.isEmpty();
    }
}
