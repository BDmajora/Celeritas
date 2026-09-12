package com.bdmajora.impetus.engine.impl.gui;

import lombok.Getter;
import com.bdmajora.impetus.api.OptionGUIConstructionEvent;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.engine.impl.gui.frame.AbstractFrame;
import com.bdmajora.impetus.engine.impl.gui.frame.BasicFrame;
import com.bdmajora.impetus.engine.impl.gui.frame.tab.Tab;
import com.bdmajora.impetus.engine.impl.gui.frame.tab.TabFrame;
import com.bdmajora.impetus.engine.impl.gui.framework.*;
import com.bdmajora.impetus.engine.impl.gui.widgets.FlatButtonWidget;
import com.bdmajora.impetus.engine.impl.gui.widgets.SearchBarWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class ImpetusVideoOptionsController implements Renderable {
    private static final float ASPECT_RATIO = 5f / 4f;
    private static final int MINIMUM_WIDTH = 550;

    private static final AtomicReference<TextComponent> tabFrameSelectedTab = new AtomicReference<>(null);
    private final AtomicReference<Integer> tabFrameScrollBarOffset = new AtomicReference<>(0);
    private final AtomicReference<Integer> optionPageScrollBarOffset = new AtomicReference<>(0);

    private final List<OptionPage> pages = new ArrayList<>();
    private final Runnable onClose;
    private final DrawContext font;

    @Getter
    private AbstractFrame frame;
    private FlatButtonWidget applyButton, closeButton, undoButton;

    // Title/id of the synthesized search-results page
    private static final TextComponent SEARCH_RESULTS_TITLE = TextComponent.translatable("impetus.search_results");

    private SearchBarWidget searchBar;
    private String searchQuery = "";
    private @Nullable TextComponent preSearchTabTitle;

    @Getter
    private boolean hasPendingChanges;

    private boolean firstInit = true;

    private int width, height;

    public ImpetusVideoOptionsController(Runnable onClose, List<OptionPage> pages, DrawContext font) {
        this.onClose = onClose;
        this.pages.addAll(pages);

        OptionGUIConstructionEvent.BUS.post(new OptionGUIConstructionEvent(this.pages));

        this.font = font;
    }

    // Rebuilds the whole frame tree for a new size
    public void init(int width, int height) {
        this.width = width;
        this.height = height;
        this.frame = this.parentFrameBuilder().build();
    }

    // The root frame: tab area plus the button bar
    protected BasicFrame.Builder parentFrameBuilder() {
        BasicFrame.Builder basicFrameBuilder;

        // Apply aspect ratio clamping on wide enough screens
        int newWidth = this.width;
        if (newWidth > MINIMUM_WIDTH && (float) this.width / (float) this.height > ASPECT_RATIO) {
            newWidth = Math.max(MINIMUM_WIDTH, (int) (this.height * ASPECT_RATIO));
        }

        Dim2i basicFrameDim = new Dim2i((this.width - newWidth) / 2, 0, newWidth, this.height);
        Dim2i tabFrameDim = new Dim2i(basicFrameDim.x() + basicFrameDim.width() / 20 / 2, basicFrameDim.y() + basicFrameDim.height() / 4 / 2, basicFrameDim.width() - (basicFrameDim.width() / 20), basicFrameDim.height() / 4 * 3);

        Dim2i undoButtonDim = new Dim2i(tabFrameDim.getLimitX() - 203, tabFrameDim.getLimitY() + 5, 65, 20);
        Dim2i applyButtonDim = new Dim2i(tabFrameDim.getLimitX() - 134, tabFrameDim.getLimitY() + 5, 65, 20);
        Dim2i closeButtonDim = new Dim2i(tabFrameDim.getLimitX() - 65, tabFrameDim.getLimitY() + 5, 65, 20);

        // Full-width search field above the tab frame; recreated on rebuild but preserving query and focus.
        Dim2i searchBarDim = new Dim2i(tabFrameDim.x(), Math.max(2, tabFrameDim.y() - 24), tabFrameDim.width(), 18);
        boolean searchFocused = this.searchBar != null && this.searchBar.isFocused();
        this.searchBar = new SearchBarWidget(searchBarDim, this.searchQuery, searchFocused, this::setSearchQuery);

        this.undoButton = new FlatButtonWidget(undoButtonDim, TextComponent.translatable("impetus.options.buttons.undo"), this::undoChanges);
        this.applyButton = new FlatButtonWidget(applyButtonDim, TextComponent.translatable("impetus.options.buttons.apply"), this::applyChanges);
        this.closeButton = new FlatButtonWidget(closeButtonDim, TextComponent.translatable("gui.done"), this.onClose);

        basicFrameBuilder = this.parentBasicFrameBuilder(basicFrameDim, tabFrameDim);

        return basicFrameBuilder;
    }

    // Draws the root frame
    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        this.updateControls();
        this.frame.render(drawContext, mouseX, mouseY, partialTicks);
    }

    // Enables Apply and Undo only when something changed
    private void updateControls() {
        boolean hasChanges = this.getAllOptions()
                .anyMatch(Option::hasChanged);

        this.applyButton.setEnabled(hasChanges);
        this.undoButton.setVisible(hasChanges);
        this.closeButton.setEnabled(!hasChanges);

        this.hasPendingChanges = hasChanges;
    }

    // Every option on every page
    private Stream<Option<?>> getAllOptions() {
        return this.pages.stream()
                .flatMap(s -> s.getOptions().stream());
    }

    // Writes each changed option, saves every storage, then runs flag side effects
    private void applyChanges() {
        final HashSet<OptionStorage<?>> dirtyStorages = new HashSet<>();
        final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);

        this.getAllOptions().forEach((option -> {
            if (!option.hasChanged()) {
                return;
            }

            option.applyChanges();

            flags.addAll(option.getFlags());
            dirtyStorages.add(option.getStorage());
        }));

        for (OptionStorage<?> storage : dirtyStorages) {
            storage.save(flags);
        }

        applyFlagSideEffects(Collections.unmodifiableSet(flags));
    }

    // Platform hook: reload renderer, textures or world as flagged
    protected void applyFlagSideEffects(Set<OptionFlag> flags) {

    }

    // Resets every option to its stored value
    private void undoChanges() {
        this.getAllOptions()
                .forEach(Option::reset);
    }

    // Hides pages with no visible options
    private boolean canShowPage(OptionPage page) {
        return !page.getGroups().isEmpty();
    }

    // Platform hook for pages that are not option pages
    protected void createExtraTabs(Map<String, List<Tab<?>>> tabs) {

    }

    // One tab per page, grouped by owning mod
    private AbstractFrame createTabFrame(Dim2i tabFrameDim) {
        // TabFrame will automatically expand its height to fit all tabs, so the scrollable frame can handle it
        return TabFrame.createBuilder()
                .setDimension(tabFrameDim)
                .shouldRenderOutline(false)
                .setTabSectionScrollBarOffset(tabFrameScrollBarOffset)
                .setTabSectionSelectedTab(tabFrameSelectedTab)
                .addTabs(tabs -> this.pages
                        .stream()
                        .filter(this::canShowPage)
                        .forEach(page -> tabs.computeIfAbsent(page.getId().getModId(), $ -> new ArrayList<>()).add(Tab.from(page, o -> true, optionPageScrollBarOffset)))
                )
                .addTabs(this::createExtraTabs)
                .addTabs(tabs -> {
                    if (!this.searchQuery.isEmpty()) {
                        var resultsPage = this.buildSearchResultsPage();
                        tabs.computeIfAbsent(resultsPage.getId().getModId(), $ -> new ArrayList<>())
                                .add(Tab.from(resultsPage, o -> true, optionPageScrollBarOffset, false));
                    }
                })
                .onSetTab(() -> {
                    optionPageScrollBarOffset.set(0);
                })
                .build(this.font);
    }

    // Assembles search bar, tabs and buttons
    public BasicFrame.Builder parentBasicFrameBuilder(Dim2i parentBasicFrameDim, Dim2i tabFrameDim) {
        return BasicFrame.createBuilder()
                .setDimension(parentBasicFrameDim)
                .shouldRenderOutline(false)
                // First child so it sees key events before anything else.
                .addChild(dim -> this.searchBar)
                .addChild(parentDim -> this.createTabFrame(tabFrameDim))
                .addChild(dim -> this.undoButton)
                .addChild(dim -> this.applyButton)
                .addChild(dim -> this.closeButton);
    }

    // Live search: while query is non-empty, shows a synthesized "Search Results" tab with matching live option instances (editable in place); clearing restores the previous tab
    private void setSearchQuery(String query) {
        var trimmed = query.trim();

        if (trimmed.equals(this.searchQuery)) {
            return;
        }

        boolean wasSearching = !this.searchQuery.isEmpty();
        boolean searching = !trimmed.isEmpty();
        this.searchQuery = trimmed;

        if (searching && !wasSearching) {
            this.preSearchTabTitle = tabFrameSelectedTab.get();
        }

        if (searching) {
            tabFrameSelectedTab.set(SEARCH_RESULTS_TITLE);
        } else if (wasSearching) {
            tabFrameSelectedTab.set(this.preSearchTabTitle);
        }

        this.optionPageScrollBarOffset.set(0);
        this.frame = this.parentFrameBuilder().build();
    }

    // A synthetic page of every option matching the query
    private OptionPage buildSearchResultsPage() {
        var needle = this.searchQuery.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<Option<?>>();

        for (var page : this.pages) {
            for (var option : page.getOptions()) {
                if (this.matchesQuery(option, needle)) {
                    matches.add(option);
                }
            }
        }

        List<OptionGroup> groups;

        if (matches.isEmpty()) {
            groups = List.of();
        } else {
            var group = OptionGroup.createBuilder()
                    .setId(OptionIdentifier.create("impetus", "search_results"));
            matches.forEach(group::add);
            groups = List.of(group.build());
        }

        return new OptionPage(OptionIdentifier.create("impetus", "search_results"), SEARCH_RESULTS_TITLE, groups);
    }

    // Case-insensitive match on name and tooltip
    private boolean matchesQuery(Option<?> option, String needle) {
        var name = this.font.extractString(option.getName());

        if (name.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }

        var tooltip = option.getTooltip();

        return tooltip != null && this.font.extractString(tooltip).toLowerCase(Locale.ROOT).contains(needle);
    }
}
