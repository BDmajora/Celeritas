package com.bdmajora.impetus.umbra.shaderpack.option;

public final class BooleanOption extends BaseOption {
    private final boolean defaultValue;

    public BooleanOption(OptionType type, String name, String comment, boolean defaultValue) {
        super(type, name, comment);

        this.defaultValue = defaultValue;
    }

    // Whether the define is uncommented in the source
    public boolean getDefaultValue() {
        return defaultValue;
    }

    // For logging
    @Override
    public String toString() {
        return "BooleanDefineOption{" +
                "name=" + getName() +
                ", comment=" + getComment() +
                ", defaultValue=" + defaultValue +
                '}';
    }
}
