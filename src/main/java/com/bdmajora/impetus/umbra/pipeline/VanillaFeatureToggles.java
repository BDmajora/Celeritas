package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPack;
import com.bdmajora.impetus.umbra.shaderpack.ShaderProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * The shaders.properties switches that let a pack suppress vanilla world features it draws itself —
 * {@code sun}, {@code moon}, {@code stars}, {@code sky}, {@code vignette}, {@code underwaterOverlay} and
 * {@code weather}.
 * <p>
 * Complementary and Photon both set several of these false because they reproduce the feature in their own passes.
 * Drawing vanilla's version too gives doubled rain, a vignette composited on top of the pack's tonemapping, and a
 * vanilla sun disc punched through the pack's sky.
 * <p>
 * Every switch defaults to "enabled": an absent directive means leave vanilla alone, and so does having no pack
 * loaded at all.
 */
public final class VanillaFeatureToggles {
    private VanillaFeatureToggles() {
    }

    private static boolean isEnabled(Function<ShaderProperties, Optional<Boolean>> directive) {
        ShaderPack pack = Umbra.getCurrentPack();
        if (pack == null || Umbra.getRenderingPipeline() == null) {
            return true;
        }
        return directive.apply(pack.getProperties()).orElse(Boolean.TRUE);
    }

    public static boolean shouldRenderSun() {
        return isEnabled(ShaderProperties::getRenderSun);
    }

    public static boolean shouldRenderMoon() {
        return isEnabled(ShaderProperties::getRenderMoon);
    }

    public static boolean shouldRenderStars() {
        return isEnabled(ShaderProperties::getRenderStars);
    }

    public static boolean shouldRenderSky() {
        return isEnabled(ShaderProperties::getRenderSky);
    }

    public static boolean shouldRenderVignette() {
        return isEnabled(ShaderProperties::getRenderVignette);
    }

    public static boolean shouldRenderUnderwaterOverlay() {
        return isEnabled(ShaderProperties::getRenderUnderwaterOverlay);
    }

    public static boolean shouldRenderWeather() {
        return isEnabled(ShaderProperties::getRenderWeather);
    }

    /**
     * {@code clouds = off | fast | fancy}, expressed as one of {@code GameSettings}' cloud modes (0 off, 1 fast,
     * 2 fancy), or empty when the pack leaves the player's video setting alone.
     * <p>
     * Umbra resolves this once at the source ({@code MixinOptions_CloudsOverride} overrides
     * {@code Options#getCloudStatus}) rather than at each caller, and that matters here too: on 1.12.2 the mode is read
     * both to decide whether to render clouds at all — {@code EntityRenderer#renderCloudsCheck}, which also swaps in
     * the cloud projection — and to choose fast versus fancy in {@code RenderGlobal#renderClouds}. Overriding only the
     * latter leaves the two disagreeing.
     */
    public static OptionalInt getCloudMode() {
        ShaderPack pack = Umbra.getCurrentPack();
        if (pack == null || Umbra.getRenderingPipeline() == null) {
            return OptionalInt.empty();
        }

        // getCloudMode() has already lowercased the value.
        String mode = pack.getProperties().getCloudMode().orElse("").trim();
        switch (mode) {
            case "":
                return OptionalInt.empty();
            case "off":
                return OptionalInt.of(0);
            case "fast":
                return OptionalInt.of(1);
            case "fancy":
                return OptionalInt.of(2);
            default:
                // Consulted every frame, so only complain about a given value once.
                if (!mode.equals(warnedCloudMode)) {
                    warnedCloudMode = mode;
                    LOGGER.error("[Umbra] Unrecognized clouds setting: {}", mode);
                }
                return OptionalInt.empty();
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");
    private static String warnedCloudMode;
}
