package org.taumc.celeritas.iris.gui;

import org.taumc.celeritas.iris.shaderpack.include.AbsolutePackPath;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The localized display strings a shader pack ships in {@code shaders/lang/<locale>.lang}, used to render friendly
 * option/value labels in the option menu (e.g. {@code option.SHADER_STYLE=Visual Style}, {@code value.RP_MODE.1=Integrated PBR+}).
 * <p>
 * Mirrors OptiFine/Iris conventions: the {@code en_us} file is the base and the active locale is layered on top.
 * {@code &}-prefixed color codes are converted to Minecraft's {@code §}. Every lookup falls back gracefully (usually to
 * the raw option name or value) so packs without lang files still work.
 */
public final class PackLanguage {
    private final Map<String, String> entries = new HashMap<>();

    public PackLanguage(Map<AbsolutePackPath, String> sources, String locale) {
        // Base locale first, then the active locale overrides it.
        loadLangFile(sources, "en_us");
        String normalized = locale == null ? "en_us" : locale.toLowerCase(Locale.ROOT);
        if (!normalized.equals("en_us")) {
            loadLangFile(sources, normalized);
        }
    }

    private void loadLangFile(Map<AbsolutePackPath, String> sources, String localeName) {
        String contents = findLangFile(sources, localeName);
        if (contents == null) {
            return;
        }
        for (String rawLine : contents.split("\r\n|\r|\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = translateColorCodes(line.substring(eq + 1).trim());
            if (!key.isEmpty()) {
                this.entries.put(key, value);
            }
        }
    }

    /** Looks up {@code /lang/<localeName>.lang} case-insensitively (packs vary between {@code en_us} and {@code en_US}). */
    private static String findLangFile(Map<AbsolutePackPath, String> sources, String localeName) {
        String target = "/lang/" + localeName + ".lang";
        for (Map.Entry<AbsolutePackPath, String> entry : sources.entrySet()) {
            if (entry.getKey().getPathString().equalsIgnoreCase(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Converts {@code &}-prefixed formatting codes to {@code §}, leaving other ampersands alone. */
    private static String translateColorCodes(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' && i + 1 < value.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(value.charAt(i + 1)) >= 0) {
                sb.append('§');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String get(String key) {
        return this.entries.get(key);
    }

    /** The display label for an option, or the raw name if the pack provides none. */
    public String optionLabel(String name, String fallback) {
        String value = get("option." + name);
        if (value != null) {
            return value;
        }
        return fallback != null ? fallback : name;
    }

    /** The display label for a specific value of an option, or the raw value string if none. */
    public String valueLabel(String name, String value) {
        String label = get("value." + name + "." + value);
        return label != null ? label : value;
    }

    /** Optional text rendered before the value (rare). */
    public String prefix(String name) {
        String value = get("prefix." + name);
        return value != null ? value : "";
    }

    /** Optional text rendered after the value, typically a unit. */
    public String suffix(String name) {
        String value = get("suffix." + name);
        return value != null ? value : "";
    }

    /** The button label for a sub-screen, or the raw name if none. */
    public String screenLabel(String name) {
        String value = get("screen." + name);
        return value != null ? value : name;
    }

    /** The tooltip/comment for an option, or {@code null} if the pack provides none. */
    public String comment(String name) {
        return get("option." + name + ".comment");
    }

    /** The display label for a profile value, or the raw name if none. */
    public String profileLabel(String name) {
        String value = get("profile." + name);
        return value != null ? value : name;
    }
}
