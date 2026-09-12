package com.bdmajora.impetus.engine.impl.gl.functions;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

import com.bdmajora.impetus.engine.impl.gl.buffer.GlBufferStorageFlags;
import com.bdmajora.impetus.engine.impl.gl.buffer.GlBufferTarget;
import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import com.bdmajora.impetus.engine.impl.gl.util.EnumBitField;
import com.bdmajora.impetus.lwjgl.GLExtension;

public enum BufferStorageFunctions {
    NONE {
        // Implementation for this GL level
        @Override
        public void createBufferStorage(GlBufferTarget target, long length, EnumBitField<GlBufferStorageFlags> flags) {
            throw new UnsupportedOperationException();
        }
    },
    CORE {
        // Implementation for this GL level
        @Override
        public void createBufferStorage(GlBufferTarget target, long length, EnumBitField<GlBufferStorageFlags> flags) {
            LWJGL.glBufferStorage(target.getTargetParameter(), length, flags.getBitField());
        }
    },
    ARB {
        // Implementation for this GL level
        @Override
        public void createBufferStorage(GlBufferTarget target, long length, EnumBitField<GlBufferStorageFlags> flags) {
            LWJGL.glBufferStorage(target.getTargetParameter(), length, flags.getBitField());
        }
    };

    // Core 4.4, then ARB, then none
    public static BufferStorageFunctions pickBest(RenderDevice device) {
        if (LWJGL.isOpenGLVersionSupported(4, 4)) {
            return CORE;
        } else if (LWJGL.isExtensionSupported(GLExtension.ARB_buffer_storage)) {
            return ARB;
        } else {
            return NONE;
        }
    }


    public abstract void createBufferStorage(GlBufferTarget target, long length, EnumBitField<GlBufferStorageFlags> flags);
}
