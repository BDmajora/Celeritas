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

// Sodium Extra's Panini projection as a post-processing pass: a screen-space remap that widens the FOV
// without the corner stretching of plain perspective past about 100 degrees
// Uniforms change per frame but ShaderGroup only bakes constants, so they are written by hand each frame
// Refuses to run under a shader pack, whose screen-space effects would be misaligned by the warp
public final class PaniniProjection {
    private static final ResourceLocation CHAIN = new ResourceLocation("impetus", "shaders/post/panini.json");
    private static final String CONFIG_UNIFORM = "PaniniParams";
    // GL_TEXTURE spelled out as a literal because GlStateManager.matrixMode takes the raw GL enum and 1.12.2's
    // GlStateManager exposes no constant for it
    private static final int GL_TEXTURE = 5890;

    // Built lazily on first use and thrown away whenever the window resizes or the effect is switched off
    private static ShaderGroup shaderGroup;
    // Size the current shaderGroup's framebuffers were allocated at, compared each frame to detect a resize
    private static int framebufferWidth;
    private static int framebufferHeight;
    // Latched on a load failure so a broken or missing chain is attempted exactly once instead of every frame
    private static boolean failed;

    // Half-extents of the live perspective frustum at unit depth, refreshed per frame by captureProjection
    // Default 1.0 so the very first frame, before any capture, produces an identity-ish remap rather than a
    // division by zero
    private static float horizontalExtent = 1.0F;
    private static float verticalExtent = 1.0F;

    private PaniniProjection() {
    }

    // Captures the frustum extents from the world projection matrix as it is being set up
    // Taken from the actual matrix rather than recomputed from the FOV setting, because the effective FOV is not
    // the setting: sprinting, speed effects and the nausea warp all scale it, and if Panini does not follow what
    // was really rendered the image swims
    // m00 and m11 of a perspective matrix are the reciprocals of the horizontal and vertical half-extents, hence
    // the inversion; abs() because a flipped-handedness projection makes them negative without changing the
    // extent
    // The zero guard skips degenerate matrices (an orthographic or not-yet-initialised one) and leaves the last
    // good values in place
    public static void captureProjection(float m00, float m11) {
        if (m00 != 0.0F && m11 != 0.0F) {
            horizontalExtent = Math.abs(1.0F / m00);
            verticalExtent = Math.abs(1.0F / m11);
        }
    }

    // Runs the pass. Called once per frame, after the world is drawn and before the GUI
    // Releasing on the disabled path is deliberate: it frees the framebuffers as soon as the effect stops being
    // used rather than holding them for a toggle that may never come back
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

        // The world pass leaves the texture matrix dirty and ShaderGroup assumes it is identity, so it is saved,
        // reset, and restored around the render rather than left for the next consumer to trip over
        GlStateManager.matrixMode(GL_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        shaderGroup.render(partialTicks);
        GlStateManager.popMatrix();

        // ShaderGroup finishes with its own last framebuffer bound; rebind the main one so the GUI draws to the
        // screen. true also resets the viewport to the framebuffer's size
        minecraft.getFramebuffer().bindFramebuffer(true);
    }

    // Frees the chain and its framebuffers; called when the option goes off, the window resizes, or the world
    // unloads. Null-safe and idempotent, so callers do not have to track whether anything was allocated
    public static void release() {
        if (shaderGroup != null) {
            shaderGroup.deleteShaderGroup();
            shaderGroup = null;
        }
    }

    // Every reason the pass might not run this frame, cheapest checks first
    // failed short-circuits a chain that would not load; strength <= 0 means the user dialled it to nothing;
    // preventShaders is the vanilla-post-chain switch, which this pass counts as; shadersSupported covers
    // drivers with no GLSL at all
    // The world/view-entity check keeps it off the main menu, and the pipeline check is the shader-pack refusal
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

    // Lazily builds the chain, rebuilding it when the window size changed since it was created
    // Returns false when the pass cannot run this frame, either because the window has no area yet or because
    // loading failed
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
