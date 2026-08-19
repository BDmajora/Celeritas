package com.bdmajora.coartatio.mixin.world;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Strips a loaded chunk's NBT down to the tags that are still needed after the chunk object exists.
 *
 * <p>Hydrogen's {@code MixinChunkSerializer}, retargeted to Forge's asynchronous chunk pipeline.
 *
 * <p>Forge splits chunk loading in two: {@code checkedReadChunkFromNBT__Async} builds the
 * {@code Chunk} on the IO thread and hands back {@code Object[]{chunk, compound}}, then
 * {@code ChunkIOProvider.callStage2} calls {@code loadEntities} on the main thread with
 * {@code compound.getCompoundTag("Level")}. Between those two points the <i>entire</i> chunk tag
 * stays reachable through the queued task — and the bulk of it is {@code Sections}, tens of
 * kilobytes of block, light and metadata arrays that have already been copied into the
 * {@code ExtendedBlockStorage}s and will never be read again.
 *
 * <p>{@code loadEntities} reads exactly three tags: {@code Entities}, {@code TileEntities} and
 * {@code TileTicks}. Everything else is dropped here, so a backlog of chunks waiting on the main
 * thread holds entity data instead of whole chunks.
 *
 * <p>{@code remap = false} on the injection: {@code checkedReadChunkFromNBT__Async} is a Forge
 * addition and is not in the obfuscation map.
 */
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {
    /** Tag names {@code AnvilChunkLoader.loadEntities} still reads after the chunk is built. */
    @Unique
    private static final String[] COARTATIO_RETAINED_TAGS = {"Entities", "TileEntities", "TileTicks"};

    @Inject(method = "checkedReadChunkFromNBT__Async", at = @At("RETURN"), remap = false)
    private void coartatio$stripPendingChunkNbt(World world, int x, int z, NBTTagCompound compound,
                                                CallbackInfoReturnable<Object[]> cir) {
        Object[] result = cir.getReturnValue();

        // Null means the chunk was rejected (wrong position, missing level tag); nothing to strip.
        if (result == null || result.length < 2 || !(result[1] instanceof NBTTagCompound)) {
            return;
        }

        NBTTagCompound original = (NBTTagCompound) result[1];

        if (!original.hasKey("Level", 10)) {
            return;
        }

        NBTTagCompound level = original.getCompoundTag("Level");
        NBTTagCompound strippedLevel = new NBTTagCompound();

        for (String tag : COARTATIO_RETAINED_TAGS) {
            // Type 9 is TAG_List. Guarded because Forge rejects a null value in setTag.
            if (level.hasKey(tag, 9)) {
                strippedLevel.setTag(tag, level.getTag(tag));
            }
        }

        NBTTagCompound stripped = new NBTTagCompound();
        stripped.setTag("Level", strippedLevel);

        result[1] = stripped;
    }
}
