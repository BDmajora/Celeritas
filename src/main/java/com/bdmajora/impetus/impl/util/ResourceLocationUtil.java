package com.bdmajora.impetus.impl.util;


import net.minecraft.util.ResourceLocation;

public class ResourceLocationUtil {
    // Plain constructor wrapper, kept so callers match the modern Sodium API
    public static ResourceLocation make(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    // Parses namespace:path
    public static ResourceLocation make(String str) {
        return new ResourceLocation(str);
    }
}
