package com.bdmajora.extras.mixin.toast;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.client.gui.toasts.AdvancementToast;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.gui.toasts.RecipeToast;
import net.minecraft.client.gui.toasts.SystemToast;
import net.minecraft.client.gui.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses toast pop-ups by kind.
 *
 * <p>Dropped at {@code add} rather than at render time, so a suppressed toast never enters the
 * queue and cannot delay the ones that are still wanted.
 *
 * <p>A modded toast that is not one of the four vanilla kinds is only affected by the master
 * switch — there is nothing to match it against, and guessing would silence things the user never
 * asked to silence.
 */
@Mixin(GuiToast.class)
public class GuiToastMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void impetus$filterToast(IToast toast, CallbackInfo ci) {
        ExtrasConfig.ExtraSettings settings = Extras.options().extra;

        if (!settings.toasts
                || (toast instanceof AdvancementToast && !settings.toastAdvancement)
                || (toast instanceof RecipeToast && !settings.toastRecipe)
                || (toast instanceof TutorialToast && !settings.toastTutorial)
                || (toast instanceof SystemToast && !settings.toastSystem)) {
            ci.cancel();
        }
    }
}
