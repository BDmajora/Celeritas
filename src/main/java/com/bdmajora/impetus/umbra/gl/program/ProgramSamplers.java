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

// The samplers one program uses, each on a texture unit allocated for that program alone
//
// Why per-program rather than global: the scheme this replaces gave every sampler NAME one fixed global unit, with
// the full-screen layout using unit == colortex index
// That only works while the names fit in the driver's GL_MAX_TEXTURE_IMAGE_UNITS, and they do not. A pack may
// declare 32 colortex, three depthtex, seven shadow samplers, noisetex, plus its own custom textures and images —
// 49 names against 32 units
// So the fixed layout had to cap MAX_COLOR_BUFFERS at 16 where Iris allows 32, and park depthtex on 16-18, shadow
// on 19-25 and custom textures/images on 24-31 — which collide with colortex16..19 the moment that cap is raised
// The visible consequence: Complementary's deferred1 declares RENDERTARGETS: 0,5,4,19,18, so colortex18 and 19 were
// never allocated here and their attachments were silently dropped
//
// Allocating per program removes the conflict rather than rearranging it. A unit is consumed only when the program
// genuinely declares the uniform, i.e. glGetUniformLocation != -1, and no single program in a real pack comes close
// to declaring all 49 names. This is Iris's nextUnit++ model exactly
//
// Three deviations from Iris, all forced by 1.12.2
//   Binding goes through GlTextureUnits rather than a raw bind plus a GlStateManagerAccessor cache patch, because
//   1.12.2's GlStateManager.TextureState is package-private with a private constructor and the cache can only be
//   written by calling GlStateManager itself
//   Iris THROWS when a program runs out of units; this logs and drops the sampler, matching what ProgramImages
//   already does — a pack that overruns the limit should lose one effect rather than take the whole pipeline down
//   mid-frame on a machine that was otherwise rendering fine
//   No GlSampler objects at all: this pipeline predates sampler objects, and every sampler uses the texture's own
//   parameters
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

    // Must be called with this program bound. Issues the deferred unit assignments on the first call, then rebinds
    // every texture
    // The selector is restored EXACTLY ONCE, after the whole run, rather than after each bind — see
    // GlTextureUnits.bindTextureInRun. Iris does the same for the same reason: leaving the active unit moved
    // part-way through a vanilla render sequence corrupts unrelated draws
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

    // One deferred glUniform1i(location, value), issued on the first update()
    // Deferred because glUniform1i writes into the currently bound program, and nothing is bound at build time
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

        // Whether this program declares that name as an ACTIVE uniform — a sampler the GLSL compiler optimised
        // out reports false, which is correct: it needs no unit
        public boolean hasSampler(String name) {
            return LWJGL.glGetUniformLocation(this.program, name) != -1;
        }

        // Points a sampler at a unit this builder does not own — the block atlas and lightmap, which vanilla binds
        // The unit must already be reserved, or the allocator would hand the same one out again and the two
        // bindings would fight
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

        // The GL_TEXTURE_2D case of the call below, which is most of them
        public boolean addDynamicSampler(IntSupplier texture, String... names) {
            return addDynamicSampler(GL11.GL_TEXTURE_2D, texture, names);
        }

        // Allocates ONE unit shared by all the given names, and only when the program declares at least one of
        // them — the names are aliases for the same texture, e.g. colortex0 and its legacy gcolor spelling
        // Returns whether a unit was actually consumed, which is what lets callers offer every sampler blindly
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

        // The first unit not yet handed out, for callers that report the resulting layout
        public int getNextUnit() {
            return this.nextUnit;
        }

        public ProgramSamplers build() {
            return new ProgramSamplers(Collections.unmodifiableList(new ArrayList<>(this.samplers)),
                    new ArrayList<>(this.calls));
        }
    }
}
