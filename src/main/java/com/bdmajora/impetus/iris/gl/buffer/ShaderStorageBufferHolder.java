package com.bdmajora.impetus.iris.gl.buffer;

import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.GLExtension;
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
    private static final int GL_MAX_SHADER_STORAGE_BLOCK_SIZE = 0x90DE;
    private static final int GL_NO_ERROR = 0;

    /** Fill descriptor for the server-side zero: one unsigned byte per byte of buffer. */
    private static final int GL_R8 = 0x8229;
    private static final int GL_RED = 0x1903;
    private static final int GL_UNSIGNED_BYTE = 0x1401;

    /**
     * Ceiling used only when the driver will not report {@link #GL_MAX_SHADER_STORAGE_BLOCK_SIZE}. This was previously
     * a hard cap on every allocation, which silently rejected buffers packs genuinely need: Complementary Reimagined
     * at {@code COLORED_LIGHTING = 512} with world-space reflections on declares
     * {@code bufferObject.0 = 810549248} (773 MiB) for its reflection face data. Skipping it left binding 0 empty
     * while the pack's shaders kept reading {@code blockDataSSBO.data[...]} for the colour, lightmap and texture
     * bounds of whatever a reflection ray hit — undefined reads that shade reflective surfaces arbitrarily bright,
     * deterministically per view direction. The symptom is every block and entity "glowing" depending on which way
     * the camera faces, and only with a pack that voxelizes for reflections.
     */
    private static final long FALLBACK_MAX_BUFFER_BYTES = 512L * 1024 * 1024;

    /** Host-staged zero-fill granularity, used only where {@code glClearBufferData} is unavailable. */
    private static final int ZERO_FILL_CHUNK_BYTES = 4 * 1024 * 1024;

    /** Capability answers, resolved once against a live context. */
    private static Boolean immutableStorageAvailable;
    private static Boolean serverSideClearAvailable;

    /** Driver's per-buffer ceiling, queried once against a live context; 0 until then. */
    private static long cachedMaxBufferBytes;

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
        this.definitions.keySet().forEach(index -> {
            Integer buffer = this.buffers.get(index);
            // A declared index whose allocation failed is bound to 0 rather than left alone, so its reads are at
            // least consistent instead of picking up whatever another pass left in that slot.
            LWJGL.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index, buffer != null ? buffer : 0);
        });
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
            long limit = maxBufferBytes();
            if (bytes <= 0 || bytes > limit) {
                // Not recoverable: the pack's shaders still declare the block and will read it regardless.
                LOGGER.error("[Iris] bufferObject.{} requests {} bytes, above this driver's per-buffer limit of {}; "
                                + "shaders reading that block will see undefined data",
                        definition.index, bytes, limit);
                continue;
            }

            Integer existing = this.buffers.remove(definition.index);
            if (existing != null) {
                LWJGL.glDeleteBuffers(existing);
            }

            int buffer = LWJGL.glGenBuffers();
            LWJGL.glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            LWJGL.glGetError(); // discard anything already pending so the check below is about this allocation
            allocateStorage(bytes);
            int error = LWJGL.glGetError();
            if (error != GL_NO_ERROR) {
                LWJGL.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
                LWJGL.glDeleteBuffers(buffer);
                LOGGER.error("[Iris] bufferObject.{} ({} bytes) failed to allocate (GL error 0x{}); the pack's "
                                + "shaders will read undefined data from that block. Lowering the pack's colored "
                                + "lighting resolution or disabling world-space reflections reduces the request.",
                        definition.index, bytes, Integer.toHexString(error));
                continue;
            }
            zeroFill(bytes);
            LWJGL.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

            this.buffers.put(definition.index, buffer);
            LOGGER.info("[Iris] Shader storage buffer {} allocated: {} bytes{}", definition.index, bytes,
                    definition.relative ? " (screen-relative)" : "");
        }

        this.width = newWidth;
        this.height = newHeight;
        bindAll();
    }

    /**
     * The driver's own ceiling for a single shader storage block, which is the only limit that actually applies.
     * Queried once against a live context and cached; {@code glGetInteger} saturates at {@link Integer#MAX_VALUE} for
     * the drivers that report a larger 64-bit value, which is well past any real pack request.
     */
    private static long maxBufferBytes() {
        if (cachedMaxBufferBytes > 0) {
            return cachedMaxBufferBytes;
        }
        LWJGL.glGetError(); // a pending error would make the query result ambiguous
        int reported = LWJGL.glGetInteger(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (LWJGL.glGetError() != GL_NO_ERROR || reported == 0) {
            cachedMaxBufferBytes = FALLBACK_MAX_BUFFER_BYTES;
            LOGGER.warn("[Iris] Driver did not report GL_MAX_SHADER_STORAGE_BLOCK_SIZE; capping buffers at {} bytes",
                    cachedMaxBufferBytes);
        } else {
            // Negative means the 64-bit limit overflowed the int query, i.e. it is at least Integer.MAX_VALUE.
            cachedMaxBufferBytes = reported < 0 ? Integer.MAX_VALUE : reported;
        }
        return cachedMaxBufferBytes;
    }

    /**
     * Allocates the currently bound shader storage buffer, preferring immutable GPU-only storage.
     * <p>
     * This is what Iris does ({@code ShaderStorageBuffer.createStatic} -&gt; {@code bufferStorage(target, size, 0)};
     * it uses {@code GL_DYNAMIC_STORAGE_BIT} only for the {@code bufferObject.<n> = <size> <file>} form that seeds a
     * buffer from a resource, which this port does not implement, so {@code 0} is right for every buffer it creates).
     * The <em>flags</em> matter as much as the immutability: {@code 0} states that the client will never map, read or
     * write this buffer, so the driver is free to place it entirely in video memory. The previous
     * {@code glBufferData(..., GL_DYNAMIC_DRAW)} said the opposite — a mutable buffer the client updates repeatedly —
     * which invites a host-visible allocation or a system-memory mirror. Complementary Reimagined declares
     * {@code bufferObject.0 = 810549248} (773 MiB) at {@code COLORED_LIGHTING = 512} with world-space reflections on,
     * so the hint is worth three quarters of a gigabyte of resident host memory that nothing on the CPU ever reads.
     * <p>
     * Immutable storage is chosen only when {@link #canClearServerSide()}, because zeroing is then the only way to
     * initialize the buffer: a {@code flags = 0} allocation rejects {@code glBufferSubData} by construction. Iris can
     * assume both entry points unconditionally; this port runs on 1.12.2 contexts that may predate either, so the two
     * capabilities are resolved together rather than independently.
     */
    private static void allocateStorage(long bytes) {
        if (immutableStorageAvailable == null) {
            immutableStorageAvailable = (LWJGL.isOpenGLVersionSupported(4, 4)
                    || LWJGL.isExtensionSupported(GLExtension.ARB_buffer_storage))
                    && canClearServerSide();
        }
        if (immutableStorageAvailable) {
            LWJGL.glBufferStorage(GL_SHADER_STORAGE_BUFFER, bytes, 0);
        } else {
            // Still not GL_DYNAMIC_DRAW: this buffer is written and read by the GPU and never by the client.
            LWJGL.glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_STATIC_COPY);
        }
    }

    /**
     * Zeroes the currently bound shader storage buffer. Freshly allocated storage is undefined and the pack only
     * writes the entries it visits, so anything it never touches has to read as zero rather than as whatever the
     * driver handed us.
     * <p>
     * {@code glClearBufferData} does this entirely server-side, which is what Iris uses
     * ({@code clearBufferSubData}). The chunked {@code glBufferSubData} fallback both costs an upload of the buffer's
     * full size and requires the buffer to be client-writable — the property that keeps a 773 MiB allocation resident
     * in host memory — so it runs only where the server-side clear does not exist, and there
     * {@link #allocateStorage} has already made the buffer mutable to match.
     */
    private static void zeroFill(long bytes) {
        if (canClearServerSide()) {
            // One texel of source data, as GL_R8/GL_RED/GL_UNSIGNED_BYTE describes it. LWJGL 2's binding calls
            // MemoryUtil.getAddress (not getAddressSafe) and BufferChecks.checkBuffer(data, 1), so unlike Iris on
            // LWJGL 3 this cannot pass null for "clear to zero" — it needs a real one-byte direct buffer.
            ByteBuffer zero = ByteBuffer.allocateDirect(1); // allocateDirect is already zeroed
            LWJGL.glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R8, GL_RED, GL_UNSIGNED_BYTE, zero);
            return;
        }
        int chunk = (int) Math.min(bytes, ZERO_FILL_CHUNK_BYTES);
        ByteBuffer zeros = ByteBuffer.allocateDirect(chunk); // allocateDirect is already zeroed
        for (long offset = 0; offset < bytes; offset += chunk) {
            zeros.clear();
            zeros.limit((int) Math.min(chunk, bytes - offset));
            LWJGL.glBufferSubData(GL_SHADER_STORAGE_BUFFER, offset, zeros);
        }
    }

    /**
     * {@code glClearBufferData} is GL 4.3 core, the same version that introduced shader storage buffers themselves, so
     * in practice any driver that can run a pack declaring {@code bufferObject} has it. It is still queried rather
     * than assumed: LWJGL 2 answers an unavailable entry point by throwing from
     * {@code BufferChecks.checkFunctionAddress}, which would turn a silently-degraded pipeline into a failed one.
     */
    private static boolean canClearServerSide() {
        if (serverSideClearAvailable == null) {
            serverSideClearAvailable = LWJGL.isOpenGLVersionSupported(4, 3)
                    || LWJGL.isExtensionSupported(GLExtension.ARB_clear_buffer_object);
        }
        return serverSideClearAvailable;
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
