package com.bdmajora.coartatio.util;

import com.bdmajora.coartatio.Coartatio;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;

// Defines a class into another class's runtime package, since package-private access needs the same loader
// Three tiers: privateLookupIn on Java 9+, unlocked defineClass on Java 8, or give up and return null
// Injected classes may reference only their host package and java.*, and are defined innermost first
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

    // Loads the class file bytes from our own jar, since the target loader cannot see it
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
