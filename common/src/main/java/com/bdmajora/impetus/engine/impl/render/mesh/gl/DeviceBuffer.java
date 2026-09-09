package com.bdmajora.impetus.engine.impl.render.mesh.gl;

// Anything the mesh backend can hand a shader as a raw GPU pointer, and anything UploadStream can copy into
// Two implementations: a dense BindlessBuffer, and a SparseBindlessBuffer whose pages are committed on demand
// The pipeline only ever sees this interface, so losing sparse support on a driver that mishandles page
// commitment changes nothing above this line
public interface DeviceBuffer {
    int getId();

    long getSize();

    // Address in GPU virtual memory; valid only while the buffer is resident
    long getDeviceAddress();

    void delete();
}
