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

// Suppresses toasts by kind at add time so a suppressed toast never enters the queue; modded toasts only answer to the master switch
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
