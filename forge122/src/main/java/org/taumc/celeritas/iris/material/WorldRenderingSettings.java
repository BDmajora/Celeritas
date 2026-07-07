package org.taumc.celeritas.iris.material;

/**
 * Render-thread-published, mesher-thread-consumed settings derived from the active shader pack — the (much smaller)
 * Celeritas counterpart of Iris's {@code WorldRenderingSettings}. The pipeline publishes on construction and clears
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

    private WorldRenderingSettings() {
    }

    public static int[] getBlockStateIds() {
        return blockStateIds;
    }

    public static void setBlockStateIds(int[] table) {
        blockStateIds = table;
    }
}
