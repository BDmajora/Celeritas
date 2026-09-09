package com.bdmajora.impetus.engine.impl.gui.frame.tab;

import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.InteractionContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.frame.MultiOptionPageFrame;
import com.bdmajora.impetus.engine.impl.gui.widgets.AbstractWidget;
import com.bdmajora.impetus.engine.impl.gui.widgets.FlatButtonWidget;
import com.bdmajora.impetus.engine.impl.gui.theme.DefaultColors;
import com.bdmajora.impetus.engine.impl.util.Dim2i;
import com.bdmajora.impetus.engine.impl.gui.frame.AbstractFrame;
import com.bdmajora.impetus.engine.impl.gui.frame.ScrollableFrame;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TabFrame extends AbstractFrame {
    private static final int TAB_OPTION_INDENT = 5;

    private Dim2i tabSection;
    private final Dim2i frameSection;
    private final Map<String, List<Tab<?>>> tabs;
    private final Map<String, Integer> modAccentColors;
    private final Runnable onSetTab;
    private final AtomicReference<TextComponent> tabSectionSelectedTab;
    private final AtomicReference<Integer> tabSectionScrollBarOffset;
    private Tab<?> selectedTab;
    private AbstractFrame selectedFrame;
    private Dim2i tabSectionInner;
    private ScrollableFrame sidebarFrame;

    public TabFrame(DrawContext drawContext, Dim2i dim, boolean renderOutline, Map<String, List<Tab<?>>> tabs, Runnable onSetTab, AtomicReference<TextComponent> tabSectionSelectedTab, AtomicReference<Integer> tabSectionScrollBarOffset) {
        super(dim, renderOutline);
        this.tabs = Collections.unmodifiableMap(tabs.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()), (a, b) -> a, LinkedHashMap::new)));
        this.modAccentColors = this.tabs.keySet().stream().collect(Collectors.toMap(id -> id, drawContext::getModAccentColor, (a, b) -> a, LinkedHashMap::new));
        int tabSectionY = (int)tabStream().count() * 18 + this.tabs.size() * TabHeaderWidget.HEIGHT;
        Optional<Integer> result = Stream.concat(
                // Icon padding + icon + icon padding, matching where TabHeaderWidget starts drawing its name
                tabs.keySet().stream().map(id -> {
                    int headerTextOffset = 5 + 20 + 5;
                    return drawContext.getStringWidth(drawContext.getFriendlyModName(id)) + headerTextOffset;
                }),
                tabStream().map(tab -> drawContext.getStringWidth(tab.title()) + TAB_OPTION_INDENT)
        ).max(Integer::compareTo);

        this.tabSection = new Dim2i(this.dim.x(), this.dim.y(), result.map(integer -> integer + (24)).orElseGet(() -> (int) (this.dim.width() * 0.35D)), this.dim.height());
        this.tabSectionInner = tabSectionY > this.dim.height() ? this.tabSection.withHeight(tabSectionY) : this.tabSection;
        this.frameSection = new Dim2i(this.tabSection.getLimitX(), this.dim.y(), this.dim.width() - this.tabSection.width(), this.dim.height());

        this.onSetTab = onSetTab;
        this.tabSectionSelectedTab = tabSectionSelectedTab;
        this.tabSectionScrollBarOffset = tabSectionScrollBarOffset;

        if (this.tabSectionSelectedTab.get() != null) {
            this.selectedTab = tabStream().filter(tab -> tab.title().equals(this.tabSectionSelectedTab.get())).findAny().orElse(null);
        }

        this.buildFrame();

        // Let's build each frame, future note for anyone: do not move this line.
        tabStream().filter(tab -> this.selectedTab != tab).forEach(tab -> {
            tab.createFrame(this.frameSection);
        });
    }

    private Stream<Tab<?>> tabStream() {
        return this.tabs.values().stream().flatMap(Collection::stream);
    }

    public static Builder createBuilder() {
        return new Builder();
    }

    public void setTab(Tab<?> tab) {
        this.selectedTab = tab;
        this.tabSectionSelectedTab.set(this.selectedTab.title());
        if (this.onSetTab != null) {
            this.onSetTab.run();
        }
        this.buildFrame();
    }

    class TabSidebarFrame extends AbstractFrame {
        TabSidebarFrame(Dim2i dim) {
            super(dim, false);
        }

        @Override
        public void buildFrame() {
            this.children.clear();
            this.drawable.clear();
            this.controlElements.clear();

            rebuildTabs();

            super.buildFrame();
        }

        private void rebuildTabs() {
            int offsetY = 0;
            int width = tabSection.width() - 4;
            int height = 18;

            for (var modEntry : tabs.entrySet()) {
                int accentColor = modAccentColors.getOrDefault(modEntry.getKey(), DefaultColors.ELEMENT_ACTIVATED);
                // Add a "button" as the header
                Dim2i modHeaderDim = new Dim2i(0, offsetY, width, TabHeaderWidget.HEIGHT).withParentOffset(tabSection);
                offsetY += TabHeaderWidget.HEIGHT;
                TabHeaderWidget headerButton = new TabHeaderWidget(modHeaderDim, modEntry.getKey());
                headerButton.setLeftAligned(true);
                this.children.add(headerButton);

                for (Tab<?> tab : modEntry.getValue()) {
                    // Add the button for the mod itself
                    Dim2i tabDim = new Dim2i(0, offsetY, width, height).withParentOffset(tabSection);

                    FlatButtonWidget button = new FlatButtonWidget(tabDim, tab.title(), () -> {
                        if(tab.onSelectFunction() == null || tab.onSelectFunction().get()) {
                            TabFrame.this.setTab(tab);
                        }
                    }) {
                        @Override
                        protected int getLeftAlignedTextOffset(DrawContext drawContext) {
                            return TAB_OPTION_INDENT + super.getLeftAlignedTextOffset(drawContext);
                        }
                    };

                    button.setSelected(TabFrame.this.selectedTab == tab);
                    button.setLeftAligned(true);
                    FlatButtonWidget.Style style = FlatButtonWidget.Style.defaults();
                    style.textDefault = DefaultColors.withAlpha(accentColor, 0xB8);
                    style.textSelected = 0xFFFFFFFF;
                    style.accentColor = accentColor;
                    button.setStyle(style);
                    this.children.add(button);

                    offsetY += height;
                }
            }
        }
    }

    @Override
    public void buildFrame() {
        this.children.clear();
        this.drawable.clear();
        this.controlElements.clear();

        if (this.selectedTab == null) {
            if (!this.tabs.isEmpty()) {
                // Just use the first tab for now
                this.selectedTab = tabStream().findFirst().orElseThrow();
            }
        }

        this.sidebarFrame = ScrollableFrame.createBuilder()
                .setDimension(this.tabSection)
                .setFrame(new TabSidebarFrame(this.tabSectionInner))
                .setVerticalScrollBarOffset(this.tabSectionScrollBarOffset)
                .build();

        this.children.add(this.sidebarFrame);

        this.rebuildTabFrame();

        super.buildFrame();
    }

    private void rebuildTabFrame() {
        if (this.selectedTab == null) return;
        AbstractFrame frame = this.createSelectedContentFrame();
        if (frame != null) {
            this.selectedFrame = frame;
            frame.buildFrame();
            this.children.add(frame);
        }
    }

    private AbstractFrame createSelectedContentFrame() {
        if (!this.selectedTab.stackable() || this.selectedTab.page() == null || this.selectedTab.verticalScrollBarOffset() == null) {
            return this.selectedTab.createFrame(this.frameSection);
        }

        List<Tab<?>> stackableTabs = this.getSelectedTabGroup().stream()
                .filter(tab -> tab.stackable() && tab.page() != null)
                .collect(Collectors.toList());

        if (stackableTabs.size() <= 1) {
            return this.selectedTab.createFrame(this.frameSection);
        }

        List<OptionPage> pages = stackableTabs.stream()
                .map(Tab::page)
                .collect(Collectors.toList());
        Predicate<Option<?>> optionFilter = this.selectedTab.optionFilter() != null ? this.selectedTab.optionFilter() : option -> true;

        MultiOptionPageFrame frame = MultiOptionPageFrame.createBuilder()
                .setDimension(new Dim2i(this.frameSection.x(), this.frameSection.y(), this.frameSection.width(), this.frameSection.height()))
                .setPages(pages)
                .setOptionFilter(optionFilter)
                .build();

        this.selectedTab.verticalScrollBarOffset().set(frame.getSectionOffset(this.selectedTab.page()));

        return ScrollableFrame.createBuilder()
                .setDimension(this.frameSection)
                .setFrame(frame)
                .setVerticalScrollBarOffset(this.selectedTab.verticalScrollBarOffset())
                .setScrollBarAccentColor(this.modAccentColors.getOrDefault(this.selectedTab.page().getId().getModId(), DefaultColors.ELEMENT_ACTIVATED))
                .build();
    }

    private List<Tab<?>> getSelectedTabGroup() {
        for (List<Tab<?>> group : this.tabs.values()) {
            if (group.contains(this.selectedTab)) {
                return group;
            }
        }

        return Collections.singletonList(this.selectedTab);
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        for (AbstractWidget widget : this.children) {
            if (widget != this.selectedFrame) {
                widget.render(drawContext, mouseX, mouseY, delta);
            }
        }
        if(this.selectedFrame != null) {
            this.selectedFrame.render(drawContext, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean mouseClicked(InteractionContext context, double mouseX, double mouseY, int button) {
        return (this.dim.containsCursor(mouseX, mouseY) && super.mouseClicked(context, mouseX, mouseY, button));
    }

    @Override
    public boolean mouseScrolled(InteractionContext context, double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.selectedFrame != null && this.frameSection.containsCursor(mouseX, mouseY)) {
            return this.selectedFrame.mouseScrolled(context, mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        return super.mouseScrolled(context, mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public static class Builder {
        private final Map<String, List<Tab<?>>> functions = new LinkedHashMap<>();
        private Dim2i dim;
        private boolean renderOutline;
        private Runnable onSetTab;
        private AtomicReference<TextComponent> tabSectionSelectedTab = new AtomicReference<>(null);
        private AtomicReference<Integer> tabSectionScrollBarOffset = new AtomicReference<>(0);

        public Builder setDimension(Dim2i dim) {
            this.dim = dim;
            return this;
        }

        public Builder shouldRenderOutline(boolean renderOutline) {
            this.renderOutline = renderOutline;
            return this;
        }

        public Builder addTabs(Consumer<Map<String, List<Tab<?>>>> tabs) {
            tabs.accept(this.functions);
            return this;
        }

        public Builder onSetTab(Runnable onSetTab) {
            this.onSetTab = onSetTab;
            return this;
        }

        public Builder setTabSectionSelectedTab(AtomicReference<TextComponent> tabSectionSelectedTab) {
            this.tabSectionSelectedTab = tabSectionSelectedTab;
            return this;
        }

        public Builder setTabSectionScrollBarOffset(AtomicReference<Integer> tabSectionScrollBarOffset) {
            this.tabSectionScrollBarOffset = tabSectionScrollBarOffset;
            return this;
        }

        public TabFrame build(DrawContext font) {
            Objects.requireNonNull(this.dim, "Dimension must be specified");

            return new TabFrame(font, this.dim, this.renderOutline, this.functions, this.onSetTab, this.tabSectionSelectedTab, this.tabSectionScrollBarOffset);
        }
    }
}
