package com.bdmajora.impetus.iris.gl.buffer;

import com.bdmajora.impetus.lwjgl.GL15;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Pack-declared shader storage buffers ({@code bufferObject.<index> = <byteSize>} or
 * {@code bufferObject.<index> = <elementSize> true <scaleX> <scaleY>} for screen-relative sizing in
 * {@code shaders.properties}). Buffers are zero-initialized at (re)creation, bound at their fixed binding index
 * every frame, and persist across frames — packs use them for history/accumulation data from compute passes.
 */
public final class ShaderStorageBufferHolder {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /** GL43 constants (the generated constant classes track LWJGL's; avoid a hard dependency for two values). */
    private static final int GL_SHADER_STORAGE_BUFFER = 0x90D2;

    /** Refuse absurd allocations rather than letting a typo'd pack directive OOM the GPU. */
    private static final long MAX_BUFFER_BYTES = 512L * 1024 * 1024;

    public static final class Definition {
        final int index;
        final long size;
        final boolean relative;
        final float scaleX, scaleY;

        public Definition(int index, long size, boolean relative, float scaleX, float scaleY) {
            this.index = index;
            this.size = size;
            this.relative = relative;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        long byteSize(int width, int height) {
            if (!this.relative) {
                return this.size;
            }
            long w = Math.max(1, (long) Math.ceil(width * (double) this.scaleX));
            long h = Math.max(1, (long) Math.ceil(height * (double) this.scaleY));
            return this.size * w * h;
        }
    }

    private final Map<Integer, Definition> definitions;
    private final Map<Integer, Integer> buffers = new LinkedHashMap<>();
    private int width = -1, height = -1;
    private boolean anyRelative;

    public ShaderStorageBufferHolder(Map<Integer, Definition> definitions, int width, int height) {
        this.definitions = definitions;
        for (Definition definition : definitions.values()) {
            this.anyRelative |= definition.relative;
        }
        allocate(width, height);
    }

    public boolean isEmpty() {
        return this.definitions.isEmpty();
    }

    /** {@return the GL buffer id bound at {@code index}, or -1 when the pack declared none there} */
    public int getBufferId(int index) {
        Integer buffer = this.buffers.get(index);
        return buffer != null ? buffer : -1;
    }

    /** Recreates screen-relative buffers when the display size changes; fixed-size buffers are untouched. */
    public void onResize(int newWidth, int newHeight) {
        if (!this.anyRelative || (newWidth == this.width && newHeight == this.height)) {
            this.width = newWidth;
            this.height = newHeight;
            return;
        }
        allocate(newWidth, newHeight);
    }

    /** Rebinds every buffer to its declared index (cheap; run once per frame for robustness). */
    public void bindAll() {
        this.buffers.forEach((index, buffer) ->
                LWJGL.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index, buffer));
    }

    public void destroy() {
        for (int buffer : this.buffers.values()) {
            LWJGL.glDeleteBuffers(buffer);
        }
        this.buffers.clear();
    }

    private void allocate(int newWidth, int newHeight) {
        for (Definition definition : this.definitions.values()) {
            // Fixed-size buffers survive resizes (they may hold accumulated history data).
            if (this.buffers.containsKey(definition.index) && !definition.relative) {
                continue;
            }

            long bytes = definition.byteSize(newWidth, newHeight);
            if (bytes <= 0 || bytes > MAX_BUFFER_BYTES) {
                LOGGER.warn("[Iris] bufferObject.{} requests {} bytes; skipping (limit {})",
                        definition.index, bytes, MAX_BUFFER_BYTES);
                continue;
            }

            Integer existing = this.buffers.remove(definition.index);
            if (existing != null) {
                LWJGL.glDeleteBuffers(existing);
            }

            int buffer = LWJGL.glGenBuffers();
            LWJGL.glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            // Zero-fill deterministically; glBufferData(size) alone leaves contents undefined.
            if (bytes <= Integer.MAX_VALUE) {
                ByteBuffer zeros = ByteBuffer.allocateDirect((int) bytes);
                LWJGL.glBufferData(GL_SHADER_STORAGE_BUFFER, zeros, GL15.GL_DYNAMIC_DRAW);
            } else {
                LWJGL.glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_DRAW);
            }
            LWJGL.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

            this.buffers.put(definition.index, buffer);
            LOGGER.info("[Iris] Shader storage buffer {} allocated: {} bytes{}", definition.index, bytes,
                    definition.relative ? " (screen-relative)" : "");
        }

        this.width = newWidth;
        this.height = newHeight;
        bindAll();
    }

    /** Parses every {@code bufferObject.<index> = ...} directive. */
    public static Map<Integer, Definition> parseDefinitions(Map<String, String> rawProperties) {
        Map<Integer, Definition> definitions = new LinkedHashMap<>();
        rawProperties.forEach((key, value) -> {
            if (!key.startsWith("bufferObject.")) {
                return;
            }
            try {
                int index = Integer.parseInt(key.substring("bufferObject.".length()));
                String[] parts = value.trim().split("\\s+");
                long size = Long.parseLong(parts[0]);
                boolean relative = parts.length >= 2 && Boolean.parseBoolean(parts[1]);
                float scaleX = parts.length >= 3 ? Float.parseFloat(parts[2]) : 1.0f;
                float scaleY = parts.length >= 4 ? Float.parseFloat(parts[3]) : 1.0f;
                definitions.put(index, new Definition(index, size, relative, scaleX, scaleY));
            } catch (RuntimeException e) {
                LOGGER.warn("[Iris] Malformed bufferObject directive '{} = {}': {}", key, value, e.toString());
            }
        });
        return definitions;
    }
}
