package com.bdmajora.fulgor.mixin.client;

import com.bdmajora.fulgor.api.LightUpdateProcessor;
import com.bdmajora.fulgor.collections.DeduplicatedLongQueue;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Replaces the renderer's light-update collection and the rule for when it is drained.
 *
 * <p>Vanilla keeps a {@code HashSet<BlockPos>} of positions whose light changed and empties it from
 * {@code updateClouds}, but only when the chunk builder has a free thread and no rebuilds are already
 * queued. Under any real load neither holds, so the set is skipped tick after tick and light changes
 * go unrendered until the load happens to drop — MC-80966. It also grows without bound while that is
 * happening, one immutable {@code BlockPos} per entry.
 *
 * <p>Fulgor swaps in a {@link DeduplicatedLongQueue} — no per-entry object, no iterator, and repeats
 * collapsed, which matters because a single light change notifies every position it touched — and
 * moves the drain to the client tick, unconditionally. See {@link MinecraftMixin} for the ordering.
 */
@SideOnly(Side.CLIENT)
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin implements LightUpdateProcessor {
    /**
     * One tick's worth of changed positions.
     *
     * <p>Always deduplicating, unlike the engine's queues: there is no diagnostic reason to want the
     * repeats here, and a single light change routinely notifies the same position from several sides.
     */
    @Unique
    private final DeduplicatedLongQueue fulgor$lightUpdates =
            new DeduplicatedLongQueue(new DeduplicatedLongQueue.Pool(), 1024, true);

    @Shadow
    protected abstract void markBlocksForUpdate(int minX, int minY, int minZ,
                                                int maxX, int maxY, int maxZ, boolean updateImmediately);

    /**
     * @reason Collect into a long queue instead of a set of boxed positions.
     * @author Luna Mira Lage (Alfheim)
     */
    @Overwrite
    public void notifyLightSet(BlockPos pos) {
        this.fulgor$lightUpdates.enqueue(pos.toLong());
    }

    /**
     * Disables vanilla's drain by claiming its collection is empty.
     *
     * <p>Targets the first {@code Set.isEmpty()} in the method, which is the light-update set; the
     * second belongs to the pending-rebuild check and is left alone. Reporting empty short-circuits the
     * whole condition, so the builder-availability check never runs either.
     */
    @Redirect(method = "updateClouds",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;isEmpty()Z", ordinal = 0))
    private boolean fulgor$disableVanillaDrain(Set<?> lightUpdates) {
        return true;
    }

    /**
     * Drains the queue into chunk rebuilds.
     *
     * <p>The deduplication reset goes after the drain here, not before it as in the engine: nothing is
     * enqueued while this runs, and the set has to be empty before the next tick's notifications start
     * arriving or a position that changed on two consecutive ticks would be dropped from the second.
     */
    @Override
    public void fulgor$processLightUpdates() {
        while (!this.fulgor$lightUpdates.isEmpty()) {
            BlockPos pos = BlockPos.fromLong(this.fulgor$lightUpdates.dequeue());

            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            // A one-block margin, matching vanilla: a light change at a section edge alters the
            // ambient occlusion of the neighbouring section's face too.
            this.markBlocksForUpdate(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1, false);
        }

        this.fulgor$lightUpdates.resetDeduplication();
    }
}
