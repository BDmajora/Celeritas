package com.bdmajora.coarctatio.util;

import com.bdmajora.coarctatio.Coarctatio;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;

// Defines a class into another class's runtime package for package-private access: privateLookupIn on Java 9+, unlocked defineClass on Java 8, else null; injected classes may reference only their host package and java.*
public final class ClassDefineTool {
    private static boolean warned;

    private ClassDefineTool() {
    }

    // Reads name from our resources and defines it alongside host, borrowing its loader and package; name must be in host's package, and null means the JVM refused, a normal outcome
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
            Coarctatio.LOGGER.warn("Could not define classes into {}'s package; features that need it stay off",
                    host.getName());
        }

        return defined;
    }

    // Tier one, Java 9+: MethodHandles.privateLookupIn(host, lookup).defineClass(bytecode), reached via reflection so this compiles at source level 8
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

    // Tier two, Java 8: unlock ClassLoader.defineClass on the host's own loader, which is what puts the class in the same runtime package
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
            Coarctatio.LOGGER.warn("Could not find bytecode for {}", name);
            return null;
        }

        try {
            return IOUtils.toByteArray(url);
        } catch (IOException e) {
            Coarctatio.LOGGER.warn("Could not read bytecode for {}", name, e);
            return null;
        }
    }
}
