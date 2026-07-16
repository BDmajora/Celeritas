package com.bdmajora.impetus.iris.shaderpack.option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A configurable option whose value is a token (number, word, or const value) and which carries an allowed-values list
 * parsed from its trailing {@code //[a b c]} comment. Ported from Iris; guava {@code ImmutableList} replaced with an
 * unmodifiable {@link List}.
 */
public class StringOption extends BaseOption {
    private final String defaultValue;
    private final List<String> allowedValues;

    private StringOption(OptionType type, String name, String comment, String defaultValue, List<String> allowedValues) {
        super(type, name, comment);

        this.defaultValue = Objects.requireNonNull(defaultValue);
        this.allowedValues = allowedValues;
    }

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

    public String getDefaultValue() {
        return defaultValue;
    }

    public List<String> getAllowedValues() {
        return allowedValues;
    }
}
