package com.bdmajora.impetus.engine.impl.gl.tessellation;

import com.bdmajora.impetus.engine.impl.gl.array.GlVertexArray;
import com.bdmajora.impetus.engine.impl.gl.device.CommandList;

public class GlVertexArrayTessellation extends GlAbstractTessellation {
    private final GlVertexArray array;

    public GlVertexArrayTessellation(GlVertexArray array, TessellationBinding[] bindings) {
        super(bindings);

        this.array = array;
    }

    // Creates the VAO and records the attribute bindings into it once
    public void init(CommandList commandList) {
        this.bind(commandList);
        this.bindAttributes(commandList);
        this.unbind(commandList);
    }

    // Frees the VAO
    @Override
    public void delete(CommandList commandList) {
        commandList.deleteVertexArray(this.array);
    }

    // One VAO bind replaces every per-draw attribute call
    @Override
    public void bind(CommandList commandList) {
        commandList.bindVertexArray(this.array);
    }

    // Unbinds the VAO
    @Override
    public void unbind(CommandList commandList) {
        commandList.unbindVertexArray();
    }
}
