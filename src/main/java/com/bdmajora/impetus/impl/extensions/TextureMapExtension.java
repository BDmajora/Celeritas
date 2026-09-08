package com.bdmajora.impetus.impl.extensions;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.bdmajora.impetus.engine.impl.util.collections.quadtree.QuadTree;

// Extension mixed into the texture atlas; lets us spatially query sprites by UV instead of scanning the whole map
public interface TextureMapExtension {
    QuadTree<TextureAtlasSprite> impetus$getQuadTree();

    TextureAtlasSprite impetus$findFromUV(float u, float v);
}
