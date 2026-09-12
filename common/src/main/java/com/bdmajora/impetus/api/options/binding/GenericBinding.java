package com.bdmajora.impetus.api.options.binding;

import java.util.function.BiConsumer;
import java.util.function.Function;

public class GenericBinding<S, T> implements OptionBinding<S, T> {
    private final BiConsumer<S, T> setter;
    private final Function<S, T> getter;

    public GenericBinding(BiConsumer<S, T> setter, Function<S, T> getter) {
        this.setter = setter;
        this.getter = getter;
    }

    // Through the setter
    @Override
    public void setValue(S storage, T value) {
        this.setter.accept(storage, value);
    }

    // Through the getter
    @Override
    public T getValue(S storage) {
        return this.getter.apply(storage);
    }
}
