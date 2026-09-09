package com.bdmajora.impetus.booter;

import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.asm.ASM;
import com.bdmajora.impetus.booter.service.ModDiscoverer;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Mixin-facing half of the bundled booter, ported from MixinBooter's MixinBooterPlugin.
 * <p>
 * Every class this touches lives in {@code org.spongepowered.asm}, so it must not be loaded until
 * {@link BooterBootstrap} has confirmed a Mixin implementation is on the classpath. ImpetusLoadingPlugin
 * enforces that ordering; do not reference this class from anywhere that runs earlier.
 */
public final class BooterCore {

    private BooterCore() { }

    // Mirrors MixinBooterPlugin#initialize. Runs only when we own the Mixin subsystem.
    public static void initialize() {
        installClassLoaderExclusionsAndTransformers();

        System.setProperty("mixin.bootstrapService", "com.bdmajora.impetus.booter.service.MixinServiceBootstrap");
        System.setProperty("mixin.service", "com.bdmajora.impetus.booter.service.MixinBooterService");

        MixinBootstrap.init();
        Mixins.addConfiguration("mixins.impetusbooter.json");
        MixinBooterConfig.load();
        ModDiscoverer.discover();
        registerCoremodsRescuer();
    }

    // Mirrors MixinBooterPlugin#injectData: hijackers first so blacklists land before configs queue.
    public static void injectData(List<?> coremodList) {
        ModDiscoverer.applyForceLoadAsMod();
        loadEarlyLoaders(gatherEarlyLoaders(coremodList));
        MixinBootstrap.getPlatform().inject();
    }

    private static void installClassLoaderExclusionsAndTransformers() {
        Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.launch.");
        Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.service.");
        Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.mixin.");
        Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.logging.");
        Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.util.");
        Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.lib.");
        Launch.classLoader.addClassLoaderExclusion("org.objectweb.asm.");
        Launch.classLoader.addClassLoaderExclusion("com.bdmajora.impetus.booter.service.");
        Launch.classLoader.registerTransformer("com.bdmajora.impetus.booter.service.ClassLoadTracer");
        if (!ASM.isAtLeastVersion(5, 1)) {
            Launch.classLoader.registerTransformer("com.bdmajora.impetus.booter.fix.mixinextras.MixinExtrasFixer");
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerCoremodsRescuer() {
        List<String> tweakClasses = (List<String>) Launch.blackboard.get("TweakClasses");
        tweakClasses.add(0, "com.bdmajora.impetus.booter.service.CoremodsRescuer");
    }

    private static Collection<IEarlyMixinLoader> gatherEarlyLoaders(List<?> coremodList) {
        ILogger logger = MixinService.getService().getLogger(Tags.MOD_NAME);
        Field fmlPluginWrapper$coreModInstance = null;
        Set<IEarlyMixinLoader> queuedLoaders = new LinkedHashSet<>();
        Context context = new Context(null, ModDiscoverer.getPresentMods()); // For hijackers
        for (Object coremod : coremodList) {
            try {
                if (fmlPluginWrapper$coreModInstance == null) {
                    fmlPluginWrapper$coreModInstance = coremod.getClass().getField("coreModInstance");
                    fmlPluginWrapper$coreModInstance.setAccessible(true);
                }
                Object theMod = fmlPluginWrapper$coreModInstance.get(coremod);
                if (theMod instanceof IMixinConfigHijacker) {
                    IMixinConfigHijacker interceptor = (IMixinConfigHijacker) theMod;
                    logger.info("Loading config hijacker {}.", interceptor.getClass().getName());
                    for (String hijacked : interceptor.getHijackedMixinConfigs(context)) {
                        Config.blacklist(hijacked);
                        logger.info("{} will hijack the mixin config {}", interceptor.getClass().getName(), hijacked);
                    }
                }
                if (theMod instanceof IEarlyMixinLoader) {
                    queuedLoaders.add((IEarlyMixinLoader) theMod);
                }
            } catch (Throwable t) {
                logger.error("Unexpected error", t);
            }
        }
        return queuedLoaders;
    }

    private static void loadEarlyLoaders(Collection<IEarlyMixinLoader> queuedLoaders) {
        ILogger logger = MixinService.getService().getLogger(Tags.MOD_NAME);
        for (IEarlyMixinLoader queuedLoader : queuedLoaders) {
            logger.info("Loading early loader {} for its mixins.", queuedLoader.getClass().getName());
            try {
                for (String mixinConfig : queuedLoader.getMixinConfigs()) {
                    Context context = new Context(mixinConfig, ModDiscoverer.getPresentMods());
                    if (queuedLoader.shouldMixinConfigQueue(context)) {
                        logger.info("Adding [{}] mixin configuration.", mixinConfig);
                        Mixins.addConfiguration(mixinConfig);
                        queuedLoader.onMixinConfigQueued(context);
                    }
                }
            } catch (Throwable t) {
                logger.error("Failed to execute early loader [{}].", queuedLoader.getClass().getName(), t);
            }
        }
    }

}
