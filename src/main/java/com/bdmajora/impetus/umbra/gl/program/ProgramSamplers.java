package com.bdmajora.impetus.umbra.gl.program;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;
import com.bdmajora.impetus.umbra.gl.sampler.SamplerBinding;
import com.bdmajora.impetus.umbra.gl.sampler.SamplerLimits;
import com.bdmajora.impetus.lwjgl.GL11;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The samplers one program uses, each on a texture unit allocated for that program alone (Umbra
 * {@code gl/program/ProgramSamplers}).
 *
 * <h2>Why per-program allocation</h2>
 * The scheme this replaces gave every sampler <em>name</em> one fixed global unit, with the full-screen layout using
 * {@code unit == colortex index}. That is only viable while the names fit in the driver's
 * {@code GL_MAX_TEXTURE_IMAGE_UNITS}, and they do not: a pack may declare 32 colortex, three depthtex, seven shadow
 * samplers, {@code noisetex} and its own custom textures and images — 49 names against 32 units. The fixed layout
 * therefore had to cap {@code UmbraRenderTargets.MAX_COLOR_BUFFERS} at 16 (Umbra allows 32) and park depthtex on 16-18,
 * shadow on 19-25 and custom textures/images on 24-31, which collide with colortex16..19 the moment the cap is
 * raised. Complementary's {@code deferred1} declares {@code RENDERTARGETS: 0,5,4,19,18}, so on this port colortex18
 * and 19 were never allocated and their attachments were silently dropped.
 * <p>
 * Allocating per program removes the conflict rather than rearranging it: a unit is consumed only when the program
 * genuinely declares the uniform ({@code glGetUniformLocation != -1}), and no single program in a real pack declares
 * anything close to all 49 names. This is exactly Umbra's {@code nextUnit++} model.
 *
 * <h2>1.12.2 deviations from Umbra</h2>
 * <ul>
 *   <li>Binding goes through {@link GlTextureUnits} rather than a raw bind plus a {@code GlStateManagerAccessor}
 *       cache patch — 1.12.2's {@code GlStateManager.TextureState} is package-private with a private constructor, so
 *       the cache can only be written by calling {@code GlStateManager} itself.</li>
 *   <li>Umbra throws when a program runs out of units. This port logs and drops the sampler, matching the position
 *       {@code ProgramImages} already takes: a pack that overruns the limit should lose one effect rather than take
 *       the whole pipeline down mid-frame on a machine that was otherwise rendering.</li>
 *   <li>No {@code GlSampler} objects: 1.12.2 predates sampler objects in this pipeline, and every sampler here uses
 *       the texture's own parameters.</li>
 * </ul>
 */
public final class ProgramSamplers {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private final List<SamplerBinding> samplerBindings;
    private List<Uniform1iCall> initializer;

    private ProgramSamplers(List<SamplerBinding> samplerBindings, List<Uniform1iCall> initializer) {
        this.samplerBindings = samplerBindings;
        this.initializer = initializer;
    }

    public static Builder builder(int program, Set<Integer> reservedTextureUnits) {
        return new Builder(program, reservedTextureUnits);
    }

    /**
     * Call with this program bound. Assigns the unit uniforms once, then rebinds every texture.
     * <p>
     * The selector is restored exactly once, after the whole run — see
     * {@link GlTextureUnits#bindTextureInRun(int, int, int)}. Umbra does the same, and for the same reason: leaving the
     * active unit moved part-way through a vanilla render sequence corrupts unrelated draws.
     */
    public void update() {
        if (this.initializer != null) {
            for (Uniform1iCall call : this.initializer) {
                LWJGL.glUniform1i(call.location, call.value);
            }
            this.initializer = null;
        }

        if (this.samplerBindings.isEmpty()) {
            return;
        }

        try {
            for (SamplerBinding binding : this.samplerBindings) {
                binding.update();
            }
        } finally {
            GlTextureUnits.releaseScratch();
        }
    }

    public int getActiveSamplers() {
        return this.samplerBindings.size();
    }

    /** A deferred {@code glUniform1i(location, value)}, issued on the first {@link #update()}. */
    private static final class Uniform1iCall {
        final int location;
        final int value;

        Uniform1iCall(int location, int value) {
            this.location = location;
            this.value = value;
        }
    }

    public static final class Builder {
        private final int program;
        private final Set<Integer> reservedTextureUnits;
        private final List<SamplerBinding> samplers = new ArrayList<>();
        private final List<Uniform1iCall> calls = new ArrayList<>();
        private final int maxTextureUnits;
        private int nextUnit;
        private boolean exhaustedReported;

        private Builder(int program, Set<Integer> reservedTextureUnits) {
            this.program = program;
            this.reservedTextureUnits = new LinkedHashSet<>(reservedTextureUnits);
            this.maxTextureUnits = SamplerLimits.get().getMaxTextureUnits();

            for (int unit : this.reservedTextureUnits) {
                if (unit >= this.maxTextureUnits) {
                    LOGGER.error("[Umbra] Texture unit {} is reserved but this driver only has {}; samplers allocated "
                            + "around it will be wrong", unit, this.maxTextureUnits);
                }
            }
            skipReserved();
        }

        /** {@return whether this program declares {@code name} as an active uniform} */
        public boolean hasSampler(String name) {
            return LWJGL.glGetUniformLocation(this.program, name) != -1;
        }

        /**
         * Points an externally-managed sampler at a unit this builder does not own. The unit must be reserved, because
         * the allocator must not later hand it to something else.
         */
        public void addExternalSampler(int textureUnit, String... names) {
            if (!this.reservedTextureUnits.contains(textureUnit)) {
                LOGGER.error("[Umbra] Sampler(s) {} point at unit {}, which is not reserved; the allocator may reuse it",
                        String.join("/", names), textureUnit);
            }
            for (String name : names) {
                int location = LWJGL.glGetUniformLocation(this.program, name);
                if (location != -1) {
                    this.calls.add(new Uniform1iCall(location, textureUnit));
                }
            }
        }

        /** Equivalent to {@link #addDynamicSampler(int, IntSupplier, String...)} for an ordinary 2D texture. */
        public boolean addDynamicSampler(IntSupplier texture, String... names) {
            return addDynamicSampler(GL11.GL_TEXTURE_2D, texture, names);
        }

        /**
         * Allocates one unit for {@code names}, but only if the program actually declares at least one of them.
         *
         * @return true if a unit was consumed, i.e. at least one name resolved to an active uniform
         */
        public boolean addDynamicSampler(int textureTarget, IntSupplier texture, String... names) {
            boolean used = false;
            for (String name : names) {
                int location = LWJGL.glGetUniformLocation(this.program, name);
                if (location == -1) {
                    // Not declared by this program. Costs nothing — this is the point of per-program allocation.
                    continue;
                }
                if (this.nextUnit >= this.maxTextureUnits) {
                    if (!this.exhaustedReported) {
                        LOGGER.error("[Umbra] Program ran out of texture units at '{}' (driver reports {}); this and "
                                + "any later sampler will read from unit 0", name, this.maxTextureUnits);
                        this.exhaustedReported = true;
                    }
                    return used;
                }
                this.calls.add(new Uniform1iCall(location, this.nextUnit));
                used = true;
            }

            if (!used) {
                return false;
            }

            this.samplers.add(new SamplerBinding(this.nextUnit, textureTarget, texture));
            this.nextUnit++;
            skipReserved();
            return true;
        }

        private void skipReserved() {
            while (this.nextUnit < this.maxTextureUnits && this.reservedTextureUnits.contains(this.nextUnit)) {
                this.nextUnit++;
            }
        }

        /** {@return the first unit this builder has not handed out, for callers that log the layout} */
        public int getNextUnit() {
            return this.nextUnit;
        }

        public ProgramSamplers build() {
            return new ProgramSamplers(Collections.unmodifiableList(new ArrayList<>(this.samplers)),
                    new ArrayList<>(this.calls));
        }
    }
}
