package net.irisshaders.iris.api.v0;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.taumc.celeritas.iris.Iris;
import org.taumc.celeritas.iris.gui.GuiSelectShaderPack;

/**
 * The small slice of modern Iris's public API that Celeritas's {@code ShaderModBridge} reflectively probes for
 * ({@code getInstance}, {@code isShaderPackInUse}, {@code openMainIrisScreenObj}). Providing this class under the
 * expected package is what makes Celeritas show its built-in "Shader Pack Selection" tab in the video options and
 * route it to our {@link GuiSelectShaderPack} — no changes to the shared {@code common} module required.
 * <p>
 * This is a thin bridge to {@link Iris}; it deliberately does not implement the full {@code IrisApi} surface (only what
 * {@code ShaderModBridge} calls), and 1.12.2 never ships the real modern-Iris class, so there is no collision.
 */
public class IrisApi {
    private static final IrisApi INSTANCE = new IrisApi();

    public static IrisApi getInstance() {
        return INSTANCE;
    }

    public boolean isShaderPackInUse() {
        return Iris.isShaderPackInUse();
    }

    /**
     * @param parent the object Celeritas passes as the "parent" (its options controller, not always a screen)
     * @return the shader selection {@link GuiScreen}; the parent to return to is the object if it is a screen,
     * otherwise the current screen (the Celeritas video options screen that opened us)
     */
    public Object openMainIrisScreenObj(Object parent) {
        GuiScreen parentScreen = parent instanceof GuiScreen
                ? (GuiScreen) parent
                : Minecraft.getMinecraft().currentScreen;
        return new GuiSelectShaderPack(parentScreen);
    }
}
