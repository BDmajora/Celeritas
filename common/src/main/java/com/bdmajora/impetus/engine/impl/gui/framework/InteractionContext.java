package com.bdmajora.impetus.engine.impl.gui.framework;

public interface InteractionContext {
    // Defaults to silent
    default void playClickSound() {

    }

    // Defaults to false
    default boolean isSpecialKeyDown(SpecialKey key) {
        return false;
    }

    enum SpecialKey {
        SHIFT,
        CTRL,
        ALT
    }
}
