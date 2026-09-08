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

    // typedChar is '\0' when none; keyCode is LWJGL2 or GLFW depending on runtime - match typedChar when possible
    default boolean keyTyped(char typedChar, int keyCode) {
        return false;
    }
}
