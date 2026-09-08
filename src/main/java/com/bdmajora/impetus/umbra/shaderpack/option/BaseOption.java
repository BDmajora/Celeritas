package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.Optional;

public abstract class BaseOption {
    private final OptionType type;
    private final String name;
    private final String comment;

    BaseOption(OptionType type, String name, String comment) {
        this.type = type;
        this.name = name;

        if (comment == null || comment.isEmpty()) {
            this.comment = null;
        } else {
            this.comment = comment;
        }
    }

    public OptionType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Optional<String> getComment() {
        return Optional.ofNullable(comment);
    }
}
