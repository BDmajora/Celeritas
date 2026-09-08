package net.irisshaders.iris.api.v0;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.gui.modern.ShaderPackSelectScreen;

// Real Iris mods probe for this exact class via reflection; package/class names must stay as-is
public class IrisApi {
    private static final IrisApi INSTANCE = new IrisApi();

    public static IrisApi getInstance() {
        return INSTANCE;
    }

    public boolean isShaderPackInUse() {
        return Umbra.isShaderPackInUse();
    }

    /** Opens the shader pack selection screen. */
    public Object openMainIrisScreenObj(Object parent) {
        GuiScreen parentScreen = parent instanceof GuiScreen
                ? (GuiScreen) parent
                : Minecraft.getMinecraft().currentScreen;
        return new ShaderPackSelectScreen(parentScreen);
    }
}
