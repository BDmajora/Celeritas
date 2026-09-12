package com.bdmajora.impetus.api.options.structure;

import java.util.Set;

public interface OptionStorage<T> {
    T getData();

    // Without flags
    default void save() {

    }

    // Defaults to ignoring the flags
    default void save(Set<OptionFlag> flags) {
        save();
    }
}
