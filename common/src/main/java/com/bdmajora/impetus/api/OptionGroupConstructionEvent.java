package com.bdmajora.impetus.api;

import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.api.eventbus.ImpetusEvent;
import com.bdmajora.impetus.api.eventbus.EventHandlerRegistrar;
import com.bdmajora.impetus.api.options.OptionIdentifier;

import java.util.List;

// Fired on option group creation so listeners can replace/extend its options (e.g. VSync, fullscreen)
public class OptionGroupConstructionEvent extends ImpetusEvent {
    public static final EventHandlerRegistrar<OptionGroupConstructionEvent> BUS = new EventHandlerRegistrar<>();

    private final OptionIdentifier<Void> id;
    private final List<Option<?>> options;

    public OptionGroupConstructionEvent(OptionIdentifier<Void> id, List<Option<?>> options) {
        this.id = id;
        this.options = options;
    }

    public List<Option<?>> getOptions() {
        return this.options;
    }

    public OptionIdentifier<Void> getId() {
        return this.id;
    }
}
