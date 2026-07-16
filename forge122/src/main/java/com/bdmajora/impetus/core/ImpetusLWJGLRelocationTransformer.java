package com.bdmajora.impetus.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import com.bdmajora.impetus.mixin.ImpetusVintageMixinPlugin;

import java.util.regex.Pattern;

public class ImpetusLWJGLRelocationTransformer implements IClassTransformer {
    private static final Remapper LWJGL_REMAPPER = new LwjglRemapper();
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass != null && (transformedName.startsWith("com.bdmajora.impetus.engine") || transformedName.startsWith("com.bdmajora.impetus"))) {
            try {
                var reader = new ClassReader(basicClass);
                var writer = new ClassWriter(0);
                var remapper = new ClassRemapper(writer, LWJGL_REMAPPER);
                reader.accept(remapper, 0);
                return writer.toByteArray();
            } catch(Exception e) {
                ImpetusVintageMixinPlugin.LOGGER.error("Exception remapping class", e);
                return basicClass;
            }
        }
        return basicClass;
    }

    private static class LwjglRemapper extends Remapper {
        private static final Pattern LWJGL3 = Pattern.compile("^org/lwjgl/");
        @Override
        public String map(String internalName) {
            if(internalName.startsWith("org/lwjgl/")) {
                return LWJGL3.matcher(internalName).replaceFirst("org/lwjgl3/");
            } else {
                return internalName;
            }
        }
    }
}
