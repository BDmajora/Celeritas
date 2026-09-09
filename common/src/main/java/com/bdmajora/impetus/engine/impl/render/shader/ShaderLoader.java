package com.bdmajora.impetus.engine.impl.render.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.bdmajora.impetus.engine.impl.gl.shader.GlShader;
import com.bdmajora.impetus.engine.impl.gl.shader.ShaderConstants;
import com.bdmajora.impetus.engine.impl.gl.shader.ShaderParser;
import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;

public class ShaderLoader {
    // Compiles one of the engine's own GLSL shaders off the classpath, from /assets/{namespace}/shaders/{path}
    // constants are specialisation defines, injected immediately AFTER the version header — which is where they
    // have to go, since GLSL requires #version to be the first real token
    // Specialising this way means one source file compiles to several variants rather than the engine shipping a
    // file per combination
    public static GlShader loadShader(ShaderType type, String name, ShaderConstants constants) {
        return new GlShader(type, name, ShaderParser.parseShader(getShaderSource(name), ShaderLoader::getShaderSource, constants));
    }

    public static String getShaderSource(String name) {
        String[] splitStr;
        if(name.contains(":")) {
            splitStr = name.split(":", 2);
        } else {
            splitStr = new String[] { "minecraft", name };
        }
        String path = String.format("/assets/%s/shaders/%s", splitStr[0], splitStr[1]);

        try (InputStream in = ShaderLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + path);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source for " + path, e);
        }
    }
}
