package com.bdmajora.fulgor.mixin;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.FulgorConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// Gates each Fulgor mixin on its config switch, and refuses to load next to a world implementation
// Fulgor can't reason about (e.g. Cubic Chunks). Mixins are declared in mixins.fulgor.json rather than
// discovered by scanning, so "off" here means "never loaded", not "loaded and inert" — lets a suspected
// mixin be pulled without a rebuild, which matters for something as load-bearing as the lighting engine
public class FulgorMixinPlugin implements IMixinConfigPlugin {
    private static final String PACKAGE = "com.bdmajora.fulgor.mixin.";

    // Cubic Chunks replaces the chunk column with a cube grid + its own lighting engine, breaking every
    // assumption Fulgor makes (16 sections, 8-bit y field, per-column heightmap); silent corruption
    // rather than a crash, so this is a hard refusal, not a warning
    private static final String CUBIC_CHUNKS_MARKER =
            "io.github.opencubicchunks.cubicchunks.core.asm.CubicChunksCoreContainer";

    private FulgorConfig config;

    private boolean enabled;

    @Override
    public void onLoad(String mixinPackage) {
        this.config = FulgorConfig.get();
        this.enabled = this.config.enabled;

        if (!this.enabled) {
            Fulgor.LOGGER.warn("Fulgor is disabled in configuration; vanilla lighting will be used");
            return;
        }

        if (isClassPresent(CUBIC_CHUNKS_MARKER)) {
            Fulgor.LOGGER.warn("Cubic Chunks was detected. It uses its own lighting engine and is "
                    + "fundamentally incompatible with Fulgor, which will not load.");
            this.enabled = false;
            return;
        }

        Fulgor.LOGGER.info("Fulgor lighting subsystem loading");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.enabled) {
            return false;
        }

        String name = mixinClassName.startsWith(PACKAGE)
                ? mixinClassName.substring(PACKAGE.length())
                : mixinClassName;

        switch (name) {
            case "world.WorldMixin":
            case "world.ChunkMixin":
            case "world.ChunkSkylightMixin":
            case "world.ChunkProviderServerMixin":
            case "network.SPacketChunkDataMixin":
            case "client.MinecraftMixin":
                // The engine itself. Without all of these a world would have some paths deferring and
                // others propagating immediately, which is worse than either.
                return this.config.deferredLightUpdates;
            case "world.AnvilChunkLoaderMixin":
                // Also flushes before saving, so it is needed whenever the engine is.
                return this.config.deferredLightUpdates || this.config.fixChunkBoundaryLighting;
            case "world.ExtendedBlockStorageMixin":
                return this.config.sendNonTrivialSectionLight;
            case "block.BlockMixin":
                return this.config.cacheBlockLightInfo;
            case "client.RenderGlobalMixin":
                return this.config.optimizeRenderLightUpdates;
            default:
                Fulgor.LOGGER.warn("No config switch is wired up for {}, applying it", mixinClassName);
                return true;
        }
    }

    // Deliberately doesn't initialize the class (initialize=false): this runs during coremod setup,
    // where eagerly loading a foreign class can change mod load order; only existence is wanted
    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, FulgorMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
