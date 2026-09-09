package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

// Ticks block entity light sources once per client tick
// Entities recompute their own luminance from onEntityUpdate, but a block entity has no equivalent hook that every
// implementation runs through — a non-tickable one never ticks at all — so the world's block entity list has to be
// walked from outside instead
// The walk is skipped entirely unless some mod has registered a block entity handler. Nothing does by default, so
// on an ordinary install this costs one map-emptiness check per tick rather than a pass over every loaded block
// entity, which on a large base is thousands of them
// Celeritas Dynamic Lights has no equivalent: its block entity code is complete but nothing ever calls it, so its
// "Block Entities" toggle governs a path that never runs
public final class TileEntityLightTicker {
    private TileEntityLightTicker() {
    }

    private static final TileEntityLightTicker INSTANCE = new TileEntityLightTicker();

    public static Object instance() {
        return INSTANCE;
    }

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
