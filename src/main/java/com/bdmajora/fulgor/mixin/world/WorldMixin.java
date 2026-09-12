package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import com.bdmajora.fulgor.lighting.LightingEngine;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Gives every world a lighting engine and routes checkLightFor into it: vanilla propagates immediately, Fulgor records the position and returns true for LightingEngine to propagate later in bulk; cancelled at HEAD rather than @Overwrite so other mods' injections still apply
@Mixin(World.class)
public abstract class WorldMixin implements LightingEngineProvider {
    @Unique
    private LightingEngine fulgor$lightingEngine;

    // Constructed with the world so the engine captures the owning thread, the only way to tell a legitimate call from a mod touching the world off-thread, and only knowable here
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fulgor$createLightingEngine(CallbackInfo ci) {
        this.fulgor$lightingEngine = new LightingEngine((World) (Object) this);
    }

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void fulgor$scheduleLightUpdate(EnumSkyBlock lightType, BlockPos pos,
                                            CallbackInfoReturnable<Boolean> cir) {
        this.fulgor$lightingEngine.scheduleLightUpdate(lightType, pos);

        // Vanilla's false means "gave up part-way"; nothing has been attempted yet, so true (the update is accepted) is the honest answer
        cir.setReturnValue(true);
    }

    @Override
    public LightingEngine fulgor$getLightingEngine() {
        return this.fulgor$lightingEngine;
    }
}
