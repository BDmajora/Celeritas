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

// Gates each animated sprite's tick on its category switch, so disabling lava animation stops paying for it, not just hiding it
// 1.12.2 sprites carry only an icon name, so the category is recovered by substring match; unmatched names fall through to "animate it"
// Table is built once per atlas on first use, not static, since sprite names aren't known until the atlas is stitched
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
