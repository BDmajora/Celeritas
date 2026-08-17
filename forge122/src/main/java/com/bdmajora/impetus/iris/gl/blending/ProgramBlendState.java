package com.bdmajora.impetus.iris.gl.blending;

import net.minecraft.client.renderer.GlStateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.shaderpack.ShaderProperties;
import com.bdmajora.impetus.lwjgl.GL11;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/** Program-level and per-logical-render-target blend directives from shaders.properties. */
public final class ProgramBlendState {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    private static final String[] LEGACY_TARGETS = {
            "gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"
    };

    private static boolean warnedNoBufferBlend;

    private final boolean baseSpecified;
    private final BlendMode baseMode;
    private final Map<Integer, BlendMode> perTargetModes;

    private ProgramBlendState(boolean baseSpecified, BlendMode baseMode, Map<Integer, BlendMode> perTargetModes) {
        this.baseSpecified = baseSpecified;
        this.baseMode = baseMode;
        this.perTargetModes = perTargetModes;
    }

    public static ProgramBlendState empty() {
        return new ProgramBlendState(false, null, new LinkedHashMap<>());
    }

    public static ProgramBlendState from(ShaderProperties properties, String programName) {
        return from(properties, programName, null);
    }

    /**
     * @param defaultBase the blend mode to fall back on when the pack declared no {@code blend.<program>}, or
     *                    {@code null} for none. Iris hangs these off
     *                    {@link com.bdmajora.impetus.iris.shaderpack.loading.ProgramId} and lets a pack directive win.
     */
    public static ProgramBlendState from(ShaderProperties properties, String programName, BlendMode defaultBase) {
        boolean baseSpecified = false;
        BlendMode baseMode = null;
        String base = properties.getBlendModeOverride(programName).orElse(null);
        if (base != null) {
            baseSpecified = true;
            baseMode = parseMode(programName, "blend." + programName, base);
        } else if (defaultBase != null) {
            baseSpecified = true;
            baseMode = defaultBase;
        }

        Map<Integer, BlendMode> perTargetModes = new LinkedHashMap<>();
        String prefix = "blend." + programName + ".";
        properties.asMap().forEach((key, value) -> {
            if (!key.startsWith(prefix)) {
                return;
            }
            String targetName = key.substring(prefix.length());
            int target = parseTarget(targetName);
            if (target < 0) {
                LOGGER.warn("[Iris] Unknown blend target '{}', ignoring {}", targetName, key);
                return;
            }
            perTargetModes.put(target, parseMode(programName, key, value));
        });

        return new ProgramBlendState(baseSpecified, baseMode, perTargetModes);
    }

    public boolean hasDirectives() {
        return this.baseSpecified || !this.perTargetModes.isEmpty();
    }

    /**
     * Applies the blend state for a program whose logical DRAWBUFFERS list is {@code drawBuffers}.
     * Per-buffer OpenGL blend indices are output slots, so logical targets first map through the draw-buffer order.
     */
    public void apply(int[] drawBuffers) {
        if (!hasDirectives()) {
            return;
        }

        if (this.baseSpecified) {
            if (this.baseMode == null) {
                GlStateManager.disableBlend();
            } else {
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(
                        this.baseMode.srcRgb(), this.baseMode.dstRgb(),
                        this.baseMode.srcAlpha(), this.baseMode.dstAlpha());
            }
        }

        if (this.perTargetModes.isEmpty()) {
            if (this.baseSpecified && LWJGL.supportsBufferBlending()) {
                for (int slot = 0; slot < drawBuffers.length; slot++) {
                    applySlotMode(slot, this.baseMode);
                }
            }
            return;
        }
        if (!LWJGL.supportsBufferBlending()) {
            if (!warnedNoBufferBlend) {
                LOGGER.warn("[Iris] Shader pack requested per-buffer blending, but this GL context does not support it");
                warnedNoBufferBlend = true;
            }
            return;
        }

        for (int slot = 0; slot < drawBuffers.length; slot++) {
            if (this.baseSpecified) {
                applySlotMode(slot, this.baseMode);
            }
            if (this.perTargetModes.containsKey(drawBuffers[slot])) {
                applySlotMode(slot, this.perTargetModes.get(drawBuffers[slot]));
            }
        }
    }

    private static void applySlotMode(int slot, BlendMode mode) {
        if (mode == null) {
            LWJGL.glDisablei(GL11.GL_BLEND, slot);
        } else {
            LWJGL.glEnablei(GL11.GL_BLEND, slot);
            LWJGL.glBlendFuncSeparatei(slot, mode.srcRgb(), mode.dstRgb(), mode.srcAlpha(), mode.dstAlpha());
        }
    }

    private static BlendMode parseMode(String programName, String key, String value) {
        if ("off".equals(value.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }
        try {
            return BlendMode.parse(value);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[Iris] Invalid blend directive for '{}': {} = {} ({})",
                    programName, key, value, e.getMessage());
            return null;
        }
    }

    private static int parseTarget(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("colortex")) {
            try {
                return Integer.parseInt(lower.substring("colortex".length()));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        for (int i = 0; i < LEGACY_TARGETS.length; i++) {
            if (LEGACY_TARGETS[i].equals(lower)) {
                return i;
            }
        }
        return -1;
    }
}
