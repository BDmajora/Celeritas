package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.extras.client.ItemFrameLodState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.MapItemRenderer;
import net.minecraft.client.renderer.entity.RenderItemFrame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.world.storage.MapData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item frame visibility, name tags, and level of detail.
 *
 * <p>The LOD is a backport of MoreCulling's "Frame LOD": past the configured distance a framed item
 * loses the four faces the frame hides anyway, and a framed map — a single flat texture with nothing
 * to reduce — is skipped outright. The flag is cleared unconditionally on the way out so it can
 * never leak into inventory, hand, GUI or dropped-item rendering.
 */
@Mixin(RenderItemFrame.class)
public class RenderItemFrameMixin {
    @Inject(
            method = "doRender(Lnet/minecraft/entity/item/EntityItemFrame;DDDFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$doRender(EntityItemFrame entity, double x, double y, double z,
                                  float entityYaw, float partialTicks, CallbackInfo ci) {
        if (!Extras.options().render.itemFrames) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderName(Lnet/minecraft/entity/item/EntityItemFrame;DDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$renderName(EntityItemFrame entity, double x, double y, double z, CallbackInfo ci) {
        if (!Extras.options().render.itemFrameNameTag) {
            ci.cancel();
        }
    }

    /**
     * Distance is measured from the view entity rather than from the render offsets, because
     * {@code renderItem} has no camera-relative coordinates to work from — the same idiom vanilla's
     * own {@code renderName} uses.
     */
    @Inject(method = "renderItem(Lnet/minecraft/entity/item/EntityItemFrame;)V", at = @At("HEAD"))
    private void impetus$beginLod(EntityItemFrame entity, CallbackInfo ci) {
        ExtrasConfig.RenderSettings settings = Extras.options().render;
        int distance = settings.itemFrameLodDistance;

        if (distance <= 0) {
            ItemFrameLodState.active = false;
            return;
        }

        Entity view = Minecraft.getMinecraft().getRenderManager().renderViewEntity;
        if (view == null) {
            ItemFrameLodState.active = false;
            return;
        }

        ItemFrameLodState.active = entity.getDistanceSq(view) > (double) distance * (double) distance;
    }

    @Inject(method = "renderItem(Lnet/minecraft/entity/item/EntityItemFrame;)V", at = @At("RETURN"))
    private void impetus$endLod(EntityItemFrame entity, CallbackInfo ci) {
        ItemFrameLodState.active = false;
    }

    @Redirect(
            method = "renderItem(Lnet/minecraft/entity/item/EntityItemFrame;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/MapItemRenderer;renderMap(Lnet/minecraft/world/storage/MapData;Z)V")
    )
    private void impetus$cullFramedMap(MapItemRenderer renderer, MapData data, boolean noOverlay) {
        if (!ItemFrameLodState.active) {
            renderer.renderMap(data, noOverlay);
        }
    }
}
