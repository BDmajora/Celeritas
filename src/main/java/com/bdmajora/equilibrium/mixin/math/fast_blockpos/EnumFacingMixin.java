package com.bdmajora.equilibrium.mixin.math.fast_blockpos;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hoists each direction's offsets into plain int fields; vanilla computes getXOffset() with a branch and a virtual call for every neighbour of every position, and unlike modern versions these are computed rather than stored
@Mixin(EnumFacing.class)
public class EnumFacingMixin {
    private int equilibrium$offsetX;
    private int equilibrium$offsetY;
    private int equilibrium$offsetZ;

    // Populated from the direction vector the constructor was handed, which already holds exactly these values
    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$captureOffsets(String enumName, int ordinal, int index, int opposite,
                                            int horizontalIndex, String name,
                                            EnumFacing.AxisDirection axisDirection, EnumFacing.Axis axis,
                                            Vec3i directionVec, CallbackInfo ci) {
        this.equilibrium$offsetX = directionVec.getX();
        this.equilibrium$offsetY = directionVec.getY();
        this.equilibrium$offsetZ = directionVec.getZ();
    }

    // Overwrite: direct field read instead of a Vec3i call
    @Overwrite
    public int getXOffset() {
        return this.equilibrium$offsetX;
    }

    // Overwrite: direct field read
    @Overwrite
    public int getYOffset() {
        return this.equilibrium$offsetY;
    }

    // Overwrite: direct field read
    @Overwrite
    public int getZOffset() {
        return this.equilibrium$offsetZ;
    }
}
