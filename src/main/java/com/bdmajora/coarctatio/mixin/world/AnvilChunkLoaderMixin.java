package com.bdmajora.coarctatio.mixin.world;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Strips a loaded chunk's NBT to the three tags loadEntities still needs (Hydrogen's MixinChunkSerializer on Forge's async pipeline); between the IO-thread build and main-thread callStage2 the whole tag, mostly already-copied Sections, stays reachable
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {
    // The three tags AnvilChunkLoader.loadEntities still reads after the chunk is built
    @Unique
    private static final String[] COARCTATIO_RETAINED_TAGS = {"Entities", "TileEntities", "TileTicks"};

    // At RETURN so the Chunk is already built from the full tag; remap = false since checkedReadChunkFromNBT__Async is a Forge addition
    @Inject(method = "checkedReadChunkFromNBT__Async", at = @At("RETURN"), remap = false)
    private void coarctatio$stripPendingChunkNbt(World world, int x, int z, NBTTagCompound compound,
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

        for (String tag : COARCTATIO_RETAINED_TAGS) {
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
