package com.bdmajora.extras.client.budget;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.impetus.impl.gui.ImpetusVideoOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

// GpuShift's two keys: F8 flips the budget overlay, F9 opens the Impetus video settings; both are rebindable through the vanilla controls screen under their own category
@Mod.EventBusSubscriber(Side.CLIENT)
@SideOnly(Side.CLIENT)
public final class RenderBudgetKeys {
    private static final String CATEGORY = "impetus.key.category";

    private static final KeyBinding TOGGLE_OVERLAY = new KeyBinding("impetus.key.budget_overlay", Keyboard.KEY_F8, CATEGORY);
    private static final KeyBinding OPEN_SETTINGS = new KeyBinding("impetus.key.open_settings", Keyboard.KEY_F9, CATEGORY);

    private RenderBudgetKeys() {
    }

    // Called once from the mod's init phase; the controls screen only lists bindings registered by then
    public static void register() {
        ClientRegistry.registerKeyBinding(TOGGLE_OVERLAY);
        ClientRegistry.registerKeyBinding(OPEN_SETTINGS);
    }

    // KeyInputEvent only fires with no screen open, so the settings screen gets a null parent and returns to the game
    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        while (TOGGLE_OVERLAY.isPressed()) {
            ExtrasConfig.RenderBudgetSettings settings = Extras.options().renderBudget;
            settings.overlay = !settings.overlay;
            Extras.save();
        }

        while (OPEN_SETTINGS.isPressed()) {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(new ImpetusVideoOptionsScreen(minecraft.currentScreen));
        }
    }
}
