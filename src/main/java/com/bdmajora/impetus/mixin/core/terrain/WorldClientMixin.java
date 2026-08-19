package com.bdmajora.impetus.mixin.core.terrain;

import net.minecraft.client.multiplayer.WorldClient;
import com.bdmajora.impetus.engine.impl.render.chunk.map.ChunkTracker;
import com.bdmajora.impetus.engine.impl.render.chunk.map.ChunkTrackerHolder;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldClient.class)
public class WorldClientMixin implements ChunkTrackerHolder {
    private final ChunkTracker impetus$tracker = new ChunkTracker();

    @Override
    public ChunkTracker impetus$getTracker() {
        return impetus$tracker;
    }
}
