package com.bdmajora.dynamiclights.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.RegistryNamespaced;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code TileEntity.REGISTRY}, so the options page can list every block entity type.
 *
 * <p>Block entities are still vanilla-registered on 1.12.2 — Forge did not wrap them in a
 * {@code ForgeRegistry} until later — and the vanilla registry is private with only a
 * class-to-id lookup exposed. Listing the types needs the registry itself.
 */
@Mixin(TileEntity.class)
public interface TileEntityRegistryAccessor {
    @Accessor("REGISTRY")
    static RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>> impetus$getRegistry() {
        throw new AssertionError("Untransformed accessor");
    }
}
