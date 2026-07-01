package org.taumc.celeritas.iris.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
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
 * are intentionally <em>not</em> emitted yet: OptiFine derives them from the fixed-function modelview matrix captured
 * during the celestial-rotation part of the vanilla sky render, which does not exist until the sky/render hooks
 * (Phase 4/5) are in place. Wiring them from a guessed coordinate frame would risk silently wrong lighting, so they
 * are left as a documented seam rather than an assumption.
 */
public final class CelestialUniforms {
    private CelestialUniforms() {
    }

    public static void addCelestialUniforms(ProgramUniforms.Builder uniforms) {
        uniforms
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "celestialAngle", CelestialUniforms::getCelestialAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "sunAngle", CelestialUniforms::getSunAngle)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "shadowAngle", CelestialUniforms::getShadowAngle);
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
