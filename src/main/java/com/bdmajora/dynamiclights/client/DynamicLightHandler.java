package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.ExplosiveLightingMode;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.item.ItemStack;

import java.util.function.Function;

// Reports how brightly one kind of entity or block entity glows; mods register via DynamicLightHandlers and get tracking, rebuilds and the lightmap for free
public interface DynamicLightHandler<T> {
    // Luminance in the vanilla 0-15 scale
    int getLuminance(T lightSource);

    // Whether this source is extinguished by being underwater
    default boolean isWaterSensitive(T lightSource) {
        return false;
    }

    static <T extends EntityLivingBase> DynamicLightHandler<T> makeHandler(
            Function<T, Integer> luminance, Function<T, Boolean> waterSensitive) {
        return new DynamicLightHandler<T>() {
            // Delegates to the function this handler was built with
            @Override
            public int getLuminance(T lightSource) {
                return luminance.apply(lightSource);
            }

            // Delegates to the function this handler was built with; explosive light always goes out underwater
            @Override
            public boolean isWaterSensitive(T lightSource) {
                return waterSensitive.apply(lightSource);
            }
        };
    }

    // Wraps handler so the entity's held and worn items count towards its luminance too
    static <T extends EntityLivingBase> DynamicLightHandler<T> makeLivingEntityHandler(
            DynamicLightHandler<T> handler) {
        return entity -> Math.max(
                DynamicLightsEngine.getLivingEntityLuminanceFromItems(entity),
                handler.getLuminance(entity));
    }

    // Creeper handler that follows the configured explosive lighting mode; handler is an extra one to max with, or null
    static <T extends EntityCreeper> DynamicLightHandler<T> makeCreeperEntityHandler(
            DynamicLightHandler<T> handler) {
        return new DynamicLightHandler<T>() {
            // Flash intensity drives brightness in FANCY mode; SIMPLE uses a constant once the fuse is lit
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

            // Explosive light always goes out underwater
            @Override
            public boolean isWaterSensitive(T lightSource) {
                return true;
            }
        };
    }
}
