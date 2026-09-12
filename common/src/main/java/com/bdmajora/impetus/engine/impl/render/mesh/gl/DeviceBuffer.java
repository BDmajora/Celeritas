package com.bdmajora.impetus.engine.impl.render.mesh.gl;

// Anything the mesh backend hands a shader as a raw GPU pointer and UploadStream can copy into; dense BindlessBuffer or on-demand SparseBindlessBuffer, so losing sparse support changes nothing above this
public interface DeviceBuffer {
    int getId();

    long getSize();

    // Address in GPU virtual memory; valid only while the buffer is resident
    long getDeviceAddress();

    void delete();
}
