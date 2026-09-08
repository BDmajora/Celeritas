package com.bdmajora.extras.mixin.misc;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * OptiFine's Autosave switch: how often the integrated server writes the world out.
 *
 * <p>Vanilla saves every 900 ticks, and on a large world that is a visible hitch. Raising the
 * interval trades how much progress an unclean exit costs for how often the game stutters.
 *
 * <p>Zero means never, which vanilla's {@code tickCounter % interval} would divide by — so it is
 * translated into an interval nothing can be congruent to instead. The world is still saved on
 * quit; this only affects the periodic save.
 *
 * <p>Only reachable in single-player. Impetus is client-only, so on a dedicated server this class
 * is not present and the server's own interval applies.
 */
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
