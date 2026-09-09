package com.bdmajora.dynamiclights.gui;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.DynamicLightsConfig;
import com.bdmajora.impetus.api.options.structure.OptionStorage;

// Bridges the Dynamic Lights config into the Impetus options framework.
public final class DynamicLightsOptionsStorage implements OptionStorage<DynamicLightsConfig> {
    @Override
    public DynamicLightsConfig getData() {
        return DynamicLights.options();
    }

    @Override
    public void save() {
        DynamicLights.save();
    }
}
