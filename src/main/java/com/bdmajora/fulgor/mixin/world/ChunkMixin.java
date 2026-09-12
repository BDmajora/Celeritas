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

// Where the chunk stops calculating light itself and asks the engine; getLightFor, checkLight and recheckGaps are @Overwritten because their vanilla bodies would fight the engine, relightBlock because vanilla's drops the cross-chunk half of its job
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

    // Caches the world's engine on the chunk; getLightFor is one of the most-called methods in the game, and going through the world each call would add an interface dispatch and a field read
    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"))
    private void fulgor$captureLightingEngine(World world, int x, int z, CallbackInfo ci) {
        this.fulgor$lightingEngine = ((LightingEngineProvider) world).fulgor$getLightingEngine();
    }

    // getLightSubtracted reads both light types at once and is the entity/rendering path's way in, so it flushes both queues rather than going through getLightFor twice
    @Inject(method = "getLightSubtracted", at = @At("HEAD"))
    private void fulgor$flushBeforeLightSubtracted(BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        this.fulgor$lightingEngine.processLightUpdates();
    }

    // Replays the boundary checks this chunk and its neighbours owe each other; loading a chunk is the only event that makes a previously impossible boundary crossing possible
    @Inject(method = "onLoad", at = @At("RETURN"))
    private void fulgor$replayBoundaryChecks(CallbackInfo ci) {
        LightingHooks.scheduleRelightChecksForChunkBoundaries(this.world, (Chunk) (Object) this);
    }

    // setLightFor rebuilds the whole chunk's skylight map when it creates a section, and the engine calls it for every position it writes; a full-column rebuild mid-pass is ruinously slow and overwrites what the pass decided, so only the new section is seeded
    @Redirect(
            method = "setLightFor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;generateSkylightMap()V"),
            expect = 0)
    private void fulgor$seedNewSectionOnly(Chunk chunk, EnumSkyBlock lightType, BlockPos pos, int value) {
        LightingHooks.initSkylightForSection(this.world, (Chunk) (Object) this, this.storageArrays[pos.getY() >> 4]);
    }

    // Vanilla relights the column but drops the part crossing into an unloaded neighbour (the source of worldgen skylight seams); this hands the column to LightingHooks#relightSkylightColumn, which records what it cannot do now. @author/@reason are Mixin's required @Overwrite metadata
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

    // The single point where deferral becomes visible: any light read resolves what is pending first, for the requested type only since the two propagate independently. @author/@reason are Mixin's required @Overwrite metadata
    @Overwrite
    public int getLightFor(EnumSkyBlock lightType, BlockPos pos) {
        this.fulgor$lightingEngine.processLightUpdatesForType(lightType);

        return this.fulgor$getCachedLightFor(lightType, pos);
    }

    // Vanilla walks all 256 columns and relights each immediately against whatever neighbours exist; this seeds the emitters into the engine and defers declaring the chunk lit until its whole neighbourhood is lit. @author/@reason are Mixin's required @Overwrite metadata
    @Overwrite
    public void checkLight() {
        this.isTerrainPopulated = true;

        LightingHooks.checkChunkLighting(this.world, (Chunk) (Object) this);
    }

    // Functionally vanilla, but its 1024 chunk-provider lookups are replaced by one 5x5 snapshot; the lookups are spread across four private helpers so the body cannot simply be redirected. @author/@reason are Mixin's required @Overwrite metadata
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
            // No section means no stored light, so answer per the sky rule; deliberately ignores the light type like vanilla, so a block-light query in an empty section under open sky returns the sky default
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
