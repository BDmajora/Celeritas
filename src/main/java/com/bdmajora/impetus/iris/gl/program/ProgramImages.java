package com.bdmajora.impetus.iris.gl.program;

import com.bdmajora.impetus.iris.gl.image.ImageBinding;
import com.bdmajora.impetus.iris.gl.image.ImageHolder;
import com.bdmajora.impetus.iris.gl.image.ImageLimits;
import com.github.bsideup.jabel.Desugar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The images one program uses, each on a unit allocated for that program alone (Iris {@code gl/program/ProgramImages}).
 * <p>
 * This is the per-program half of Iris's allocation model. A unit is consumed only when the program genuinely
 * declares the uniform — {@code glGetUniformLocation != -1} — so a program that never mentions
 * {@code floodfill_img} spends nothing on it. The older scheme in
 * {@link com.bdmajora.impetus.iris.pipeline.CustomImageManager} assigned one fixed global unit per declared image and
 * bound all of them for every program, which works but scales with the pack's declarations rather than with what any
 * individual program needs.
 * <p>
 * The {@code glUniform1i} calls that point each sampler-style image uniform at its unit are deferred to the first
 * {@link #update()} because they require the program to be bound, which it is not at build time.
 */
public class ProgramImages {
    private final List<ImageBinding> imageBindings;
    private List<Uniform1iCall> initializer;

    private ProgramImages(List<ImageBinding> imageBindings, List<Uniform1iCall> initializer) {
        this.imageBindings = imageBindings;
        this.initializer = initializer;
    }

    public static Builder builder(int program) {
        return new Builder(program);
    }

    /** Call with this program bound. Assigns the unit uniforms once, then rebinds every image. */
    public void update() {
        if (this.initializer != null) {
            for (Uniform1iCall call : this.initializer) {
                LWJGL.glUniform1i(call.location(), call.value());
            }
            this.initializer = null;
        }

        for (ImageBinding binding : this.imageBindings) {
            binding.update();
        }
    }

    public int getActiveImages() {
        return this.imageBindings.size();
    }

    /** A deferred {@code glUniform1i(location, value)}, issued on the first {@link #update()}. */
    @Desugar
    private record Uniform1iCall(int location, int value) {
    }

    public static final class Builder implements ImageHolder {
        private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

        private final int program;
        private final List<ImageBinding> images = new ArrayList<>();
        private final List<Uniform1iCall> calls = new ArrayList<>();
        private final int maxImageUnits;
        private int nextImageUnit;

        private Builder(int program) {
            this.program = program;
            this.maxImageUnits = ImageLimits.get().getMaxImageUnits();
        }

        @Override
        public boolean hasImage(String name) {
            return LWJGL.glGetUniformLocation(this.program, name) != -1;
        }

        @Override
        public void addTextureImage(IntSupplier textureID, int internalFormat, String name) {
            int location = LWJGL.glGetUniformLocation(this.program, name);
            if (location == -1) {
                // This program does not use this image. Costs nothing — this is the point of per-program allocation.
                return;
            }

            if (this.nextImageUnit >= this.maxImageUnits) {
                // Iris throws here. This port logs and drops instead: a pack that overruns the limit should lose one
                // effect, not take the whole pipeline down mid-frame on a machine that was otherwise rendering.
                LOGGER.error("[Iris] No image units left for '{}' (driver reports {}); it will be unbound",
                        name, this.maxImageUnits);
                return;
            }

            this.images.add(new ImageBinding(this.nextImageUnit, internalFormat, textureID));
            this.calls.add(new Uniform1iCall(location, this.nextImageUnit));
            this.nextImageUnit++;
        }

        public ProgramImages build() {
            return new ProgramImages(Collections.unmodifiableList(new ArrayList<>(this.images)),
                    new ArrayList<>(this.calls));
        }
    }
}
