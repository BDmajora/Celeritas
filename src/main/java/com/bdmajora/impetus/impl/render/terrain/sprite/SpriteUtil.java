package com.bdmajora.impetus.impl.render.terrain.sprite;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.bdmajora.impetus.impl.extensions.SpriteExtension;

public class SpriteUtil {
    public static void markSpriteActive(TextureAtlasSprite sprite) {
        ((SpriteExtension)sprite).impetus$markActive();
    }
}
