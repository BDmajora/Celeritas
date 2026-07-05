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
import org.taumc.celeritas.iris.gui.PackLanguage;
import org.taumc.celeritas.iris.shaderpack.ShaderPack;
import org.taumc.celeritas.iris.shaderpack.ShaderProperties;
import org.taumc.celeritas.iris.shaderpack.option.BooleanOption;
import org.taumc.celeritas.iris.shaderpack.option.OptionSet;
import org.taumc.celeritas.iris.shaderpack.option.Profile;
import org.taumc.celeritas.iris.shaderpack.option.ProfileSet;
import org.taumc.celeritas.iris.shaderpack.option.StringOption;
import org.taumc.celeritas.iris.shaderpack.option.values.MutableOptionValues;
import org.taumc.celeritas.iris.shaderpack.option.values.OptionValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Modern Sodium/Iris-style shader-pack option screen. Lays out the pack's {@code screen}/{@code screen.*} directives as
 * a grid of {@link OptionButtonWidget} tiles (auto-generated when the pack declares no layout), rendering friendly
 * localized labels/values via {@link PackLanguage}. Sits on the shared Embeddium GUI framework (rendered through
 * {@link VintageDrawContext}); the option model + persistence is the ported Iris backend behind {@link Iris}.
 */
public class ShaderPackConfigScreen extends GuiScreen {
    private static final int DEFAULT_COLUMNS = 2;
    private static final int ROW_HEIGHT = 22;
    private static final int COL_GAP = 6;
    private static final int TOP = 56;
    private static final int VALUE_CHANGED = 0xFFFFF555;
    private static final int VALUE_DEFAULT = 0xFF55FFFF;

    private final GuiScreen parent;

    private OptionSet options;
    private OptionValues currentValues;
    private ShaderProperties properties;
    private ProfileSet profileSet;
    private PackLanguage lang;

    private final Map<String, String> pending = new HashMap<>();
    private String currentScreen;
    private int page;

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<OptionButtonWidget> elements = new ArrayList<>();

    public ShaderPackConfigScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.widgets.clear();
        this.elements.clear();

        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            addButton(this.width / 2 - 100, this.height - 28, 200, 20, TextComponent.literal("Done"),
                    () -> this.mc.displayGuiScreen(this.parent));
            return;
        }

        this.options = pack.getShaderPackOptions().getOptionSet();
        this.currentValues = pack.getShaderPackOptions().getOptionValues();
        this.properties = pack.getProperties();
        this.lang = new PackLanguage(pack.getSources(), this.mc.gameSettings.language);
        try {
            this.profileSet = ProfileSet.fromTree(new LinkedHashMap<>(this.properties.getProfiles()), this.options);
        } catch (RuntimeException e) {
            Iris.logger().warn("Failed to parse shader pack profiles; profile switching disabled", e);
            this.profileSet = new ProfileSet(new LinkedHashMap<>());
        }

        List<String> tokens = layoutTokens();
        int columns = columnCount();

        int totalWidth = Math.min(this.width - 40, 440);
        int colWidth = (totalWidth - COL_GAP * (columns - 1)) / columns;
        int gridLeft = (this.width - totalWidth) / 2;

        int rowsPerPage = Math.max(1, (this.height - TOP - 62) / ROW_HEIGHT);
        int perPage = rowsPerPage * columns;
        int totalPages = Math.max(1, (int) Math.ceil(tokens.size() / (double) perPage));
        this.page = Math.max(0, Math.min(this.page, totalPages - 1));

        int start = this.page * perPage;
        int end = Math.min(tokens.size(), start + perPage);
        for (int i = start; i < end; i++) {
            int slot = i - start;
            int col = slot % columns;
            int row = slot / columns;
            int x = gridLeft + col * (colWidth + COL_GAP);
            int y = TOP + row * ROW_HEIGHT;
            OptionButtonWidget widget = buildElement(tokens.get(i), new Dim2i(x, y, colWidth, 20));
            if (widget != null) {
                this.widgets.add(widget);
                this.elements.add(widget);
            }
        }

        // Navigation row
        int navY = this.height - 52;
        if (this.currentScreen != null) {
            addButton(gridLeft, navY, 70, 20, TextComponent.literal("< Back"), () -> {
                this.currentScreen = null;
                this.page = 0;
                initGui();
            });
        }
        if (totalPages > 1) {
            FlatButtonWidget prev = addButton(this.width / 2 - 74, navY, 70, 20, TextComponent.literal("< Prev"), () -> {
                this.page--;
                initGui();
            });
            FlatButtonWidget next = addButton(this.width / 2 + 4, navY, 70, 20, TextComponent.literal("Next >"), () -> {
                this.page++;
                initGui();
            });
            prev.setEnabled(this.page > 0);
            next.setEnabled(this.page < totalPages - 1);
        }

        // Bottom bar
        int y = this.height - 28;
        addButton(this.width / 2 - 154, y, 100, 20, TextComponent.literal("Reset All"), () -> {
            this.pending.clear();
            Iris.resetShaderPackOptionsAndReload();
            reloadRenderers();
            initGui();
        });
        FlatButtonWidget apply = addButton(this.width / 2 - 50, y, 100, 20, TextComponent.literal("Apply"), this::apply);
        apply.setEnabled(!this.pending.isEmpty());
        addButton(this.width / 2 + 54, y, 100, 20, TextComponent.literal("Done"), () -> {
            if (!this.pending.isEmpty()) {
                apply();
            }
            this.mc.displayGuiScreen(this.parent);
        });
    }

    private FlatButtonWidget addButton(int x, int y, int w, int h, TextComponent label, Runnable action) {
        FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, w, h), label, action);
        this.widgets.add(button);
        return button;
    }

    // --- Element construction ---

    private OptionButtonWidget buildElement(String token, Dim2i dim) {
        if (token.equals("<empty>") || token.isEmpty()) {
            return null;
        }
        if (token.equals("<profile>")) {
            return this.profileSet.size() > 0 ? buildProfile(dim) : null;
        }
        if (token.startsWith("[") && token.endsWith("]")) {
            String name = token.substring(1, token.length() - 1);
            return new OptionButtonWidget(dim, TextComponent.literal(this.lang.screenLabel(name)), null, 0, true, true,
                    () -> {
                        this.currentScreen = name;
                        this.page = 0;
                        initGui();
                    }, null, null);
        }
        if (this.options.getStringOptions().containsKey(token)) {
            return buildString(token, dim);
        }
        if (this.options.getBooleanOptions().containsKey(token)) {
            return buildBoolean(token, dim);
        }
        // Declared in the layout but not a discovered option — show a disabled tile.
        return new OptionButtonWidget(dim, TextComponent.literal("§7" + token), null, 0, true, false, null, null, null);
    }

    private OptionButtonWidget buildString(String name, Dim2i dim) {
        StringOption option = this.options.getStringOptions().get(name).getOption();
        String current = stringValueOf(name);
        boolean changed = !current.equals(option.getDefaultValue());
        String value = this.lang.prefix(name) + this.lang.valueLabel(name, current) + this.lang.suffix(name);
        TextComponent label = TextComponent.literal(this.lang.optionLabel(name, option.getComment().orElse(null)));
        List<String> allowed = option.getAllowedValues();
        return new OptionButtonWidget(dim, label, value, changed ? VALUE_CHANGED : VALUE_DEFAULT, false, true,
                () -> cycleString(name, allowed, 1),
                () -> cycleString(name, allowed, -1),
                tooltip(name));
    }

    private OptionButtonWidget buildBoolean(String name, Dim2i dim) {
        BooleanOption option = this.options.getBooleanOptions().get(name).getOption();
        boolean on = booleanValueOf(name);
        String raw = on ? "true" : "false";
        String localized = this.lang.valueLabel(name, raw);
        String value = localized.equals(raw) ? (on ? "§aON" : "§cOFF") : localized;
        TextComponent label = TextComponent.literal(this.lang.optionLabel(name, option.getComment().orElse(null)));
        Runnable toggle = () -> {
            this.pending.put(name, Boolean.toString(!booleanValueOf(name)));
            initGui();
        };
        return new OptionButtonWidget(dim, label, value, VALUE_DEFAULT, false, true, toggle, toggle, tooltip(name));
    }

    private OptionButtonWidget buildProfile(Dim2i dim) {
        ProfileSet.ProfileResult result = this.profileSet.scan(this.options, effectiveValues());
        String name = result.current.map(p -> this.lang.profileLabel(p.name)).orElse("§7Custom");
        TextComponent label = TextComponent.literal(this.lang.screenLabel("profile"));
        return new OptionButtonWidget(dim, label, name, VALUE_DEFAULT, false, true,
                () -> applyProfile(true), () -> applyProfile(false), null);
    }

    private void applyProfile(boolean forward) {
        ProfileSet.ProfileResult result = this.profileSet.scan(this.options, effectiveValues());
        Profile target = forward ? result.next : result.previous;
        if (target != null) {
            this.pending.putAll(target.optionValues);
        }
        initGui();
    }

    private void cycleString(String name, List<String> allowed, int direction) {
        if (allowed.isEmpty()) {
            return;
        }
        int index = allowed.indexOf(stringValueOf(name));
        if (index < 0) {
            index = 0;
        }
        index = Math.floorMod(index + direction, allowed.size());
        this.pending.put(name, allowed.get(index));
        initGui();
    }

    private List<String> tooltip(String name) {
        String comment = this.lang.comment(name);
        if (comment == null) {
            return null;
        }
        return new ArrayList<>(Arrays.asList(comment.split("\\.\\s+")));
    }

    // --- Layout ---

    private List<String> layoutTokens() {
        if (this.currentScreen != null) {
            List<String> sub = this.properties.getSubScreenOptions().get(this.currentScreen);
            return sub != null ? sub : new ArrayList<>();
        }
        Optional<List<String>> main = this.properties.getMainScreenOptions();
        if (main.isPresent()) {
            return main.get();
        }
        List<String> tokens = new ArrayList<>();
        if (this.profileSet.size() > 0) {
            tokens.add("<profile>");
            tokens.add("<empty>");
        }
        tokens.addAll(new TreeMap<>(this.options.getStringOptions()).keySet());
        tokens.addAll(new TreeMap<>(this.options.getBooleanOptions()).keySet());
        return tokens;
    }

    private int columnCount() {
        if (this.currentScreen != null) {
            Integer c = this.properties.getSubScreenColumnCount().get(this.currentScreen);
            return c != null && c > 0 ? c : DEFAULT_COLUMNS;
        }
        return this.properties.getMainScreenColumnCount().filter(c -> c > 0).orElse(DEFAULT_COLUMNS);
    }

    // --- Values ---

    private String stringValueOf(String name) {
        if (this.pending.containsKey(name)) {
            return this.pending.get(name);
        }
        Optional<String> v = this.currentValues.getStringValue(name);
        return v.orElseGet(() -> this.options.getStringOptions().get(name).getOption().getDefaultValue());
    }

    private boolean booleanValueOf(String name) {
        if (this.pending.containsKey(name)) {
            return "true".equals(this.pending.get(name));
        }
        BooleanOption option = this.options.getBooleanOptions().get(name).getOption();
        return this.currentValues.getBooleanValue(name).orElse(option.getDefaultValue());
    }

    private OptionValues effectiveValues() {
        MutableOptionValues values = this.currentValues.mutableCopy();
        values.addAll(this.pending);
        return values;
    }

    private void apply() {
        Map<String, String> changes = new HashMap<>(this.pending);
        this.pending.clear();
        Iris.queueShaderPackOptions(changes);
        reloadRenderers();
        initGui();
    }

    private void reloadRenderers() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    // --- GuiScreen plumbing ---

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

        ShaderPack pack = Iris.getCurrentPack();
        String packName = pack != null ? Iris.getSelectedPackName() : "Shader Options";
        this.drawCenteredString(this.fontRenderer, packName, this.width / 2, 16, 0xFFFFFFFF);
        if (this.currentScreen != null) {
            this.drawCenteredString(this.fontRenderer, "§7" + this.lang.screenLabel(this.currentScreen), this.width / 2, 30, 0xFFAAAAAA);
        }

        for (AbstractWidget widget : this.widgets) {
            widget.render(ctx, mouseX, mouseY, partialTicks);
        }

        // Tooltip for the hovered option element.
        for (OptionButtonWidget element : this.elements) {
            if (element.isEnabled() && element.getTooltip() != null && element.getDim().containsCursor(mouseX, mouseY)) {
                this.drawHoveringText(element.getTooltip(), mouseX, mouseY);
                break;
            }
        }
    }
}
