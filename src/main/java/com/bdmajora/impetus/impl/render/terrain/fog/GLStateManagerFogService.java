package com.bdmajora.impetus.impl.render.terrain.fog;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import com.bdmajora.impetus.engine.impl.render.chunk.fog.FogService;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkFogMode;
import com.bdmajora.impetus.lwjgl.GL20;

public class GLStateManagerFogService implements FogService {
    // From GlStateManager's cached fog state
    @Override
    public float getFogEnd() {
        return GlStateManager.fogState.end;
    }

    // From GlStateManager's cached fog state
    @Override
    public float getFogStart() {
        return GlStateManager.fogState.start;
    }

    // From GlStateManager's cached fog state
    @Override
    public float getFogDensity() {
        return GlStateManager.fogState.density;
    }

    // the terrain shader's u_FogShape, driven by the Extras fog-shape option
    // only terrain goes through that shader - entities, particles and the sky still fog through
    // fixed-function GL, which can only measure distance from the eye - so any shape other than VANILLA
    // makes terrain and everything drawn over it disagree about where fog begins
    // that is inherent to the option rather than a defect in it: the shapes exist precisely so the
    // horizon can fog while what is above it does not
    @Override
    public int getFogShapeIndex() {
        return com.bdmajora.extras.Extras.options().render.fogShape.shaderIndex();
    }

    // the distance past which fog is fully opaque, so chunks behind it can be skipped - *not* "whatever
    // GL_FOG_END currently holds"
    // RenderSectionManager.getEffectiveRenderDistance() clamps the occlusion graph's search distance to
    // this, so a stale or meaningless value here does not dim the horizon: it deletes the world - the
    // BFS stops a few sections out, terrain disappears, and entities, which are not chunk-graph culled,
    // keep rendering over an empty sky
    // the escape hatch has to live here rather than where Sodium puts it, because upstream's "don't cull
    // by fog" exit is the *alpha* test in getEffectiveRenderDistance:
    // if (!Mth.equal(alpha, 1.0f)) return renderDistance;
    // that is load-bearing upstream - FogParameters.NONE's own cullDistance works out to
    // -Float.MAX_VALUE (min(renderEnd, environmentalEnd) with both at -MAX), which would cull
    // everything, and is harmless only because NONE also carries alpha = Float.MAX_VALUE and so never
    // reaches the clamp
    // on 1.12 there is no fog alpha to test at all: EntityRenderer has fogColorRed/Green/Blue and no
    // alpha field (see getFogColor()), so that guard can never fire on this version and the cutoff
    // itself has to be able to say "no cutoff"
    // two states where fogState.end says nothing about visibility, both verified against the 1.12
    // sources:
    //   GL_EXP / GL_EXP2 - EntityRenderer.setupFog selects FogMode.EXP for water, lava, cloud fog and
    //   blindness and drives them purely from setFogDensity(...), never calling setFogEnd on those
    //   paths, so end is left over from the last GL_LINEAR setup
    //   fog disabled - vanilla itself calls GlStateManager.disableFog() at many points during world
    //   rendering (EntityRenderer, RenderGlobal), and end keeps its last value across those
    // reading GlStateManager.fogState is otherwise legitimate even with a pack loaded: OptiFine's
    // Shaders.setFog forwards to GlStateManager.setFog before uploading its own fogMode uniform, so the
    // tracked state stays true
    // the defect was only that this method never consulted the mode or the enable flag, both of which
    // getFogMode() directly below already reads
    // Float.MAX_VALUE is the safe "no cutoff" answer: the caller computes
    // Math.min(renderDistance, cutoff + 0.5f), and MAX_VALUE + 0.5f stays MAX_VALUE, so the render
    // distance wins
    @Override
    public float getFogCutoff() {
        if (!GlStateManager.fogState.fog.currentState || GlStateManager.fogState.mode != GL20.GL_LINEAR) {
            return Float.MAX_VALUE;
        }
        return GlStateManager.fogState.end;
    }

    // the alpha is genuinely 1.0 on 1.12 - EntityRenderer.setupFog always uses an opaque fog colour -
    // so Sodium's "fog must be fully opaque before we cull behind it" guard,
    // if (!Mth.equal(alpha, 1.0f)) return renderDistance;, can never fire on this version
    // that is not a licence to cull unconditionally: it means the whole weight of that decision rests
    // on getFogCutoff() returning a distance that is actually meaningful
    @Override
    public float[] getFogColor() {
        EntityRenderer entityRenderer = Minecraft.getMinecraft().entityRenderer;
        return new float[]{entityRenderer.fogColorRed, entityRenderer.fogColorGreen, entityRenderer.fogColorBlue, 1.0F};
    }

    // Maps vanilla's fog mode constants to the chunk shader's
    @Override
    public ChunkFogMode getFogMode() {
        if (!GlStateManager.fogState.fog.currentState) {
            return ChunkFogMode.NONE;
        }
        return ChunkFogMode.fromGLMode(GlStateManager.fogState.mode);
    }
}
