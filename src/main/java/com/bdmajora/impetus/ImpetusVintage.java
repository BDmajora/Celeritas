package com.bdmajora.impetus;

import com.mojang.realmsclient.gui.ChatFormatting;

import java.lang.management.ManagementFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.engine.impl.common.util.MathUtil;
import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;
import com.bdmajora.impetus.engine.impl.compat.checks.StartupChecks;
import com.bdmajora.impetus.engine.impl.compat.environment.GlContextInfo;
import com.bdmajora.impetus.engine.impl.gl.device.GLRenderDevice;
import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;
import com.bdmajora.impetus.engine.impl.render.chunk.region.RenderRegionManager;
import com.bdmajora.impetus.impl.command.TogglePassCommand;
import com.bdmajora.impetus.impl.compat.ResourcePackScanner;
import com.bdmajora.impetus.impl.gui.overlay.ImpetusToastRenderer;
import com.bdmajora.impetus.impl.render.terrain.ImpetusWorldRenderer;
import com.bdmajora.impetus.impl.util.PlatformUtil;
import com.bdmajora.impetus.iris.Iris;

@Mod(modid = ImpetusVintage.MODID, useMetadata = true, clientSideOnly = true, acceptableRemoteVersions = "*")
public class ImpetusVintage {
    public static final String MODID = "impetus";
    private static final Logger LOGGER = LogManager.getLogger("Impetus");
    public static String VERSION;
    private static final ImpetusGameOptions CONFIG = loadConfig();

    @EventHandler
    public void onConstruct(FMLConstructionEvent event) {
        GLRenderDevice.VANILLA_STATE_RESETTER = () -> OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        VERSION = Loader.instance().getIndexedModList().get(MODID).getVersion();
        MinecraftForge.EVENT_BUS.register(this);

        // Seed the engine's hot-path option snapshot from the loaded config.
        com.bdmajora.impetus.engine.impl.ImpetusRuntimeOptions.apply(CONFIG);

        // Platform compatibility: GL strings must be read on the client thread (which owns the context during
        // FML construction); the adapter probe and overlay scan then continue on a background thread.
        StartupChecks.installCrashDialog();
        StartupChecks.runAsync(GlContextInfo.capture());
    }

    @EventHandler
    public void onInit(FMLInitializationEvent event) {
        if ((Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment")) {
            ClientCommandHandler.instance.registerCommand(new TogglePassCommand());
        }

        // Phase 1: load (parse only) the selected shader pack. No rendering changes happen here — if no pack is
        // selected or loading fails, Impetus renders exactly as before.
        Iris.initialize(PlatformUtil.getGameDir().toPath());
        ResourcePackScanner.scanIfChanged(Minecraft.getMinecraft());
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        // Render thread with a live GL context: build/rebuild the Iris pipeline the first frame after a pack change.
        // No-op unless a shader pack was (un)loaded. Safe when Iris is disabled.
        if (event.phase == TickEvent.Phase.START) {
            ResourcePackScanner.tick(Minecraft.getMinecraft());
            Iris.updatePipeline();
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            ImpetusToastRenderer.render(Minecraft.getMinecraft(), event.getResolution());
        }
    }

    @SubscribeEvent
    public void onF3Text(RenderGameOverlayEvent.Text event) {
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            return;
        }

        var strings = event.getRight();
        strings.add("");
        strings.add(String.format("%s%s Renderer (%s)", ChatFormatting.AQUA, "Impetus", VERSION));

        // Impetus: Show a lot less with reduced debug info
        if (Minecraft.getMinecraft().isReducedDebug()) {
            return;
        }

        var renderer = ImpetusWorldRenderer.instanceNullable();

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

    private static ImpetusGameOptions loadConfig() {
        try {
            ImpetusGameOptions config = ImpetusGameOptions.load();
            applyRuntimeConfig(config);
            return config;
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration file", e);
            LOGGER.error("Using default configuration file in read-only mode");
            ImpetusGameOptions config = new ImpetusGameOptions();
            config.setReadOnly();
            applyRuntimeConfig(config);
            return config;
        }
    }

    private static void applyRuntimeConfig(ImpetusGameOptions config) {
        NativeBuffer.ENABLE_MEMORY_TRACING = config.advanced.enableMemoryTracing;
        RenderRegionManager.USE_ADVANCED_STAGING_BUFFERS = config.advanced.useAdvancedStagingBuffers;
    }

    public static ImpetusGameOptions options() {
        if (CONFIG == null) {
            throw new IllegalStateException("Config not yet available");
        } else {
            return CONFIG;
        }
    }
}
