package com.bdmajora.impetus.impl.util;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Loader;

import java.io.File;

public class PlatformUtil {

    // Whether Forge has finished loading mods
    public static boolean isLoadValid() {
        //TODO Implement. Doesn't seem to be used outside of shaders
        return true;
    }

    // Forge's Loader check
    public static boolean modPresent(String modid) {
        return Loader.isModLoaded(modid);
    }

    // Display name from the mod container, or the id when unknown
    public static String getModName(String modId) {
        return Loader.instance().getIndexedModList().get(modId).getName();
    }

    // Forge's config directory
    public static File getConfigDir() {
        return Loader.instance().getConfigDir();
    }

    // The game directory
    public static File getGameDir() {
        return Minecraft.getMinecraft().gameDir;
    }
}
