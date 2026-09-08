package com.bdmajora.extras.mixin.light_updates;

import com.bdmajora.extras.Extras;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the client recomputing lighting.
 *
 * <p>Client-side only — the server keeps its own lighting, so this changes nothing anyone else sees
 * and nothing about gameplay. What it does change is that light stops updating visibly: a torch
 * placed after this is switched off lights nothing until the chunk is rebuilt for some other reason.
 * That is why {@code ExtrasHud} draws a permanent warning while it is off; the symptom otherwise
 * looks exactly like a rendering bug.
 */
@Mixin(World.class)
public class WorldMixin {
    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void impetus$checkLightFor(EnumSkyBlock lightType, BlockPos pos,
                                       CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (self.isRemote && !Extras.options().render.lightUpdates) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "checkLight", at = @At("HEAD"), cancellable = true)
    private void impetus$checkLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (self.isRemote && !Extras.options().render.lightUpdates) {
            cir.setReturnValue(false);
        }
    }
}
