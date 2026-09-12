package com.bdmajora.fulgor;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import com.bdmajora.fulgor.api.SectionLightInfo;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

// The two places the terrain renderer must know Fulgor exists: it copies sections wholesale for the mesher, bypassing every hook Fulgor relies on, so one seam lets both call sites degrade the same way when off
public final class FulgorRenderBridge {
    private FulgorRenderBridge() {
    }

    // Resolves anything the world still owes before its light arrays are copied; Chunk.getLightFor flushes first but a NibbleArray copy does not, and a stale batch would render until something else forced a rebuild. Two volatile reads when idle
    public static void flushPendingLightUpdates(World world) {
        if (world instanceof LightingEngineProvider) {
            ((LightingEngineProvider) world).fulgor$getLightingEngine().processLightUpdates();
        }
    }

    // Whether a section contains no blocks and thus nothing to mesh; not ExtendedBlockStorage.isEmpty(), which Fulgor widens so a blockless section with real light data still gets sent (see SectionLightInfo), and the renderer wants the narrow question
    public static boolean isEmptyOfBlocks(ExtendedBlockStorage section) {
        if (section instanceof SectionLightInfo) {
            return ((SectionLightInfo) section).fulgor$hasNoBlocks();
        }

        return section.isEmpty();
    }
}
