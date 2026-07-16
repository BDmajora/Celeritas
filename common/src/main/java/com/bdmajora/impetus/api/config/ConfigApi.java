package com.bdmajora.impetus.api.config;

import com.bdmajora.impetus.api.OptionGUIConstructionEvent;
import com.bdmajora.impetus.api.OptionGroupConstructionEvent;
import com.bdmajora.impetus.api.OptionPageConstructionEvent;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionPage;

import java.util.Objects;
import java.util.function.Supplier;

public final class ConfigApi {
    private ConfigApi() {
    }

    public static void registerPage(Supplier<OptionPage> page) {
        Objects.requireNonNull(page, "Page supplier must not be null");
        OptionGUIConstructionEvent.BUS.addListener(event -> event.addPage(page.get()));
    }

    public static void registerGroup(OptionIdentifier<Void> pageId, Supplier<OptionGroup> group) {
        Objects.requireNonNull(pageId, "Page id must not be null");
        Objects.requireNonNull(group, "Group supplier must not be null");

        OptionPageConstructionEvent.BUS.addListener(event -> {
            if (pageId.matches(event.getId())) {
                event.addGroup(group.get());
            }
        });
    }

    public static void registerOption(OptionIdentifier<Void> groupId, Supplier<Option<?>> option) {
        Objects.requireNonNull(groupId, "Group id must not be null");
        Objects.requireNonNull(option, "Option supplier must not be null");

        OptionGroupConstructionEvent.BUS.addListener(event -> {
            if (groupId.matches(event.getId())) {
                event.getOptions().add(option.get());
            }
        });
    }
}
