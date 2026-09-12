package com.bdmajora.impetus.booter.service;

import net.minecraft.launchwrapper.IClassTransformer;
import org.spongepowered.asm.logging.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Debug transformer that logs the stack that triggered loading of a watched class, for diagnosing
// "mixin target loaded too early". Watched names come from the mixinbooter.watchedClasses property; * watches all
// Inert beyond one property read per class until the property is set
public final class ClassLoadTracer implements IClassTransformer {

    public static final String WATCH_PROPERTY = "mixinbooter.watchedClasses";
    public static final String WATCH_ALL = "*";

    private static volatile Set<String> watched;

    private final Set<String> traced = Collections.synchronizedSet(new HashSet<>());

    // Parses the watch property once; * means everything
    private static Set<String> watched() {
        Set<String> current = watched;
        if (current != null) {
            return current;
        }
        String property = System.getProperty(WATCH_PROPERTY);
        if (property == null || property.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> parsed = new HashSet<>();
        for (String name : property.split(",")) {
            name = name.trim();
            if (!name.isEmpty()) {
                parsed.add(name);
            }
        }
        watched = parsed;
        return parsed;
    }

    // Trims the captured stack to the interesting portion. Drops the first 5 frames (transformer + classloader
    // find/loading) Stopping at the LaunchWrapper/Minecraft entrypoint
    private static StackTraceElement[] trim(StackTraceElement[] elements) {
        int start = Math.min(5, elements.length);
        int end = elements.length;
        for (int i = start; i < elements.length; i++) {
            if (isEntryPoint(elements[i])) {
                end = i;
                break;
            }
        }
        List<StackTraceElement> kept = new ArrayList<>();
        for (int i = start; i < end; i++) {
            if (!isInternalCall(elements[i])) {
                kept.add(elements[i]);
            }
        }
        return kept.toArray(new StackTraceElement[0]);
    }

    // Where to stop printing a trace: the launcher's main
    private static boolean isEntryPoint(StackTraceElement element) {
        String className = element.getClassName();
        String methodName = element.getMethodName();
        return ("net.minecraft.launchwrapper.Launch".equals(className) && "launch".equals(methodName))
                || ("net.minecraft.client.main.Main".equals(className) && "main".equals(methodName));
    }

    // Frames inside the class loading machinery itself, skipped for readability
    private static boolean isInternalCall(StackTraceElement element) {
        String className = element.getClassName();
        String methodName = element.getMethodName();
        if ("java.lang.ClassLoader".equals(className) || "java.security.SecureClassLoader".equals(className)
                || "sun.reflect.NativeMethodAccessorImpl".equals(className)
                || "sun.reflect.DelegatingMethodAccessorImpl".equals(className)) {
            return true;
        }
        return "net.minecraft.launchwrapper.LaunchClassLoader".equals(className) &&
                ("findClass".equals(methodName) || "loadClass".equals(methodName));
    }

    // Never changes bytes; logs the loading stack for watched classes and passes through
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        Set<String> watchedClasses = watched();
        if (!watchedClasses.isEmpty()) {
            String target;
            if (watchedClasses.contains(WATCH_ALL)) {
                target = transformedName != null ? transformedName : name;
            } else {
                target = watchedClasses.contains(transformedName) ? transformedName : (watchedClasses.contains(name) ? name : null);
            }
            if (target != null && this.traced.add(target)) {
                Throwable trace = new Throwable(target);
                trace.setStackTrace(trim(trace.getStackTrace()));
                MixinBooterService.auditFile().write(Level.DEBUG, "ClassLoadTracer", "'" + target + "' is being loaded, load stack:", trace);
            }
        }
        return basicClass;
    }

}
