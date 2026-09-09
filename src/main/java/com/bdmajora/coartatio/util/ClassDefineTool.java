package com.bdmajora.coartatio.util;

import com.bdmajora.coartatio.Coartatio;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;

// Defines a class into another class's runtime package
// Java enforces package-private access by RUNTIME package, meaning the same package name AND the same
// classloader. Guava's ImmutableMap has a package-private constructor, so a subclass must be loaded by whatever
// loader loaded Guava. Simply shipping a com.google.common.collect class inside our own jar is not enough: if
// Guava came from a different loader, the JVM refuses the access at first use with an IllegalAccessError
// thrown a long way from its cause
// Hydrogen calls this category of trick "things too dirty to put in Lithium", and this is the only place
// Coartatio does anything of the sort
// Three tiers are tried, most preferred first: MethodHandles.privateLookupIn(...).defineClass on Java 9+, which
// is what an lwjgl3ify/RetroFuturaBootstrap setup runs; ClassLoader.defineClass unlocked with setAccessible,
// the ordinary 1.12.2-on-Java-8 case where the module system is not there to stop it; and giving up, in which
// case defineClass returns null, the caller disables its feature, and the game starts normally
// Two rules for anything injected this way. It may reference ONLY its host package and java.* — no Coartatio
// imports, because if Guava is on a loader that cannot see our jar an import turns into a NoClassDefFoundError
// at first use instead of the clean, detectable failure this class produces. And classes must be defined in
// reverse dependency order, innermost helper first, so a partial failure cannot leave a half-linked class behind
public final class ClassDefineTool {
    private static boolean warned;

    private ClassDefineTool() {
    }

    // Reads name out of our own resources and defines it alongside host
    // host is any class already in the target package; its classloader and package are what get borrowed
    // name is the binary name of the class to define and must live in host's package, or the JVM rejects it
    // Returns null when the JVM will not allow the definition, which is a normal outcome, not an error
    public static Class<?> defineClass(Class<?> host, String name) {
        byte[] bytecode = readBytecode(name);

        if (bytecode == null) {
            return null;
        }

        // Already present from a previous attempt (or a duplicate call) — reuse rather than fail.
        try {
            return Class.forName(name, false, host.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            // Expected on the first call.
        }

        Class<?> defined = defineWithLookup(host, bytecode);

        if (defined == null) {
            defined = defineWithClassLoader(host, name, bytecode);
        }

        if (defined == null && !warned) {
            warned = true;
            Coartatio.LOGGER.warn("Could not define classes into {}'s package; features that need it stay off",
                    host.getName());
        }

        return defined;
    }

    // Tier one, Java 9 and later: MethodHandles.privateLookupIn(host, lookup).defineClass(bytecode)
    // Reached entirely through reflection so this file still compiles at source level 8, where neither
    // privateLookupIn nor Lookup.defineClass exists
    private static Class<?> defineWithLookup(Class<?> host, byte[] bytecode) {
        try {
            Class<?> lookupClass = Class.forName("java.lang.invoke.MethodHandles$Lookup");
            Class<?> handles = Class.forName("java.lang.invoke.MethodHandles");

            Method lookup = handles.getMethod("lookup");
            Method privateLookupIn = handles.getMethod("privateLookupIn", Class.class, lookupClass);
            Method defineClass = lookupClass.getMethod("defineClass", byte[].class);

            Object privateLookup = privateLookupIn.invoke(null, host, lookup.invoke(null));

            return (Class<?>) defineClass.invoke(privateLookup, (Object) bytecode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // NoSuchMethodException on Java 8, IllegalAccessException if the module is not open.
            return null;
        }
    }

    // Tier two, Java 8: unlock ClassLoader.defineClass on the host's own loader with setAccessible
    // Defining through the host's loader is the whole point — that is what puts the new class in the same
    // runtime package as the host and makes the package-private constructor reachable
    private static Class<?> defineWithClassLoader(Class<?> host, String name, byte[] bytecode) {
        ClassLoader loader = host.getClassLoader();

        if (loader == null) {
            // Guava on the bootstrap loader; nothing we can do, and nothing we should try.
            return null;
        }

        try {
            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);

            return (Class<?>) defineClass.invoke(loader, name, bytecode, 0, bytecode.length);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] readBytecode(String name) {
        String path = "/" + name.replace('.', '/') + ".class";
        URL url = ClassDefineTool.class.getResource(path);

        if (url == null) {
            Coartatio.LOGGER.warn("Could not find bytecode for {}", name);
            return null;
        }

        try {
            return IOUtils.toByteArray(url);
        } catch (IOException e) {
            Coartatio.LOGGER.warn("Could not read bytecode for {}", name, e);
            return null;
        }
    }
}
