package com.bdmajora.extras.gui;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.impetus.api.options.structure.OptionStorage;

// Bridges the Extras config into the Impetus options framework.
public final class ExtrasOptionsStorage implements OptionStorage<ExtrasConfig> {
    // The options screen edits the live config directly
    @Override
    public ExtrasConfig getData() {
        return Extras.options();
    }

    // Writes the file once the screen is dismissed
    @Override
    public void save() {
        Extras.save();
    }
}
