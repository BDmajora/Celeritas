package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

// Ticks block entity light sources once per client tick, since unlike entities they have no shared update hook
// The walk is skipped unless a mod registered a block entity handler, so a default install pays one map check
public final class TileEntityLightTicker {
    private TileEntityLightTicker() {
    }

    private static final TileEntityLightTicker INSTANCE = new TileEntityLightTicker();

    // Typed as Object so the event bus registration needs no Forge import at the call site
    public static Object instance() {
        return INSTANCE;
    }

    // Runs at END so it sees the world state the tick produced
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!DynamicLights.options().mode.isEnabled()
                || !DynamicLights.options().blockEntitiesLightSource
                || !DynamicLightHandlers.hasTileEntityHandlers()) {
            return;
        }

        WorldClient world = Minecraft.getMinecraft().world;
        if (world == null) {
            return;
        }

        List<TileEntity> tileEntities = world.loadedTileEntityList;

        // Indexed rather than for-each: a handler is free to invalidate a block entity, and the
        // resulting removal would fail a live iterator with a ConcurrentModificationException.
        for (int i = 0, size = tileEntities.size(); i < size; i++) {
            TileEntity tileEntity;
            try {
                tileEntity = tileEntities.get(i);
            } catch (IndexOutOfBoundsException e) {
                // The list shrank underneath us; whatever is left will be picked up next tick.
                break;
            }

            if (tileEntity instanceof DynamicLightSource) {
                ((DynamicLightSource) tileEntity).impetus$dynamicLightTick();
            }
        }
    }
}
