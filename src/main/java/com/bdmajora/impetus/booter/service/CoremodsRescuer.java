package com.bdmajora.impetus.booter.service;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;

public class CoremodsRescuer implements ITweaker {

    public CoremodsRescuer() {
        ModDiscoverer.rescueDroppedCoremods();
    }

    // Nothing needed; the rescue happens in the constructor
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    }

    // Flushes the tweak classes rescued coremods added, now that this tweaker is being processed
    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        ModDiscoverer.flushRescuedTweakClasses();
    }

    // Not a real launch target; null defers to Forge's
    @Override
    public String getLaunchTarget() {
        return "";
    }

    // Contributes no arguments
    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }

}