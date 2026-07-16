package com.bdmajora.impetus.impl.extensions;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.bdmajora.impetus.engine.impl.util.collections.quadtree.QuadTree;

public interface TextureMapExtension {
    QuadTree<TextureAtlasSprite> impetus$getQuadTree();

    TextureAtlasSprite impetus$findFromUV(float u, float v);
}
