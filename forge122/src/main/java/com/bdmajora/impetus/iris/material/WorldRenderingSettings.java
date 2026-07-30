package com.bdmajora.impetus.iris.material;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import com.bdmajora.impetus.iris.shaderpack.materialmap.NamespacedId;

import java.util.Map;

/**
 * Render-thread-published, mesher-thread-consumed settings derived from the active shader pack — the (much smaller)
 * Impetus counterpart of Iris's {@code WorldRenderingSettings}. The pipeline publishes on construction and clears
 * on destroy; chunk-build workers only read.
 */
public final class WorldRenderingSettings {
    /**
     * The pack's {@code block.properties} mapping as a flat table indexed by the 1.12.2 global state id
     * ({@code Block.getStateId}: blockId | meta << 12, 16 bits), holding the pack's ID for that state or {@code -1}
     * (Iris parity: blocks the pack does not map get {@code mc_Entity.x = -1}).
     * <p>
     * {@code null} when no pack is active or the pack ships no {@code block.properties} — the mesher then emits raw
     * 1.12.2 block IDs and metadata, which is what classic OptiFine-era packs expect.
     */
    private static volatile int[] blockStateIds;
    private static volatile Map<NamespacedId, Integer> itemIds;
    private static volatile Map<NamespacedId, Integer> entityIds;
    private static volatile int voxelRenderDistanceChunks;

    private WorldRenderingSettings() {
    }

    public static int[] getBlockStateIds() {
        return blockStateIds;
    }

    public static void setBlockStateIds(int[] table) {
        blockStateIds = table;
    }

    public static Map<NamespacedId, Integer> getItemIds() {
        return itemIds;
    }

    public static void setItemIds(Map<NamespacedId, Integer> table) {
        itemIds = table;
    }

    public static Map<NamespacedId, Integer> getEntityIds() {
        return entityIds;
    }

    public static void setEntityIds(Map<NamespacedId, Integer> table) {
        entityIds = table;
    }

    public static int getVoxelRenderDistanceChunks() {
        return voxelRenderDistanceChunks;
    }

    public static void setVoxelRenderDistanceChunks(int chunks) {
        voxelRenderDistanceChunks = Math.max(0, chunks);
    }

    public static int getBlockStateId(IBlockState state) {
        if (state == null) {
            return -1;
        }
        int rawStateId = Block.getStateId(state) & 0xFFFF;
        int[] table = blockStateIds;
        if (table == null) {
            return Block.getIdFromBlock(state.getBlock());
        }
        return rawStateId < table.length ? table[rawStateId] : -1;
    }

    public static int getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }

        Item item = stack.getItem();
        ResourceLocation key = item.getRegistryName();
        int rawId = Item.getIdFromItem(item);
        return mappedOrRaw(itemIds, key, rawId);
    }

    public static int getEntityId(Entity entity) {
        if (entity == null) {
            return -1;
        }

        ResourceLocation key = EntityList.getKey(entity);
        int rawId = EntityList.getID(entity.getClass());
        return mappedOrRaw(entityIds, key, rawId);
    }

    public static int getBlockEntityId(TileEntity tileEntity) {
        if (tileEntity == null) {
            return -1;
        }

        ResourceLocation key = TileEntity.getKey(tileEntity.getClass());
        return mappedOrRaw(entityIds, key, -1);
    }

    private static int mappedOrRaw(Map<NamespacedId, Integer> map, ResourceLocation key, int rawId) {
        if (map == null || map.isEmpty()) {
            return rawId;
        }
        if (key == null) {
            return -1;
        }
        Integer mapped = map.get(new NamespacedId(key.getNamespace(), key.getPath()));
        return mapped != null ? mapped : -1;
    }
}
