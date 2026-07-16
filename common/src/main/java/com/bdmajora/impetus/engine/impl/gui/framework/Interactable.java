package com.bdmajora.impetus.engine.impl.gui.framework;

public interface Interactable {
    boolean isMouseOver(double mouseX, double mouseY);

    default boolean mouseClicked(InteractionContext context, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseReleased(InteractionContext context, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(InteractionContext context, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return false;
    }

    default boolean mouseScrolled(InteractionContext context, double mouseX, double mouseY, double deltaX, double deltaY) {
        return false;
    }

    /**
     * Handles a key press. {@code typedChar} is the produced character ({@code '\0'} when none); {@code keyCode}
     * is the platform key code (LWJGL2 or GLFW depending on the runtime — consumers should prefer matching on
     * {@code typedChar} and accept both code sets for editing keys).
     */
    default boolean keyTyped(char typedChar, int keyCode) {
        return false;
    }
}
