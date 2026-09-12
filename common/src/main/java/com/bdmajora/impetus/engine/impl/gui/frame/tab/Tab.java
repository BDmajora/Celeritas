package com.bdmajora.impetus.engine.impl.gui.frame.tab;

import lombok.Builder;
import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.theme.DefaultColors;
import com.bdmajora.impetus.engine.impl.util.Dim2i;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.engine.impl.gui.frame.AbstractFrame;
import com.bdmajora.impetus.engine.impl.gui.frame.OptionPageFrame;
import com.bdmajora.impetus.engine.impl.gui.frame.ScrollableFrame;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Builder(builderClassName = "Builder", setterPrefix = "set")
public record Tab<T extends AbstractFrame>(
        OptionIdentifier<Void> id,
        TextComponent title,
        Supplier<Boolean> onSelectFunction,
        Function<Dim2i, T> frameFunction,
        @Nullable OptionPage page,
        @Nullable Predicate<Option<?>> optionFilter,
        @Nullable AtomicReference<Integer> verticalScrollBarOffset,
        boolean stackable
) {
    // Starts a builder
    public static Tab.Builder<?> createBuilder() {
        return new Tab.Builder<>();
    }

    // Builds this tab's content frame at the given size
    public T createFrame(Dim2i dim) {
        return this.frameFunction != null ? this.frameFunction.apply(dim) : null;
    }

    // A scrollable tab for one page
    public static Tab<ScrollableFrame> from(OptionPage page, Predicate<Option<?>> optionFilter, AtomicReference<Integer> verticalScrollBarOffset) {
        return from(page, optionFilter, verticalScrollBarOffset, true);
    }

    // Same, with stackable controlling whether it merges into a multi-page view
    public static Tab<ScrollableFrame> from(OptionPage page, Predicate<Option<?>> optionFilter, AtomicReference<Integer> verticalScrollBarOffset, boolean stackable) {
        Function<Dim2i, ScrollableFrame> frameFunction = dim2i -> ScrollableFrame
                .createBuilder()
                .setDimension(dim2i)
                .setFrame(OptionPageFrame
                        .createBuilder()
                        .setDimension(new Dim2i(dim2i.x(), dim2i.y(), dim2i.width(), dim2i.height()))
                        .setOptionPage(page)
                        .setOptionFilter(optionFilter)
                        .build())
                .setVerticalScrollBarOffset(verticalScrollBarOffset)
                .setScrollBarAccentColor(DefaultColors.getModAccentColor(page.getId().getModId()))
                .build();
        return Tab.<ScrollableFrame>builder()
                .setTitle(page.getName())
                .setId(page.getId())
                .setPage(page)
                .setOptionFilter(optionFilter)
                .setVerticalScrollBarOffset(verticalScrollBarOffset)
                .setStackable(stackable)
                .setFrameFunction(frameFunction)
                .build();
    }
}
