package com.bdmajora.impetus.iris.gl.shader;

import com.bdmajora.impetus.iris.gl.GlResource;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.lwjgl.GL20;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A single compiled GLSL stage (vertex, geometry, tessellation, or fragment).
 * <p>
 * Mirrors OptiFine's {@code createVertShader}/{@code createFragShader} but goes through the Impetus LWJGL
 * abstraction. The caller is expected to have already run the source through the {@code GlslPreprocessor} (version
 * normalisation, {@code #define} injection); this class only compiles.
 */
public class GlShader extends GlResource {
    private final String name;

    public GlShader(ShaderType type, String name, String source) {
        this.name = name;

        // Strict-driver rewrites for the paths that reach the driver through THIS class: gbuffer programs, the
        // fullscreen composite/deferred/final chain, and compute. The terrain/shadow override does not — it builds
        // the engine's GlShader instead — so it calls finalizeForDriver itself. See that method's note.
        source = GlslPreprocessor.finalizeForDriver(name, source);

        int handle = LWJGL.glCreateShader(type.id);
        if (handle == 0) {
            throw new ShaderCompileException(name, "glCreateShader returned 0 (driver could not allocate a shader object)");
        }

        // glShaderSourceSafe works around an AMD driver bug; see LWJGLService#glShaderSourceSafe.
        LWJGL.glShaderSourceSafe(handle, source);
        LWJGL.glCompileShader(handle);

        String log = LWJGL.glGetShaderInfoLog(handle, 32768);
        int status = LWJGL.glGetShaderi(handle, GL20.GL_COMPILE_STATUS);

        if (status != GL20.GL_TRUE) {
            LWJGL.glDeleteShader(handle);
            throw new ShaderCompileException(name, log.isEmpty() ? "(no info log)" : log.trim());
        }

        setHandle(handle);
    }

    public String getName() {
        return this.name;
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteShader(getGlId());
    }
}
