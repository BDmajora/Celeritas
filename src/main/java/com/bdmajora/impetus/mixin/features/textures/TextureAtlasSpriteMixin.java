package com.bdmajora.impetus.mixin.features.textures;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.bdmajora.impetus.impl.extensions.SpriteExtension;

@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteMixin implements SpriteExtension {
    private boolean impetus$isActive = false;

    @Override
    public void impetus$markActive() {
        this.impetus$isActive = true;
    }

    @Override
    public boolean impetus$shouldUpdate() {
        if (this.impetus$isActive) {
            this.impetus$isActive = false;
            return true;
        } else {
            return false;
        }
    }

    // Any UV read means the sprite is being drawn, which is what makes it eligible to animate
    @ModifyReturnValue(method = { "getMinU", "getInterpolatedU" }, at = @At("RETURN"))
    private float markActiveWhenGettingCoords(float original) {
        this.impetus$isActive = true;
        return original;
    }
}
