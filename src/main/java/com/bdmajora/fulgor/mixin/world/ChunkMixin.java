package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.api.ChunkLightingData;
import com.bdmajora.fulgor.api.LightingEngineProvider;
import com.bdmajora.fulgor.lighting.LightingEngine;
import com.bdmajora.fulgor.lighting.LightingHooks;
import com.bdmajora.fulgor.lighting.WorldChunkSlice;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// where the chunk stops calculating light itself and starts asking the engine
// four vanilla methods are replaced outright: three of them - getLightFor, checkLight, recheckGaps -
// because their vanilla bodies would fight the engine for the same data, and the fourth, relightBlock,
// because vanilla's version silently drops the cross-chunk half of its job
// @Overwrite is used rather than a cancelling inject in exactly the cases where leaving the vanilla
// body reachable would be a bug rather than dead weight
@Mixin(Chunk.class)
public abstract class ChunkMixin implements ChunkLightingData, LightingEngineProvider {
    @Unique
    private static final EnumFacing[] FULGOR_HORIZONTALS = EnumFacing.Plane.HORIZONTAL.facings();

    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    private ExtendedBlockStorage[] storageArrays;

    @Shadow
    @Final
    private int[] heightMap;

    @Shadow
    @Final
    private boolean[] updateSkylightColumns;

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Shadow
    private int heightMapMinimum;

    @Shadow
    private boolean dirty;

    @Shadow
    private boolean isTerrainPopulated;

    @Shadow
    private boolean isGapLightingUpdated;

    @Shadow
    public abstract boolean canSeeSky(BlockPos pos);

    @Shadow
    public abstract int getHeightValue(int x, int z);

    @Shadow
    protected abstract int getBlockLightOpacity(int x, int y, int z);

    @Shadow
    protected abstract void setSkylightUpdated();

    @Unique
    private LightingEngine fulgor$lightingEngine;

    @Unique
    private short[] fulgor$neighborLightChecks;

    @Unique
    private boolean fulgor$lightInitialized;

    // caches the world's engine on the chunk
    // getLightFor is one of the most-called methods in the game and every call needs the engine, so
    // going through the world each time would add an interface dispatch and a field read to all of them
    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"))
    private void fulgor$captureLightingEngine(World world, int x, int z, CallbackInfo ci) {
        this.fulgor$lightingEngine = ((LightingEngineProvider) world).fulgor$getLightingEngine();
    }

    // getLightSubtracted reads both light types at once and is the entity/rendering path's way in, so
    // it flushes both queues rather than going through getLightFor twice
    @Inject(method = "getLightSubtracted", at = @At("HEAD"))
    private void fulgor$flushBeforeLightSubtracted(BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        this.fulgor$lightingEngine.processLightUpdates();
    }

    // replays the boundary checks this chunk and its neighbours owe each other
    // loading a chunk is the only event that can make a previously impossible boundary crossing
    // possible, which is why the replay hangs off here rather than off a tick
    @Inject(method = "onLoad", at = @At("RETURN"))
    private void fulgor$replayBoundaryChecks(CallbackInfo ci) {
        LightingHooks.scheduleRelightChecksForChunkBoundaries(this.world, (Chunk) (Object) this);
    }

    // setLightFor rebuilds the whole chunk's skylight map when it has to create a section
    // the engine calls setLightFor for every position it writes, so leaving that in place would mean a
    // full-column rebuild in the middle of a propagation pass - both ruinously slow and liable to
    // overwrite what the pass just decided
    // only the new section needs seeding
    @Redirect(
            method = "setLightFor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;generateSkylightMap()V"),
            expect = 0)
    private void fulgor$seedNewSectionOnly(Chunk chunk, EnumSkyBlock lightType, BlockPos pos, int value) {
        LightingHooks.initSkylightForSection(this.world, (Chunk) (Object) this, this.storageArrays[pos.getY() >> 4]);
    }

    // vanilla relights the column but drops the part of the job that crosses into a neighbour that is
    // not loaded, which is where world-generation skylight seams come from
    // this version hands the column to LightingHooks#relightSkylightColumn instead, which records what
    // it cannot do now so it can be done on load
    // @author / @reason are Mixin's required metadata on an @Overwrite, not documentation
    @Overwrite
    private void relightBlock(int x, int y, int z) {
        int oldHeight = this.heightMap[z << 4 | x] & 255;
        int newHeight = Math.max(y, oldHeight);

        while (newHeight > 0 && this.getBlockLightOpacity(x, newHeight - 1, z) == 0) {
            newHeight--;
        }

        if (newHeight == oldHeight) {
            return;
        }

        this.heightMap[z << 4 | x] = newHeight;

        if (this.world.provider.hasSkyLight()) {
            LightingHooks.relightSkylightColumn(this.world, (Chunk) (Object) this, x, z, oldHeight, newHeight);
        }

        if (newHeight < this.heightMapMinimum) {
            this.heightMapMinimum = newHeight;
        }
    }

    // the single point where deferral becomes visible: anything reading light gets whatever is pending
    // resolved first
    // only the requested type is flushed, since the two propagate independently and a block-light read
    // has no reason to pay for pending skylight
    // @author / @reason are Mixin's required metadata on an @Overwrite, not documentation
    @Overwrite
    public int getLightFor(EnumSkyBlock lightType, BlockPos pos) {
        this.fulgor$lightingEngine.processLightUpdatesForType(lightType);

        return this.fulgor$getCachedLightFor(lightType, pos);
    }

    // vanilla walks all 256 columns and relights each one immediately, against whatever neighbours
    // happen to exist
    // this seeds the emitting blocks into the engine instead and defers declaring the chunk lit until
    // its whole neighbourhood is lit too
    // @author / @reason are Mixin's required metadata on an @Overwrite, not documentation
    @Overwrite
    public void checkLight() {
        this.isTerrainPopulated = true;

        LightingHooks.checkChunkLighting(this.world, (Chunk) (Object) this);
    }

    // functionally vanilla, but the 1024 chunk-provider lookups it performs are replaced by one 5x5
    // snapshot; the vanilla body cannot simply be redirected because the lookups are spread across
    // four private helpers
    // @author / @reason are Mixin's required metadata on an @Overwrite, not documentation
    @Overwrite
    private void recheckGaps(boolean onlyOne) {
        this.world.profiler.startSection("recheckGaps");

        try {
            if (!this.world.isAreaLoaded(new BlockPos((this.x << 4) + 8, 0, (this.z << 4) + 8), 16)) {
                return;
            }

            WorldChunkSlice slice = new WorldChunkSlice(this.world.getChunkProvider(), this.x, this.z);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (this.fulgor$recheckGapsForColumn(slice, x, z) && onlyOne) {
                        return;
                    }
                }
            }

            this.isGapLightingUpdated = false;
        } finally {
            this.world.profiler.endSection();
        }
    }

    @Unique
    private boolean fulgor$recheckGapsForColumn(WorldChunkSlice slice, int x, int z) {
        int index = x + z * 16;

        if (!this.updateSkylightColumns[index]) {
            return false;
        }

        this.updateSkylightColumns[index] = false;

        int worldX = (this.x << 4) + x;
        int worldZ = (this.z << 4) + z;

        int height = this.getHeightValue(x, z);
        int lowestNeighborHeight = this.fulgor$lowestNeighborHeight(slice, worldX, worldZ);

        this.fulgor$checkSkylightNeighborHeight(slice, worldX, worldZ, lowestNeighborHeight);

        for (EnumFacing facing : FULGOR_HORIZONTALS) {
            this.fulgor$checkSkylightNeighborHeight(slice,
                    worldX + facing.getXOffset(), worldZ + facing.getZOffset(), height);
        }

        return true;
    }

    @Unique
    private int fulgor$lowestNeighborHeight(WorldChunkSlice slice, int x, int z) {
        int lowest = Integer.MAX_VALUE;

        for (EnumFacing facing : FULGOR_HORIZONTALS) {
            Chunk chunk = slice.getChunkFromWorldCoords(x + facing.getXOffset(), z + facing.getZOffset());

            if (chunk != null) {
                lowest = Math.min(lowest, chunk.getLowestHeight());
            }
        }

        return lowest;
    }

    @Unique
    private void fulgor$checkSkylightNeighborHeight(WorldChunkSlice slice, int x, int z, int maxValue) {
        Chunk chunk = slice.getChunkFromWorldCoords(x, z);

        if (chunk == null) {
            return;
        }

        int height = chunk.getHeightValue(x & 15, z & 15);

        if (height > maxValue) {
            this.fulgor$updateSkylightNeighborHeight(slice, x, z, maxValue, height + 1);
        } else if (height < maxValue) {
            this.fulgor$updateSkylightNeighborHeight(slice, x, z, height, maxValue + 1);
        }
    }

    @Unique
    private void fulgor$updateSkylightNeighborHeight(WorldChunkSlice slice, int x, int z, int startY, int endY) {
        if (endY <= startY || !slice.isLoaded(x, z, 16)) {
            return;
        }

        for (int y = startY; y < endY; y++) {
            this.world.checkLightFor(EnumSkyBlock.SKY, new BlockPos(x, y, z));
        }

        this.dirty = true;
    }

    // === ChunkLightingData ===

    @Override
    public short[] fulgor$getNeighborLightChecks() {
        return this.fulgor$neighborLightChecks;
    }

    @Override
    public void fulgor$setNeighborLightChecks(short[] data) {
        this.fulgor$neighborLightChecks = data;
    }

    @Override
    public void fulgor$initNeighborLightChecks() {
        if (this.fulgor$neighborLightChecks == null) {
            this.fulgor$neighborLightChecks = new short[Fulgor.BOUNDARY_FLAG_COUNT];
        }
    }

    @Override
    public boolean fulgor$isLightInitialized() {
        return this.fulgor$lightInitialized;
    }

    @Override
    public void fulgor$setLightInitialized(boolean lightInitialized) {
        this.fulgor$lightInitialized = lightInitialized;
    }

    @Override
    public void fulgor$setSkylightUpdated() {
        this.setSkylightUpdated();
    }

    @Override
    public int fulgor$getCachedLightFor(EnumSkyBlock lightType, BlockPos pos) {
        int x = pos.getX() & 15;
        int y = pos.getY();
        int z = pos.getZ() & 15;

        ExtendedBlockStorage section = this.storageArrays[y >> 4];

        if (section == Chunk.NULL_BLOCK_STORAGE) {
            // No section means no stored light, so the answer is whatever the sky rule implies. Note
            // this deliberately ignores the light type, matching vanilla: a block-light query in an
            // empty section under open sky returns the sky default.
            return this.canSeeSky(pos) ? lightType.defaultLightValue : 0;
        }

        if (lightType == EnumSkyBlock.SKY) {
            return this.world.provider.hasSkyLight() ? section.getSkyLight(x, y & 15, z) : 0;
        }

        return lightType == EnumSkyBlock.BLOCK ? section.getBlockLight(x, y & 15, z) : lightType.defaultLightValue;
    }

    @Override
    public LightingEngine fulgor$getLightingEngine() {
        return this.fulgor$lightingEngine;
    }
}
