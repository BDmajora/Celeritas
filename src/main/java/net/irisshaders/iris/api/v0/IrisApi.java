package net.irisshaders.iris.api.v0;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.gui.modern.ShaderPackSelectScreen;

// The Iris v0 public API surface, implemented on top of our Umbra pipeline
// This file deliberately sits outside com.bdmajora: mods that want to know whether shaders are running probe for
// this package/class/method spelling by reflection and nothing else, so the whole file is a naming contract
// ShaderModBridge is our own such prober — keep the two in step
public class IrisApi {
    // The API is handed out as a singleton because callers cache a bound MethodHandle against this instance
    private static final IrisApi INSTANCE = new IrisApi();

    public static IrisApi getInstance() {
        return INSTANCE;
    }

    // "In use" means a pack is loaded and rendering, not merely that the mod is installed
    public boolean isShaderPackInUse() {
        return Umbra.isShaderPackInUse();
    }

    // Builds the pack selection screen so a caller can push it without linking against our GUI classes
    // Parameter and return are Object to keep the API free of Minecraft types; parent is what the screen returns
    // to on close, falling back to whatever is on screen when the caller passes something that is not a GuiScreen
    public Object openMainIrisScreenObj(Object parent) {
        GuiScreen parentScreen = parent instanceof GuiScreen
                ? (GuiScreen) parent
                : Minecraft.getMinecraft().currentScreen;
        return new ShaderPackSelectScreen(parentScreen);
    }
}
