package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.client.item.ItemLightSources;
import com.bdmajora.dynamiclights.mixin.RenderGlobalRebuildAccessor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

/**
 * The tracked light sources, and the lightmap arithmetic over them.
 *
 * <p>Read from three kinds of thread: the client thread ticks sources, the render thread calls
 * {@link #updateAll}, and the chunk-builder workers call {@link #getDynamicLightLevel} for every
 * block position they compile. The set is guarded by a read/write lock accordingly, with
 * {@link #sourceCount} as a lock-free fast path so the overwhelmingly common "nothing is glowing"
 * case costs a single volatile read rather than a lock acquisition per block.
 */
public final class DynamicLightsEngine {
    private static final DynamicLightsEngine INSTANCE = new DynamicLightsEngine();

    /**
     * How far a source's light reaches, in blocks.
     *
     * <p>Deliberately short of vanilla's 15: every extra block of radius is another ring of chunk
     * sections to rebuild whenever the source moves.
     */
    private static final double MAX_RADIUS = 7.75;
    private static final double MAX_RADIUS_SQUARED = MAX_RADIUS * MAX_RADIUS;

    /** Luminance is clamped here rather than at 15 so a source never reads as a full-bright block. */
    private static final int MAX_LUMINANCE = 14;

    private final Set<DynamicLightSource> dynamicLightSources = new HashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Mirrors {@code dynamicLightSources.size()} for readers that must not take the lock. */
    private volatile int sourceCount;

    private long lastUpdate = System.currentTimeMillis();
    private int lastUpdateCount;

    private DynamicLightsEngine() {
    }

    public static DynamicLightsEngine get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------------------------------
    // Per-frame update
    // ------------------------------------------------------------------------------------------

    /**
     * Gives every tracked source the chance to re-light the chunks around it.
     *
     * <p>Rate-limited to once per 50ms — a tick — because scheduling chunk rebuilds more often than
     * the sources can actually move is pure waste.
     */
    public void updateAll(RenderGlobal renderer) {
        if (!DynamicLights.options().mode.isEnabled() || this.sourceCount == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < this.lastUpdate + 50L) {
            return;
        }

        this.lastUpdate = now;
        int updated = 0;

        this.lock.readLock().lock();
        try {
            for (DynamicLightSource source : this.dynamicLightSources) {
                if (source.impetus$updateDynamicLight(renderer)) {
                    updated++;
                }
            }
        } finally {
            this.lock.readLock().unlock();
        }

        this.lastUpdateCount = updated;
    }

    /** How many sources scheduled a rebuild on the last update pass. For the F3 overlay. */
    public int getLastUpdateCount() {
        return this.lastUpdateCount;
    }

    /** How many sources are currently tracked. For the F3 overlay. */
    public int getLightSourcesCount() {
        return this.sourceCount;
    }

    // ------------------------------------------------------------------------------------------
    // Lightmap arithmetic
    // ------------------------------------------------------------------------------------------

    /** Folds the dynamic light at {@code pos} into a packed vanilla lightmap coordinate. */
    public int getLightmapWithDynamicLight(BlockPos pos, int lightmap) {
        return this.getLightmapWithDynamicLight(this.getDynamicLightLevel(pos), lightmap);
    }

    /**
     * Folds both the light at the entity's feet and the entity's own luminance into {@code lightmap}.
     *
     * <p>Runs once per rendered entity per frame, so the empty case returns before allocating the
     * {@link BlockPos} the position lookup would need.
     */
    public int getLightmapWithDynamicLight(Entity entity, int lightmap) {
        if (this.sourceCount == 0) {
            return lightmap;
        }

        int atPosition = (int) this.getDynamicLightLevel(new BlockPos(entity.posX, entity.posY, entity.posZ));
        int ownLuminance = ((DynamicLightSource) entity).impetus$getLuminance();

        return this.getLightmapWithDynamicLight(Math.max(atPosition, ownLuminance), lightmap);
    }

    /**
     * Raises the block-light half of {@code lightmap} to {@code dynamicLightLevel} if that is brighter.
     *
     * <p>Sky light is carried through untouched — a torch does not make it feel like daytime.
     */
    public int getLightmapWithDynamicLight(double dynamicLightLevel, int lightmap) {
        if (dynamicLightLevel <= 0) {
            return lightmap;
        }

        int blockLight = (lightmap >> 4) & 0xF;
        int skyLight = (lightmap >> 20) & 0xF;

        if (dynamicLightLevel > blockLight) {
            blockLight = (int) dynamicLightLevel;
        }

        return (skyLight << 20) | (blockLight << 4);
    }

    /**
     * The brightest dynamic light reaching {@code pos}, in the 0-15 scale.
     *
     * <p>On the hot path: called once per block position per chunk-section compile. The empty-set
     * check short-circuits before the lock, which is what keeps dynamic lights from costing anything
     * measurable when nothing is glowing.
     */
    public double getDynamicLightLevel(BlockPos pos) {
        if (this.sourceCount == 0) {
            return 0.0D;
        }

        double result = 0.0D;

        this.lock.readLock().lock();
        try {
            for (DynamicLightSource source : this.dynamicLightSources) {
                result = maxDynamicLightLevel(pos, source, result);
            }
        } finally {
            this.lock.readLock().unlock();
        }

        return result < 0.0D ? 0.0D : Math.min(result, 15.0D);
    }

    /**
     * {@code currentLightLevel}, or this source's contribution at {@code pos} if that is brighter.
     *
     * <p>Falls off linearly with distance rather than by vanilla's per-block subtraction: there is no
     * block grid to step along, and a smooth ramp is what stops a moving source from visibly banding.
     */
    public static double maxDynamicLightLevel(BlockPos pos, DynamicLightSource lightSource,
                                              double currentLightLevel) {
        int luminance = lightSource.impetus$getLuminance();
        if (luminance <= 0) {
            return currentLightLevel;
        }

        // Not Entity#getDistanceSq: the source's Y is its eye height, not its feet.
        double dx = (pos.getX() + 0.5D) - lightSource.impetus$getDynamicLightX();
        double dy = (pos.getY() + 0.5D) - lightSource.impetus$getDynamicLightY();
        double dz = (pos.getZ() + 0.5D) - lightSource.impetus$getDynamicLightZ();

        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > MAX_RADIUS_SQUARED) {
            return currentLightLevel;
        }

        double lightLevel = (1.0D - Math.sqrt(distanceSquared) / MAX_RADIUS) * luminance;
        return lightLevel > currentLightLevel ? lightLevel : currentLightLevel;
    }

    // ------------------------------------------------------------------------------------------
    // The tracked set
    // ------------------------------------------------------------------------------------------

    public void addLightSource(DynamicLightSource lightSource) {
        World world = lightSource.impetus$getDynamicLightWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        if (!DynamicLights.options().mode.isEnabled() || this.containsLightSource(lightSource)) {
            return;
        }

        this.lock.writeLock().lock();
        try {
            if (this.dynamicLightSources.add(lightSource)) {
                this.sourceCount = this.dynamicLightSources.size();
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public boolean containsLightSource(DynamicLightSource lightSource) {
        World world = lightSource.impetus$getDynamicLightWorld();
        if (world == null || !world.isRemote || this.sourceCount == 0) {
            return false;
        }

        this.lock.readLock().lock();
        try {
            return this.dynamicLightSources.contains(lightSource);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void removeLightSource(DynamicLightSource lightSource) {
        this.lock.writeLock().lock();
        try {
            if (this.dynamicLightSources.remove(lightSource)) {
                this.sourceCount = this.dynamicLightSources.size();
                lightSource.impetus$scheduleTrackedChunksRebuild(Minecraft.getMinecraft().renderGlobal);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /** Drops every source and re-lights whatever they were lighting. */
    public void clearLightSources() {
        this.lock.writeLock().lock();
        try {
            RenderGlobal renderer = Minecraft.getMinecraft().renderGlobal;

            for (Iterator<DynamicLightSource> it = this.dynamicLightSources.iterator(); it.hasNext(); ) {
                DynamicLightSource source = it.next();
                it.remove();

                if (source.impetus$getLuminance() > 0) {
                    source.impetus$resetDynamicLight();
                }
                source.impetus$scheduleTrackedChunksRebuild(renderer);
            }

            this.sourceCount = 0;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Drops every source matching {@code filter}.
     *
     * <p>Upstream breaks out of this loop after the first match, which silently leaves the rest of a
     * matching group tracked — turning "Entities" off would drop one mob and no more. Removing every
     * match is what the callers below actually mean.
     */
    public void removeLightSources(Predicate<DynamicLightSource> filter) {
        this.lock.writeLock().lock();
        try {
            RenderGlobal renderer = Minecraft.getMinecraft().renderGlobal;
            boolean changed = false;

            for (Iterator<DynamicLightSource> it = this.dynamicLightSources.iterator(); it.hasNext(); ) {
                DynamicLightSource source = it.next();
                if (!filter.test(source)) {
                    continue;
                }

                it.remove();
                changed = true;

                if (source.impetus$getLuminance() > 0) {
                    source.impetus$resetDynamicLight();
                }
                source.impetus$scheduleTrackedChunksRebuild(renderer);
            }

            if (changed) {
                this.sourceCount = this.dynamicLightSources.size();
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void removeEntitiesLightSource() {
        this.removeLightSources(source -> source instanceof Entity && !(source instanceof EntityPlayer));
    }

    public void removeCreeperLightSources() {
        this.removeLightSources(source -> source instanceof EntityCreeper);
    }

    public void removeTntLightSources() {
        this.removeLightSources(source -> source instanceof EntityTNTPrimed);
    }

    public void removeBlockEntitiesLightSource() {
        this.removeLightSources(source -> source instanceof TileEntity);
    }

    // ------------------------------------------------------------------------------------------
    // Tracking and chunk rebuilds
    // ------------------------------------------------------------------------------------------

    /** Starts tracking a source that has become lit, or stops tracking one that has gone dark. */
    public static void updateTracking(DynamicLightSource lightSource) {
        boolean enabled = lightSource.impetus$isDynamicLightEnabled();
        int luminance = lightSource.impetus$getLuminance();

        if (!enabled && luminance > 0) {
            lightSource.impetus$setDynamicLightEnabled(true);
        } else if (enabled && luminance < 1) {
            lightSource.impetus$setDynamicLightEnabled(false);
        }
    }

    public static void scheduleChunkRebuild(RenderGlobal renderer, BlockPos chunkPos) {
        scheduleChunkRebuild(renderer, chunkPos.getX(), chunkPos.getY(), chunkPos.getZ());
    }

    public static void scheduleChunkRebuild(RenderGlobal renderer, long packedChunkPos) {
        scheduleChunkRebuild(renderer, unpackX(packedChunkPos), unpackY(packedChunkPos), unpackZ(packedChunkPos));
    }

    /**
     * Queues a rebuild of the section at chunk coordinates {@code (x, y, z)}.
     *
     * <p>Goes through {@code markBlocksForUpdate}, which Impetus overwrites to route into its own
     * chunk renderer — so this schedules an Impetus section rebuild, not a vanilla one.
     */
    public static void scheduleChunkRebuild(RenderGlobal renderer, int x, int y, int z) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }

        int minX = x << 4;
        int minY = y << 4;
        int minZ = z << 4;

        ((RenderGlobalRebuildAccessor) renderer)
                .impetus$markBlocksForUpdate(minX, minY, minZ, minX + 15, minY + 15, minZ + 15, false);
    }

    /** Moves {@code chunkPos} from the {@code old} tracked set into {@code newPos}. */
    public static void updateTrackedChunks(BlockPos chunkPos, LongOpenHashSet old, LongOpenHashSet newPos) {
        if (old == null && newPos == null) {
            return;
        }

        long packed = packChunkPos(chunkPos);
        if (old != null) {
            old.remove(packed);
        }
        if (newPos != null) {
            newPos.add(packed);
        }
    }

    /** Packs signed 26/12/26-bit chunk coordinates into one long. */
    public static long packChunkPos(BlockPos pos) {
        return (((long) pos.getX() & 0x3FFFFFFL) << 38)
                | (((long) pos.getY() & 0xFFFL) << 26)
                | ((long) pos.getZ() & 0x3FFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) ((packed >> 26) & 0xFFFL);
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 38 >> 38);
    }

    // ------------------------------------------------------------------------------------------
    // Item luminance
    // ------------------------------------------------------------------------------------------

    /** True when the entity's eyes are inside a fluid and the water-sensitivity check is on. */
    public static boolean isEyeSubmergedInFluid(EntityLivingBase entity) {
        return DynamicLights.options().waterSensitiveCheck && FluidHandler.isFluid(entity);
    }

    /**
     * The brightest item the entity is holding or wearing.
     *
     * <p>Runs for every living entity every tick, so the submersion test is deferred until a non-empty
     * stack is actually found. That test costs a block lookup, and most mobs in a loaded world are
     * carrying nothing at all — resolving it up front would spend a chunk read per cow per tick.
     *
     * <p>{@code submerged} is tri-state rather than a {@code Boolean} to keep the deferral allocation-free.
     */
    public static int getLivingEntityLuminanceFromItems(EntityLivingBase entity) {
        int luminance = 0;
        int submerged = SUBMERSION_UNKNOWN;

        for (ItemStack equipped : entity.getHeldEquipment()) {
            if (equipped.isEmpty()) {
                continue;
            }
            if (submerged == SUBMERSION_UNKNOWN) {
                submerged = isEyeSubmergedInFluid(entity) ? 1 : 0;
            }
            luminance = Math.max(luminance, getLuminanceFromItemStack(equipped, submerged == 1));
        }

        for (ItemStack armor : entity.getArmorInventoryList()) {
            if (armor.isEmpty()) {
                continue;
            }
            if (submerged == SUBMERSION_UNKNOWN) {
                submerged = isEyeSubmergedInFluid(entity) ? 1 : 0;
            }
            luminance = Math.max(luminance, getLuminanceFromItemStack(armor, submerged == 1));
        }

        return luminance;
    }

    /** Sentinel for "the submersion test has not been run yet"; see above. */
    private static final int SUBMERSION_UNKNOWN = -1;

    /**
     * The luminance of an item stack, capped at {@link #MAX_LUMINANCE}.
     *
     * <p>A glowstone block is luminance 15, but a source at 15 would read as full-bright and defeat
     * the distance falloff — so held light tops out one step below the brightest block.
     */
    public static int getLuminanceFromItemStack(ItemStack stack, boolean submergedInWater) {
        int luminance = ItemLightSources.getLuminance(stack, submergedInWater);
        return Math.min(luminance, MAX_LUMINANCE);
    }
}
