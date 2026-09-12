package com.bdmajora.impetus.impl.render.terrain.sprite;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.bdmajora.impetus.impl.extensions.SpriteExtension;

public class SpriteUtil {
    // Flags a sprite as drawn this frame, which is what lets it animate
    public static void markSpriteActive(TextureAtlasSprite sprite) {
        ((SpriteExtension)sprite).impetus$markActive();
    }
}
