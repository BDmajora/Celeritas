package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// A configurable option whose value is a token (number, word, const value) with allowed values from the trailing `//[a b c]` comment, which lets the screen offer a cycle control; without one it is open text. From Iris with an unmodifiable List
public class StringOption extends BaseOption {
    private final String defaultValue;
    private final List<String> allowedValues;

    private StringOption(OptionType type, String name, String comment, String defaultValue, List<String> allowedValues) {
        super(type, name, comment);

        this.defaultValue = Objects.requireNonNull(defaultValue);
        this.allowedValues = allowedValues;
    }

    // Parses the allowed values from the comment; null when there are none
    public static StringOption create(OptionType type, String name, String comment, String defaultValue) {
        if (comment == null) {
            return null;
        }

        int openingBracket = comment.indexOf('[');

        if (openingBracket == -1) {
            return null;
        }

        int closingBracket = comment.indexOf(']', openingBracket);

        if (closingBracket == -1) {
            return null;
        }

        String[] allowedValues = comment.substring(openingBracket + 1, closingBracket).split(" ");
        comment = comment.substring(0, openingBracket) + comment.substring(closingBracket + 1);
        boolean allowedValuesContainsDefaultValue = false;

        for (String value : allowedValues) {
            if (defaultValue.equals(value)) {
                allowedValuesContainsDefaultValue = true;
                break;
            }
        }

        List<String> builder = new ArrayList<>();

        Collections.addAll(builder, allowedValues);

        if (!allowedValuesContainsDefaultValue) {
            builder.add(defaultValue);
        }

        return new StringOption(type, name, comment.trim(), defaultValue, Collections.unmodifiableList(builder));
    }

    // Value in the source
    public String getDefaultValue() {
        return defaultValue;
    }

    // From the [a b c] comment
    public List<String> getAllowedValues() {
        return allowedValues;
    }
}
