package org.taumc.celeritas.iris.uniforms;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.world.World;
import org.taumc.celeritas.iris.gl.program.ProgramUniforms;
import org.taumc.celeritas.iris.gl.uniform.UniformUpdateFrequency;

/**
 * Registers the OptiFine 1.12.2 "common" uniforms — the ones that are a direct read of world/player/display state.
 * All formulas are faithful to OptiFine's {@code Shaders} and every Minecraft accessor here was checked against the
 * build's own deobfuscated sources (MCP {@code stable_39}) rather than assumed.
 * <p>
 * Deliberately omitted for now (each needs temporal accumulation or a captured GL matrix that only exists once the
 * render hooks land, and guessing them risks silently-wrong output): {@code wetness}, {@code eyeBrightness(Smooth)},
 * {@code centerDepthSmooth}, and the gbuffer/shadow matrix uniforms (those come from {@link CapturedRenderingState}
 * and are registered by the matrix-uniform provider in a later phase).
 */
public final class CommonUniforms {
    private CommonUniforms() {
    }

    public static void addCommonUniforms(ProgramUniforms.Builder uniforms) {
        CelestialUniforms.addCelestialUniforms(uniforms);
        SystemTimeUniforms.addSystemTimeUniforms(uniforms);

        uniforms
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainStrength", CommonUniforms::getRainStrength)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "eyeAltitude", CommonUniforms::getEyeAltitude)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", CommonUniforms::isEyeInWater)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "blindness", CommonUniforms::getBlindness)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "nightVision", CommonUniforms::getNightVision)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "worldTime", CommonUniforms::getWorldTime)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "worldDay", CommonUniforms::getWorldDay)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "moonPhase", CommonUniforms::getMoonPhase)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "aspectRatio", CommonUniforms::getAspectRatio)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "viewWidth", CommonUniforms::getViewWidth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "viewHeight", CommonUniforms::getViewHeight)
                .uniform1f(UniformUpdateFrequency.ONCE, "near", () -> 0.05f)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "far", CommonUniforms::getFar);
    }

    private static World world() {
        return Minecraft.getMinecraft().world;
    }

    private static float getRainStrength() {
        World world = world();
        return world == null ? 0.0f : world.getRainStrength(CapturedRenderingState.INSTANCE.getTickDelta());
    }

    private static float getEyeAltitude() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        return camera == null ? 0.0f : (float) (camera.posY + camera.getEyeHeight());
    }

    private static int isEyeInWater() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        return (camera != null && camera.isInsideOfMaterial(Material.WATER)) ? 1 : 0;
    }

    private static int getBlindness() {
        EntityLivingBase player = livingCamera();
        return (player != null && player.isPotionActive(MobEffects.BLINDNESS)) ? 1 : 0;
    }

    private static int getNightVision() {
        EntityLivingBase player = livingCamera();
        return (player != null && player.isPotionActive(MobEffects.NIGHT_VISION)) ? 1 : 0;
    }

    private static EntityLivingBase livingCamera() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        return camera instanceof EntityLivingBase ? (EntityLivingBase) camera : null;
    }

    private static int getWorldTime() {
        World world = world();
        return world == null ? 0 : (int) (world.getWorldTime() % 24000L);
    }

    private static int getWorldDay() {
        World world = world();
        return world == null ? 0 : (int) (world.getWorldTime() / 24000L);
    }

    private static int getMoonPhase() {
        World world = world();
        return world == null ? 0 : world.getMoonPhase();
    }

    private static float getViewWidth() {
        return Minecraft.getMinecraft().displayWidth;
    }

    private static float getViewHeight() {
        return Minecraft.getMinecraft().displayHeight;
    }

    private static float getAspectRatio() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.displayHeight == 0) {
            return 1.0f;
        }
        return (float) mc.displayWidth / (float) mc.displayHeight;
    }

    private static float getFar() {
        int renderDistanceChunks = Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
        return renderDistanceChunks * 16.0f;
    }
}
