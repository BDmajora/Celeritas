package com.bdmajora.impetus.iris.pipeline;

import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.ShaderProperties;

import java.util.Optional;
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
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null || Iris.getRenderingPipeline() == null) {
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
}
