package org.taumc.celeritas.iris.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.taumc.celeritas.iris.Iris;
import org.taumc.celeritas.iris.config.IrisConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game shader-pack selector (video-options "Shader Packs" tab). Iris-native: drives Celeritas' own
 * {@link Iris} config. Selecting a pack persists it, re-parses it, and reloads the chunk renderers (which switches the
 * vertex format and rebuilds terrain through the pack's shader). Selecting "off" restores stock Celeritas.
 */
public class GuiSelectShaderPack extends GuiScreen {
    private static final int VISIBLE_PER_PAGE = 12;
    private static final int ID_DONE = 1;
    private static final int ID_PREV = 2;
    private static final int ID_NEXT = 3;
    private static final int ID_RELOAD = 4;
    private static final int ID_ENTRY_BASE = 100;

    private final GuiScreen parent;
    private List<String> entries = new ArrayList<>();
    private int page;

    public GuiSelectShaderPack(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();

        this.entries = new ArrayList<>();
        this.entries.add(IrisConfig.NO_PACK);
        this.entries.addAll(Iris.listAvailablePacks());

        int totalPages = Math.max(1, (int) Math.ceil(this.entries.size() / (double) VISIBLE_PER_PAGE));
        this.page = Math.max(0, Math.min(this.page, totalPages - 1));

        int start = this.page * VISIBLE_PER_PAGE;
        int end = Math.min(this.entries.size(), start + VISIBLE_PER_PAGE);
        String selected = Iris.getSelectedPackName();

        int y = 32;
        for (int i = start; i < end; i++) {
            String name = this.entries.get(i);
            String label = displayName(name);
            if (name.equals(selected)) {
                label = "§a" + label;
            }
            this.buttonList.add(new GuiButton(ID_ENTRY_BASE + (i - start), this.width / 2 - 155, y, 310, 20, label));
            y += 22;
        }

        int bottom = this.height - 28;
        GuiButton prev = new GuiButton(ID_PREV, this.width / 2 - 155, bottom, 60, 20, "< Prev");
        GuiButton reload = new GuiButton(ID_RELOAD, this.width / 2 - 90, bottom, 90, 20, "Reload");
        GuiButton next = new GuiButton(ID_NEXT, this.width / 2 + 5, bottom, 60, 20, "Next >");
        GuiButton done = new GuiButton(ID_DONE, this.width / 2 + 70, bottom, 85, 20, "Done");
        prev.enabled = this.page > 0;
        next.enabled = this.page < totalPages - 1;
        this.buttonList.add(prev);
        this.buttonList.add(reload);
        this.buttonList.add(next);
        this.buttonList.add(done);
    }

    private static String displayName(String name) {
        return IrisConfig.NO_PACK.equals(name) ? "(shaders off - vanilla Celeritas)" : name;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_DONE:
                this.mc.displayGuiScreen(this.parent);
                break;
            case ID_PREV:
                this.page--;
                initGui();
                break;
            case ID_NEXT:
                this.page++;
                initGui();
                break;
            case ID_RELOAD:
                Iris.loadCurrentShaderpack();
                reloadRenderers();
                initGui();
                break;
            default:
                if (button.id >= ID_ENTRY_BASE) {
                    int index = this.page * VISIBLE_PER_PAGE + (button.id - ID_ENTRY_BASE);
                    if (index >= 0 && index < this.entries.size()) {
                        Iris.setShaderpackAndReload(this.entries.get(index));
                        reloadRenderers();
                        initGui();
                    }
                }
                break;
        }
    }

    private void reloadRenderers() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "Select Shader Pack", this.width / 2, 12, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
