package com.bdmajora.dynamiclights.mixin.lightsource;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.client.DynamicLightHandlers;
import com.bdmajora.dynamiclights.client.DynamicLightSource;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Lights living entities from what they hold and wear; targets EntityLivingBase rather than Celeritas' EntityLiving because EntityArmorStand skips EntityLiving and an armor stand holding a torch must glow
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseMixin extends Entity implements DynamicLightSource {
    @Unique
    private int impetus$livingLuminance;

    private EntityLivingBaseMixin(World world) {
        super(world);
    }

    @Override
    public void impetus$dynamicLightTick() {
        EntityLivingBase self = (EntityLivingBase) (Object) this;

        if (!DynamicLights.options().entitiesLightSource || !DynamicLightHandlers.canEntityLightUp(self)) {
            this.impetus$livingLuminance = 0;
            return;
        }

        if (this.isBurning() || this.isGlowing()) {
            this.impetus$livingLuminance = 14;
        } else {
            this.impetus$livingLuminance = DynamicLightsEngine.getLivingEntityLuminanceFromItems(self);
        }

        this.impetus$livingLuminance = Math.max(this.impetus$livingLuminance,
                DynamicLightHandlers.getLuminanceFrom(self));
    }

    @Override
    public int impetus$getLuminance() {
        return this.impetus$livingLuminance;
    }
}
