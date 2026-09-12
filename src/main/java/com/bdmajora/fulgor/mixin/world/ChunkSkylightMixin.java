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

// Stops Chunk.setBlockState doing its own skylight work: closes vanilla's three redundant (two harmful) lighting paths and seeds a freshly created section's skylight, the one thing that must be right immediately; split from ChunkMixin since these are call-site interceptions inside one long method
@Mixin(Chunk.class)
public abstract class ChunkSkylightMixin {
    @Shadow
    @Final
    private World world;

    // Seeds the skylight of a section setBlockState just created, matched on the second store to the section local (the first is the array read); must happen before the section is published or the column relight reads zeroed skylight as genuinely dark
    @ModifyVariable(method = "setBlockState", at = @At(value = "STORE", ordinal = 1), index = 12)
    private ExtendedBlockStorage fulgor$seedNewSection(ExtendedBlockStorage section) {
        LightingHooks.initSkylightForSection(this.world, (Chunk) (Object) this, section);

        return section;
    }

    // Forces the "regenerate skylight map" flag off: generateSkylightMap rebuilds all 256 columns when one changed, and taking that branch skips the else branch where relightBlock (which records work owed to unloaded neighbours) lives
    @ModifyVariable(method = "setBlockState", at = @At(value = "STORE", ordinal = 1), index = 13)
    private boolean fulgor$suppressSkylightMapRegeneration(boolean generateSkylightMap) {
        return false;
    }

    // Vanilla's skylight occlusion pass, disabled; it only flags the column for recheckGaps, and relightBlock has already told the engine, so the flag would re-derive the same column twice
    @Redirect(
            method = "setBlockState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;propagateSkylightOcclusion(II)V"))
    private void fulgor$skipSkylightOcclusion(Chunk chunk, int x, int z) {
    }

    // Short-circuits the light reads deciding whether to propagate occlusion; the answer is never used with fulgor$skipSkylightOcclusion in place, but getLightFor flushes the whole queue, which would turn every placement into a full lighting pass
    @Redirect(
            method = "setBlockState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/Chunk;getLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I"))
    private int fulgor$skipLightQuery(Chunk chunk, EnumSkyBlock lightType, BlockPos pos) {
        return 0;
    }
}
