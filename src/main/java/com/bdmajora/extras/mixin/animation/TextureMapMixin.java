package com.bdmajora.extras.mixin.animation;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Gates each animated sprite's tick on its category switch, so turning off (say) lava animation
 * stops paying for it rather than just hiding it.
 *
 * <p>Sodium Extra can ask the sprite what it is. 1.12.2 cannot: {@code TextureAtlasSprite} carries
 * only an icon name, so the category has to be recovered by matching that name. Matching is
 * substring-based on purpose — resource packs and mods prefix and suffix these names freely, and a
 * pack that renames {@code water_still} out of recognition falls through to "animate it", which is
 * the safe direction to be wrong in.
 *
 * <p>The table is built once per atlas on first use; it cannot be static because the sprite names a
 * given atlas contains are not known until it is stitched.
 */
@Mixin(TextureMap.class)
public abstract class TextureMapMixin {
    @Unique
    private Map<String, Predicate<ExtrasConfig.AnimationSettings>> impetus$categories;

    @WrapOperation(
            method = "updateAnimations",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;updateAnimation()V")
    )
    private void impetus$controlAnimation(TextureAtlasSprite sprite, Operation<Void> original) {
        if (impetus$shouldAnimate(sprite)) {
            original.call(sprite);
        }
    }

    @Unique
    private boolean impetus$shouldAnimate(TextureAtlasSprite sprite) {
        ExtrasConfig.AnimationSettings settings = Extras.options().animation;

        if (!settings.all) {
            return false;
        }

        if (sprite == null) {
            return true;
        }

        String iconName = sprite.getIconName();
        if (iconName == null) {
            return true;
        }

        if (impetus$categories == null) {
            impetus$categories = impetus$buildCategories();
        }

        for (Map.Entry<String, Predicate<ExtrasConfig.AnimationSettings>> entry : impetus$categories.entrySet()) {
            if (iconName.contains(entry.getKey())) {
                return entry.getValue().test(settings);
            }
        }

        // Anything unrecognised is covered by the general block-animation switch, which is what
        // makes that switch mean "everything else" rather than "a handful of vanilla blocks".
        return settings.blockAnimations;
    }

    @Unique
    private static Map<String, Predicate<ExtrasConfig.AnimationSettings>> impetus$buildCategories() {
        Map<String, Predicate<ExtrasConfig.AnimationSettings>> map = new HashMap<>();

        map.put("water_still", settings -> settings.water);
        map.put("water_flow", settings -> settings.water);
        map.put("water_overlay", settings -> settings.water);

        map.put("lava_still", settings -> settings.lava);
        map.put("lava_flow", settings -> settings.lava);

        map.put("fire_layer_0", settings -> settings.fire);
        map.put("fire_layer_1", settings -> settings.fire);

        map.put("portal", settings -> settings.portal);

        // OptiFine breaks these four out of the general block-animation switch.
        map.put("redstone", settings -> settings.redstone);
        map.put("explosion", settings -> settings.explosion);
        map.put("flame", settings -> settings.flame);
        map.put("smoke", settings -> settings.smoke);

        // 1.12.2 has no sculk sensor; the switch exists for mods that add one and name it so.
        map.put("sculk_sensor", settings -> settings.sculkSensor);

        return map;
    }
}
