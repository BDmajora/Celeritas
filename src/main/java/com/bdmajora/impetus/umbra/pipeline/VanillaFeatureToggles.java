package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPack;
import com.bdmajora.impetus.umbra.shaderpack.ShaderProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

// The sun, moon, stars, sky, vignette, underwaterOverlay and weather switches packs use to suppress vanilla
// features they draw themselves. Everything defaults to enabled, and to enabled with no pack loaded
public final class VanillaFeatureToggles {
    private VanillaFeatureToggles() {
    }

    // Reads a directive from the active pack; true when unset or no pack
    private static boolean isEnabled(Function<ShaderProperties, Optional<Boolean>> directive) {
        ShaderPack pack = Umbra.getCurrentPack();
        if (pack == null || Umbra.getRenderingPipeline() == null) {
            return true;
        }
        return directive.apply(pack.getProperties()).orElse(Boolean.TRUE);
    }

    // sun directive
    public static boolean shouldRenderSun() {
        return isEnabled(ShaderProperties::getRenderSun);
    }

    // moon directive
    public static boolean shouldRenderMoon() {
        return isEnabled(ShaderProperties::getRenderMoon);
    }

    // stars directive
    public static boolean shouldRenderStars() {
        return isEnabled(ShaderProperties::getRenderStars);
    }

    // sky directive
    public static boolean shouldRenderSky() {
        return isEnabled(ShaderProperties::getRenderSky);
    }

    // vignette directive
    public static boolean shouldRenderVignette() {
        return isEnabled(ShaderProperties::getRenderVignette);
    }

    // underwaterOverlay directive
    public static boolean shouldRenderUnderwaterOverlay() {
        return isEnabled(ShaderProperties::getRenderUnderwaterOverlay);
    }

    // weather directive
    public static boolean shouldRenderWeather() {
        return isEnabled(ShaderProperties::getRenderWeather);
    }

    // The pack's `clouds = off | fast | fancy`, mapped onto GameSettings' own cloud modes (0 off, 1 fast, 2 fancy),
    // or empty when the pack leaves the player's video setting alone
    // Resolved once at the source rather than at each caller, the way Iris overrides Options#getCloudStatus. That
    // matters on 1.12.2 because the mode is read in two places: EntityRenderer#renderCloudsCheck decides whether to
    // render clouds at all AND swaps in the cloud projection, while RenderGlobal#renderClouds picks fast versus
    // fancy. Overriding only the second leaves the two disagreeing — clouds set up but never drawn, or drawn
    // without their projection
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
