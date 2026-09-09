package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the hurt flash and the creeper charge-up are the only entity tints vanilla draws with fixed-function
// texture combiners rather than vertex colours, so a bound shader program dropped them and damaged
// mobs kept their normal texture
// publish the tint as OptiFine's entityColor uniform instead - see CapturedRenderingState#getEntityColor()
// the combiner setup is skipped outright while a pipeline is active rather than left to run harmlessly
// alongside: setBrightness binds its white brightness texture over unit 2, which is the gbuffer stage's
// normals sampler, and unsetBrightness leaves that unit bound to 0 afterwards
@Mixin(RenderLivingBase.class)
public abstract class RenderLivingBaseEntityColorMixin {
    @Shadow
    protected abstract int getColorMultiplier(EntityLivingBase entity, float lightBrightness, float partialTicks);

    @Inject(method = "setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z", at = @At("HEAD"), cancellable = true)
    private void impetus$captureEntityColor(EntityLivingBase entity, float partialTicks, boolean combineTextures,
                                            CallbackInfoReturnable<Boolean> cir) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline == null) {
            return;
        }

        int multiplier = this.getColorMultiplier(entity, entity.getBrightness(), partialTicks);
        boolean hasMultiplier = (multiplier >> 24 & 255) > 0;
        boolean hurt = entity.hurtTime > 0 || entity.deathTime > 0;

        // Mirrors vanilla's two early exits: nothing to tint at all, and a hurt flash that this pass does not own.
        if (!hasMultiplier && (!hurt || !combineTextures)) {
            cir.setReturnValue(false);
            return;
        }

        if (hurt) {
            CapturedRenderingState.INSTANCE.setEntityColor(1.0f, 0.0f, 0.0f, 0.3f);
        } else {
            CapturedRenderingState.INSTANCE.setEntityColor(
                    (float) (multiplier >> 16 & 255) / 255.0f,
                    (float) (multiplier >> 8 & 255) / 255.0f,
                    (float) (multiplier & 255) / 255.0f,
                    1.0f - (float) (multiplier >> 24 & 255) / 255.0f);
        }
        pipeline.refreshDynamicUniforms();

        // Returning true still pairs us with the unsetBrightness call that clears the tint again.
        cir.setReturnValue(true);
    }

    @Inject(method = "unsetBrightness", at = @At("HEAD"), cancellable = true)
    private void impetus$clearEntityColor(CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline == null) {
            return;
        }

        CapturedRenderingState.INSTANCE.resetEntityColor();
        pipeline.refreshDynamicUniforms();
        ci.cancel();
    }

    // doRender swallows any exception thrown while rendering an entity, which would otherwise skip
    // unsetBrightness and leave every later entity in the frame tinted red
    // the blend factor is still zero on every normal entity, so this costs a float compare rather than
    // a uniform re-upload
    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("RETURN"))
    private void impetus$clearStrandedEntityColor(EntityLivingBase entity, double x, double y, double z,
                                                  float entityYaw, float partialTicks, CallbackInfo ci) {
        if (CapturedRenderingState.INSTANCE.getEntityColor().w == 0.0f) {
            return;
        }

        CapturedRenderingState.INSTANCE.resetEntityColor();
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.refreshDynamicUniforms();
        }
    }
}
