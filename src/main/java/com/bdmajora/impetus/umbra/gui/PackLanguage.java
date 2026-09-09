package com.bdmajora.impetus.umbra.gui;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// The localised display strings a pack ships in shaders/lang/<locale>.lang, which is what turns the option screen
// from raw identifiers into readable labels — `option.SHADER_STYLE=Visual Style`, `value.RP_MODE.1=Integrated PBR+`
// Follows the OptiFine/Iris convention: en_us is the base, and the active locale is layered on top of it, so a
// partially translated pack falls back per key rather than per file
// &-prefixed colour codes are converted to Minecraft's section sign
// Every lookup falls back to the raw option name or value, so a pack shipping no lang files at all still shows a
// usable screen
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

    // Case-insensitive lookup, because packs are genuinely inconsistent about en_us versus en_US and an exact
    // match silently finds nothing for half of them
    private static String findLangFile(Map<AbsolutePackPath, String> sources, String localeName) {
        String target = "/lang/" + localeName + ".lang";
        for (Map.Entry<AbsolutePackPath, String> entry : sources.entrySet()) {
            if (entry.getKey().getPathString().equalsIgnoreCase(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // Converts &-prefixed formatting codes to the section sign, leaving any other ampersand alone — a label
    // reading "Sun & Moon" must not lose its ampersand
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

    // The display label for an option, falling back to the raw name when the pack provides none
    public String optionLabel(String name, String fallback) {
        String value = get("option." + name);
        if (value != null) {
            return value;
        }
        return fallback != null ? fallback : name;
    }

    // The display label for one specific VALUE of an option, e.g. turning RP_MODE's "1" into "Integrated PBR+",
    // falling back to the raw value string
    public String valueLabel(String name, String value) {
        String label = get("value." + name + "." + value);
        return label != null ? label : value;
    }

    // Optional text rendered before the value; rare, but a pack can use it for a leading symbol
    public String prefix(String name) {
        String value = get("prefix." + name);
        return value != null ? value : "";
    }

    // Optional text rendered after the value, typically a unit like "%" or " blocks"
    public String suffix(String name) {
        String value = get("suffix." + name);
        return value != null ? value : "";
    }

    // The button label for a sub-screen, falling back to the raw screen name
    public String screenLabel(String name) {
        String value = get("screen." + name);
        return value != null ? value : name;
    }

    // The tooltip for an option, or null when the pack provides none — null rather than the raw name, because a
    // tooltip repeating the label is worse than no tooltip
    public String comment(String name) {
        return get("option." + name + ".comment");
    }

    // The display label for a profile, falling back to the raw profile name
    public String profileLabel(String name) {
        String value = get("profile." + name);
        return value != null ? value : name;
    }
}
