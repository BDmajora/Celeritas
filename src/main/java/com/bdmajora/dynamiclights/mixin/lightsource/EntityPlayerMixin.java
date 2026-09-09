package com.bdmajora.dynamiclights.mixin.lightsource;

import com.bdmajora.dynamiclights.client.DynamicLightHandlers;
import com.bdmajora.dynamiclights.client.DynamicLightSource;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

// the player's own light, which differs from every other living entity in two ways:
// the "Entities" switch does not silence a player's held torch - that is what the separate
// first-person switch is for - and a spectator emits nothing, since they are not really there
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerMixin extends EntityLivingBase implements DynamicLightSource {
    @Shadow
    public abstract boolean isSpectator();

    @Unique
    private int impetus$playerLuminance;

    // The world this player was last ticked in, so a dimension change clears the stale light.
    @Unique
    private World impetus$lastWorld;

    private EntityPlayerMixin(World world) {
        super(world);
    }

    @Override
    public void impetus$dynamicLightTick() {
        EntityPlayer self = (EntityPlayer) (Object) this;

        if (!DynamicLightHandlers.canEntityLightUp(self)) {
            this.impetus$playerLuminance = 0;
            return;
        }

        if (this.isBurning() || this.isGlowing()) {
            this.impetus$playerLuminance = 14;
        } else {
            this.impetus$playerLuminance = Math.max(
                    DynamicLightHandlers.getLuminanceFrom(self),
                    DynamicLightsEngine.getLivingEntityLuminanceFromItems(self));
        }

        if (this.isSpectator()) {
            this.impetus$playerLuminance = 0;
        }

        if (this.impetus$lastWorld != this.world) {
            this.impetus$lastWorld = this.world;
            this.impetus$playerLuminance = 0;
        }
    }

    @Override
    public int impetus$getLuminance() {
        return this.impetus$playerLuminance;
    }
}
