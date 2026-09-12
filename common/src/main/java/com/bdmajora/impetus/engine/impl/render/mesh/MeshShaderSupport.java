package com.bdmajora.impetus.engine.impl.render.mesh;

import com.bdmajora.impetus.engine.impl.compat.environment.OsKind;
import com.bdmajora.impetus.lwjgl.GLExtension;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The single gate for the mesh-shader terrain backend
// Probed once, on the render thread, after a GL context exists — every other class in this package assumes the
// probe already passed, which is why none of them re-check anything
public final class MeshShaderSupport {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/MeshBackend");

    // The whole set has to be present; there is no partial path. Turing and newer NVIDIA hardware has all of it,
    // and nothing else has NV_mesh_shader at all
    private static final GLExtension[] REQUIRED = {
            GLExtension.NV_mesh_shader,
            GLExtension.NV_shader_buffer_load,
            GLExtension.NV_vertex_buffer_unified_memory,
            GLExtension.NV_uniform_buffer_unified_memory,
            GLExtension.NV_representative_fragment_test,
            GLExtension.NV_bindless_multi_draw_indirect,
            GLExtension.NV_gpu_shader5,
            GLExtension.NV_fragment_shader_barycentric,
            GLExtension.ARB_direct_state_access
    };

    private static Boolean supported;
    private static String unsupportedReason = "not probed";
    private static boolean sparseGeometry;

    private MeshShaderSupport() {}

    // Probed once and cached
    public static boolean isSupported() {
        if (supported == null) {
            probe();
        }
        return supported;
    }

    // Why the backend is unavailable, for the F3 overlay and the log; empty once isSupported() is true
    public static String getUnsupportedReason() {
        isSupported();
        return supported ? "" : unsupportedReason;
    }

    // Whether geometry can live in one enormous sparse buffer rather than a fixed dense allocation
    // Separate from isSupported because losing it costs VRAM, not correctness
    public static boolean supportsSparseGeometry() {
        isSupported();
        return sparseGeometry;
    }

    // Checks the extensions and a minimal compile, since some drivers advertise but fail
    private static void probe() {
        List<String> missing = new ArrayList<>();

        for (GLExtension extension : REQUIRED) {
            if (!LWJGL.isExtensionSupported(extension)) {
                missing.add(extension.name());
            }
        }

        // The unified-memory client states are fixed-function calls, so a core-profile context cannot bind the
        // scene uniform or the indirect command buffer by address at all
        if (!LWJGL.isExtensionSupported(GLExtension.ARB_compatibility)) {
            missing.add("ARB_compatibility");
        }

        if (!missing.isEmpty()) {
            supported = false;
            unsupportedReason = "missing " + String.join(", ", missing);
            LOGGER.info("Mesh-shader terrain backend unavailable: {}", unsupportedReason);
            return;
        }

        supported = true;
        unsupportedReason = "";

        // Page commitment on the NVIDIA Linux driver does not reliably release physical memory, so the sparse
        // 80 GB address space degenerates into an ever-growing resident allocation
        // Nvidium hits the same wall and falls back the same way: a fixed dense buffer, more VRAM, correct results
        sparseGeometry = LWJGL.isExtensionSupported(GLExtension.ARB_sparse_buffer) && OsKind.current() != OsKind.LINUX;

        LOGGER.info("Mesh-shader terrain backend available (sparse geometry: {})", sparseGeometry);
    }
}
