package com.bdmajora.impetus.iris.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import com.bdmajora.impetus.iris.gl.program.ProgramUniforms;
import com.bdmajora.impetus.iris.gl.uniform.UniformCollector;
import com.bdmajora.impetus.iris.gl.uniform.UniformUpdateFrequency;

/**
 * The sun/moon timing uniforms. Only the scalar angles are provided here because they are a pure function of the
 * world's celestial angle (a faithful transcription of OptiFine's {@code Shaders.setCamera}):
 * <pre>
 *   celestialAngle = world.getCelestialAngle(partialTicks)
 *   sunAngle       = celestialAngle &lt; 0.75 ? celestialAngle + 0.25 : celestialAngle - 0.75
 *   shadowAngle    = sunAngle &lt;= 0.5 ? sunAngle : sunAngle - 0.5
 * </pre>
 * <p>
 * The directional uniforms ({@code sunPosition}, {@code moonPosition}, {@code upPosition}, {@code shadowLightPosition})
 * are derived from the captured {@code gbufferModelView} plus vanilla's celestial rotation (the modern-Iris approach),
 * so they only carry real values once the {@code EntityRenderer} mixin has captured the frame's matrices.
 */
public final class CelestialUniforms {
    /**
     * The pack's {@code sunPathRotation} (degrees), tilting the sun/moon's daily arc off the vertical. Applied to the
     * celestial positions here <em>and</em> to the shadow model-view in {@link com.bdmajora.impetus.iris.pipeline.IrisShadowRenderer}
     * so the shadows stay aligned with the lit side. Set once when a pack is loaded; 0 (untilted) until then.
     */
    private static volatile float sunPathRotation = 0.0f;

    private CelestialUniforms() {
    }

    public static void setSunPathRotation(float degrees) {
        sunPathRotation = degrees;
    }

    public static float getSunPathRotation() {
        return sunPathRotation;
    }

    public static void addCelestialUniforms(UniformCollector uniforms) {
        uniforms
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "celestialAngle", CelestialUniforms::getCelestialAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "sunAngle", CelestialUniforms::getSunAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "shadowAngle", CelestialUniforms::getShadowAngle)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "sunPosition", CelestialUniforms::getSunPosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "moonPosition", CelestialUniforms::getMoonPosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "shadowLightPosition", CelestialUniforms::getShadowLightPosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "endFlashPosition", CelestialUniforms::getEndFlashPosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "upPosition", CelestialUniforms::getUpPosition);
    }

    /**
     * Eye-space sun direction, computed the way modern Iris does it: apply vanilla's celestial rotation
     * ({@code rotate(-90, Y)} then {@code rotate(celestialAngle * 360, X)}, exactly what {@code RenderGlobal.renderSky}
     * pushes onto the modelview) to the captured {@code gbufferModelView}, then transform the sky-local sun vector
     * {@code (0, 100, 0)}. OptiFine instead reads the matrix back mid-sky-render ({@code postCelestialRotate}); the
     * math is identical but this needs no sky hook.
     */
    public static Vector3f getSunPosition() {
        return getCelestialPosition(100.0f);
    }

    public static Vector3f getMoonPosition() {
        return getCelestialPosition(-100.0f);
    }

    /** The shadow-casting light: the sun while it is up ({@code sunAngle <= 0.5}), the moon at night. */
    private static Vector3f getShadowLightPosition() {
        return getSunAngle() <= 0.5f ? getSunPosition() : getMoonPosition();
    }

    private static Vector3f getCelestialPosition(float y) {
        Vector4f position = new Vector4f(0.0f, y, 0.0f, 0.0f);
        Matrix4f celestial = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        // renderSky's transform, plus the pack's sunPathRotation (Iris applies it as a Z-rotation between the fixed
        // -90 Y-rotation and the time-of-day X-rotation).
        celestial.rotateY((float) Math.toRadians(-90.0));
        celestial.rotateZ((float) Math.toRadians(sunPathRotation));
        celestial.rotateX((float) Math.toRadians(getCelestialAngle() * 360.0f));
        celestial.transform(position);
        return new Vector3f(position.x, position.y, position.z);
    }

    /** Eye-space world-up, i.e. {@code gbufferModelView * (0, 100, 0, 0)} — OptiFine's {@code setUpPosition}. */
    public static Vector3f getUpPosition() {
        Vector4f up = new Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
        new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transform(up);
        return new Vector3f(up.x, up.y, up.z);
    }

    private static Vector3f getEndFlashPosition() {
        World world = Minecraft.getMinecraft().world;
        if (world == null || world.provider.getDimension() != 1) {
            return new Vector3f();
        }

        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
        Vector3d camera = CameraUniforms.getCurrentCameraPositionUnshifted();
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityDragon && ((EntityDragon) entity).deathTicks > 0) {
                double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * tickDelta - camera.x;
                double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * tickDelta - camera.y;
                double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * tickDelta - camera.z;
                Vector4f position = new Vector4f((float) x, (float) y, (float) z, 1.0f);
                new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transform(position);
                return new Vector3f(position.x, position.y, position.z);
            }
        }
        return new Vector3f();
    }

    public static float getCelestialAngle() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return 0.0f;
        }
        return world.getCelestialAngle(CapturedRenderingState.INSTANCE.getTickDelta());
    }

    public static float getSunAngle() {
        float celestialAngle = getCelestialAngle();
        return celestialAngle < 0.75f ? celestialAngle + 0.25f : celestialAngle - 0.75f;
    }

    public static float getShadowAngle() {
        float sunAngle = getSunAngle();
        return sunAngle <= 0.5f ? sunAngle : sunAngle - 0.5f;
    }
}
