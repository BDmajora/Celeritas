package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.lighting.LightingHooks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

// stops Chunk.setBlockState from doing its own skylight work
// placing a block sends vanilla down three separate lighting paths, all of them redundant once the
// engine exists and two of them actively harmful
// this mixin closes all three, and seeds the one piece of state - a freshly created section's skylight
// - that genuinely does have to be right immediately
// split out from ChunkMixin because these are local-variable and call-site interceptions inside one
// long vanilla method, and they read very differently from the method replacements next door
@Mixin(Chunk.class)
public abstract class ChunkSkylightMixin {
    @Shadow
    @Final
    private World world;

    // seeds the skylight of a section setBlockState just created
    // matched on the second store to the section local: the first is the read from the storage array,
    // the second is the newly constructed one
    // intercepting the store rather than the constructor keeps this working regardless of how the build
    // pipeline reobfuscates constructor references
    // this has to happen before the section is published - setBlockState goes on to relight the column,
    // and a section full of zeroed skylight would read as genuinely dark
    @ModifyVariable(method = "setBlockState", at = @At(value = "STORE", ordinal = 1), index = 12)
    private ExtendedBlockStorage fulgor$seedNewSection(ExtendedBlockStorage section) {
        LightingHooks.initSkylightForSection(this.world, (Chunk) (Object) this, section);

        return section;
    }

    // forces the "regenerate the skylight map" flag off, for two reasons
    // generateSkylightMap rebuilds all 256 columns of the chunk when at most one of them changed, and
    // taking that branch skips the else branch - which is where relightBlock lives, and relightBlock is
    // the version that knows how to record work owed to unloaded neighbours
    // forcing the flag off gets the cheaper path and the correct one at the same time
    @ModifyVariable(method = "setBlockState", at = @At(value = "STORE", ordinal = 1), index = 13)
    private boolean fulgor$suppressSkylightMapRegeneration(boolean generateSkylightMap) {
        return false;
    }

    // vanilla's skylight occlusion pass, disabled
    // all it does is flag the column for recheckGaps to look at later, and the engine has already been
    // told about the position by relightBlock, so the flag would only cause the same column to be
    // re-derived a second time
    @Redirect(
            method = "setBlockState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;propagateSkylightOcclusion(II)V"))
    private void fulgor$skipSkylightOcclusion(Chunk chunk, int x, int z) {
    }

    // short-circuits the light reads that decide whether to propagate occlusion
    // with fulgor$skipSkylightOcclusion in place the answer is never used, but the question is
    // expensive to ask because getLightFor flushes the whole pending queue - that would turn every
    // block placement into a full lighting pass and undo the batching entirely
    @Redirect(
            method = "setBlockState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/Chunk;getLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I"))
    private int fulgor$skipLightQuery(Chunk chunk, EnumSkyBlock lightType, BlockPos pos) {
        return 0;
    }
}
