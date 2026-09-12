package com.bdmajora.impetus.booter.service;

import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.service.IClassProvider;

import java.net.URL;

final class ClassProvider implements IClassProvider {

    // LaunchClassLoader's sources
    @Override
    @Deprecated
    public URL[] getClassPath() {
        return Launch.classLoader.getSources().toArray(new URL[0]);
    }

    // Initialising lookup through LaunchClassLoader
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Launch.classLoader.findClass(name);
    }

    // Lookup through LaunchClassLoader
    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, Launch.classLoader);
    }

    // Agents live on the system loader, not LaunchClassLoader
    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, Launch.class.getClassLoader());
    }

}
