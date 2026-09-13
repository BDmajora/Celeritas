package com.bdmajora.extras.client.budget;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.widgets.FlatButtonWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;
import com.bdmajora.impetus.impl.gui.ImpetusVideoOptionsScreen;
import com.bdmajora.impetus.impl.gui.VintageDrawContext;
import com.bdmajora.impetus.impl.gui.VintageInteractionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

// GpuShift's one-time quick setup, shown a couple of seconds into the first world: one click turns the Render Budget and GPU Booster on together under a profile, or leaves both off; either way it never returns, and the full page stays a key press away
@Mod.EventBusSubscriber(Side.CLIENT)
@SideOnly(Side.CLIENT)
public final class QuickSetupScreen extends GuiScreen {
    private static final String LANG = "impetus.quick_setup.";
    // Ticks in the world before the screen opens, so it never lands on top of the loading fade
    private static final int SETTLE_TICKS = 40;
    private static final int BUTTON_WIDTH = 110;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private final List<HelpButton> buttons = new ArrayList<>();
    private List<String> bodyLines = new ArrayList<>();
    private int bodyTop;
    private int buttonsTop;

    // Opens once the player has been standing in a world with no other screen up
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        ExtrasConfig.RenderBudgetSettings settings = Extras.options().renderBudget;
        if (settings.quickSetupShown) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.world == null || minecraft.currentScreen != null
                || minecraft.player.ticksExisted < SETTLE_TICKS) {
            return;
        }

        minecraft.displayGuiScreen(new QuickSetupScreen());
    }

    @Override
    public void initGui() {
        this.buttons.clear();

        int textWidth = Math.min(this.width - 40, 320);
        this.bodyLines = this.fontRenderer.listFormattedStringToWidth(I18n.format(LANG + "body"), textWidth);
        int bodyHeight = this.bodyLines.size() * (this.fontRenderer.FONT_HEIGHT + 2);
        // Title, body, two button rows and a help line, centred as a block
        int blockHeight = 20 + bodyHeight + 12 + BUTTON_HEIGHT * 2 + GAP + 24;
        int top = Math.max(20, (this.height - blockHeight) / 2);
        this.bodyTop = top + 20;
        this.buttonsTop = this.bodyTop + bodyHeight + 12;

        int rowWidth = BUTTON_WIDTH * 3 + GAP * 2;
        int left = (this.width - rowWidth) / 2;
        int row1 = this.buttonsTop;
        int row2 = row1 + BUTTON_HEIGHT + GAP;

        addPreset(left, row1, "balanced", ExtrasConfig.BudgetProfile.BALANCED);
        addPreset(left + BUTTON_WIDTH + GAP, row1, "performance", ExtrasConfig.BudgetProfile.PERFORMANCE);
        addPreset(left + (BUTTON_WIDTH + GAP) * 2, row1, "quality", ExtrasConfig.BudgetProfile.QUALITY);

        int halfWidth = (rowWidth - GAP) / 2;
        addButton(left, row2, halfWidth, "settings", () -> {
            markShown();
            this.mc.displayGuiScreen(new ImpetusVideoOptionsScreen(null));
        });
        addButton(left + halfWidth + GAP, row2, halfWidth, "skip", () -> {
            markShown();
            this.mc.displayGuiScreen(null);
        });
    }

    // A profile button turns both feature groups on under that profile
    private void addPreset(int x, int y, String key, ExtrasConfig.BudgetProfile profile) {
        addButton(x, y, BUTTON_WIDTH, key, () -> {
            ExtrasConfig options = Extras.options();
            options.renderBudget.enabled = true;
            options.renderBudget.profile = profile;
            options.gpuBooster.enabled = true;
            markShown();
            this.mc.displayGuiScreen(null);
        });
    }

    private void addButton(int x, int y, int width, String key, Runnable action) {
        FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, width, BUTTON_HEIGHT),
                TextComponent.translatable(LANG + key), action);
        this.buttons.add(new HelpButton(button, I18n.format(LANG + key + ".help")));
    }

    // Persists the flag with whatever the button changed, so a crash afterwards cannot bring the screen back
    private static void markShown() {
        Extras.options().renderBudget.quickSetupShown = true;
        Extras.save();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        DrawContext ctx = new VintageDrawContext();

        this.drawCenteredString(this.fontRenderer, I18n.format(LANG + "title"), this.width / 2, this.bodyTop - 18, 0xFFFFFFFF);

        int y = this.bodyTop;
        for (String line : this.bodyLines) {
            this.drawCenteredString(this.fontRenderer, line, this.width / 2, y, 0xFFAAAAAA);
            y += this.fontRenderer.FONT_HEIGHT + 2;
        }

        String help = null;
        for (HelpButton entry : this.buttons) {
            entry.button.render(ctx, mouseX, mouseY, partialTicks);
            if (help == null && entry.button.isMouseOver(mouseX, mouseY)) {
                help = entry.help;
            }
        }

        if (help != null) {
            this.drawCenteredString(this.fontRenderer, help, this.width / 2,
                    this.buttonsTop + BUTTON_HEIGHT * 2 + GAP + 10, 0xFFFFFF55);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        for (HelpButton entry : new ArrayList<>(this.buttons)) {
            if (entry.button.mouseClicked(VintageInteractionContext.INSTANCE, mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }

    // Escape counts as "not now" rather than leaving the flag unset, otherwise it would reopen next tick
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            markShown();
            this.mc.displayGuiScreen(null);
        }
    }

    // Skips vanilla's dirt background since this always opens over a world
    @Override
    public void drawWorldBackground(int tint) {
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    // A button with the line shown under the row while hovered
    private static final class HelpButton {
        final FlatButtonWidget button;
        final String help;

        HelpButton(FlatButtonWidget button, String help) {
            this.button = button;
            this.help = help;
        }
    }
}
