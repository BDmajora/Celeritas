package com.bdmajora.extras.client;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;

/**
 * Sodium Extra's "protected gameplay fog": the fog the Extras switches must never touch.
 *
 * <p>Turning off atmospheric fog or pushing the fog distance out is a visibility preference. Doing
 * the same to the fog that tells you you are blind, underwater or in lava is a cheat, and the fog
 * mixins would otherwise not know the difference — 1.12.2 configures all of it through the same
 * {@code EntityRenderer.setupFog}.
 */
public final class FogState {
    private FogState() {
    }

    /**
     * Whether the fog currently being set up conveys gameplay state.
     *
     * <p>True for blindness and for being submerged in water or lava. Boss fog is not detected;
     * 1.12.2 has no distinct fog state for it.
     */
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
