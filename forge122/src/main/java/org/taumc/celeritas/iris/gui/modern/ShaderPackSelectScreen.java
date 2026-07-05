package org.taumc.celeritas.iris.gui.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.embeddedt.embeddium.impl.gui.framework.DrawContext;
import org.embeddedt.embeddium.impl.gui.framework.TextComponent;
import org.embeddedt.embeddium.impl.gui.widgets.AbstractWidget;
import org.embeddedt.embeddium.impl.gui.widgets.FlatButtonWidget;
import org.embeddedt.embeddium.impl.util.Dim2i;
import org.taumc.celeritas.impl.gui.VintageDrawContext;
import org.taumc.celeritas.impl.gui.VintageInteractionContext;
import org.taumc.celeritas.iris.Iris;
import org.taumc.celeritas.iris.config.IrisConfig;

import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;

/**
 * Modern Sodium/Iris-style shader-pack selection screen: a scrollable list with an enable/disable toggle at the top and
 * each available pack below, over the shared Embeddium GUI framework. Selecting a pack + Apply loads it and reloads the
 * chunk renderers (switching terrain onto the pack's shader); "Shader Pack Settings" opens {@link ShaderPackConfigScreen}.
 */
public class ShaderPackSelectScreen extends GuiScreen {
    private static final int LIST_TOP = 44;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_WIDTH = 420;

    private final GuiScreen parent;

    private List<String> packs = new ArrayList<>();
    private String stagedSelection;
    private boolean stagedEnabled;
    private int page;

    private final List<AbstractWidget> widgets = new ArrayList<>();

    public ShaderPackSelectScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.widgets.clear();

        if (this.stagedSelection == null) {
            this.stagedSelection = Iris.getSelectedPackName();
            this.stagedEnabled = !IrisConfig.NO_PACK.equals(this.stagedSelection);
        }

        this.packs = new ArrayList<>();
        this.packs.addAll(Iris.listAvailablePacks());

        int rowsPerPage = Math.max(1, (this.height - LIST_TOP - 80) / ROW_HEIGHT) - 1; // reserve the toggle row
        int totalPages = Math.max(1, (int) Math.ceil(this.packs.size() / (double) rowsPerPage));
        this.page = Math.max(0, Math.min(this.page, totalPages - 1));

        int rowLeft = (this.width - ROW_WIDTH) / 2;
        int y = LIST_TOP;

        // Enable/disable toggle row
        addRow(rowLeft, y, "Shaders: " + (this.stagedEnabled ? "§aEnabled" : "§cDisabled"), true, () -> {
            this.stagedEnabled = !this.stagedEnabled;
            initGui();
        });
        y += ROW_HEIGHT + 2;

        int start = this.page * rowsPerPage;
        int end = Math.min(this.packs.size(), start + rowsPerPage);
        for (int i = start; i < end; i++) {
            String name = this.packs.get(i);
            boolean selected = this.stagedEnabled && name.equals(this.stagedSelection);
            String label = selected ? "§e" + name : name;
            addRow(rowLeft, y, label, true, () -> {
                this.stagedSelection = name;
                this.stagedEnabled = true;
                initGui();
            });
            y += ROW_HEIGHT;
        }

        if (totalPages > 1) {
            int navY = this.height - 76;
            FlatButtonWidget prev = addButton(this.width / 2 - 104, navY, 100, 20, "< Prev", () -> {
                this.page--;
                initGui();
            });
            FlatButtonWidget next = addButton(this.width / 2 + 4, navY, 100, 20, "Next >", () -> {
                this.page++;
                initGui();
            });
            prev.setEnabled(this.page > 0);
            next.setEnabled(this.page < totalPages - 1);
        }

        // Utility row
        int utilY = this.height - 52;
        addButton(this.width / 2 - 154, utilY, 150, 20, "Open Shader Pack Folder...", this::openPackFolder);
        FlatButtonWidget settings = addButton(this.width / 2 + 4, utilY, 150, 20, "Shader Pack Settings...",
                () -> this.mc.displayGuiScreen(new ShaderPackConfigScreen(this)));
        settings.setEnabled(Iris.isShaderPackInUse());

        // Action row
        int actY = this.height - 28;
        addButton(this.width / 2 - 154, actY, 100, 20, "Cancel", () -> this.mc.displayGuiScreen(this.parent));
        addButton(this.width / 2 - 50, actY, 100, 20, "Apply", this::applySelection);
        addButton(this.width / 2 + 54, actY, 100, 20, "Done", () -> {
            applySelection();
            this.mc.displayGuiScreen(this.parent);
        });
    }

    private FlatButtonWidget addRow(int x, int y, String label, boolean enabled, Runnable action) {
        FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, ROW_WIDTH, 20), TextComponent.literal(label), action);
        button.setEnabled(enabled);
        this.widgets.add(button);
        return button;
    }

    private FlatButtonWidget addButton(int x, int y, int w, int h, String label, Runnable action) {
        FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, w, h), TextComponent.literal(label), action);
        this.widgets.add(button);
        return button;
    }

    private void applySelection() {
        String target = this.stagedEnabled ? this.stagedSelection : IrisConfig.NO_PACK;
        if (target.equals(Iris.getSelectedPackName())) {
            return;
        }
        Iris.setShaderpackAndReload(target);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
        initGui();
    }

    private void openPackFolder() {
        if (Iris.getConfig() == null) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(Iris.getConfig().getShaderpacksDirectory().toFile());
            }
        } catch (Exception e) {
            Iris.logger().warn("Failed to open shaderpacks folder", e);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        for (AbstractWidget widget : new ArrayList<>(this.widgets)) {
            if (widget.mouseClicked(VintageInteractionContext.INSTANCE, mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        DrawContext ctx = new VintageDrawContext();

        this.drawCenteredString(this.fontRenderer, "Shader Packs", this.width / 2, 12, 0xFFFFFFFF);
        this.drawCenteredString(this.fontRenderer, "§7Select", this.width / 2, 24, 0xFFAAAAAA);

        for (AbstractWidget widget : this.widgets) {
            widget.render(ctx, mouseX, mouseY, partialTicks);
        }
    }
}
