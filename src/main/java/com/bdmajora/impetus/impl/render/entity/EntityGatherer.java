package com.bdmajora.impetus.impl.render.entity;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.chunk.Chunk;
import com.bdmajora.impetus.mixin.core.terrain.ChunkAccessor;
import com.bdmajora.impetus.mixin.core.terrain.ChunkProviderClientAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class EntityGatherer {
    public static final int NUM_PASSES = 2;

    private final List<Entity>[] entityLists;
    private final Set<Entity> seenEntities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<Entity> addEntity;

    @SuppressWarnings("unchecked")
    public EntityGatherer() {
        this.entityLists = new List[NUM_PASSES];
        for (int i = 0; i < NUM_PASSES; i++) {
            this.entityLists[i] = new ArrayList<>();
        }
        var entityLists = this.entityLists;
        this.addEntity = entity -> {
            if (!this.seenEntities.add(entity)) {
                return;
            }
            for (int i = 0; i < NUM_PASSES; i++) {
                if (entity.shouldRenderInPass(i)) {
                    entityLists[i].add(entity);
                }
            }
        };
    }

    // Empties the per-section lists between frames
    public void clear() {
        for (int i = 0; i < NUM_PASSES; i++) {
            entityLists[i].clear();
        }
        this.seenEntities.clear();
    }

    // Buckets loaded entities by render section so the renderer can skip invisible sections
    public List<Entity>[] getLoadedEntityList(WorldClient world) {
        Consumer<Entity> addEntity = this.addEntity;
        // Iterate chunk entity lists directly where possible; mods may create multipart entities not added to loadedEntityList
        if (world.getChunkProvider() instanceof ChunkProviderClientAccessor provider) {
            var loadedChunks = provider.impetus$getLoadedChunks();
            for (Chunk chunk : loadedChunks.values()) {
                if (!((ChunkAccessor)chunk).impetus$getHasEntities()) {
                    continue;
                }
                ClassInheritanceMultiMap<Entity>[] entityMaps = chunk.getEntityLists();
                for (ClassInheritanceMultiMap<Entity> map : entityMaps) {
                    map.forEach(addEntity);
                }
            }
        }
        // Keep the vanilla loaded list too; some mods keep renderable proxies here without reliable chunk membership.
        world.loadedEntityList.forEach(addEntity);
        return this.entityLists;
    }
}
