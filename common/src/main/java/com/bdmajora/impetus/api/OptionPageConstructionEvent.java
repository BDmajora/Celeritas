package com.bdmajora.impetus.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.eventbus.ImpetusEvent;
import com.bdmajora.impetus.api.eventbus.EventHandlerRegistrar;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Fired on option page creation so listeners can append extra OptionGroups to the page
@RequiredArgsConstructor
@Getter
public class OptionPageConstructionEvent extends ImpetusEvent {
    public static final EventHandlerRegistrar<OptionPageConstructionEvent> BUS = new EventHandlerRegistrar<>();

    private final OptionIdentifier<Void> id;
    private final TextComponent translationKey;

    private final List<OptionGroup> additionalGroups = new ArrayList<>();

    // Groups are always appended after any existing ones
    public void addGroup(OptionGroup group) {
        this.additionalGroups.add(group);
    }

    // Mutable; handlers append
    public List<OptionGroup> getAdditionalGroups() {
        return Collections.unmodifiableList(this.additionalGroups);
    }
}
