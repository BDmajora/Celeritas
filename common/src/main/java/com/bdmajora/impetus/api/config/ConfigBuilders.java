package com.bdmajora.impetus.api.config;

import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;

public final class ConfigBuilders {
    private ConfigBuilders() {
    }

    public static ConfigPageBuilder page(String modId, String path, TextComponent name) {
        return new ConfigPageBuilder(OptionIdentifier.create(modId, path), name);
    }

    public static ConfigPageBuilder page(OptionIdentifier<Void> id, TextComponent name) {
        return new ConfigPageBuilder(id, name);
    }

    public static OptionGroup.Builder group(String modId, String path) {
        return OptionGroup.createBuilder().setId(OptionIdentifier.create(modId, path));
    }

    public static <S, T> OptionImpl.Builder<S, T> option(String modId, String path, Class<T> type, OptionStorage<S> storage) {
        return OptionImpl.createBuilder(type, storage)
                .setId(OptionIdentifier.create(modId, path, type));
    }

    public static <S, T> OptionImpl.Builder<S, T> option(OptionIdentifier<T> id, Class<T> type, OptionStorage<S> storage) {
        return OptionImpl.createBuilder(type, storage)
                .setId(id);
    }

    public static <T> ConfigStorage<T> storage(T data, Runnable saveAction) {
        return ConfigStorage.of(data, saveAction);
    }
}
