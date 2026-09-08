package com.bdmajora.extras.client.particle;

import com.bdmajora.extras.Extras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Runs particle discovery off the options screen and off the per-particle path.
 *
 * <p>1.12.2 recreates {@code Minecraft.effectRenderer} per world load, and that is the moment every
 * vanilla and most mod factories are registered. Keying the scan off the instance rather than a
 * world-load event makes it independent of load ordering while still running exactly once per world.
 */
@Mod.EventBusSubscriber(Side.CLIENT)
@SideOnly(Side.CLIENT)
public final class ParticleDiscoveryHandler {
    /** The last instance scanned; a different one means a new world was loaded. */
    private static ParticleManager lastScanned;

    private ParticleDiscoveryHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        ParticleManager effectRenderer = Minecraft.getMinecraft().effectRenderer;
        if (effectRenderer == null || effectRenderer == lastScanned) {
            return;
        }
        lastScanned = effectRenderer;

        ParticleClassRegistry registry = ParticleClassRegistry.getInstance();
        registry.pruneDiscoveredCache();
        registry.scanFactories(effectRenderer);
        flushIfDirty(registry);
    }

    /**
     * Persists what the session accumulated: classes only ever seen at spawn time, and the user's
     * per-class toggles, neither of which goes through a scan.
     */
    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() == null || !event.getWorld().isRemote) {
            return;
        }
        flushIfDirty(ParticleClassRegistry.getInstance());
    }

    private static void flushIfDirty(ParticleClassRegistry registry) {
        if (registry.isDirty()) {
            Extras.save();
        }
    }
}
