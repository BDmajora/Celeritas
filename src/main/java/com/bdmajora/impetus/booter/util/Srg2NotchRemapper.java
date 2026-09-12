package com.bdmajora.impetus.booter.util;

import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import org.objectweb.asm.commons.Remapper;
import org.spongepowered.asm.obfuscation.mapping.remap.Unmapper;

public class Srg2NotchRemapper extends Remapper implements Unmapper {

    // Class name SRG to notch through FML's deobfuscator
    @Override
    public String map(String typeName) {
        return FMLDeobfuscatingRemapper.INSTANCE.map(typeName);
    }

    // Method name SRG to notch
    @Override
    public String mapMethodName(String owner, String name, String desc) {
        return FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(owner, name, desc);
    }

    // Field name SRG to notch
    @Override
    public String mapFieldName(String owner, String name, String desc) {
        return FMLDeobfuscatingRemapper.INSTANCE.mapFieldName(owner, name, desc);
    }

    // Class name notch to SRG, the reverse direction
    @Override
    public String unmap(String typeName) {
        return FMLDeobfuscatingRemapper.INSTANCE.unmap(typeName);
    }

}
