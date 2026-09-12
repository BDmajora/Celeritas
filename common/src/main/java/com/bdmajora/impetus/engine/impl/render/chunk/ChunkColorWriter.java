package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.api.util.ColorABGR;
import com.bdmajora.impetus.engine.api.util.ColorMixer;

// The two ways per-vertex AO can be folded into a chunk vertex's packed colour; chosen by the active shader pack, so the mesher asks once per quad rather than branching in the encoder
public enum ChunkColorWriter {
    // AO in alpha, RGB stays the pure block/biome tint: the shaders.properties `separateAo` directive, set by nearly every modern pack so it can apply AO in its own lighting model (as Iris does in XHFPTerrainVertex)
    SEPARATE_AO {
        // Applies AO to the colour for this mode
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorABGR.withAlpha(colorWithAlpha, aoValue);
        }
    },
    // AO multiplied straight into RGB like vanilla 1.12.2; aoValue is 0..1 scaled to 0..255, alpha still carries the quad's translucency
    IMPETUS {
        // Applies AO to the colour for this mode
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorMixer.mulSingleWithoutAlpha(colorWithAlpha, (int)(aoValue * 255));
        }
    };

    // Called once per vertex on the meshing hot path; colorWithAlpha is packed ABGR, aoValue is 0..1
    public abstract int writeColor(int colorWithAlpha, float aoValue);

    // The writer the mesher should use right now per the active pack's `separateAo`; read per section build so a pack reload applies on the next rebuild
    public static ChunkColorWriter active() {
        return SeparateAoState.enabled ? SEPARATE_AO : IMPETUS;
    }

    // Holds the flag here rather than in Umbra because the mesher runs on engine worker threads and the engine must not depend on the shader-pack model; Umbra pushes the value via set()
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
