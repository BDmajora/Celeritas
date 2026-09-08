package com.bdmajora.impetus.impl.render.terrain.fog;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import com.bdmajora.impetus.engine.impl.render.chunk.fog.FogService;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkFogMode;
import com.bdmajora.impetus.lwjgl.GL20;

public class GLStateManagerFogService implements FogService {
    @Override
    public float getFogEnd() {
        return GlStateManager.fogState.end;
    }

    @Override
    public float getFogStart() {
        return GlStateManager.fogState.start;
    }

    @Override
    public float getFogDensity() {
        return GlStateManager.fogState.density;
    }

    /**
     * The terrain shader's {@code u_FogShape}, driven by the Extras fog-shape option.
     *
     * <p>Only terrain goes through that shader. Entities, particles and the sky still fog through
     * fixed-function GL, which can only measure distance from the eye — so any shape other than
     * {@code VANILLA} makes terrain and everything drawn over it disagree about where fog begins.
     * That is inherent to the option rather than a defect in it: the shapes exist precisely so the
     * horizon can fog while what is above it does not.
     */
    @Override
    public int getFogShapeIndex() {
        return com.bdmajora.extras.Extras.options().render.fogShape.shaderIndex();
    }

    /**
     * The distance past which fog is fully opaque, so chunks behind it can be skipped — <em>not</em> "whatever
     * {@code GL_FOG_END} currently holds". {@code RenderSectionManager.getEffectiveRenderDistance()} clamps the
     * occlusion graph's search distance to this, so a stale or meaningless value here does not dim the horizon: it
     * deletes the world. The BFS stops a few sections out, terrain disappears, and entities — which are not
     * chunk-graph culled — keep rendering over an empty sky.
     * <p>
     * <b>Why the escape hatch has to live here and not where Sodium puts it.</b> Upstream's "don't cull by fog"
     * exit is the <em>alpha</em> test in {@code getEffectiveRenderDistance}:
     * {@code if (!Mth.equal(alpha, 1.0f)) return renderDistance;}. That is load-bearing —
     * {@code FogParameters.NONE}'s own {@code cullDistance} works out to {@code -Float.MAX_VALUE}
     * ({@code min(renderEnd, environmentalEnd)} with both at {@code -MAX}), which would cull everything, and is
     * harmless only because {@code NONE} also carries {@code alpha = Float.MAX_VALUE} and so never reaches the
     * clamp. On 1.12 there is no fog alpha to test at all: {@code EntityRenderer} has
     * {@code fogColorRed/Green/Blue} and no alpha field (see {@link #getFogColor()}). So that guard can never fire
     * on this version, and the cutoff itself has to be able to say "no cutoff".
     * <p>
     * Two states where {@code fogState.end} says nothing about visibility, both verified against the 1.12 sources:
     * <ul>
     * <li><b>{@code GL_EXP}/{@code GL_EXP2}.</b> {@code EntityRenderer.setupFog} selects {@code FogMode.EXP} for
     *     water, lava, cloud fog and blindness and drives them purely from {@code setFogDensity(…)};
     *     {@code setFogEnd} is never called on those paths, so {@code end} is left over from the last
     *     {@code GL_LINEAR} setup.</li>
     * <li><b>Fog disabled.</b> Vanilla itself calls {@code GlStateManager.disableFog()} at many points during world
     *     rendering ({@code EntityRenderer}, {@code RenderGlobal}), and {@code end} keeps its last value across
     *     those.</li>
     * </ul>
     * Reading {@code GlStateManager.fogState} is otherwise legitimate even with a pack loaded — OptiFine's
     * {@code Shaders.setFog} forwards to {@code GlStateManager.setFog} before uploading its own {@code fogMode}
     * uniform, so the tracked state stays true. The defect was only that this method never consulted the mode or
     * the enable flag, both of which {@link #getFogMode()} directly below already reads.
     * {@code Float.MAX_VALUE} is the safe "no cutoff" answer: the caller computes
     * {@code Math.min(renderDistance, cutoff + 0.5f)}, and {@code MAX_VALUE + 0.5f} stays {@code MAX_VALUE}, so the
     * render distance wins.
     */
    @Override
    public float getFogCutoff() {
        if (!GlStateManager.fogState.fog.currentState || GlStateManager.fogState.mode != GL20.GL_LINEAR) {
            return Float.MAX_VALUE;
        }
        return GlStateManager.fogState.end;
    }

    /**
     * The alpha is genuinely 1.0 on 1.12 — {@code EntityRenderer.setupFog} always uses an opaque fog colour — so
     * Sodium's "fog must be fully opaque before we cull behind it" guard
     * ({@code if (!Mth.equal(alpha, 1.0f)) return renderDistance;}) can never fire on this version. That is not a
     * licence to cull unconditionally: it means the whole weight of that decision rests on
     * {@link #getFogCutoff()} returning a distance that is actually meaningful.
     */
    @Override
    public float[] getFogColor() {
        EntityRenderer entityRenderer = Minecraft.getMinecraft().entityRenderer;
        return new float[]{entityRenderer.fogColorRed, entityRenderer.fogColorGreen, entityRenderer.fogColorBlue, 1.0F};
    }

    @Override
    public ChunkFogMode getFogMode() {
        if (!GlStateManager.fogState.fog.currentState) {
            return ChunkFogMode.NONE;
        }
        return ChunkFogMode.fromGLMode(GlStateManager.fogState.mode);
    }
}
