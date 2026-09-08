package com.bdmajora.impetus.impl.loader.common;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import com.bdmajora.impetus.ImpetusVintage;

import java.io.IOException;

// TODO: current Impetus logo is a placeholder, replace with real artwork

public class ModLogoUtil {

    private static final ResourceLocation IMPETUS_LOGO = new ResourceLocation("impetus", "textures/gui/impetus.png");

    // Resolves a mod's logo, falling back to mcmod.info's logoFile or "impetus.png", or null if the mod/file is missing
    public static ResourceLocation registerLogo(String modId) {
        if ("impetus".equals(modId)) return IMPETUS_LOGO;

        ModContainer container = Loader.instance().getIndexedModList().get(modId);
        if (container == null) return null;

        String logoPath = container.getMetadata().logoFile;
        if (logoPath == null || logoPath.isEmpty()) {
            logoPath = "impetus.png";
        }

        ResourceLocation location = new ResourceLocation(modId, logoPath);

        try {
            // getResource throws if the file doesn't actually exist, use that to validate before returning
            Minecraft.getMinecraft().getResourceManager().getResource(location);
            return location;
        } catch (IOException e) {
            // don't log the stack trace here, this path is hit often for mods with no logo and would spam the log
            ImpetusVintage.logger().warn("Missing logo for mod: " + modId);
            return null;
        }
    }
}