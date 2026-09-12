package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.api.util.ColorABGR;
import com.bdmajora.impetus.engine.api.util.ColorMixer;

// The two ways per-vertex ambient occlusion can be folded into a chunk vertex's packed colour
// Which one the mesher uses is decided by the active shader pack, not by the engine, so the choice is an enum
// the mesher asks for once per quad rather than a branch buried in the encoder
public enum ChunkColorWriter {
    // AO goes in the alpha channel and RGB stays the pure block/biome tint
    // This is the shaders.properties `separateAo` directive, which effectively every modern pack sets, because
    // the pack wants to apply AO itself inside its own lighting model instead of receiving it pre-multiplied
    // Iris does exactly this split in XHFPTerrainVertex
    SEPARATE_AO {
        // Applies AO to the colour for this mode
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorABGR.withAlpha(colorWithAlpha, aoValue);
        }
    },
    // AO multiplied straight into RGB, which is what vanilla 1.12.2 effectively does
    // aoValue is 0..1 and the mixer wants 0..255, hence the scale; alpha is left alone because it still carries
    // the quad's own translucency here
    IMPETUS {
        // Applies AO to the colour for this mode
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorMixer.mulSingleWithoutAlpha(colorWithAlpha, (int)(aoValue * 255));
        }
    };

    // Called once per vertex on the meshing hot path; colorWithAlpha is packed ABGR, aoValue is 0..1
    public abstract int writeColor(int colorWithAlpha, float aoValue);

    // The writer the terrain mesher should be using right now, following the active pack's `separateAo`
    // Read per section build rather than cached, so a pack reload takes effect on the next rebuild
    public static ChunkColorWriter active() {
        return SeparateAoState.enabled ? SEPARATE_AO : IMPETUS;
    }

    // Holds the flag itself
    // It lives here rather than in the Umbra packages on purpose: the mesher runs on worker threads inside the
    // engine module, and the engine module must not depend on the shader-pack model. Umbra pushes the value in
    // through set() at pack load; the engine only ever reads it
    public static final class SeparateAoState {
        // volatile because the writer is the render thread at pack load and the readers are mesh workers
        private static volatile boolean enabled;

        private SeparateAoState() {
        }

        // Switches the mode
        public static void set(boolean value) {
            enabled = value;
        }

        // Current mode
        public static boolean isEnabled() {
            return enabled;
        }
    }
}
