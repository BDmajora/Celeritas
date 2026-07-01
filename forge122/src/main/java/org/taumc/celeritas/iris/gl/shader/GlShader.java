package org.taumc.celeritas.iris.gl.shader;

import org.taumc.celeritas.iris.gl.GlResource;
import org.taumc.celeritas.lwjgl.GL20;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A single compiled GLSL stage (vertex, geometry, tessellation, or fragment).
 * <p>
 * Mirrors OptiFine's {@code createVertShader}/{@code createFragShader} but goes through the Celeritas LWJGL
 * abstraction. The caller is expected to have already run the source through the {@code GlslPreprocessor} (version
 * normalisation, {@code #define} injection); this class only compiles.
 */
public class GlShader extends GlResource {
    private final String name;

    public GlShader(ShaderType type, String name, String source) {
        this.name = name;

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
