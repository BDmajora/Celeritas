package org.taumc.celeritas.iris.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.iris.gui.GuiSelectShaderPack;

/**
 * Adds a "Shaders..." button to the vanilla Video Settings screen (OptiFine convention) that opens
 * {@link GuiSelectShaderPack}. The button sits in the top-left corner, clear of the vanilla Done button and the
 * options row list. Handling is added at the head of {@code actionPerformed}; vanilla's handler ignores unknown ids,
 * so no cancellation is needed.
 */
@Mixin(GuiVideoSettings.class)
public abstract class GuiVideoSettingsMixin extends GuiScreen {
    private static final int CELERITAS_SHADERS_BUTTON_ID = 0x51_1D3;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void celeritas$addShadersButton(CallbackInfo ci) {
        this.buttonList.add(new GuiButton(CELERITAS_SHADERS_BUTTON_ID, 5, 5, 120, 20, "Shaders..."));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"))
    private void celeritas$openShaderSelector(GuiButton button, CallbackInfo ci) {
        if (button.id == CELERITAS_SHADERS_BUTTON_ID) {
            this.mc.displayGuiScreen(new GuiSelectShaderPack((GuiScreen) (Object) this));
        }
    }
}
