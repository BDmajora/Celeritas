package com.bdmajora.impetus.iris.uniforms;

import com.bdmajora.impetus.ImpetusVintage;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeBeach;
import net.minecraft.world.biome.BiomeDesert;
import net.minecraft.world.biome.BiomeEnd;
import net.minecraft.world.biome.BiomeForest;
import net.minecraft.world.biome.BiomeHell;
import net.minecraft.world.biome.BiomeHills;
import net.minecraft.world.biome.BiomeJungle;
import net.minecraft.world.biome.BiomeMesa;
import net.minecraft.world.biome.BiomeMushroomIsland;
import net.minecraft.world.biome.BiomeOcean;
import net.minecraft.world.biome.BiomeRiver;
import net.minecraft.world.biome.BiomeSavanna;
import net.minecraft.world.biome.BiomeSnow;
import net.minecraft.world.biome.BiomeSwamp;
import net.minecraft.world.biome.BiomeTaiga;
import net.minecraft.world.biome.BiomeVoid;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4i;
import com.bdmajora.impetus.iris.gl.uniform.UniformCollector;
import com.bdmajora.impetus.iris.gl.uniform.UniformUpdateFrequency;
import com.bdmajora.impetus.iris.material.WorldRenderingSettings;
import com.bdmajora.impetus.iris.pipeline.ColorSpaceConverter;
import com.bdmajora.impetus.iris.vertices.IrisChunkVertexType;
import com.bdmajora.impetus.lwjgl.GL11;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

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
    private static final ResourceLocation DARKNESS_EFFECT_ID = new ResourceLocation("darkness");
    private static final int GL_FOG_DENSITY = 0x0B62;
    private static final int GL_FOG_START = 0x0B63;
    private static final int GL_FOG_END = 0x0B64;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_BLEND_SRC_RGB = 0x80C9;
    private static final int GL_BLEND_DST_RGB = 0x80C8;
    private static final int GL_BLEND_SRC_ALPHA = 0x80CB;
    private static final int GL_BLEND_DST_ALPHA = 0x80CA;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_TEXTURE_WIDTH = 0x1000;
    private static final int GL_TEXTURE_HEIGHT = 0x1001;

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
    private static final SmoothedValue rainStrengthS = new SmoothedValue();
    private static final SmoothedValue rainStrengthShiningStars = new SmoothedValue();
    private static final SmoothedValue rainStrengthS2 = new SmoothedValue();
    private static final SmoothedValue precipitationRain = new SmoothedValue();
    private static final SmoothedValue touchMyBody = new SmoothedValue();
    private static final SmoothedValue sneakSmooth = new SmoothedValue();
    private static final SmoothedValue burningSmooth = new SmoothedValue();
    private static final SmoothedValue smoothSpeed = new SmoothedValue();

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
    private static float cachedVelocity;
    private static float cachedRainStrengthS;
    private static float cachedRainStrengthShiningStars;
    private static float cachedRainStrengthS2;
    private static float cachedPrecipitationRain;
    private static float cachedTouchMyBody;
    private static float cachedSneakSmooth;
    private static float cachedBurningSmooth;
    private static float cachedEffectStrength;
    private static float cachedEndFlashIntensity;
    private static float cachedPreviousEndFlashIntensity;

    private CommonUniforms() {
    }

    public static void beginFrame() {
        updateComplementaryCustomUniforms();
    }

    public static void addCommonUniforms(UniformCollector uniforms) {
        CelestialUniforms.addCelestialUniforms(uniforms);
        SystemTimeUniforms.addSystemTimeUniforms(uniforms);

        uniforms
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "centerDepthSmooth",
                        com.bdmajora.impetus.iris.pipeline.CenterDepthSampler::getCenterDepthSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainStrength", CommonUniforms::getRainStrength)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "eyeAltitude", CommonUniforms::getEyeAltitude)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", CommonUniforms::isEyeInWater)
                // OptiFine declares blindness/nightVision as FLOAT uniforms (potion effect strength 0..1);
                // uploading them as ints hits the wrong glUniform family and the type validator disables them.
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", CommonUniforms::getBlindness)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessFactor", CommonUniforms::getDarknessFactor)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor",
                        CommonUniforms::getDarknessLightFactor)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "nightVision", CommonUniforms::getNightVision)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "renderStage",
                        CapturedRenderingState.INSTANCE::getRenderStage)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "entityId",
                        CapturedRenderingState.INSTANCE::getCurrentRenderedEntity)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "blockEntityId",
                        CapturedRenderingState.INSTANCE::getCurrentRenderedBlockEntity)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "currentRenderedItemId",
                        CapturedRenderingState.INSTANCE::getCurrentRenderedItem)
                .uniform4f(UniformUpdateFrequency.DYNAMIC, "iris_ColorModulator",
                        CapturedRenderingState.INSTANCE::getColorModulator)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "iris_GlintAlpha", CommonUniforms::getIrisGlintAlpha)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "iris_TextureScale",
                        CommonUniforms::getIrisTextureScale)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "iris_ModelScale",
                        CommonUniforms::getIrisModelScale)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "iris_ModelOffset",
                        CommonUniforms::getIrisModelOffset)
                .uniform2f(UniformUpdateFrequency.PER_FRAME, "u_TextureScale",
                        CommonUniforms::getTextureScaleVector)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "u_ModelScale",
                        CommonUniforms::getModelScaleVector)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "iris_CameraTranslation",
                        CommonUniforms::getIrisCameraTranslation)
                .uniform4f(UniformUpdateFrequency.DYNAMIC, "entityColor",
                        CapturedRenderingState.INSTANCE::getEntityColor)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "gtextureId", CommonUniforms::getGtextureId)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "textureReloadCount",
                        CapturedRenderingState.INSTANCE::getTextureReloadCount)
                .uniform2i(UniformUpdateFrequency.DYNAMIC, "gtextureSize", CommonUniforms::getGtextureSize)
                .uniform4i(UniformUpdateFrequency.DYNAMIC, "blendFunc", CommonUniforms::getBlendFunc)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "iris_currentAlphaTest",
                        CapturedRenderingState.INSTANCE::getCurrentAlphaTest)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "alphaTestRef",
                        CapturedRenderingState.INSTANCE::getCurrentAlphaTest)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "isRightHanded", CommonUniforms::isRightHanded)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_sneaking", CommonUniforms::isSneaking)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_sprinting", CommonUniforms::isSprinting)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_hurt", CommonUniforms::isHurt)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_invisible", CommonUniforms::isInvisible)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_burning", CommonUniforms::isBurning)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "is_on_ground", CommonUniforms::isOnGround)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "feetInWater", CommonUniforms::isFeetInWater)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "inSwimmingAnimation", CommonUniforms::isInSwimmingAnimation)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "isRiding", CommonUniforms::isRiding)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "isElytraFlying", CommonUniforms::isElytraFlying)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "vehicleInWater", CommonUniforms::isVehicleInWater)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "vehicleId", CommonUniforms::getVehicleId)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "vehicleLookVector", CommonUniforms::getVehicleLookVector)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "relativeVehiclePosition",
                        CommonUniforms::getRelativeVehiclePosition)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "currentColorSpace",
                        () -> ColorSpaceConverter.getColorSpace().ordinal())
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "chunkFadeTimeInv",
                        CommonUniforms::getChunkFadeTimeInv)
                // Both are constants here: the block atlas is always sampled with vanilla minification and no
                // anisotropy, because it has no border between sprites (see BlockAtlasFiltering).
                .uniform1i(UniformUpdateFrequency.ONCE, "textureFilteringMode", () -> 0)
                .uniform1i(UniformUpdateFrequency.ONCE, "anisotropicFiltering", () -> 0)
                .uniform1f(UniformUpdateFrequency.ONCE, "pi", () -> (float) Math.PI)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "playerMood", CommonUniforms::getPlayerMood)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "constantMood", CommonUniforms::getConstantMood)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "thunderStrength", CommonUniforms::getThunderStrength)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "heavyFog", CommonUniforms::isHeavyFog)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHealth",
                        CommonUniforms::getCurrentPlayerHealth)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHealth", CommonUniforms::getMaxPlayerHealth)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHunger",
                        CommonUniforms::getCurrentPlayerHunger)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHunger", () -> 20.0f)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerArmor",
                        CommonUniforms::getCurrentPlayerArmor)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerArmor", () -> 50.0f)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerAir", CommonUniforms::getCurrentPlayerAir)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerAir", CommonUniforms::getMaxPlayerAir)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "firstPersonCamera", CommonUniforms::isFirstPersonCamera)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "isSpectator", CommonUniforms::isSpectator)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "currentSelectedBlockId",
                        CommonUniforms::getCurrentSelectedBlockId)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "seaLevel", CommonUniforms::getSeaLevel)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "currentSelectedBlockPos",
                        CommonUniforms::getCurrentSelectedBlockPos)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "eyePosition", CommonUniforms::getEyePosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "relativeEyePosition",
                        CommonUniforms::getRelativeEyePosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "playerLookVector", CommonUniforms::getPlayerLookVector)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "playerBodyVector", CommonUniforms::getPlayerBodyVector)
                .uniform4f(UniformUpdateFrequency.PER_TICK, "lightningBoltPosition",
                        CommonUniforms::getLightningBoltPosition)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "cloudTime", CommonUniforms::getCloudTime)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "endFlashIntensity",
                        CommonUniforms::getEndFlashIntensity)
                .uniform1f(UniformUpdateFrequency.PER_TICK, "previousEndFlashIntensity",
                        CommonUniforms::getPreviousEndFlashIntensity)
                .uniform2f(UniformUpdateFrequency.PER_FRAME, "iris_ScreenSize", CommonUniforms::getScreenSize)
                // Complementary's framemod custom uniforms are pure frameCounter expressions used by the colored
                // lighting ping-pong passes; exposing them here keeps built-ins and custom expressions in lockstep.
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod2",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() & 1))
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod4",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() & 3))
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod8",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() & 7))
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "framemod600",
                        () -> (float) (SystemTimeUniforms.COUNTER.getFrameCounter() % 600))
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "biome", CommonUniforms::getBiomeId)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "biome_category", CommonUniforms::getBiomeCategory)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "biome_precipitation",
                        CommonUniforms::getBiomePrecipitation)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainfall", CommonUniforms::getRainfall)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "temperature", CommonUniforms::getTemperature)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "BiomeTemp", CommonUniforms::getTemperature)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "isEyeInCave", CommonUniforms::getIsEyeInCave)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "inDry", CommonUniforms::getInDry)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "inRainy", CommonUniforms::getInRainy)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "inSnowy", CommonUniforms::getInSnowy)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "isDry", CommonUniforms::getInDry)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "isRainy", CommonUniforms::getInRainy)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "isSnowy", CommonUniforms::getInSnowy)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "velocity", CommonUniforms::getVelocity)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "starter", CommonUniforms::getStarter)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "frameTimeSmooth",
                        CommonUniforms::getFrameTimeSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessM", CommonUniforms::getEyeBrightnessM)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessM2", CommonUniforms::getEyeBrightnessM2)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainFactor", CommonUniforms::getRainFactor)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "timeAngle", CommonUniforms::getTimeAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "timeBrightness", CommonUniforms::getTimeBrightness)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "moonBrightness", CommonUniforms::getMoonBrightness)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "shadowFade", CommonUniforms::getShadowFade)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "shdFade", CommonUniforms::getShdFade)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainStrengthS", CommonUniforms::getRainStrengthS)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainStrengthShiningStars",
                        CommonUniforms::getRainStrengthShiningStars)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "rainStrengthS2", CommonUniforms::getRainStrengthS2)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "blindFactor", CommonUniforms::getBlindFactor)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "day", CommonUniforms::getDay)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "night", CommonUniforms::getNight)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "dawnDusk", CommonUniforms::getDawnDusk)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "isPrecipitationRain",
                        CommonUniforms::getIsPrecipitationRain)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "touchmybody", CommonUniforms::getTouchMyBody)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "sneakSmooth", CommonUniforms::getSneakSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "burningSmooth", CommonUniforms::getBurningSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "effectStrength", CommonUniforms::getEffectStrength)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "worldTime", CommonUniforms::getWorldTime)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "worldDay", CommonUniforms::getWorldDay)
                .uniform1i(UniformUpdateFrequency.PER_TICK, "moonPhase", CommonUniforms::getMoonPhase)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "bedrockLevel", CommonUniforms::getBedrockLevel)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "cloudHeight", CommonUniforms::getCloudHeight)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heightLimit", CommonUniforms::getHeightLimit)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "logicalHeightLimit",
                        CommonUniforms::getLogicalHeightLimit)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "hasCeiling", CommonUniforms::hasCeiling)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "hasSkylight", CommonUniforms::hasSkylight)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "ambientLight", CommonUniforms::getAmbientLight)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "aspectRatio", CommonUniforms::getAspectRatio)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "viewWidth", CommonUniforms::getViewWidth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "viewHeight", CommonUniforms::getViewHeight)
                // Legacy OptiFine screen-texel size (1/viewWidth, 1/viewHeight). Pre-1.13 packs (MakeUp, and many
                // other 1.12.2 packs) use these instead of taaOffset for TAA neighbourhood taps, jitter and blur
                // kernels. Without them the shader's uniforms default to 0, collapsing every neighbour tap onto the
                // centre texel — TAA stops resolving the rotating dither and the sky/horizon fills with grain.
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "pixelSizeX", CommonUniforms::getPixelSizeX)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "pixelSizeY", CommonUniforms::getPixelSizeY)
                .uniform1f(UniformUpdateFrequency.ONCE, "near", () -> 0.05f)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "far", CommonUniforms::getFar)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "dhFarPlane", CommonUniforms::getFar)
                .uniform1f(UniformUpdateFrequency.ONCE, "dhNearPlane", () -> 0.05f)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "dhRenderDistance", CommonUniforms::getDhRenderDistance)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance", CommonUniforms::getVxRenderDistance)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "fogColor", CapturedRenderingState.INSTANCE::getFogColor)
                .uniform4f(UniformUpdateFrequency.DYNAMIC, "iris_FogColor", CommonUniforms::getIrisFogColor)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "iris_FogDensity", CommonUniforms::getFogDensity)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "iris_FogStart", CommonUniforms::getFogStart)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "iris_FogEnd", CommonUniforms::getFogEnd)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "skyColor", CommonUniforms::getSkyColor)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", EyeBrightnessTracker::getEyeBrightness)
                .uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightnessSmooth", EyeBrightnessTracker::getEyeBrightnessSmooth)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "wetness", EyeBrightnessTracker::getWetness)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "fogMode", CommonUniforms::getFogMode)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "fogDensity", CommonUniforms::getFogDensity)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "fogStart", CommonUniforms::getFogStart)
                .uniform1f(UniformUpdateFrequency.DYNAMIC, "fogEnd", CommonUniforms::getFogEnd)
                .uniform1i(UniformUpdateFrequency.DYNAMIC, "fogShape", CommonUniforms::getFogShape)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldItemId", CommonUniforms::getHeldItemId)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldBlockLightValue", CommonUniforms::getHeldBlockLightValue)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldItemId2", CommonUniforms::getHeldItemId2)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "heldBlockLightValue2", CommonUniforms::getHeldBlockLightValue2)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "heldBlockLightColor",
                        CommonUniforms::getHeldBlockLightColor)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "heldBlockLightColor2",
                        CommonUniforms::getHeldBlockLightColor2)
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

    public static float getEyeBrightnessM() {
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

    private static float getVelocity() {
        updateComplementaryCustomUniforms();
        return cachedVelocity;
    }

    private static float getRainStrengthS() {
        updateComplementaryCustomUniforms();
        return cachedRainStrengthS;
    }

    private static float getRainStrengthShiningStars() {
        updateComplementaryCustomUniforms();
        return cachedRainStrengthShiningStars;
    }

    private static float getRainStrengthS2() {
        updateComplementaryCustomUniforms();
        return cachedRainStrengthS2;
    }

    private static float getIsPrecipitationRain() {
        updateComplementaryCustomUniforms();
        return cachedPrecipitationRain;
    }

    private static float getTouchMyBody() {
        updateComplementaryCustomUniforms();
        return cachedTouchMyBody;
    }

    private static float getSneakSmooth() {
        updateComplementaryCustomUniforms();
        return cachedSneakSmooth;
    }

    private static float getBurningSmooth() {
        updateComplementaryCustomUniforms();
        return cachedBurningSmooth;
    }

    private static float getEffectStrength() {
        updateComplementaryCustomUniforms();
        return cachedEffectStrength;
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
        float rainStrength = getRainStrength();
        cachedRainFactor = rainFactor.update(rainStrength, 3.0f, 3.0f, deltaSeconds);
        cachedRainStrengthS = rainStrengthS.update(rainStrength, 15.0f, 15.0f, deltaSeconds);
        cachedRainStrengthShiningStars = rainStrengthShiningStars.update(rainStrength, 10.0f, 11.0f, deltaSeconds);
        cachedRainStrengthS2 = rainStrengthS2.update(rainStrength, 70.0f, 1.0f, deltaSeconds);

        cachedVelocity = getRawVelocity();
        float frameTime = Math.max(getSafeFrameTime(), 1.0e-6f);
        float smoothedSpeed = smoothSpeed.update(cachedVelocity / frameTime, 1.0f, 1.5f, deltaSeconds);
        cachedEffectStrength = (float) (1.0f - Math.exp(-smoothedSpeed * 0.003906f));
        cachedPrecipitationRain = precipitationRain.update(
                precipitation == 1 && getEyeAltitude() < 96.0f ? 1.0f : 0.0f, 6.0f, 6.0f, deltaSeconds);
        cachedTouchMyBody = touchMyBody.update(getRawHurtFactor(), 0.0f, 0.1f, deltaSeconds);
        cachedSneakSmooth = sneakSmooth.update(isSneaking(), 2.0f, 0.9f, deltaSeconds);
        cachedBurningSmooth = burningSmooth.update(isBurning(), 1.0f, 2.0f, deltaSeconds);
        cachedPreviousEndFlashIntensity = cachedEndFlashIntensity;
        cachedEndFlashIntensity = getRawEndFlashIntensity();
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

    private static float getRawVelocity() {
        Vector3d current = CameraUniforms.getCurrentCameraPosition();
        Vector3d previous = CameraUniforms.getPreviousCameraPosition();
        double dx = current.x - previous.x;
        double dy = current.y - previous.y;
        double dz = current.z - previous.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int getBiomePrecipitation() {
        Biome biome = getCameraBiome();
        if (biome == null) {
            return 0;
        }
        if (biome.getEnableSnow()) {
            return 2;
        }
        return biome.canRain() ? 1 : 0;
    }

    private static int getBiomeId() {
        Biome biome = getCameraBiome();
        return biome == null ? -1 : Biome.getIdForBiome(biome);
    }

    private static int getBiomeCategory() {
        Biome biome = getCameraBiome();
        if (biome == null) {
            return 0;
        }
        if (biome instanceof BiomeTaiga) {
            return 1;
        }
        if (biome instanceof BiomeHills) {
            return 2;
        }
        if (biome instanceof BiomeJungle) {
            return 3;
        }
        if (biome instanceof BiomeMesa) {
            return 4;
        }
        if (biome instanceof BiomeSavanna) {
            return 6;
        }
        if (biome instanceof BiomeSnow) {
            return 7;
        }
        if (biome instanceof BiomeEnd) {
            return 8;
        }
        if (biome instanceof BiomeBeach) {
            return 9;
        }
        if (biome instanceof BiomeForest) {
            return 10;
        }
        if (biome instanceof BiomeOcean) {
            return 11;
        }
        if (biome instanceof BiomeDesert) {
            return 12;
        }
        if (biome instanceof BiomeRiver) {
            return 13;
        }
        if (biome instanceof BiomeSwamp) {
            return 14;
        }
        if (biome instanceof BiomeMushroomIsland) {
            return 15;
        }
        if (biome instanceof BiomeHell) {
            return 16;
        }
        if (biome instanceof BiomeVoid) {
            return 0;
        }
        return 5;
    }

    private static float getRainfall() {
        Biome biome = getCameraBiome();
        return biome == null ? 0.0f : biome.getRainfall();
    }

    private static float getTemperature() {
        World world = world();
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (world == null || camera == null) {
            return 0.0f;
        }
        return world.getBiome(new BlockPos(camera)).getTemperature(new BlockPos(camera));
    }

    private static Biome getCameraBiome() {
        World world = world();
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (world == null || camera == null) {
            return null;
        }
        return world.getBiome(new BlockPos(camera));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static EntityPlayer player() {
        return Minecraft.getMinecraft().player;
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    private static float getIrisGlintAlpha() {
        return 1.0f;
    }

    private static float getIrisTextureScale() {
        return IrisChunkVertexType.INSTANCE.getTextureScale();
    }

    private static float getIrisModelScale() {
        return IrisChunkVertexType.INSTANCE.getPositionScale();
    }

    private static Vector3f getIrisModelOffset() {
        float offset = IrisChunkVertexType.INSTANCE.getPositionOffset();
        return new Vector3f(offset, offset, offset);
    }

    private static Vector2f getTextureScaleVector() {
        float scale = getIrisTextureScale();
        return new Vector2f(scale, scale);
    }

    private static Vector3f getModelScaleVector() {
        float scale = getIrisModelScale();
        return new Vector3f(scale, scale, scale);
    }

    private static Vector3f getIrisCameraTranslation() {
        return CameraUniforms.getCameraPositionFract(CameraUniforms.getCurrentCameraPositionUnshifted()).negate();
    }

    private static int getGtextureId() {
        return getTextureUnit0Integer(GL_TEXTURE_BINDING_2D);
    }

    private static Vector2i getGtextureSize() {
        int activeTexture = LWJGL.glGetInteger(GL_ACTIVE_TEXTURE);
        LWJGL.glActiveTexture(GL_TEXTURE0);
        try {
            return new Vector2i(
                    LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH),
                    LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT));
        } finally {
            LWJGL.glActiveTexture(activeTexture);
        }
    }

    private static int getTextureUnit0Integer(int pname) {
        int activeTexture = LWJGL.glGetInteger(GL_ACTIVE_TEXTURE);
        LWJGL.glActiveTexture(GL_TEXTURE0);
        try {
            return LWJGL.glGetInteger(pname);
        } finally {
            LWJGL.glActiveTexture(activeTexture);
        }
    }

    private static Vector4i getBlendFunc() {
        if (LWJGL.glGetInteger(GL11.GL_BLEND) == 0) {
            return new Vector4i(0, 0, 0, 0);
        }
        return new Vector4i(
                LWJGL.glGetInteger(GL_BLEND_SRC_RGB),
                LWJGL.glGetInteger(GL_BLEND_DST_RGB),
                LWJGL.glGetInteger(GL_BLEND_SRC_ALPHA),
                LWJGL.glGetInteger(GL_BLEND_DST_ALPHA));
    }

    private static int isRightHanded() {
        EntityPlayer player = player();
        return bool(player == null || player.getPrimaryHand() == EnumHandSide.RIGHT);
    }

    private static int isSneaking() {
        EntityPlayer player = player();
        return bool(player != null && player.isSneaking());
    }

    private static int isSprinting() {
        EntityPlayer player = player();
        return bool(player != null && player.isSprinting());
    }

    private static int isHurt() {
        EntityPlayer player = player();
        return bool(player != null && player.hurtTime > 0);
    }

    private static float getRawHurtFactor() {
        EntityPlayer player = player();
        return player != null && (player.hurtTime > 0 || player.deathTime > 0) ? 0.4f : 0.0f;
    }

    private static int isInvisible() {
        EntityPlayer player = player();
        return bool(player != null && player.isInvisible());
    }

    private static int isBurning() {
        EntityPlayer player = player();
        return bool(player != null && player.isBurning());
    }

    private static int isOnGround() {
        EntityPlayer player = player();
        return bool(player != null && player.onGround);
    }

    private static int isFeetInWater() {
        EntityPlayer player = player();
        World world = world();
        if (player == null || world == null) {
            return 0;
        }
        BlockPos feet = new BlockPos(player.posX, player.posY + 0.01D, player.posZ);
        return bool(world.isBlockLoaded(feet) && world.getBlockState(feet).getMaterial() == Material.WATER);
    }

    private static int isInSwimmingAnimation() {
        EntityPlayer player = player();
        return bool(player != null && player.isInWater());
    }

    private static int isRiding() {
        EntityPlayer player = player();
        return bool(player != null && player.isRiding());
    }

    private static int isElytraFlying() {
        EntityPlayer player = player();
        return bool(player != null && player.isElytraFlying());
    }

    private static int isVehicleInWater() {
        Entity vehicle = vehicle();
        return bool(vehicle != null && vehicle.isInWater());
    }

    private static int getVehicleId() {
        Entity vehicle = vehicle();
        return vehicle == null ? 0 : WorldRenderingSettings.getEntityId(vehicle);
    }

    private static Vector3f getVehicleLookVector() {
        Entity vehicle = vehicle();
        return vehicle == null ? new Vector3f() : toVector3f(vehicle.getLook(CapturedRenderingState.INSTANCE.getTickDelta()));
    }

    private static Vector3f getRelativeVehiclePosition() {
        Entity vehicle = vehicle();
        if (vehicle == null) {
            return new Vector3f();
        }
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
        double x = vehicle.lastTickPosX + (vehicle.posX - vehicle.lastTickPosX) * tickDelta;
        double y = vehicle.lastTickPosY + (vehicle.posY - vehicle.lastTickPosY) * tickDelta;
        double z = vehicle.lastTickPosZ + (vehicle.posZ - vehicle.lastTickPosZ) * tickDelta;
        Vector3d camera = CameraUniforms.getCurrentCameraPositionUnshifted();
        return new Vector3f((float) (camera.x - x), (float) (camera.y - y), (float) (camera.z - z));
    }

    private static Entity vehicle() {
        EntityPlayer player = player();
        return player == null ? null : player.getRidingEntity();
    }

    private static boolean isSurvivalLike() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.player != null && mc.playerController != null && mc.playerController.isNotCreative()
                && !mc.playerController.isSpectator();
    }

    private static float getChunkFadeTimeInv() {
        int durationMs = ImpetusVintage.options().quality.chunkFadeInDuration;
        return durationMs > 0 ? 1.0f / durationMs : 0.0f;
    }

    private static float getPlayerMood() {
        return getIsEyeInCave();
    }

    private static float getConstantMood() {
        return getIsEyeInCave();
    }

    private static float getCurrentPlayerHealth() {
        EntityPlayer player = player();
        return player != null && isSurvivalLike() ? player.getHealth() / player.getMaxHealth() : -1.0f;
    }

    private static float getMaxPlayerHealth() {
        EntityPlayer player = player();
        return player != null && isSurvivalLike() ? player.getMaxHealth() : -1.0f;
    }

    private static float getCurrentPlayerHunger() {
        EntityPlayer player = player();
        return player != null && isSurvivalLike() ? player.getFoodStats().getFoodLevel() / 20.0f : -1.0f;
    }

    private static float getCurrentPlayerArmor() {
        EntityPlayer player = player();
        return player != null && isSurvivalLike() ? player.getTotalArmorValue() / 50.0f : -1.0f;
    }

    private static float getCurrentPlayerAir() {
        EntityPlayer player = player();
        return player != null && isSurvivalLike() ? player.getAir() / 300.0f : -1.0f;
    }

    private static float getMaxPlayerAir() {
        return isSurvivalLike() ? 300.0f : -1.0f;
    }

    private static int isFirstPersonCamera() {
        return bool(Minecraft.getMinecraft().gameSettings.thirdPersonView == 0);
    }

    private static int isSpectator() {
        Minecraft mc = Minecraft.getMinecraft();
        return bool(mc.playerController != null && mc.playerController.isSpectator());
    }

    private static float getThunderStrength() {
        World world = world();
        return world == null ? 0.0f : clamp(world.getThunderStrength(CapturedRenderingState.INSTANCE.getTickDelta()), 0.0f, 1.0f);
    }

    private static int isHeavyFog() {
        return bool(getBlindness() > 0.0f || isEyeInWater() == 2);
    }

    private static int getSeaLevel() {
        World world = world();
        return world == null ? 0 : world.getSeaLevel();
    }

    private static int getCurrentSelectedBlockId() {
        Minecraft mc = Minecraft.getMinecraft();
        RayTraceResult hit = mc.objectMouseOver;
        if (mc.world == null || hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos == null || !mc.world.isBlockLoaded(pos)) {
            return 0;
        }
        return WorldRenderingSettings.getBlockStateId(mc.world.getBlockState(pos));
    }

    private static Vector3f getCurrentSelectedBlockPos() {
        Minecraft mc = Minecraft.getMinecraft();
        RayTraceResult hit = mc.objectMouseOver;
        Entity camera = mc.getRenderViewEntity();
        if (mc.world == null || hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK || camera == null) {
            return new Vector3f(-256.0f, -256.0f, -256.0f);
        }
        BlockPos pos = hit.getBlockPos();
        Vec3d eye = camera.getPositionEyes(CapturedRenderingState.INSTANCE.getTickDelta());
        double cx = pos.getX() + 0.5 - eye.x;
        double cy = pos.getY() + 0.5 - eye.y;
        double cz = pos.getZ() + 0.5 - eye.z;
        return new Vector3f((float) cx, (float) cy, (float) cz);
    }

    private static Vector3f getEyePosition() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (camera == null) {
            return new Vector3f();
        }
        Vec3d eye = camera.getPositionEyes(CapturedRenderingState.INSTANCE.getTickDelta());
        return toVector3f(eye);
    }

    private static Vector3f getRelativeEyePosition() {
        Vector3d camera = CameraUniforms.getCurrentCameraPositionUnshifted();
        Vector3f eye = getEyePosition();
        return new Vector3f((float) (camera.x - eye.x), (float) (camera.y - eye.y), (float) (camera.z - eye.z));
    }

    private static Vector3f getPlayerLookVector() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        return camera == null ? new Vector3f() : toVector3f(camera.getLook(CapturedRenderingState.INSTANCE.getTickDelta()));
    }

    private static Vector3f getPlayerBodyVector() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (camera == null) {
            return new Vector3f();
        }
        float yaw = (float) Math.toRadians(camera.rotationYaw);
        return new Vector3f(-MathHelper.sin(yaw), 0.0f, MathHelper.cos(yaw));
    }

    private static Vector4f getLightningBoltPosition() {
        // NB: w must be 0 when no bolt is present -- packs use it as the "lightning is flashing" flag. Spell all four
        // components out: JOML's no-arg Vector4f() is (0, 0, 0, 1), which would leave lightning permanently active.
        World world = world();
        if (world == null) {
            return new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
        }
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
        Vector3d camera = CameraUniforms.getCurrentCameraPositionUnshifted();
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityLightningBolt) {
                double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * tickDelta;
                double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * tickDelta;
                double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * tickDelta;
                return new Vector4f((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z), 1.0f);
            }
        }
        return new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    }

    private static float getCloudTime() {
        World world = world();
        return world == null ? 0.0f : (world.getTotalWorldTime() + CapturedRenderingState.INSTANCE.getTickDelta()) * 0.03f;
    }

    private static float getEndFlashIntensity() {
        updateComplementaryCustomUniforms();
        return cachedEndFlashIntensity;
    }

    private static float getPreviousEndFlashIntensity() {
        updateComplementaryCustomUniforms();
        return cachedPreviousEndFlashIntensity;
    }

    private static float getRawEndFlashIntensity() {
        World world = world();
        return world != null && world.provider.getDimension() == 1 ? 1.0f : 0.0f;
    }

    private static Vector2f getScreenSize() {
        Minecraft mc = Minecraft.getMinecraft();
        return new Vector2f(mc.displayWidth, mc.displayHeight);
    }

    /**
     * The fixed-function fog mode (LINEAR/EXP/EXP2), or 0 while fog is disabled — read live from GL state like
     * OptiFine, so each program bound mid-frame sees the fog vanilla configured for that stage.
     */
    private static int getFogMode() {
        return isFogEnabled() ? LWJGL.glGetInteger(GL11.GL_FOG_MODE) : 0;
    }

    private static float getFogDensity() {
        return isFogEnabled() ? finiteNonNegative(LWJGL.glGetFloat(GL_FOG_DENSITY), 0.0f) : 0.0f;
    }

    // OptiFine's standard terrain fog (EntityRenderer.setupFog, non-blindness, non-underwater):
    //   fogStart = farPlaneDistance * ofFogStart (default 0.8), fogEnd = farPlaneDistance.
    private static final float FOG_START_FRACTION = 0.8f;
    // Swamp / boss-bar biomes render a much closer fog: OptiFine sets fogStart = farPlaneDistance * 0.05.
    private static final float FOG_START_FRACTION_THICK = 0.05f;

    private static float getFogStart() {
        // Deterministically mirror OptiFine's setupFog instead of sampling GL_FOG_START. Reading the
        // live GL fog state during the fullscreen composite pass is unreliable: it often still holds
        // the sky pass's setupFog(-1) value (0.0), which makes the Sildur/OptiFine fog term
        // (dist - fogStart)/(fogEnd - fogStart) ramp linearly across the whole screen and wash
        // everything to the sky/fog colour (the "everything above water is blue" haze).
        return getFar() * (showsThickFog() ? FOG_START_FRACTION_THICK : FOG_START_FRACTION);
    }

    private static float getFogEnd() {
        // OptiFine sets fogEnd = farPlaneDistance for every above-water terrain case.
        return getFar();
    }

    // Mirrors WorldProvider.doesXZShowFog (swamp biomes) plus the boss-bar fog, matching the
    // "this.mc.world.provider.doesXZShowFog(...) || bossOverlay.shouldCreateFog()" check in setupFog.
    private static boolean showsThickFog() {
        World world = world();
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (world == null || camera == null) {
            return false;
        }
        boolean bossFog = Minecraft.getMinecraft().ingameGUI != null
                && Minecraft.getMinecraft().ingameGUI.getBossOverlay() != null
                && Minecraft.getMinecraft().ingameGUI.getBossOverlay().shouldCreateFog();
        return world.provider.doesXZShowFog((int) camera.posX, (int) camera.posZ) || bossFog;
    }

    private static int getFogShape() {
        return 1;
    }

    private static Vector4f getIrisFogColor() {
        Vector3f fogColor = CapturedRenderingState.INSTANCE.getFogColor();
        return new Vector4f(fogColor.x, fogColor.y, fogColor.z, 1.0f);
    }

    private static int getDhRenderDistance() {
        return Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
    }

    private static int getVxRenderDistance() {
        int chunks = WorldRenderingSettings.getVoxelRenderDistanceChunks();
        return chunks > 0 ? chunks : Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
    }

    private static boolean isFogEnabled() {
        return LWJGL.glGetInteger(GL11.GL_FOG) != 0;
    }

    private static float finiteNonNegative(float value, float fallback) {
        return Float.isFinite(value) && value >= 0.0f ? value : fallback;
    }

    private static ItemStack heldItem() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return player == null ? ItemStack.EMPTY : player.getHeldItemMainhand();
    }

    /**
     * The item the {@code heldItemId}/{@code heldBlockLightValue} uniforms describe. With {@code oldHandLight} on
     * (OptiFine's default) a brighter offhand item wins, so a torch in the offhand still lights the world — that is
     * the swap {@code Shaders.java} performs right before uploading these uniforms.
     */
    private static ItemStack brightestHeldItem() {
        ItemStack main = heldItem();
        if (!WorldRenderingSettings.isOldHandLight()) {
            return main;
        }
        ItemStack off = offhandItem();
        return blockLightValue(off) > blockLightValue(main) ? off : main;
    }

    private static int getHeldItemId() {
        return WorldRenderingSettings.getItemId(brightestHeldItem());
    }

    private static int getHeldBlockLightValue() {
        // dynamicHandLight = false: the pack does not want held items lighting the world, so report nothing held.
        return WorldRenderingSettings.isDynamicHandLight() ? blockLightValue(brightestHeldItem()) : 0;
    }

    private static Vector3f getHeldBlockLightColor() {
        return WorldRenderingSettings.isDynamicHandLight()
                ? heldLightColor(brightestHeldItem()) : new Vector3f(0.0f, 0.0f, 0.0f);
    }

    private static ItemStack offhandItem() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return player == null ? ItemStack.EMPTY : player.getHeldItemOffhand();
    }

    private static int getHeldItemId2() {
        return WorldRenderingSettings.getItemId(offhandItem());
    }

    private static int getHeldBlockLightValue2() {
        return WorldRenderingSettings.isDynamicHandLight() ? blockLightValue(offhandItem()) : 0;
    }

    private static Vector3f getHeldBlockLightColor2() {
        if (!WorldRenderingSettings.isDynamicHandLight()) {
            return new Vector3f(0.0f, 0.0f, 0.0f);
        }
        return heldLightColor(offhandItem());
    }

    private static int blockLightValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Block block = Block.getBlockFromItem(stack.getItem());
        return block.getDefaultState().getLightValue();
    }

    private static Vector3f heldLightColor(ItemStack stack) {
        return new Vector3f(1.0f, 1.0f, 1.0f);
    }

    private static Vector3f toVector3f(Vec3d vector) {
        return new Vector3f((float) vector.x, (float) vector.y, (float) vector.z);
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
        World world = world();
        if (camera == null || world == null) {
            return 0;
        }
        Material material = ActiveRenderInfo.getBlockStateAtEntityViewpoint(
                world, camera, CapturedRenderingState.INSTANCE.getTickDelta()).getMaterial();
        if (material == Material.WATER) {
            return 1;
        }
        if (material == Material.LAVA) {
            return 2;
        }
        return 0;
    }

    private static float getBlindness() {
        EntityLivingBase player = livingCamera();
        return (player != null && player.isPotionActive(MobEffects.BLINDNESS)) ? 1.0f : 0.0f;
    }

    private static float getDarknessFactor() {
        EntityLivingBase player = livingCamera();
        if (player == null) {
            return 0.0f;
        }
        Potion darkness = Potion.REGISTRY.getObject(DARKNESS_EFFECT_ID);
        if (darkness == null) {
            return 0.0f;
        }
        PotionEffect effect = player.getActivePotionEffect(darkness);
        if (effect == null) {
            return 0.0f;
        }
        return clamp(effect.getDuration() / 20.0f, 0.0f, 1.0f);
    }

    private static float getDarknessLightFactor() {
        return getDarknessFactor();
    }

    /** Vanilla's night-vision brightness ramp (EntityRenderer): 1.0 held, pulsing fade in the last 10s. */
    private static float getNightVision() {
        EntityLivingBase player = livingCamera();
        if (player == null || !player.isPotionActive(MobEffects.NIGHT_VISION)) {
            return 0.0f;
        }
        int duration = player.getActivePotionEffect(MobEffects.NIGHT_VISION).getDuration();
        if (duration > 200) {
            return 1.0f;
        }
        return 0.7f + (float) Math.sin(duration * Math.PI * 0.2) * 0.3f;
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

    private static float getTimeAngle() {
        return getWorldTime() / 24000.0f;
    }

    private static float getTimeBrightness() {
        return (float) Math.max(Math.sin(getTimeAngle() * Math.PI * 2.0), 0.0);
    }

    private static float getMoonBrightness() {
        return (float) Math.max(Math.sin(getTimeAngle() * Math.PI * -2.0), 0.0);
    }

    private static float getShadowFade() {
        return clamp(1.0f - (Math.abs(Math.abs(CelestialUniforms.getSunAngle() - 0.5f) - 0.25f) - 0.23f)
                * 100.0f, 0.0f, 1.0f);
    }

    private static float getShdFade() {
        return clamp(1.0f - (Math.abs(Math.abs(CelestialUniforms.getSunAngle() - 0.5f) - 0.25f) - 0.225f)
                * 40.0f, 0.0f, 1.0f);
    }

    private static float getBlindFactor() {
        float blindFactorSqrt = clamp(getBlindness() * 2.0f - 1.0f, 0.0f, 1.0f);
        return blindFactorSqrt * blindFactorSqrt;
    }

    private static float getAdjustedTime() {
        return Math.abs((((getWorldTime() / 1000.0f) + 6.0f) % 24.0f) - 12.0f);
    }

    private static float getDay() {
        return clamp(5.4f - getAdjustedTime(), 0.0f, 1.0f);
    }

    private static float getNight() {
        return clamp(getAdjustedTime() - 6.0f, 0.0f, 1.0f);
    }

    private static float getDawnDusk() {
        return (1.0f - getDay()) - getNight();
    }

    private static int getBedrockLevel() {
        return 0;
    }

    private static float getCloudHeight() {
        return ImpetusVintage.options().quality.cloudHeight;
    }

    private static int getHeightLimit() {
        World world = world();
        return world == null ? 256 : world.getHeight();
    }

    private static int getLogicalHeightLimit() {
        World world = world();
        return world == null ? 256 : world.getActualHeight();
    }

    private static int hasCeiling() {
        World world = world();
        return bool(world != null && !world.provider.isSurfaceWorld());
    }

    private static int hasSkylight() {
        World world = world();
        return bool(world == null || world.provider.isSurfaceWorld());
    }

    private static float getAmbientLight() {
        World world = world();
        if (world == null) {
            return 0.0f;
        }
        return world.provider.isSurfaceWorld() ? 0.0f : 0.1f;
    }

    private static float getViewWidth() {
        return Minecraft.getMinecraft().displayWidth;
    }

    private static float getViewHeight() {
        return Minecraft.getMinecraft().displayHeight;
    }

    private static float getPixelSizeX() {
        int width = Minecraft.getMinecraft().displayWidth;
        return width > 0 ? 1.0f / width : 0.0f;
    }

    private static float getPixelSizeY() {
        int height = Minecraft.getMinecraft().displayHeight;
        return height > 0 ? 1.0f / height : 0.0f;
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

            float scaledHalfLife = halfLife / 20.0f;
            float decay = (float) (LOG_2 / scaledHalfLife);
            float smoothingFactor = 1.0f - (float) Math.exp(-decay * deltaSeconds);
            this.accumulator += (target - this.accumulator) * smoothingFactor;
            return this.accumulator;
        }
    }
}
