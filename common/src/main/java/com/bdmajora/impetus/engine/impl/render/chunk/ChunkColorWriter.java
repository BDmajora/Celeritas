package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.api.util.ColorABGR;
import com.bdmajora.impetus.engine.api.util.ColorMixer;

/**
 * How per-vertex ambient occlusion is folded into the chunk vertex colour.
 * <p>
 * {@link #IMPETUS} multiplies AO into RGB, which is what vanilla 1.12.2 effectively does. {@link #SEPARATE_AO}
 * instead carries AO in the alpha channel and leaves RGB as the pure block/biome tint — the shaders.properties
 * {@code separateAo} directive, which every modern pack sets, so its own lighting model can apply AO itself
 * (Iris does exactly this split in {@code XHFPTerrainVertex}).
 */
public enum ChunkColorWriter {
    SEPARATE_AO {
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorABGR.withAlpha(colorWithAlpha, aoValue);
        }
    },
    IMPETUS {
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorMixer.mulSingleWithoutAlpha(colorWithAlpha, (int)(aoValue * 255));
        }
    };

    public abstract int writeColor(int colorWithAlpha, float aoValue);

    /** The writer the terrain mesher should use right now, following the active pack's {@code separateAo}. */
    public static ChunkColorWriter active() {
        return SeparateAoState.enabled ? SEPARATE_AO : IMPETUS;
    }

    /**
     * Holder for the flag. Lives here rather than in the Iris packages because the mesher runs on worker threads in
     * the engine module, which must not depend on the shader-pack model.
     */
    public static final class SeparateAoState {
        private static volatile boolean enabled;

        private SeparateAoState() {
        }

        public static void set(boolean value) {
            enabled = value;
        }

        public static boolean isEnabled() {
            return enabled;
        }
    }
}
