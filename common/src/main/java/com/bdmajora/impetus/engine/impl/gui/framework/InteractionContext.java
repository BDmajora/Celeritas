package com.bdmajora.impetus.engine.impl.gui.framework;

public interface InteractionContext {
    default void playClickSound() {

    }

    default boolean isSpecialKeyDown(SpecialKey key) {
        return false;
    }

    enum SpecialKey {
        SHIFT,
        CTRL,
        ALT
    }
}
