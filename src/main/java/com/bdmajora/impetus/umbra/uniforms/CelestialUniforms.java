package com.bdmajora.impetus.umbra.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import com.bdmajora.impetus.umbra.gl.program.ProgramUniforms;
import com.bdmajora.impetus.umbra.gl.uniform.UniformCollector;
import com.bdmajora.impetus.umbra.gl.uniform.UniformUpdateFrequency;

// The sun and moon uniforms
//
// The scalar angles are a pure function of the world's celestial angle, transcribed from OptiFine's
// Shaders.setCamera
//   celestialAngle = world.getCelestialAngle(partialTicks)
//   sunAngle       = celestialAngle < 0.75 ? celestialAngle + 0.25 : celestialAngle - 0.75
//   shadowAngle    = sunAngle <= 0.5 ? sunAngle : sunAngle - 0.5
//
// The directional ones — sunPosition, moonPosition, upPosition, shadowLightPosition — are derived from the
// captured gbufferModelView plus vanilla's celestial rotation, which is the modern-Iris approach
// That means they only carry real values once the EntityRenderer mixin has captured the frame's matrices; before
// that they are whatever the previous frame left
public final class CelestialUniforms {
    // The pack's sunPathRotation in degrees, tilting the sun and moon's daily arc off the vertical
    // Applied in two places that must agree: the celestial positions here, AND the shadow model-view in
    // UmbraShadowRenderer. Applying it to only one leaves the shadows pointing away from the lit side
    // Set once per pack load, 0 (untilted) until then
    // volatile because the pack load writes it and the render thread reads it
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

    // Eye-space sun direction, computed the modern-Iris way: take the captured gbufferModelView, apply vanilla's
    // celestial rotation to it — rotate(-90, Y) then rotate(celestialAngle * 360, X), exactly what
    // RenderGlobal.renderSky pushes onto the modelview — then transform the sky-local sun vector (0, 100, 0)
    // OptiFine gets the same answer by reading the matrix back mid-sky-render in postCelestialRotate. The maths is
    // identical; doing it this way needs no hook inside the sky rendering at all
    public static Vector3f getSunPosition() {
        return getCelestialPosition(100.0f);
    }

    public static Vector3f getMoonPosition() {
        return getCelestialPosition(-100.0f);
    }

    // The light that actually casts shadows: the sun while it is up (sunAngle <= 0.5), the moon after that
    private static Vector3f getShadowLightPosition() {
        return getSunAngle() <= 0.5f ? getSunPosition() : getMoonPosition();
    }

    // The shadow light direction in WORLD space, matching Iris's getShadowLightPositionInWorldSpace
    // Same construction as the eye-space version but with gbufferModelView left out, because the shadow frustum
    // reasons about world-space plane normals — feeding it an eye-space vector would rotate the culling volume with
    // the camera
    public static Vector3f getShadowLightPositionInWorldSpace() {
        Vector4f position = new Vector4f(0.0f, getSunAngle() <= 0.5f ? 100.0f : -100.0f, 0.0f, 0.0f);
        Matrix4f celestial = new Matrix4f();
        celestial.rotateY((float) Math.toRadians(-90.0));
        celestial.rotateZ((float) Math.toRadians(sunPathRotation));
        celestial.rotateX((float) Math.toRadians(getCelestialAngle() * 360.0f));
        celestial.transform(position);
        return new Vector3f(position.x, position.y, position.z);
    }

    private static Vector3f getCelestialPosition(float y) {
        Vector4f position = new Vector4f(0.0f, y, 0.0f, 0.0f);
        Matrix4f celestial = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        // renderSky's transform, plus the pack's sunPathRotation (Umbra applies it as a Z-rotation between the fixed
        // -90 Y-rotation and the time-of-day X-rotation).
        celestial.rotateY((float) Math.toRadians(-90.0));
        celestial.rotateZ((float) Math.toRadians(sunPathRotation));
        celestial.rotateX((float) Math.toRadians(getCelestialAngle() * 360.0f));
        celestial.transform(position);
        return new Vector3f(position.x, position.y, position.z);
    }

    // Eye-space world-up: gbufferModelView * (0, 100, 0, 0), OptiFine's setUpPosition
    // w = 0 makes it a direction rather than a point, so the matrix's translation is ignored
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
