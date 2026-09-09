package com.bdmajora.equilibrium.mixin.math.fast_blockpos;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// hoists each direction's offsets into plain int fields
// vanilla answers getXOffset() with "axis == Axis.X ? axisDirection.getOffset() : 0" - a field load, a
// reference comparison, a branch and a virtual call, to produce one of three constants that were
// fixed the moment the enum was constructed
// it is called for every neighbour of every block position anything ever offsets, which on this
// version is most of the server tick
// Lithium's version of this reads the offsets out of the direction's Vec3i, because on modern versions
// that is where they live; here they are computed rather than stored, so the saving is larger
@Mixin(EnumFacing.class)
public class EnumFacingMixin {
    private int equilibrium$offsetX;
    private int equilibrium$offsetY;
    private int equilibrium$offsetZ;

    // populated from the direction vector the constructor was handed, which already holds exactly
    // these three values and is otherwise only read through getDirectionVec
    @Inject(method = "<init>", at = @At("RETURN"))
    private void equilibrium$captureOffsets(String enumName, int ordinal, int index, int opposite,
                                            int horizontalIndex, String name,
                                            EnumFacing.AxisDirection axisDirection, EnumFacing.Axis axis,
                                            Vec3i directionVec, CallbackInfo ci) {
        this.equilibrium$offsetX = directionVec.getX();
        this.equilibrium$offsetY = directionVec.getY();
        this.equilibrium$offsetZ = directionVec.getZ();
    }

    /**
     * @author JellySquid
     * @reason Avoid indirection to aid inlining
     */
    @Overwrite
    public int getXOffset() {
        return this.equilibrium$offsetX;
    }

    /**
     * @author JellySquid
     * @reason Avoid indirection to aid inlining
     */
    @Overwrite
    public int getYOffset() {
        return this.equilibrium$offsetY;
    }

    /**
     * @author JellySquid
     * @reason Avoid indirection to aid inlining
     */
    @Overwrite
    public int getZOffset() {
        return this.equilibrium$offsetZ;
    }
}
