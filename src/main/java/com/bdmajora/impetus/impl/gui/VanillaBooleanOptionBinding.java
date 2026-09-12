package com.bdmajora.impetus.impl.gui;

import net.minecraft.client.settings.GameSettings;
import com.bdmajora.impetus.api.options.binding.OptionBinding;

public class VanillaBooleanOptionBinding implements OptionBinding<GameSettings, Boolean> {
    private final GameSettings.Options option;

    public VanillaBooleanOptionBinding(GameSettings.Options option) {
        this.option = option;
    }

    // Through GameSettings.setOptionValue so vanilla's own side effects run
    @Override
    public void setValue(GameSettings settings, Boolean value) {
        settings.setOptionValue(this.option, value ? 1 : 0);
    }

    // Through GameSettings.getOptionOrdinalValue
    @Override
    public Boolean getValue(GameSettings settings) {
        return settings.getOptionOrdinalValue(this.option);
    }
}
