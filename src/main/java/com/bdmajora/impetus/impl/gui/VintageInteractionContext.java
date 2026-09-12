package com.bdmajora.impetus.impl.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.SoundEvents;
import com.bdmajora.impetus.engine.impl.gui.framework.InteractionContext;

public enum VintageInteractionContext implements InteractionContext {
    INSTANCE;

    // Maps Sodium's modifier keys onto LWJGL2 keyboard state
    @Override
    public boolean isSpecialKeyDown(SpecialKey key) {
        return switch (key) {
            case SHIFT -> GuiScreen.isShiftKeyDown();
            case CTRL -> GuiScreen.isCtrlKeyDown();
            case ALT -> GuiScreen.isAltKeyDown();
        };
    }

    // Vanilla's button click
    @Override
    public void playClickSound() {
        Minecraft.getMinecraft().getSoundHandler().playSound(
                PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F)
        );
    }
}
