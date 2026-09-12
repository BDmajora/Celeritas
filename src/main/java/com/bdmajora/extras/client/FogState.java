package com.bdmajora.extras.client;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;

// Sodium Extra's "protected gameplay fog": blindness, water and lava fog must never be touched by the Extras switches, and 1.12.2 configures all fog through the same EntityRenderer.setupFog so the mixins need this to tell them apart
public final class FogState {
    private FogState() {
    }

    // Whether the fog being set up conveys gameplay state: blindness, submerged in water or lava; boss fog is undetectable since 1.12.2 has no distinct state for it
    public static boolean isGameplayFog() {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) {
            return false;
        }

        if (view instanceof EntityLivingBase
                && ((EntityLivingBase) view).isPotionActive(MobEffects.BLINDNESS)) {
            return true;
        }

        return view.isInsideOfMaterial(Material.WATER) || view.isInsideOfMaterial(Material.LAVA);
    }
}
