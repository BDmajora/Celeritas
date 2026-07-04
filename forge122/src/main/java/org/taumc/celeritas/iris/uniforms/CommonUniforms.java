package org.taumc.celeritas.iris.uniforms;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;
import org.taumc.celeritas.iris.gl.program.ProgramUniforms;
import org.taumc.celeritas.iris.gl.uniform.UniformUpdateFrequency;
import org.taumc.celeritas.lwjgl.GL11;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

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
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "far", CommonUniforms::getFar)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "fogColor", CapturedRenderingState.INSTANCE::getFogColor)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "skyColor", CommonUniforms::getSkyColor)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", EyeBrightnessTracker::getEyeBrightness)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessSmooth", EyeBrightnessTracker::getEyeBrightnessSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "wetness", EyeBrightnessTracker::getWetness)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "fogMode", CommonUniforms::getFogMode)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldItemId", CommonUniforms::getHeldItemId)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldBlockLightValue", CommonUniforms::getHeldBlockLightValue)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldItemId2", CommonUniforms::getHeldItemId2)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldBlockLightValue2", CommonUniforms::getHeldBlockLightValue2)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "hideGUI",
                        () -> Minecraft.getMinecraft().gameSettings.hideGUI ? 1 : 0)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "screenBrightness",
                        () -> Minecraft.getMinecraft().gameSettings.gammaSetting)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "atlasSize", CapturedRenderingState.INSTANCE::getAtlasSize)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "terrainTextureSize", CapturedRenderingState.INSTANCE::getAtlasSize);
    }

    /**
     * The fixed-function fog mode (LINEAR/EXP/EXP2), or 0 while fog is disabled — read live from GL state like
     * OptiFine, so each program bound mid-frame sees the fog vanilla configured for that stage.
     */
    private static int getFogMode() {
        return LWJGL.glGetInteger(GL11.GL_FOG) != 0 ? LWJGL.glGetInteger(GL11.GL_FOG_MODE) : 0;
    }

    private static ItemStack heldItem() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return player == null ? ItemStack.EMPTY : player.getHeldItemMainhand();
    }

    private static int getHeldItemId() {
        ItemStack stack = heldItem();
        return stack.isEmpty() ? -1 : Item.getIdFromItem(stack.getItem());
    }

    private static int getHeldBlockLightValue() {
        return blockLightValue(heldItem());
    }

    private static ItemStack offhandItem() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return player == null ? ItemStack.EMPTY : player.getHeldItemOffhand();
    }

    private static int getHeldItemId2() {
        ItemStack stack = offhandItem();
        return stack.isEmpty() ? -1 : Item.getIdFromItem(stack.getItem());
    }

    private static int getHeldBlockLightValue2() {
        return blockLightValue(offhandItem());
    }

    private static int blockLightValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Block block = Block.getBlockFromItem(stack.getItem());
        return block.getDefaultState().getLightValue();
    }

    private static Vector3f getSkyColor() {
        World world = world();
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (world == null || camera == null) {
            return new Vector3f();
        }
        Vec3d sky = world.getSkyColor(camera, CapturedRenderingState.INSTANCE.getTickDelta());
        return new Vector3f((float) sky.x, (float) sky.y, (float) sky.z);
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
