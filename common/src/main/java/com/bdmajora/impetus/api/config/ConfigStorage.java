package com.bdmajora.impetus.api.config;

import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionStorage;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class ConfigStorage<T> implements OptionStorage<T> {
    private final T data;
    private final Consumer<Set<OptionFlag>> saveAction;

    private ConfigStorage(T data, Consumer<Set<OptionFlag>> saveAction) {
        this.data = Objects.requireNonNull(data, "Config data must not be null");
        this.saveAction = Objects.requireNonNull(saveAction, "Save action must not be null");
    }

    // Save ignores flags
    public static <T> ConfigStorage<T> of(T data, Runnable saveAction) {
        Objects.requireNonNull(saveAction, "Save action must not be null");
        return new ConfigStorage<>(data, flags -> saveAction.run());
    }

    // Save receives the flags
    public static <T> ConfigStorage<T> of(T data, Consumer<Set<OptionFlag>> saveAction) {
        return new ConfigStorage<>(data, saveAction);
    }

    // The config object
    @Override
    public T getData() {
        return this.data;
    }

    // Without flags
    @Override
    public void save() {
        this.saveAction.accept(Set.of());
    }

    // With the flags of the options that changed
    @Override
    public void save(Set<OptionFlag> flags) {
        this.saveAction.accept(flags);
    }
}
