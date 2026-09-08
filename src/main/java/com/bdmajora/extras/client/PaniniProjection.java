package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.extras.mixin.panini.ShaderGroupAccessor;
import com.bdmajora.impetus.umbra.Umbra;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;

/**
 * Sodium Extra's Panini projection, as a 1.12.2 post-processing pass.
 *
 * <p>Panini is a cylindrical-ish projection that widens the field of view without the corner
 * stretching a plain perspective projection gives you past about 100°. It is a screen-space remap of
 * the finished frame, which is why it can be a post effect at all.
 *
 * <p>The strength and the two projection extents change per frame, and 1.12.2's
 * {@link ShaderGroup} can only set uniforms to constants from its JSON, so the pass is reached
 * through {@link ShaderGroupAccessor} and written directly.
 *
 * <p>Disabled next to a shader pack. A pack owns the framebuffer contents and its own projection
 * uniforms; warping the result afterwards would put every screen-space effect in the pack — SSAO,
 * reflections, volumetric light — out of register with what is on screen. Sodium Extra refuses for
 * the same reason.
 */
public final class PaniniProjection {
    private static final ResourceLocation CHAIN = new ResourceLocation("impetus", "shaders/post/panini.json");
    private static final String CONFIG_UNIFORM = "PaniniParams";
    /** {@code GL_TEXTURE}; {@link GlStateManager#matrixMode} takes the raw enum. */
    private static final int GL_TEXTURE = 5890;

    private static ShaderGroup shaderGroup;
    private static int framebufferWidth;
    private static int framebufferHeight;
    private static boolean failed;

    /** Extents of the current perspective frustum at unit depth, captured from the live projection. */
    private static float horizontalExtent = 1.0F;
    private static float verticalExtent = 1.0F;

    private PaniniProjection() {
    }

    /**
     * Records the frustum extents from the world projection matrix.
     *
     * <p>Read from GL rather than recomputed from the FOV setting because the effective FOV is not
     * the setting: sprinting, speed effects and the nausea warp all scale it, and Panini has to
     * follow whatever was actually rendered or the image swims.
     *
     * <p>{@code m00} and {@code m11} of a perspective matrix are the reciprocals of the horizontal
     * and vertical half-extents.
     */
    public static void captureProjection(float m00, float m11) {
        if (m00 != 0.0F && m11 != 0.0F) {
            horizontalExtent = Math.abs(1.0F / m00);
            verticalExtent = Math.abs(1.0F / m11);
        }
    }

    /** Runs the pass, if it is enabled and usable. Call once per frame with the world drawn. */
    public static void render(float partialTicks) {
        if (!shouldApply()) {
            release();
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!ensureShaderGroup(minecraft)) {
            return;
        }

        updateUniforms();

        // The texture matrix is left dirty by the world pass; ShaderGroup assumes identity.
        GlStateManager.matrixMode(GL_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        shaderGroup.render(partialTicks);
        GlStateManager.popMatrix();

        minecraft.getFramebuffer().bindFramebuffer(true);
    }

    /** Drops the framebuffers when the option is turned off or the game window changes size. */
    public static void release() {
        if (shaderGroup != null) {
            shaderGroup.deleteShaderGroup();
            shaderGroup = null;
        }
    }

    private static boolean shouldApply() {
        ExtrasConfig.ExtraSettings settings = Extras.options().extra;

        if (failed
                || !settings.paniniProjection
                || settings.paniniProjectionStrength <= 0
                || Extras.options().render.preventShaders
                || !OpenGlHelper.shadersSupported) {
            return false;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null || minecraft.getRenderViewEntity() == null) {
            return false;
        }

        return Umbra.getRenderingPipeline() == null;
    }

    private static boolean ensureShaderGroup(Minecraft minecraft) {
        int width = minecraft.displayWidth;
        int height = minecraft.displayHeight;

        if (width <= 0 || height <= 0) {
            return false;
        }

        if (shaderGroup != null && (width != framebufferWidth || height != framebufferHeight)) {
            release();
        }

        if (shaderGroup == null) {
            try {
                shaderGroup = new ShaderGroup(minecraft.getTextureManager(), minecraft.getResourceManager(),
                        minecraft.getFramebuffer(), CHAIN);
                shaderGroup.createBindFramebuffers(width, height);
                framebufferWidth = width;
                framebufferHeight = height;
            } catch (Exception e) {
                // One failure is enough: the chain is shipped with the mod, so if it will not load
                // it will not load next frame either, and retrying would log once per frame forever.
                failed = true;
                shaderGroup = null;
                Extras.LOGGER.error("Could not load the Panini projection post effect; disabling it", e);
                return false;
            }
        }

        return true;
    }

    private static void updateUniforms() {
        float strength = Extras.options().extra.paniniProjectionStrength / 100.0F;

        for (Shader shader : ((ShaderGroupAccessor) shaderGroup).impetus$getShaders()) {
            ShaderUniform uniform = shader.getShaderManager().getShaderUniform(CONFIG_UNIFORM);
            if (uniform != null) {
                uniform.set(strength, horizontalExtent, verticalExtent, 0.0F);
                return;
            }
        }
    }
}
