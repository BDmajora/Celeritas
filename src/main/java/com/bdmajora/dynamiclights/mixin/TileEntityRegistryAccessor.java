package com.bdmajora.dynamiclights.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.RegistryNamespaced;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reaches TileEntity.REGISTRY so the options page can list block entity types; on 1.12.2 they are vanilla-registered in a private registry with only a class-to-id lookup exposed
@Mixin(TileEntity.class)
public interface TileEntityRegistryAccessor {
    @Accessor("REGISTRY")
    static RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>> impetus$getRegistry() {
        throw new AssertionError("Untransformed accessor");
    }
}
