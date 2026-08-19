package com.bdmajora.coartatio.util;

import com.bdmajora.coartatio.Coartatio;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;

/**
 * Defines a class into another class's runtime package.
 *
 * <p>Java enforces package-private access by <i>runtime package</i>: same package name <b>and</b>
 * same classloader. Guava's {@code ImmutableMap} has a package-private constructor, so a subclass
 * must be loaded by whichever loader loaded Guava. Shipping a {@code com.google.common.collect}
 * class in our own jar is not enough — if Guava came from a different loader the JVM refuses the
 * access at first use, with an {@code IllegalAccessError} far from the cause.
 *
 * <p>Hydrogen calls this category of trick "things too dirty to put in Lithium", and it is the only
 * place Coartatio does anything of the sort. Three tiers, most-preferred first:
 *
 * <ol>
 *   <li>{@code MethodHandles.privateLookupIn(...).defineClass(byte[])} — Java 9 and later, which is
 *       what an lwjgl3ify/RetroFuturaBootstrap setup runs. Reached reflectively so this still
 *       compiles at source level 8.
 *   <li>{@code ClassLoader.defineClass} via {@code setAccessible} — the ordinary 1.12.2 case on
 *       Java 8, where the module system is not there to stop us.
 *   <li>Give up. {@link #defineClass} returns {@code null}, the caller disables its feature, and the
 *       game starts normally.
 * </ol>
 *
 * <h2>Rules for anything injected this way</h2>
 *
 * <ul>
 *   <li><b>No Coartatio imports.</b> The injected class may reference only its host package and
 *       {@code java.*}. If Guava turns out to be on a loader that cannot see our jar, an import
 *       would produce {@code NoClassDefFoundError} at first use rather than a clean, detectable
 *       failure here.
 *   <li><b>Define in reverse dependency order</b>, innermost helper first, so a partial failure
 *       cannot leave a half-linked class behind.
 * </ul>
 */
public final class ClassDefineTool {
    private static boolean warned;

    private ClassDefineTool() {
    }

    /**
     * Loads {@code name} from our own resources and defines it alongside {@code host}.
     *
     * @param host a class in the target package, whose classloader and package are borrowed
     * @param name binary name of the class to define, which must live in {@code host}'s package
     * @return the defined class, or {@code null} if the JVM would not allow it
     */
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

    /** Java 9+: {@code MethodHandles.privateLookupIn(host, lookup).defineClass(bytecode)}. */
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

    /** Java 8: reflectively unlock {@code ClassLoader.defineClass} on the host's loader. */
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
