package net.irisshaders.iris.api.v0;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.gui.modern.ShaderPackSelectScreen;

/** Minimal Iris API bridge for ShaderModBridge. */
public class IrisApi {
    private static final IrisApi INSTANCE = new IrisApi();

    public static IrisApi getInstance() {
        return INSTANCE;
    }

    public boolean isShaderPackInUse() {
        return Iris.isShaderPackInUse();
    }

    /** Opens the shader pack selection screen. */
    public Object openMainIrisScreenObj(Object parent) {
        GuiScreen parentScreen = parent instanceof GuiScreen
                ? (GuiScreen) parent
                : Minecraft.getMinecraft().currentScreen;
        return new ShaderPackSelectScreen(parentScreen);
    }
}
