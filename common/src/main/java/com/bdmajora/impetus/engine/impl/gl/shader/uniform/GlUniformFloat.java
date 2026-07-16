package com.bdmajora.impetus.engine.impl.gl.shader.uniform;

import com.bdmajora.impetus.lwjgl.GL30;
import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;


public class GlUniformFloat extends GlUniform<Float> {
    public GlUniformFloat(int index) {
        super(index);
    }

    @Override
    public void set(Float value) {
        this.setFloat(value);
    }

    public void setFloat(float value) {
        LWJGL.glUniform1f(this.index, value);
    }
}
