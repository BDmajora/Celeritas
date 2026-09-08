package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.ExplosiveLightingMode;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.item.ItemStack;

import java.util.function.Function;

/**
 * Reports how brightly one kind of entity or block entity glows.
 *
 * <p>The public extension point: a mod registers a handler for its own type through
 * {@link DynamicLightHandlers}, and everything else — tracking, chunk rebuilds, the lightmap — comes
 * for free.
 *
 * @param <T> the light source type
 */
public interface DynamicLightHandler<T> {
    /** Luminance in the vanilla 0-15 scale. */
    int getLuminance(T lightSource);

    /** Whether this source is extinguished by being underwater. */
    default boolean isWaterSensitive(T lightSource) {
        return false;
    }

    static <T extends EntityLivingBase> DynamicLightHandler<T> makeHandler(
            Function<T, Integer> luminance, Function<T, Boolean> waterSensitive) {
        return new DynamicLightHandler<T>() {
            @Override
            public int getLuminance(T lightSource) {
                return luminance.apply(lightSource);
            }

            @Override
            public boolean isWaterSensitive(T lightSource) {
                return waterSensitive.apply(lightSource);
            }
        };
    }

    /** Wraps {@code handler} so the entity's held and worn items count towards its luminance too. */
    static <T extends EntityLivingBase> DynamicLightHandler<T> makeLivingEntityHandler(
            DynamicLightHandler<T> handler) {
        return entity -> Math.max(
                DynamicLightsEngine.getLivingEntityLuminanceFromItems(entity),
                handler.getLuminance(entity));
    }

    /**
     * A creeper handler that follows the configured explosive lighting mode.
     *
     * @param handler an extra handler to take the maximum with, or null
     */
    static <T extends EntityCreeper> DynamicLightHandler<T> makeCreeperEntityHandler(
            DynamicLightHandler<T> handler) {
        return new DynamicLightHandler<T>() {
            @Override
            public int getLuminance(T entity) {
                int luminance = 0;
                float flash = entity.getCreeperFlashIntensity(0.0F);

                if (flash > 0.001F) {
                    ExplosiveLightingMode mode = DynamicLights.options().creeperLighting;
                    if (mode == ExplosiveLightingMode.SIMPLE) {
                        luminance = 10;
                    } else if (mode == ExplosiveLightingMode.FANCY) {
                        luminance = (int) (flash * 10.0F);
                    }
                }

                if (handler != null) {
                    luminance = Math.max(luminance, handler.getLuminance(entity));
                }

                return luminance;
            }

            @Override
            public boolean isWaterSensitive(T lightSource) {
                return true;
            }
        };
    }
}
