package com.bdmajora.impetus.api.eventbus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Holds listeners for one event type and dispatches to them
public class EventHandlerRegistrar<T extends ImpetusEvent> {
    private final List<Handler<T>> handlerList = new CopyOnWriteArrayList<>();

    public EventHandlerRegistrar() {}

    public void addListener(Handler<T> listener) {
        handlerList.add(listener);
    }

    // Returns true if the event was cancelable and got canceled by a listener
    public boolean post(T event) {
        boolean canceled = false;

        // Skip doing work if the handler list is empty
        if(!handlerList.isEmpty()) {
            boolean isCancelable = event.isCancelable();
            for(Handler<T> handler : handlerList) {
                handler.acceptEvent(event);
                if(isCancelable && event.isCanceled()) {
                    canceled = true;
                }
            }
        }

        // Dispatch to the platform event bus as well (currently only used on Forge)
        canceled |= postPlatformSpecificEvent(event);
        return canceled;
    }

    private static <T extends ImpetusEvent> boolean postPlatformSpecificEvent(T event) {
        return false;
    }

    @FunctionalInterface
    public interface Handler<T extends ImpetusEvent> {
        void acceptEvent(T event);
    }
}
