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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.joml.Vector2i;
import org.joml.Vector3d;
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
 * The matrix, camera, and previous-frame uniforms come from {@link CapturedRenderingState} and are registered by the
 * matrix-uniform provider.
 */
public final class CommonUniforms {
    private static final float DEFAULT_FRAME_TIME = 1.0f / 60.0f;

    private static final SmoothedValue eyeInCave = new SmoothedValue();
    private static final SmoothedValue inDry = new SmoothedValue();
    private static final SmoothedValue inRainy = new SmoothedValue();
    private static final SmoothedValue inSnowy = new SmoothedValue();
    private static final SmoothedValue moved = new SmoothedValue();
    private static final SmoothedValue starter = new SmoothedValue();
    private static final SmoothedValue frameTimeSmooth = new SmoothedValue(DEFAULT_FRAME_TIME);
    private static final SmoothedValue eyeBrightnessM = new SmoothedValue();
    private static final SmoothedValue eyeBrightnessM2 = new SmoothedValue();
    private static final SmoothedValue rainFactor = new SmoothedValue();

    private static int complementaryUniformFrame = Integer.MIN_VALUE;
    private static float cachedEyeInCave;
    private static float cachedInDry;
    private static float cachedInRainy;
    private static float cachedInSnowy;
    private static float cachedStarter;
    private static float cachedFrameTimeSmooth = DEFAULT_FRAME_TIME;
    private static float cachedEyeBrightnessM;
    private static float cachedEyeBrightnessM2;
    private static float cachedRainFactor;

    private CommonUniforms() {
    }

    public static void beginFrame() {
        updateComplementaryCustomUniforms();
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
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "renderStage",
                        CapturedRenderingState.INSTANCE::getRenderStage)
                // Iris-exclusive: whether the player is invisible (spectator/potion). Not tracked yet.
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> 0)
                // Complementary's custom uniform `uniform.float.framemod2 = frameCounter % 2` (shaders.properties).
                // The general custom-uniform expression system is unimplemented; this one drives the colored-lighting
                // floodfill ping-pong (shadowcomp reads one volume and writes the other by frame parity), so leaving
                // it at a constant breaks light accumulation and flickers. Provided as a built-in until then.
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod2",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() & 1))
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod4",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() & 3))
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod8",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() & 7))
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod600",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() % 600))
                // Complementary's remaining custom uniforms from shaders.properties. Real Iris evaluates these with
                // its custom-uniform expression system; until that lands here, provide the hardcoded Iris-compatible
                // values that are unsafe to leave at GLSL's default 0.
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "biome_precipitation",
                        CommonUniforms::getBiomePrecipitation)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "isEyeInCave", CommonUniforms::getIsEyeInCave)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "inDry", CommonUniforms::getInDry)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "inRainy", CommonUniforms::getInRainy)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "inSnowy", CommonUniforms::getInSnowy)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "starter", CommonUniforms::getStarter)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "frameTimeSmooth",
                        CommonUniforms::getFrameTimeSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessM", CommonUniforms::getEyeBrightnessM)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessM2", CommonUniforms::getEyeBrightnessM2)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainFactor", CommonUniforms::getRainFactor)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "worldTime", CommonUniforms::getWorldTime)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "worldDay", CommonUniforms::getWorldDay)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "moonPhase", CommonUniforms::getMoonPhase)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "aspectRatio", CommonUniforms::getAspectRatio)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "viewWidth", CommonUniforms::getViewWidth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "viewHeight", CommonUniforms::getViewHeight)
                .uniform1f(UniformUpdateFrequency.ONCE, "near", () -> 0.05f)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "far", CommonUniforms::getFar)
                // Distant Horizons / voxelization render distances. We ship neither, so report "absent" (0) — modern
                // packs (Complementary) gate on these and fall back to `far`. Present so their shaders link.
                .uniform1i(UniformUpdateFrequency.ONCE, "dhRenderDistance", () -> 0)
                .uniform1i(UniformUpdateFrequency.ONCE, "vxRenderDistance", () -> 0)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "fogColor", CapturedRenderingState.INSTANCE::getFogColor)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "skyColor", CommonUniforms::getSkyColor)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", EyeBrightnessTracker::getEyeBrightness)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessSmooth", EyeBrightnessTracker::getEyeBrightnessSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "wetness", EyeBrightnessTracker::getWetness)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "fogMode", CommonUniforms::getFogMode)
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

    private static float getIsEyeInCave() {
        updateComplementaryCustomUniforms();
        return isEyeInWater() == 0 ? cachedEyeInCave : 0.0f;
    }

    private static float getInDry() {
        updateComplementaryCustomUniforms();
        return cachedInDry;
    }

    private static float getInRainy() {
        updateComplementaryCustomUniforms();
        return cachedInRainy;
    }

    private static float getInSnowy() {
        updateComplementaryCustomUniforms();
        return cachedInSnowy;
    }

    private static float getStarter() {
        updateComplementaryCustomUniforms();
        return cachedStarter;
    }

    private static float getFrameTimeSmooth() {
        updateComplementaryCustomUniforms();
        return cachedFrameTimeSmooth;
    }

    private static float getEyeBrightnessM() {
        updateComplementaryCustomUniforms();
        return cachedEyeBrightnessM;
    }

    private static float getEyeBrightnessM2() {
        updateComplementaryCustomUniforms();
        return cachedEyeBrightnessM2;
    }

    private static float getRainFactor() {
        updateComplementaryCustomUniforms();
        return cachedRainFactor;
    }

    private static void updateComplementaryCustomUniforms() {
        int frame = SystemTimeUniforms.COUNTER.getFrameCounter();
        if (frame == complementaryUniformFrame) {
            return;
        }
        complementaryUniformFrame = frame;

        float deltaSeconds = Math.max(getSafeFrameTime(), 0.0f);
        float skyBrightness = getEyeSkyBrightness();
        int precipitation = getBiomePrecipitation();

        cachedEyeInCave = eyeInCave.update(getRawEyeInCave(skyBrightness), 6.0f, 12.0f, deltaSeconds);
        cachedInDry = inDry.update(precipitation == 0 ? 1.0f : 0.0f, 20.0f, 10.0f, deltaSeconds);
        cachedInRainy = inRainy.update(precipitation == 1 ? 1.0f : 0.0f, 20.0f, 10.0f, deltaSeconds);
        cachedInSnowy = inSnowy.update(precipitation == 2 ? 1.0f : 0.0f, 20.0f, 10.0f, deltaSeconds);

        float moving = getMoving();
        float movedValue = moved.update(moving, 0.0f, 31536000.0f, deltaSeconds);
        cachedStarter = starter.update(movedValue, 20.0f, 20.0f, deltaSeconds);

        cachedFrameTimeSmooth = Math.max(DEFAULT_FRAME_TIME / 4.0f,
                frameTimeSmooth.update(getSafeFrameTime(), 5.0f, 5.0f, deltaSeconds));
        cachedEyeBrightnessM = eyeBrightnessM.update(skyBrightness, 5.0f, 5.0f, deltaSeconds);
        cachedEyeBrightnessM2 = eyeBrightnessM2.update(skyBrightness > 239.0f / 240.0f ? 1.0f : 0.0f,
                2.0f, 2.0f, deltaSeconds);
        cachedRainFactor = rainFactor.update(getRainStrength(), 3.0f, 3.0f, deltaSeconds);
    }

    private static float getRawEyeInCave(float skyBrightness) {
        return getEyeAltitude() < 5.0f ? 1.0f - skyBrightness : 0.0f;
    }

    private static float getEyeSkyBrightness() {
        Vector2i brightness = EyeBrightnessTracker.getEyeBrightness();
        return clamp(brightness.y / 240.0f, 0.0f, 1.0f);
    }

    private static float getSafeFrameTime() {
        float frameTime = SystemTimeUniforms.COUNTER.getLastFrameTime();
        return frameTime > 0.0f ? Math.min(frameTime, 0.25f) : DEFAULT_FRAME_TIME;
    }

    private static float getMoving() {
        Vector3d current = CameraUniforms.getCurrentCameraPosition();
        Vector3d previous = CameraUniforms.getPreviousCameraPosition();
        double diffSum = Math.abs(current.x - previous.x)
                + Math.abs(current.y - previous.y)
                + Math.abs(current.z - previous.z);
        return diffSum > 0.0 && diffSum < 1.0 ? 1.0f : 0.0f;
    }

    private static int getBiomePrecipitation() {
        World world = world();
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (world == null || camera == null) {
            return 0;
        }

        Biome biome = world.getBiome(new BlockPos(camera));
        if (biome == null) {
            return 0;
        }
        if (biome.getEnableSnow()) {
            return 2;
        }
        return biome.canRain() ? 1 : 0;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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

    private static final class SmoothedValue {
        private static final double LOG_2 = Math.log(2.0);

        private boolean initialized;
        private float accumulator;

        private SmoothedValue() {
        }

        private SmoothedValue(float initialValue) {
            this.initialized = true;
            this.accumulator = initialValue;
        }

        private float update(float target, float halfLifeUp, float halfLifeDown, float deltaSeconds) {
            if (!this.initialized) {
                this.initialized = true;
                this.accumulator = target;
                return target;
            }

            float halfLife = target > this.accumulator ? halfLifeUp : halfLifeDown;
            if (halfLife <= 0.0f) {
                this.accumulator = target;
                return target;
            }

            float scaledHalfLife = halfLife * 0.1f;
            float decay = (float) (LOG_2 / scaledHalfLife);
            float smoothingFactor = 1.0f - (float) Math.exp(-decay * deltaSeconds);
            this.accumulator += (target - this.accumulator) * smoothingFactor;
            return this.accumulator;
        }
    }
}
