package com.bdmajora.impetus.lwjgl;

// The NV and ARB enum values the mesh-shader terrain backend needs
// The GL11..GL46 wrapper classes next to this one are generated from LWJGL's core GL classes, so they carry no
// extension enums at all; these are transcribed from the extension specs instead
// Values only — the entry points themselves live on LWJGLService, because they have to be dispatched through the
// LWJGL2/LWJGL3 split like everything else
public final class GLNv {
    private GLNv() {}

    // ---- NV_mesh_shader ----
    public static final int GL_MESH_SHADER_NV = 0x9559;
    public static final int GL_TASK_SHADER_NV = 0x955A;

    // ---- NV_shader_buffer_load ----
    public static final int GL_BUFFER_GPU_ADDRESS_NV = 0x8F1D;
    // Barrier bit for writes made through a resident buffer pointer, as opposed to a bound SSBO
    public static final int GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV = 0x00000010;

    // ---- NV_vertex_buffer_unified_memory ----
    public static final int GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV = 0x8F1E;
    public static final int GL_ELEMENT_ARRAY_UNIFIED_NV = 0x8F1F;

    // ---- NV_uniform_buffer_unified_memory ----
    public static final int GL_UNIFORM_BUFFER_UNIFIED_NV = 0x936E;
    public static final int GL_UNIFORM_BUFFER_ADDRESS_NV = 0x936F;

    // ---- NV_bindless_multi_draw_indirect ----
    // Not in a header LWJGL exposes as constants on the extension class, so spelled out here
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    // ---- NV_representative_fragment_test ----
    public static final int GL_REPRESENTATIVE_FRAGMENT_TEST_NV = 0x937F;

    // ---- ARB_sparse_buffer ----
    public static final int GL_SPARSE_STORAGE_BIT_ARB = 0x0400;
}
