package com.bdmajora.impetus.engine.impl.render.chunk.sprite;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import com.bdmajora.impetus.engine.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.ChunkRenderList;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.SectionTicker;

import java.util.List;
import java.util.function.Consumer;

public class GenericSectionSpriteTicker<T> implements SectionTicker {
    private volatile ReferenceOpenHashSet<T> sprites = new ReferenceOpenHashSet<>();

    private final Consumer<T> markActive;

    public GenericSectionSpriteTicker(Consumer<T> markActive) {
        this.markActive = markActive;
    }

    // Marks every sprite in a visible section as active
    @Override
    public void tickVisibleRenders() {
        this.sprites.forEach(this.markActive);
    }

    // Sprite count
    @Override
    public String getDebugString() {
        return "A: " + this.sprites.size();
    }

    // Caches the visible sprite set from the new lists
    @Override
    public void onRenderListUpdated(List<ChunkRenderList> renderLists) {
        var spriteSet = new ReferenceOpenHashSet<T>(this.sprites.size());

        for (ChunkRenderList renderList : renderLists) {
            var region = renderList.getRegion();
            var iterator = renderList.sectionsWithSpritesIterator();

            if (iterator == null) {
                continue;
            }

            while (iterator.hasNext()) {
                var section = region.getSection(iterator.nextByteAsInt());

                if (section == null) {
                    continue;
                }

                var context = section.getBuiltContext();

                if (!(context instanceof MinecraftBuiltRenderSectionData<?, ?> mcData)) {
                    continue;
                }

                //noinspection unchecked
                var sprites = (List<T>) mcData.animatedSprites;

                // Indexed loop on purpose: the iterator allocation is very expensive at large render distances (noinspection ForLoopReplaceableByForEach)
                for (int i = 0; i < sprites.size(); i++) {
                    //noinspection UseBulkOperation
                    spriteSet.add(sprites.get(i));
                }
            }
        }

        this.sprites = spriteSet;
    }
}
