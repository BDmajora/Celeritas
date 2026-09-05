package com.bdmajora.impetus.iris.gl.program;

import net.minecraft.client.Minecraft;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;
import com.bdmajora.impetus.iris.gl.uniform.FloatSupplier;
import com.bdmajora.impetus.iris.gl.uniform.FloatUniform;
import com.bdmajora.impetus.iris.gl.uniform.IntUniform;
import com.bdmajora.impetus.iris.gl.uniform.Matrix3Uniform;
import com.bdmajora.impetus.iris.gl.uniform.MatrixUniform;
import com.bdmajora.impetus.iris.gl.uniform.Uniform;
import com.bdmajora.impetus.iris.gl.uniform.UniformCollector;
import com.bdmajora.impetus.iris.gl.uniform.UniformUpdateFrequency;
import com.bdmajora.impetus.iris.gl.uniform.Vector2IntUniform;
import com.bdmajora.impetus.iris.gl.uniform.Vector2Uniform;
import com.bdmajora.impetus.iris.gl.uniform.Vector3IntUniform;
import com.bdmajora.impetus.iris.gl.uniform.Vector3Uniform;
import com.bdmajora.impetus.iris.gl.uniform.Vector4IntUniform;
import com.bdmajora.impetus.iris.gl.uniform.Vector4Uniform;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The set of uniforms bound to one GL program, plus the per-frame upload driver.
 * <p>
 * Built with {@link Builder}, which resolves each uniform's location at build time and silently drops uniforms the
 * program does not actually declare (OptiFine packs reference far more uniforms than any single program uses).
 * {@link #update()} mirrors Iris' cadence: {@code DYNAMIC} uniforms upload on every bind, {@code ONCE} uniforms upload
 * on first use, {@code PER_TICK} only when the world tick changes, and {@code PER_FRAME} only when {@code frameCounter}
 * changes. This matters for previous-frame suppliers, which intentionally advance when their per-frame uniform is
 * sampled.
 */
public class ProgramUniforms {
    /**
     * Uniforms that change per rendered object rather than per phase, and so are re-uploaded by
     * {@code IrisRenderingPipeline#refreshDynamicUniforms} from the per-object hooks. Deliberately tiny: see
     * {@link #updatePerObject()} for why this cannot just be the whole {@code DYNAMIC} set.
     */
    private static final java.util.Set<String> PER_OBJECT_UNIFORMS = new java.util.HashSet<>(java.util.Arrays.asList(
            "entityId", "blockEntityId", "currentRenderedItemId", "entityColor"));

    private final List<Uniform> dynamic;
    private final List<Uniform> once;
    private final List<Uniform> perTick;
    private final List<Uniform> perFrame;
    private final List<Uniform> perObject;
    private long lastTick = -1L;
    private int lastFrame = -1;
    private boolean firstUpdate = true;

    private ProgramUniforms(List<Uniform> dynamic, List<Uniform> once, List<Uniform> perTick, List<Uniform> perFrame,
                            List<Uniform> perObject) {
        this.dynamic = dynamic;
        this.once = once;
        this.perTick = perTick;
        this.perFrame = perFrame;
        this.perObject = perObject;
    }

    /**
     * Re-uploads only the handful of uniforms that vary per rendered object.
     * <p>
     * The per-object hooks originally called {@link #update()}, which walks the whole {@code DYNAMIC} list. That is
     * ruinous here, and not for the reason it looks: {@link IntUniform} caches its last value and skips the upload
     * when nothing changed, but {@link MatrixUniform} and {@link Matrix3Uniform} have <em>no</em> such check — they
     * invoke the supplier and call {@code glUniformMatrix*fv} unconditionally. Six matrix uniforms are registered
     * {@code DYNAMIC}, and five of their suppliers read the live fixed-function modelview, which means a direct
     * {@code ByteBuffer} allocation plus a {@code glGetFloat(GL_MODELVIEW_MATRIX)} pipeline query <em>each</em>. At
     * two calls per object across items, entities and block entities, a scene with a few thousand of them turns into
     * tens of thousands of stalling GL queries and native allocations per frame.
     * <p>
     * Those matrices genuinely are per-draw state and still need to be right, but the phase-level {@link #update()}
     * already covers them for the batch; only the ids and the hurt-flash colour actually differ object to object.
     */
    public void updatePerObject() {
        updateStage(this.perObject);
    }

    private static long currentTick() {
        return Minecraft.getMinecraft().world == null ? 0L : Minecraft.getMinecraft().world.getTotalWorldTime();
    }

    private static void updateStage(List<Uniform> uniforms) {
        for (int i = 0; i < uniforms.size(); i++) {
            uniforms.get(i).update();
        }
    }

    public void update() {
        long currentTick = currentTick();
        int currentFrame = com.bdmajora.impetus.iris.uniforms.SystemTimeUniforms.COUNTER.getFrameCounter();
        updateStage(this.dynamic);
        if (this.firstUpdate) {
            this.firstUpdate = false;
            updateStage(this.once);
            updateStage(this.perTick);
            updateStage(this.perFrame);
            this.lastTick = currentTick;
            this.lastFrame = currentFrame;
            return;
        }
        if (this.lastTick != currentTick) {
            this.lastTick = currentTick;
            updateStage(this.perTick);
        }
        if (this.lastFrame != currentFrame) {
            this.lastFrame = currentFrame;
            updateStage(this.perFrame);
        }
    }

    public static Builder builder(String name, int program) {
        return new Builder(name, program);
    }

    public static class Builder implements UniformCollector {
        private static final org.apache.logging.log4j.Logger LOGGER =
                org.apache.logging.log4j.LogManager.getLogger("Impetus/Iris");
        // GL uniform type enums (glGetActiveUniform), used for provided-vs-declared validation.
        private static final int GL_ACTIVE_UNIFORMS = 0x8B86;
        private static final int GL_FLOAT_T = 0x1406;
        private static final int GL_INT_T = 0x1404;
        private static final int GL_BOOL_T = 0x8B56;
        private static final int GL_FLOAT_VEC2_T = 0x8B50;
        private static final int GL_FLOAT_VEC3_T = 0x8B51;
        private static final int GL_FLOAT_VEC4_T = 0x8B52;
        private static final int GL_INT_VEC2_T = 0x8B53;
        private static final int GL_INT_VEC3_T = 0x8B54;
        private static final int GL_INT_VEC4_T = 0x8B55;
        private static final int GL_FLOAT_MAT3_T = 0x8B5B;
        private static final int GL_FLOAT_MAT4_T = 0x8B5C;
        private static final int GL_SAMPLER_1D_T = 0x8B5D;
        private static final int GL_SAMPLER_2D_T = 0x8B5E;
        private static final int GL_SAMPLER_3D_T = 0x8B5F;
        private static final int GL_SAMPLER_CUBE_T = 0x8B60;
        private static final int GL_SAMPLER_1D_SHADOW_T = 0x8B61;
        private static final int GL_SAMPLER_2D_SHADOW_T = 0x8B62;
        private static final int GL_UNSIGNED_INT_SAMPLER_2D_T = 0x8DD2;
        private static final int GL_UNSIGNED_INT_SAMPLER_3D_T = 0x8DD3;

        /** The GL type family a builder setter uploads with; compared against the program's declared type. */
        private enum ProvidedType {
            FLOAT, INT, VEC2, VEC2I, VEC3, VEC3I, VEC4, VEC4I, MAT3, MAT4
        }

        private static final class PendingUniform {
            final String uniformName;
            final ProvidedType provided;
            final UniformUpdateFrequency frequency;
            final Uniform uniform;
            // Scalar suppliers + location are retained so a FLOAT/INT type mismatch against the program's declared
            // type can be adapted (re-uploaded through the other family) instead of dropped. Null/-1 for non-scalars.
            final int location;
            final FloatSupplier floatSupplier;
            final IntSupplier intSupplier;

            PendingUniform(String uniformName, ProvidedType provided, UniformUpdateFrequency frequency, Uniform uniform,
                    int location, FloatSupplier floatSupplier, IntSupplier intSupplier) {
                this.uniformName = uniformName;
                this.provided = provided;
                this.frequency = frequency;
                this.uniform = uniform;
                this.location = location;
                this.floatSupplier = floatSupplier;
                this.intSupplier = intSupplier;
            }
        }

        private final String name;
        private final int program;
        /**
         * Keyed by uniform name so a later registration <em>replaces</em> an earlier one instead of stacking a second
         * provider on the same GL location. Program build sites call {@code CommonUniforms.addCommonUniforms} and then
         * {@code ActiveCustomUniforms.assignTo}, so pack-declared custom uniforms win over built-ins of the same name —
         * which is Iris's rule (it has no built-in for names packs define themselves, e.g. Sildur's
         * {@code uniform.int.framemod8 = fmod(frameCounter, 8)}). With a plain list both providers uploaded to the same
         * location every frame and the winner depended on registration order. Insertion order is preserved.
         */
        private final java.util.LinkedHashMap<String, PendingUniform> pending = new java.util.LinkedHashMap<>();

        private Builder(String name, int program) {
            this.name = name;
            this.program = program;
        }

        public String getName() {
            return this.name;
        }

        private int location(CharSequence uniformName) {
            return LWJGL.glGetUniformLocation(this.program, uniformName);
        }

        private void put(PendingUniform uniform) {
            this.pending.put(uniform.uniformName, uniform);
        }

        private void add(String uniformName, ProvidedType provided, UniformUpdateFrequency frequency, Uniform uniform) {
            put(new PendingUniform(uniformName, provided, frequency, uniform, -1, null, null));
        }

        public Builder uniform1f(UniformUpdateFrequency frequency, String uniformName, FloatSupplier value) {
            int location = location(uniformName);
            if (location != -1) {
                put(new PendingUniform(uniformName, ProvidedType.FLOAT, frequency,
                        new FloatUniform(location, value), location, value, null));
            }
            return this;
        }

        public Builder uniform2f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2f> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.VEC2, frequency, new Vector2Uniform(location, value));
            }
            return this;
        }

        public Builder uniform1i(UniformUpdateFrequency frequency, String uniformName, IntSupplier value) {
            int location = location(uniformName);
            if (location != -1) {
                put(new PendingUniform(uniformName, ProvidedType.INT, frequency,
                        new IntUniform(location, value), location, null, value));
            }
            return this;
        }

        public Builder uniform2i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2i> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.VEC2I, frequency, new Vector2IntUniform(location, value));
            }
            return this;
        }

        public Builder uniform3f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3f> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.VEC3, frequency, new Vector3Uniform(location, value));
            }
            return this;
        }

        public Builder uniform3i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3i> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.VEC3I, frequency, new Vector3IntUniform(location, value));
            }
            return this;
        }

        public Builder uniform4f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4f> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.VEC4, frequency, new Vector4Uniform(location, value));
            }
            return this;
        }

        public Builder uniform4i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4i> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.VEC4I, frequency, new Vector4IntUniform(location, value));
            }
            return this;
        }

        public Builder uniformMatrix3(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix3fc> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.MAT3, frequency, new Matrix3Uniform(location, value));
            }
            return this;
        }

        public Builder uniformMatrix(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix4fc> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(uniformName, ProvidedType.MAT4, frequency, new MatrixUniform(location, value));
            }
            return this;
        }

        /** The provider family a declared GL type needs, or {@code null} for types we cannot supply. Iris parity. */
        private static ProvidedType expectedType(int glType) {
            switch (glType) {
                case GL_FLOAT_T:
                    return ProvidedType.FLOAT;
                case GL_INT_T:
                case GL_BOOL_T:
                case GL_SAMPLER_1D_T:
                case GL_SAMPLER_2D_T:
                case GL_SAMPLER_3D_T:
                case GL_SAMPLER_CUBE_T:
                case GL_SAMPLER_1D_SHADOW_T:
                case GL_SAMPLER_2D_SHADOW_T:
                case GL_UNSIGNED_INT_SAMPLER_2D_T:
                case GL_UNSIGNED_INT_SAMPLER_3D_T:
                    return ProvidedType.INT;
                case GL_FLOAT_VEC2_T:
                    return ProvidedType.VEC2;
                case GL_INT_VEC2_T:
                    return ProvidedType.VEC2I;
                case GL_FLOAT_VEC3_T:
                    return ProvidedType.VEC3;
                case GL_INT_VEC3_T:
                    return ProvidedType.VEC3I;
                case GL_FLOAT_VEC4_T:
                    return ProvidedType.VEC4;
                case GL_INT_VEC4_T:
                    return ProvidedType.VEC4I;
                case GL_FLOAT_MAT3_T:
                    return ProvidedType.MAT3;
                case GL_FLOAT_MAT4_T:
                    return ProvidedType.MAT4;
                default:
                    return null;
            }
        }

        /**
         * Uploading through the wrong glUniform* family (e.g. glUniform1i to a {@code uniform float}) raises
         * GL_INVALID_OPERATION on EVERY upload — the "1282 @ Post render" spam — because packs disagree about the
         * declared types of OptiFine uniforms (int vs float worldTime/isEyeInWater/...). Iris parity
         * (ProgramUniforms.buildUniforms): read every active uniform's declared type and disable, with a log line,
         * any uniform whose provider family doesn't match.
         */
        private java.util.Map<String, ProvidedType> declaredTypes() {
            java.util.Map<String, ProvidedType> declared = new java.util.HashMap<>();
            int activeUniforms = LWJGL.glGetProgrami(this.program, GL_ACTIVE_UNIFORMS);
            java.nio.IntBuffer sizeType = java.nio.ByteBuffer.allocateDirect(8)
                    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            for (int index = 0; index < activeUniforms; index++) {
                String uniformName = LWJGL.glGetActiveUniform(this.program, index, 256, sizeType);
                if (uniformName == null || uniformName.isEmpty()) {
                    continue;
                }
                if (uniformName.endsWith("[0]")) {
                    uniformName = uniformName.substring(0, uniformName.length() - 3);
                }
                declared.put(uniformName, expectedType(sizeType.get(1)));
            }
            return declared;
        }

        public ProgramUniforms buildUniforms() {
            java.util.Map<String, ProvidedType> declared = declaredTypes();
            List<Uniform> dynamic = new ArrayList<>();
            List<Uniform> once = new ArrayList<>();
            List<Uniform> perTick = new ArrayList<>();
            List<Uniform> perFrame = new ArrayList<>();
            List<Uniform> perObject = new ArrayList<>();
            for (PendingUniform entry : this.pending.values()) {
                Uniform uniform = entry.uniform;
                ProvidedType declaredType = declared.get(entry.uniformName);
                if (declared.containsKey(entry.uniformName) && declaredType != entry.provided) {
                    // Packs disagree on whether OptiFine scalars (framemod8, worldTime, isEyeInWater, ...) are int
                    // or float. Rather than drop the uniform on a scalar int<->float mismatch — which leaves the
                    // program reading GLSL's default 0 (e.g. Sildur declares `uniform int framemod8`, breaking TAA
                    // jitter) — re-upload through the family the program actually declares.
                    if (entry.provided == ProvidedType.FLOAT && declaredType == ProvidedType.INT
                            && entry.floatSupplier != null) {
                        FloatSupplier fs = entry.floatSupplier;
                        uniform = new IntUniform(entry.location, () -> Math.round(fs.getAsFloat()));
                    } else if (entry.provided == ProvidedType.INT && declaredType == ProvidedType.FLOAT
                            && entry.intSupplier != null) {
                        IntSupplier is = entry.intSupplier;
                        uniform = new FloatUniform(entry.location, () -> (float) is.getAsInt());
                    } else {
                        LOGGER.warn("[{}] Wrong uniform type for {}: providing {} but the program declares a different"
                                        + " type. Disabling that uniform.",
                                this.name, entry.uniformName, entry.provided);
                        continue;
                    }
                }
                if (entry.frequency == UniformUpdateFrequency.DYNAMIC) {
                    dynamic.add(uniform);
                } else if (entry.frequency == UniformUpdateFrequency.ONCE) {
                    once.add(uniform);
                } else if (entry.frequency == UniformUpdateFrequency.PER_TICK) {
                    perTick.add(uniform);
                } else {
                    perFrame.add(uniform);
                }
                // Also indexed separately, staying in its frequency list above: the per-object hooks re-upload just
                // these between draws, while the phase-level update still covers them with everything else.
                if (PER_OBJECT_UNIFORMS.contains(entry.uniformName)) {
                    perObject.add(uniform);
                }
            }
            return new ProgramUniforms(dynamic, once, perTick, perFrame, perObject);
        }
    }
}
