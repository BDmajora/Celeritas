package com.bdmajora.extras.client;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;

// Sodium Extra's "protected gameplay fog": the fog the Extras switches must never touch
// turning off atmospheric fog or pushing the fog distance out is a visibility preference, but doing
// the same to the fog that tells you you are blind, underwater or in lava is a cheat
// the fog mixins would otherwise not know the difference, because 1.12.2 configures all of it through
// the same EntityRenderer.setupFog
public final class FogState {
    private FogState() {
    }

    // whether the fog currently being set up conveys gameplay state
    // true for blindness and for being submerged in water or lava; boss fog is not detected, because
    // 1.12.2 has no distinct fog state for it
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
