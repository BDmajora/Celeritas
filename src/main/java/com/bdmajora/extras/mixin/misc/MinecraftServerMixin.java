package com.bdmajora.extras.mixin.misc;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

// OptiFine's Autosave switch: how often the integrated server writes the world out; vanilla's 900-tick default hitches on large worlds
// Zero means never; vanilla's tickCounter % interval would divide by that, so it's translated to an interval nothing is congruent to
// Only reachable in single-player, since Impetus is client-only and this class isn't present on a dedicated server
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 900))
    private int impetus$autosaveInterval(int vanillaInterval) {
        int configured = Extras.options().extra.autosaveInterval;

        if (configured == ExtrasConfig.ExtraSettings.AUTOSAVE_MIN_TICKS) {
            return Integer.MAX_VALUE;
        }

        return configured;
    }
}
