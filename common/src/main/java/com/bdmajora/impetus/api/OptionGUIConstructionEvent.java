package com.bdmajora.impetus.api;

import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.api.eventbus.ImpetusEvent;
import com.bdmajora.impetus.api.eventbus.EventHandlerRegistrar;

import java.util.List;

// Fired while building the main options GUI; full page list is passed in so listeners can insert their own page anywhere
public class OptionGUIConstructionEvent extends ImpetusEvent {
    public static final EventHandlerRegistrar<OptionGUIConstructionEvent> BUS = new EventHandlerRegistrar<>();

    private final List<OptionPage> pages;

    public OptionGUIConstructionEvent(List<OptionPage> pages) {
        this.pages = pages;
    }

    // List is mutable, callers can insert pages directly
    public List<OptionPage> getPages() {
        return this.pages;
    }
    
    public void addPage(OptionPage page) {
        this.pages.add(page);
    }
}
