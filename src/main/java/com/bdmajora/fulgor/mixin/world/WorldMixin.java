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

/**
 * Gives every world a lighting engine and routes {@code checkLightFor} into it.
 *
 * <p>This is the whole of the redirection. Vanilla's {@code checkLightFor} is a full propagation — it
 * walks outwards from the position, reading and rewriting neighbours until the light settles, and it
 * returns false when it ran out of its own iteration budget. Fulgor records the position and returns
 * true, and the propagation happens later, in bulk, from {@code LightingEngine}.
 *
 * <p>Cancelling at HEAD rather than {@code @Overwrite} deliberately: the vanilla body stays intact, so
 * another mod that injects into it still applies cleanly even though the code no longer runs.
 */
@Mixin(World.class)
public abstract class WorldMixin implements LightingEngineProvider {
    @Unique
    private LightingEngine fulgor$lightingEngine;

    /**
     * Constructed with the world so the engine can capture the owning thread.
     *
     * <p>That thread identity is the only way to tell a legitimate call from another mod touching the
     * world off-thread, and it is only knowable here — by the time the first light update arrives, the
     * call could be coming from anywhere.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fulgor$createLightingEngine(CallbackInfo ci) {
        this.fulgor$lightingEngine = new LightingEngine((World) (Object) this);
    }

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void fulgor$scheduleLightUpdate(EnumSkyBlock lightType, BlockPos pos,
                                            CallbackInfoReturnable<Boolean> cir) {
        this.fulgor$lightingEngine.scheduleLightUpdate(lightType, pos);

        // Vanilla's false means "I gave up part-way through"; nothing sensible can be reported here
        // because nothing has been attempted yet. True is the honest answer: the update is accepted.
        cir.setReturnValue(true);
    }

    @Override
    public LightingEngine fulgor$getLightingEngine() {
        return this.fulgor$lightingEngine;
    }
}
