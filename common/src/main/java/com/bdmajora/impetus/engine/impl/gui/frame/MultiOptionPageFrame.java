package com.bdmajora.impetus.engine.impl.gui.frame;

import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class MultiOptionPageFrame extends AbstractFrame {
    private static final int SECTION_GAP = 8;

    private final List<OptionPage> pages;
    private final Predicate<Option<?>> optionFilter;
    private final Map<OptionPage, Integer> sectionOffsets = new IdentityHashMap<>();

    public MultiOptionPageFrame(Dim2i dim, boolean renderOutline, List<OptionPage> pages, Predicate<Option<?>> optionFilter) {
        super(dim, renderOutline);
        this.pages = pages;
        this.optionFilter = optionFilter;
        this.buildFrame();
    }

    @Override
    public void buildFrame() {
        this.children.clear();
        this.drawable.clear();
        this.controlElements.clear();
        this.sectionOffsets.clear();

        int y = 0;

        for (OptionPage page : this.pages) {
            if (!hasVisibleOptions(page)) {
                continue;
            }

            this.sectionOffsets.put(page, y);

            OptionPageFrame frame = OptionPageFrame.createBuilder()
                    .setDimension(new Dim2i(this.dim.x(), this.dim.y() + y, this.dim.width(), this.dim.height()))
                    .setOptionPage(page)
                    .setOptionFilter(this.optionFilter)
                    .build();

            this.children.add(frame);
            y += frame.getDimensions().height() + SECTION_GAP;
        }

        this.dim = this.dim.withHeight(Math.max(0, y - SECTION_GAP));
        super.buildFrame();
    }

    private boolean hasVisibleOptions(OptionPage page) {
        return page.getOptions().stream().anyMatch(this.optionFilter);
    }

    public int getSectionOffset(OptionPage page) {
        return this.sectionOffsets.getOrDefault(page, 0);
    }

    public static Builder createBuilder() {
        return new Builder();
    }

    public static class Builder {
        private Dim2i dim;
        private boolean renderOutline;
        private List<OptionPage> pages;
        private Predicate<Option<?>> optionFilter = o -> true;

        public Builder setDimension(Dim2i dim) {
            this.dim = dim;
            return this;
        }

        public Builder shouldRenderOutline(boolean renderOutline) {
            this.renderOutline = renderOutline;
            return this;
        }

        public Builder setPages(List<OptionPage> pages) {
            this.pages = pages;
            return this;
        }

        public Builder setOptionFilter(Predicate<Option<?>> optionFilter) {
            this.optionFilter = optionFilter;
            return this;
        }

        public MultiOptionPageFrame build() {
            Objects.requireNonNull(this.dim, "Dimension must be specified");
            Objects.requireNonNull(this.pages, "Option pages must be specified");

            return new MultiOptionPageFrame(this.dim, this.renderOutline, this.pages, this.optionFilter);
        }
    }
}
