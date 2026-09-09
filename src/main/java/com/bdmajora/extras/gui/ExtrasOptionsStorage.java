package com.bdmajora.extras.gui;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.impetus.api.options.structure.OptionStorage;

// Bridges the Extras config into the Impetus options framework.
public final class ExtrasOptionsStorage implements OptionStorage<ExtrasConfig> {
    @Override
    public ExtrasConfig getData() {
        return Extras.options();
    }

    @Override
    public void save() {
        Extras.save();
    }
}
