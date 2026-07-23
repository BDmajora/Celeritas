package com.bdmajora.impetus.iris.pipeline;

import com.bdmajora.impetus.iris.gl.program.GlProgram;
import com.bdmajora.impetus.iris.gl.program.ProgramBuilder;
import com.bdmajora.impetus.iris.gl.shader.GlShader;
import com.bdmajora.impetus.iris.gl.shader.ShaderType;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL13;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Final-presentation colorspace conversion (Iris parity): converts the finished sRGB frame to a wide-gamut
 * target (DCI-P3 / Display-P3 / Rec.2020 / Adobe RGB) for users on monitors configured for those spaces.
 *
 * <p>Runs as the very last step of the shader frame: the backbuffer color is copied into a scratch texture,
 * then a fullscreen quad re-renders it through a conversion shader (sRGB EOTF decode → 3×3 primaries transform
 * → target OETF encode). The matrices are standard colorimetry data (Rec.709→XYZ→target, D65). Zero cost when
 * the target is SRGB — the pass simply doesn't run.
 */
public final class ColorSpaceConverter {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    public enum ColorSpace {
        SRGB, DCI_P3, DISPLAY_P3, REC2020, ADOBE_RGB;

        public static ColorSpace byName(String name) {
            if (name == null) {
                return SRGB;
            }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return SRGB;
            }
        }
    }

    /** The user-selected output colorspace; read by the pipeline each frame. */
    private static ColorSpace current = ColorSpace.SRGB;

    private GlProgram program;
    private ColorSpace programSpace;
    private int scratchTexture = -1;
    private int scratchWidth = -1, scratchHeight = -1;
    private boolean broken;

    public static void setColorSpace(ColorSpace space) {
        current = space != null ? space : ColorSpace.SRGB;
    }

    public static ColorSpace getColorSpace() {
        return current;
    }

    public boolean isActive() {
        return current != ColorSpace.SRGB && !this.broken;
    }

    /**
     * Converts the currently bound draw framebuffer's color in place. Caller must have the presentation
     * framebuffer bound for both read and draw, with blending/depth disabled (composite-chain end state).
     */
    public void run(int width, int height, FullscreenQuadRenderer quad) {
        if (!isActive() || width <= 0 || height <= 0) {
            return;
        }

        try {
            ensureProgram();
            ensureScratch(width, height);

            LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
            int previous = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.scratchTexture);
            LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

            this.program.bind();
            quad.draw();
            this.program.unbind();

            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        } catch (Exception e) {
            // A conversion failure must never take out presentation; disable and keep rendering sRGB.
            this.broken = true;
            LOGGER.error("[Iris] Colorspace conversion disabled after error", e);
        }
    }

    public void destroy() {
        if (this.program != null) {
            this.program.destroy();
            this.program = null;
            this.programSpace = null;
        }
        if (this.scratchTexture != -1) {
            LWJGL.glDeleteTextures(this.scratchTexture);
            this.scratchTexture = -1;
        }
        this.scratchWidth = -1;
        this.scratchHeight = -1;
        this.broken = false;
    }

    private void ensureScratch(int width, int height) {
        if (this.scratchTexture != -1 && this.scratchWidth == width && this.scratchHeight == height) {
            return;
        }
        if (this.scratchTexture == -1) {
            this.scratchTexture = LWJGL.glGenTextures();
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
        int previous = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.scratchTexture);
        LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        this.scratchWidth = width;
        this.scratchHeight = height;
    }

    private void ensureProgram() {
        if (this.program != null && this.programSpace == current) {
            return;
        }
        if (this.program != null) {
            this.program.destroy();
            this.program = null;
        }

        String name = "impetus_colorspace_" + current.name().toLowerCase(Locale.ROOT);
        GlShader vertex = new GlShader(ShaderType.VERTEX, name + ".vsh", VERTEX_SOURCE);
        GlShader fragment = new GlShader(ShaderType.FRAGMENT, name + ".fsh", fragmentSource(current));
        try {
            this.program = ProgramBuilder.begin(name)
                    .attach(vertex)
                    .attach(fragment)
                    .bindAttributeLocation(FullscreenQuadRenderer.POSITION_SLOT, "a_Position")
                    .bindAttributeLocation(FullscreenQuadRenderer.TEXCOORD_SLOT, "a_TexCoord")
                    .link();
        } finally {
            vertex.destroy();
            fragment.destroy();
        }
        this.programSpace = current;

        // The scratch copy always sits on unit 0.
        this.program.bind();
        int location = this.program.getUniformLocation("u_Source");
        if (location != -1) {
            LWJGL.glUniform1i(location, 0);
        }
        this.program.unbind();
    }

    private static final String VERTEX_SOURCE =
            "#version 120\n" +
            "attribute vec2 a_Position;\n" +
            "attribute vec2 a_TexCoord;\n" +
            "varying vec2 v_Tex;\n" +
            "void main() {\n" +
            "    v_Tex = a_TexCoord;\n" +
            // The shared fullscreen quad supplies a_Position in [0,1]; map to NDC [-1,1] here.
            "    gl_Position = vec4(a_Position * 2.0 - 1.0, 0.0, 1.0);\n" +
            "}\n";

    /**
     * Standard colorimetry: linear Rec.709/sRGB → XYZ (D65) → target primaries, then the target's transfer
     * function. Matrices are the widely published CIE data for each space.
     */
    private static String fragmentSource(ColorSpace space) {
        String matrix;
        String encode;
        switch (space) {
            case DCI_P3:
                matrix = "mat3(0.8224621, 0.0331941, 0.0170827, 0.1775380, 0.9668058, 0.0723974, -0.0000001, 0.0000001, 0.9105199)";
                encode = "pow(c, vec3(1.0 / 2.6))";
                break;
            case DISPLAY_P3:
                matrix = "mat3(0.8224621, 0.0331941, 0.0170827, 0.1775380, 0.9668058, 0.0723974, -0.0000001, 0.0000001, 0.9105199)";
                encode = "linearToSrgb(c)";
                break;
            case REC2020:
                matrix = "mat3(0.6274040, 0.0690970, 0.0163916, 0.3292820, 0.9195400, 0.0880132, 0.0433136, 0.0113612, 0.8955950)";
                encode = "pow(c, vec3(1.0 / 2.4))";
                break;
            case ADOBE_RGB:
                matrix = "mat3(0.7152249, 0.0000000, 0.0000000, 0.2848247, 1.0000000, 0.0411507, 0.0000000, 0.0000000, 0.9587653)";
                encode = "pow(c, vec3(1.0 / 2.19921875))";
                break;
            default:
                matrix = "mat3(1.0)";
                encode = "linearToSrgb(c)";
                break;
        }

        return "#version 120\n" +
                "uniform sampler2D u_Source;\n" +
                "varying vec2 v_Tex;\n" +
                "vec3 srgbToLinear(vec3 c) {\n" +
                "    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));\n" +
                "}\n" +
                "vec3 linearToSrgb(vec3 c) {\n" +
                "    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));\n" +
                "}\n" +
                "void main() {\n" +
                "    vec3 srgb = texture2D(u_Source, v_Tex).rgb;\n" +
                "    vec3 c = " + matrix + " * srgbToLinear(srgb);\n" +
                "    c = clamp(c, 0.0, 1.0);\n" +
                "    gl_FragColor = vec4(" + encode + ", 1.0);\n" +
                "}\n";
    }
}
