package com.bdmajora.impetus.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import com.bdmajora.impetus.mixin.ImpetusVintageMixinPlugin;

import java.nio.charset.StandardCharsets;

// TODO: Delete this translation layer and implement LWJGL 3 directly via Cleanroom.
// 1. Update build.gradle to use LWJGL 3 dependencies instead of LWJGL 2.
// 2. Globally replace 'org.lwjgl' imports with 'org.lwjgl3' (or standard LWJGL 3).
// 3. Manually rewrite legacy Display, Mouse, and Keyboard calls to use GLFW.

public class ImpetusLWJGLRelocationTransformer implements IClassTransformer {
    // Reused instance to prevent memory allocation overhead on every class load
    private static final Remapper LWJGL_REMAPPER = new LwjglRemapper();
    
    // Cache byte sequence of target string for rapid memory scanning
    private static final byte[] TARGET_BYTES = "org/lwjgl/".getBytes(StandardCharsets.UTF_8);
    
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // Ignore null classes or anything outside the base impetus package
        if (basicClass == null || !transformedName.startsWith("com.bdmajora.impetus")) {
            return basicClass;
        }
        
        // Fast-fail: bypass ASM entirely if the class bytes do not contain "org/lwjgl/"
        if (!containsTargetBytes(basicClass)) {
            return basicClass;
        }

        try {
            // Parse incoming bytecode
            var reader = new ClassReader(basicClass);
            // Optimization: Pass reader to writer to bulk-copy untouched constant pool entries
            var writer = new ClassWriter(reader, 0);
            // Pipe reader through the remapper into the writer
            reader.accept(new ClassRemapper(writer, LWJGL_REMAPPER), 0);
            // Output the patched class bytes
            return writer.toByteArray();
        } catch(Exception e) {
            // Fallback to unpatched class if ASM fails
            ImpetusVintageMixinPlugin.LOGGER.error("Exception remapping class", e);
            return basicClass;
        }
    }

    // High-speed linear scan to avoid O(N) heavy object allocations in ASM
    private boolean containsTargetBytes(byte[] data) {
        int max = data.length - TARGET_BYTES.length;
        for (int i = 0; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < TARGET_BYTES.length; j++) {
                if (data[i + j] != TARGET_BYTES[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static class LwjglRemapper extends Remapper {
        @Override
        public String map(String internalName) {
            // Fast-path string swap without regex overhead
            if(internalName.startsWith("org/lwjgl/")) {
                // Replace 'lwjgl' with 'lwjgl3' (10 is the length of "org/lwjgl/")
                return "org/lwjgl3/" + internalName.substring(10);
            }
            // Return untouched if no match
            return internalName;
        }
    }
}