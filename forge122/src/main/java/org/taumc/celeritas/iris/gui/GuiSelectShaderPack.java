package org.taumc.celeritas.iris.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.taumc.celeritas.iris.Iris;
import org.taumc.celeritas.iris.config.IrisConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal in-game shader-pack selector (OptiFine's "Shaders" screen, trimmed to essentials): lists the packs found
 * in {@code shaderpacks/} plus an "off" entry, applies the choice immediately (persisting to
 * {@code optionsshaders.txt} and re-parsing the pack), and lets the user reload the current pack while iterating.
 * <p>
 * Built on plain {@link GuiScreen}/{@link GuiButton} only (no Forge {@code GuiScrollingList}) with simple paging, so
 * there are no unverified GUI-API assumptions. The GL pipeline rebuild happens on the next render frame via
 * {@link Iris#updatePipeline()}, so selecting a pack here surfaces the compile log without a restart.
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
            if (selected.equals(name)) {
                label = "§a" + label; // highlight the active selection in green
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
        return IrisConfig.NO_PACK.equals(name) ? "(shaders off — vanilla Celeritas)" : name;
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
                initGui();
                break;
            default:
                if (button.id >= ID_ENTRY_BASE) {
                    int index = this.page * VISIBLE_PER_PAGE + (button.id - ID_ENTRY_BASE);
                    if (index >= 0 && index < this.entries.size()) {
                        Iris.setShaderpackAndReload(this.entries.get(index));
                        initGui();
                    }
                }
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "Select Shader Pack", this.width / 2, 12, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
