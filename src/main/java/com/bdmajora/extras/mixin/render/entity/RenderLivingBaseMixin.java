package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.Extras;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Armor stand visibility and player name tags.
 *
 * <p>A hidden armor stand still draws its label. Armor stands are routinely used as invisible
 * signposts, and hiding the model to save the render while losing the text with it would break that
 * use rather than optimise it.
 *
 * <p>{@code RenderLivingBase} is 1.12.2's shared living-entity renderer, so both switches land in
 * one place.
 */
@Mixin(RenderLivingBase.class)
public abstract class RenderLivingBaseMixin<T extends EntityLivingBase> extends Render<T> {
    private RenderLivingBaseMixin(RenderManager renderManager) {
        super(renderManager);
    }

    @Inject(
            method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$doRender(T entity, double x, double y, double z,
                                  float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityArmorStand && !Extras.options().render.armorStands) {
            ci.cancel();

            if (this.canRenderName(entity)) {
                this.renderLivingLabel(entity, entity.getDisplayName().getFormattedText(), x, y, z, 64);
            }
        }
    }

    @Inject(
            method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$canRenderName(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof AbstractClientPlayer && !Extras.options().render.playerNameTag) {
            cir.setReturnValue(false);
        }
    }
}
