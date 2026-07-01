package org.taumc.celeritas;

import com.mojang.realmsclient.gui.ChatFormatting;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.embeddedt.embeddium.impl.common.util.MathUtil;
import org.embeddedt.embeddium.impl.common.util.NativeBuffer;
import org.embeddedt.embeddium.impl.gl.device.GLRenderDevice;
import org.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import org.taumc.celeritas.impl.command.TogglePassCommand;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;
import org.taumc.celeritas.impl.util.PlatformUtil;
import org.taumc.celeritas.iris.Iris;

@Mod(modid = CeleritasVintage.MODID, useMetadata = true, clientSideOnly = true, acceptableRemoteVersions = "*")
public class CeleritasVintage {
    public static final String MODID = "celeritas";
    private static final Logger LOGGER = LogManager.getLogger("Celeritas");
    public static String VERSION;
    private static final SodiumGameOptions CONFIG = loadConfig();

    @EventHandler
    public void onConstruct(FMLConstructionEvent event) {
        GLRenderDevice.VANILLA_STATE_RESETTER = () -> OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        VERSION = Loader.instance().getIndexedModList().get(MODID).getVersion();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @EventHandler
    public void onInit(FMLInitializationEvent event) {
        if ((Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment")) {
            ClientCommandHandler.instance.registerCommand(new TogglePassCommand());
        }

        // Phase 1: load (parse only) the selected shader pack. No rendering changes happen here — if no pack is
        // selected or loading fails, Celeritas renders exactly as before.
        Iris.initialize(PlatformUtil.getGameDir().toPath());
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        // Render thread with a live GL context: build/rebuild the Iris pipeline the first frame after a pack change.
        // No-op unless a shader pack was (un)loaded. Safe when Iris is disabled.
        if (event.phase == TickEvent.Phase.START) {
            Iris.updatePipeline();
        }
    }

    @SubscribeEvent
    public void onF3Text(RenderGameOverlayEvent.Text event) {
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            return;
        }

        var strings = event.getRight();
        strings.add("");
        strings.add(String.format("%s%s Renderer (%s)", ChatFormatting.AQUA, "Celeritas", VERSION));

        // Embeddium: Show a lot less with reduced debug info
        if (Minecraft.getMinecraft().isReducedDebug()) {
            return;
        }

        var renderer = CeleritasWorldRenderer.instanceNullable();

        if (renderer != null) {
            strings.addAll(renderer.getDebugStrings());
        }

        for (int i = 0; i < strings.size(); i++) {
            String str = strings.get(i);

            if (str.startsWith("Allocated:")) {
                strings.add(i + 1, getNativeMemoryString());

                break;
            }
        }
    }

    private static String getNativeMemoryString() {
        return "Off-Heap: +" + MathUtil.toMib(getNativeMemoryUsage()) + "MB";
    }

    private static long getNativeMemoryUsage() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + NativeBuffer.getTotalAllocated();
    }

    public static Logger logger() {
        return LOGGER;
    }

    private static SodiumGameOptions loadConfig() {
        try {
            return SodiumGameOptions.load();
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration file", e);
            LOGGER.error("Using default configuration file in read-only mode");
            SodiumGameOptions config = new SodiumGameOptions();
            config.setReadOnly();
            return config;
        }
    }

    public static SodiumGameOptions options() {
        if (CONFIG == null) {
            throw new IllegalStateException("Config not yet available");
        } else {
            return CONFIG;
        }
    }
}
