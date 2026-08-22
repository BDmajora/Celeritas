package com.bdmajora.impetus.impl.loader.common;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import com.bdmajora.impetus.ImpetusVintage;

import java.io.IOException;

// TODO: Make sure to create some new logos for this mod and make sure they actually load correctly. The current logo is just a placeholder and will be replaced with a proper one in the future.

public class ModLogoUtil {

    // Pre-allocate the hardcoded Impetus logo to save memory and string parsing overhead
    private static final ResourceLocation IMPETUS_LOGO = new ResourceLocation("impetus", "textures/gui/impetus.png");

    // Attempts to find and return the ResourceLocation for a mod's logo file
    public static ResourceLocation registerLogo(String modId) {
        // Fast-path: bypass all lookups and file I/O entirely for the main mod (O(1) time)
        if ("impetus".equals(modId)) return IMPETUS_LOGO;

        // Fetch the Forge mod container (O(1) HashMap lookup)
        ModContainer container = Loader.instance().getIndexedModList().get(modId);
        
        // Abort if the specified mod ID is not loaded
        if (container == null) return null;

        // Fetch custom logo from mcmod.info, fallback to explicit impetus.png if undefined
        String logoPath = container.getMetadata().logoFile;
        if (logoPath == null || logoPath.isEmpty()) {
            logoPath = "impetus.png";
        }

        // Construct the final resource identifier
        ResourceLocation location = new ResourceLocation(modId, logoPath);

        try {
            // Verify the file actually exists in the resource manager
            Minecraft.getMinecraft().getResourceManager().getResource(location);
            // Return the validated resource location
            return location;
        } catch (IOException e) {
            // Log missing file WITHOUT the expensive stack trace to prevent lag spikes
            ImpetusVintage.logger().warn("Missing logo for mod: " + modId);
            // Fallback to null on failure
            return null;
        }
    }
}