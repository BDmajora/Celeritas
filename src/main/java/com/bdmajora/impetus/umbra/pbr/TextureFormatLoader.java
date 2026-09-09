package com.bdmajora.impetus.umbra.pbr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

// Reads the resource pack's PBR format declaration and turns it into preprocessor macros
// The declaration lives in assets/minecraft/optifine/texture.properties as `format = <name>-<version>`, e.g.
// `format = lab-pbr-1.3`
// It becomes two macros, MC_TEXTURE_FORMAT_<NAME> and MC_TEXTURE_FORMAT_<NAME>_<VERSION>, because packs gate on
// both — the coarse one to know the family, the versioned one to know which revision's channel packing to decode
// Without this a pack decodes LabPBR data with the wrong channel layout and every surface comes out wrong
public final class TextureFormatLoader {
    private static final ResourceLocation LOCATION = new ResourceLocation("minecraft", "optifine/texture.properties");

    private TextureFormatLoader() {
    }

    public static void addFormatMacros(Map<String, String> macros) {
        String format = readDeclaredFormat();
        if (format == null || format.isEmpty()) {
            return;
        }

        // `<name>-<version>` where the name itself may contain dashes (lab-pbr-1.3 → "lab-pbr" + "1.3"). The
        // version is the trailing dash-separated token iff it starts with a digit.
        String name = format;
        String version = null;
        int lastDash = format.lastIndexOf('-');
        if (lastDash > 0 && lastDash + 1 < format.length() && Character.isDigit(format.charAt(lastDash + 1))) {
            name = format.substring(0, lastDash);
            version = format.substring(lastDash + 1);
        }

        String base = "MC_TEXTURE_FORMAT_" + sanitize(name);
        macros.put(base, "");
        if (version != null) {
            macros.put(base + "_" + sanitize(version), "");
        }
    }

    private static String sanitize(String token) {
        return token.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private static String readDeclaredFormat() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getResourceManager() == null) {
            return null;
        }

        try (IResource resource = mc.getResourceManager().getResource(LOCATION)) {
            Properties properties = new Properties();
            properties.load(resource.getInputStream());
            String format = properties.getProperty("format");
            return format != null ? format.trim() : null;
        } catch (Exception e) {
            return null; // no declaration — the common case
        }
    }
}
