package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lights the first-person hand and the item in it.
 *
 * <p>The held item is drawn after the world with its own lightmap coordinate, so it does not pick up
 * the light of the torch it <em>is</em>. Without this the world brightens but the player's own arm
 * stays dark, which reads as a bug rather than a stylistic choice.
 *
 * <p>The argument being modified is {@code getCombinedLight}'s minimum block light, not the light
 * itself — raising the floor is how vanilla already handles a held light source.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Shadow
    private ItemStack itemStackMainHand;

    @Shadow
    private ItemStack itemStackOffHand;

    @Shadow
    @Final
    private Minecraft mc;

    @ModifyArg(
            method = "setLightmap",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;"
                            + "getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I"),
            index = 1)
    private int impetus$handLightFloor(int minimumBlockLight) {
        AbstractClientPlayer player = this.mc.player;

        if (!DynamicLights.options().mode.isEnabled() || player == null) {
            return minimumBlockLight;
        }

        boolean submerged = DynamicLightsEngine.isEyeSubmergedInFluid(player);
        int held = Math.max(
                DynamicLightsEngine.getLuminanceFromItemStack(this.itemStackMainHand, submerged),
                DynamicLightsEngine.getLuminanceFromItemStack(this.itemStackOffHand, submerged));

        // Also take the light of anything else nearby, so standing next to a lit creeper lights the hand.
        BlockPos eyes = new BlockPos(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        int surrounding = (int) DynamicLights.engine().getDynamicLightLevel(eyes);

        return Math.max(minimumBlockLight, Math.max(held, surrounding));
    }
}
