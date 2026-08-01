package com.bdmajora.impetus.iris.material;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.shaderpack.materialmap.BlockEntry;
import com.bdmajora.impetus.iris.shaderpack.materialmap.IdMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Resolves a pack's parsed {@code block.properties} entries against the 1.12.2 block registry — the port of Iris's
 * {@code BlockMaterialMapping}, producing a flat lookup table instead of a {@code BlockState} map so the meshing hot
 * path is a single array read.
 * <p>
 * Iris parity notes: entries resolve in declaration order and the <em>first</em> mapping of a state wins
 * (OptiFine behavior, see Iris issue #1327); property predicates are filters — a predicate naming a property the
 * block does not have is ignored; identifiers that don't resolve (modern-only block names behind the pack's
 * {@code MC_VERSION} guards, or missing mods) are skipped silently.
 */
public final class BlockMaterialMapping {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /** 1.12.2 global state ids are {@code blockId | meta << 12}: 12 + 4 bits. */
    private static final int STATE_ID_SPACE = 1 << 16;

    private BlockMaterialMapping() {
    }

    /**
     * @return the state-id → pack-id table, or {@code null} when the pack has no {@code block.properties} (raw
     * 1.12.2 IDs remain the contract then).
     */
    /**
     * Resolves the pack's {@code layer.<rendertype>} overrides to concrete blocks. Unknown ids are skipped with a
     * warning rather than failing the pack — a pack commonly lists blocks from mods the user does not have.
     */
    public static java.util.Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> createBlockRenderLayerTable(
            IdMap idMap) {
        java.util.Map<com.bdmajora.impetus.iris.shaderpack.materialmap.NamespacedId,
                net.minecraft.util.BlockRenderLayer> declared = idMap.getBlockRenderLayerMap();
        if (declared.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> resolved =
                new java.util.HashMap<>();
        declared.forEach((id, layer) -> {
            net.minecraft.block.Block block = net.minecraft.block.Block.REGISTRY.getObject(
                    new net.minecraft.util.ResourceLocation(id.getNamespace(), id.getName()));
            if (block == null || block == net.minecraft.init.Blocks.AIR) {
                LOGGER.warn("[Iris] block.properties: unknown block \"{}\" in a render-layer override", id);
                return;
            }
            resolved.put(block, layer);
        });
        return resolved;
    }

    public static int[] createBlockStateIdTable(IdMap idMap) {
        if (!idMap.hasBlockProperties()) {
            return null;
        }

        int[] table = new int[STATE_ID_SPACE];
        Arrays.fill(table, -1);

        int[] statesMapped = {0};
        idMap.getBlockProperties().forEach((intId, entries) -> {
            for (BlockEntry entry : entries) {
                statesMapped[0] += addBlockStates(entry, table, intId);
            }
        });

        LOGGER.info("[Iris] block.properties resolved: {} block state(s) mapped", statesMapped[0]);
        return table;
    }

    private static int addBlockStates(BlockEntry entry, int[] table, int intId) {
        ResourceLocation location = new ResourceLocation(entry.getId().getNamespace(), entry.getId().getName());
        if (!Block.REGISTRY.containsKey(location)) {
            // Normal and expected: modern-only names behind the pack's own MC_VERSION guards, or absent mods.
            LOGGER.debug("[Iris] block.{}: no block named {} on this version, skipping", intId, location);
            return 0;
        }

        Block block = Block.REGISTRY.getObject(location);
        Map<String, String> predicates = entry.getPropertyPredicates();
        int mapped = 0;

        for (IBlockState state : block.getBlockState().getValidStates()) {
            if (!matches(state, predicates, intId)) {
                continue;
            }
            int stateId = Block.getStateId(state) & (STATE_ID_SPACE - 1);
            // First mapping wins (Iris putIfAbsent / OptiFine parity).
            if (table[stateId] == -1) {
                table[stateId] = intId;
                mapped++;
            }
        }
        return mapped;
    }

    private static boolean matches(IBlockState state, Map<String, String> predicates, int intId) {
        if (predicates.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> predicate : predicates.entrySet()) {
            IProperty<?> property = findProperty(state, predicate.getKey());
            if (property == null) {
                // Iris parity: a predicate naming a property the block lacks is dropped, not treated as a mismatch.
                LOGGER.debug("[Iris] block.{}: {} has no property named {}, ignoring that filter",
                        intId, state.getBlock().getRegistryName(), predicate.getKey());
                continue;
            }
            if (!valueName(state, property).equals(predicate.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static IProperty<?> findProperty(IBlockState state, String name) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueName(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }
}
