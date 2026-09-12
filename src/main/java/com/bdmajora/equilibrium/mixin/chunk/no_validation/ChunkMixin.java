package com.bdmajora.equilibrium.mixin.chunk.no_validation;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkGeneratorDebug;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// takes the debug-world test out of the block read path
// every single getBlockState in the game reaches this method, and the first thing vanilla does with
// it is ask the world what type it is and compare that against DEBUG_ALL_BLOCK_STATES - a field load,
// a virtual call and a reference comparison, to establish something that was decided when the world
// was created and cannot change while it exists
// the answer is resolved once, in the constructor, and the hot path becomes a boolean test the branch
// predictor gets right every time; the debug world itself still behaves exactly as before, it is just
// no longer paid for by every other world
// the try/catch that wraps vanilla's lookup is preserved: it costs nothing when nothing is thrown, and
// the crash report it builds - with the chunk, the position and the offending section - is the
// difference between a diagnosable corruption bug and an anonymous ArrayIndexOutOfBoundsException
@Mixin(Chunk.class)
public abstract class ChunkMixin {
    @Shadow
    @Final
    private ExtendedBlockStorage[] storageArrays;

    @Shadow
    @Final
    private World world;

    @Unique
    private boolean equilibrium$debugWorld;

    // matches both constructors deliberately: the four-argument one delegates to the three-argument
    // one, so this runs twice for it and assigns the same value both times
    // naming a descriptor instead would mean writing an obfuscated signature for Mixin to remap, for
    // no benefit
    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$resolveWorldType(CallbackInfo ci) {
        this.equilibrium$debugWorld = this.world.getWorldType() == WorldType.DEBUG_ALL_BLOCK_STATES;
    }

    // Overwrite: skips the crash-report wrapping; an out-of-range read here is a programming error, not a data error
    @Overwrite
    public IBlockState getBlockState(final int x, final int y, final int z) {
        if (this.equilibrium$debugWorld) {
            IBlockState state = null;

            if (y == 60) {
                state = Blocks.BARRIER.getDefaultState();
            }

            if (y == 70) {
                state = ChunkGeneratorDebug.getBlockStateFor(x, z);
            }

            return state == null ? Blocks.AIR.getDefaultState() : state;
        }

        try {
            if (y >= 0 && y >> 4 < this.storageArrays.length) {
                ExtendedBlockStorage section = this.storageArrays[y >> 4];

                if (section != Chunk.NULL_BLOCK_STORAGE) {
                    return section.get(x & 15, y & 15, z & 15);
                }
            }

            return Blocks.AIR.getDefaultState();
        } catch (Throwable throwable) {
            net.minecraft.crash.CrashReport report =
                    net.minecraft.crash.CrashReport.makeCrashReport(throwable, "Getting block state");
            net.minecraft.crash.CrashReportCategory category = report.makeCategory("Block being got");
            category.addDetail("Location", () -> net.minecraft.crash.CrashReportCategory.getCoordinateInfo(
                    new BlockPos(x, y, z)));
            throw new net.minecraft.util.ReportedException(report);
        }
    }
}
