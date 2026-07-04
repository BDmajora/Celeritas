package org.taumc.celeritas.iris.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.taumc.celeritas.iris.gl.program.ProgramUniforms;
import org.taumc.celeritas.iris.gl.uniform.UniformUpdateFrequency;

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
    private CelestialUniforms() {
    }

    public static void addCelestialUniforms(ProgramUniforms.Builder uniforms) {
        uniforms
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "celestialAngle", CelestialUniforms::getCelestialAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "sunAngle", CelestialUniforms::getSunAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "shadowAngle", CelestialUniforms::getShadowAngle)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "sunPosition", CelestialUniforms::getSunPosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "moonPosition", CelestialUniforms::getMoonPosition)
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "shadowLightPosition", CelestialUniforms::getShadowLightPosition)
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
        celestial.rotateY((float) Math.toRadians(-90.0));
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
