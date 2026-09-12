package com.bdmajora.impetus.api.config;

import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConfigPageBuilder {
    private final OptionIdentifier<Void> id;
    private final TextComponent name;
    private final List<OptionGroup> groups = new ArrayList<>();

    ConfigPageBuilder(OptionIdentifier<Void> id, TextComponent name) {
        this.id = Objects.requireNonNull(id, "Page id must not be null");
        this.name = Objects.requireNonNull(name, "Page name must not be null");
    }

    // Appends
    public ConfigPageBuilder addGroup(OptionGroup group) {
        this.groups.add(Objects.requireNonNull(group, "Group must not be null"));
        return this;
    }

    // Finalises
    public OptionPage build() {
        return new OptionPage(this.id, this.name, List.copyOf(this.groups));
    }
}
