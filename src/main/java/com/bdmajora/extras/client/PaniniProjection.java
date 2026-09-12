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

// Sodium Extra's Panini projection as a post pass, a screen-space remap widening FOV without perspective's corner stretch past ~100 degrees; uniforms are written by hand per frame since ShaderGroup only bakes constants, and it refuses to run under a shader pack
public final class PaniniProjection {
    private static final ResourceLocation CHAIN = new ResourceLocation("impetus", "shaders/post/panini.json");
    private static final String CONFIG_UNIFORM = "PaniniParams";
    // GL_TEXTURE as a literal because GlStateManager.matrixMode takes the raw enum and 1.12.2 exposes no constant for it
    private static final int GL_TEXTURE = 5890;

    // Built lazily on first use and thrown away whenever the window resizes or the effect is switched off
    private static ShaderGroup shaderGroup;
    // Size the current shaderGroup's framebuffers were allocated at, compared each frame to detect a resize
    private static int framebufferWidth;
    private static int framebufferHeight;
    // Latched on a load failure so a broken or missing chain is attempted exactly once instead of every frame
    private static boolean failed;

    // Half-extents of the live frustum at unit depth, refreshed per frame by captureProjection; default 1.0 so the first frame gets an identity-ish remap rather than a division by zero
    private static float horizontalExtent = 1.0F;
    private static float verticalExtent = 1.0F;

    private PaniniProjection() {
    }

    // Captures frustum extents from the actual projection matrix (sprinting, speed and nausea all scale the effective FOV, and Panini must follow what was rendered); m00/m11 are reciprocals of the half-extents, abs() handles flipped handedness, and the zero guard skips degenerate matrices
    public static void captureProjection(float m00, float m11) {
        if (m00 != 0.0F && m11 != 0.0F) {
            horizontalExtent = Math.abs(1.0F / m00);
            verticalExtent = Math.abs(1.0F / m11);
        }
    }

    // Runs the pass once per frame after the world and before the GUI; releasing on the disabled path frees the framebuffers as soon as the effect stops
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

        // The world pass leaves the texture matrix dirty and ShaderGroup assumes identity, so it is saved, reset and restored around the render
        GlStateManager.matrixMode(GL_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        shaderGroup.render(partialTicks);
        GlStateManager.popMatrix();

        // ShaderGroup finishes with its own framebuffer bound; rebind the main one so the GUI draws to screen (true also resets the viewport)
        minecraft.getFramebuffer().bindFramebuffer(true);
    }

    // Frees the chain and its framebuffers on option off, window resize or world unload; null-safe and idempotent
    public static void release() {
        if (shaderGroup != null) {
            shaderGroup.deleteShaderGroup();
            shaderGroup = null;
        }
    }

    // Every reason the pass might not run, cheapest first: a failed load, zero strength, preventShaders (the vanilla post-chain switch), no GLSL, no world/view entity (main menu), or an active shader pack
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

    // Lazily builds the chain, rebuilding when the window size changed; false when the window has no area yet or loading failed
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
                // One failure is enough: the chain ships with the mod, so if it will not load now it will not load next frame, and retrying would log every frame forever
                failed = true;
                shaderGroup = null;
                Extras.LOGGER.error("Could not load the Panini projection post effect; disabling it", e);
                return false;
            }
        }

        return true;
    }

    // Writes strength and the two frustum extents into the pass for this frame
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
