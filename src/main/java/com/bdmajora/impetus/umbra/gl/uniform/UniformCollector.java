package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

// Where uniform registrations go. Two implementations, and the second is the reason this is an interface at all
// ProgramUniforms.Builder is the real one: it resolves GL locations and builds the objects that upload
// CustomUniformInputs implements it too, capturing the same name -> supplier pairs without any GL, so a pack's
// custom uniform expressions can reference every built-in uniform by name. Without the shared interface the whole
// registration list in CommonUniforms would have to be written out a second time and kept in step by hand
// Every method returns the collector so registrations chain
public interface UniformCollector {
    UniformCollector uniform1f(UniformUpdateFrequency frequency, String uniformName, FloatSupplier value);

    UniformCollector uniform1i(UniformUpdateFrequency frequency, String uniformName, IntSupplier value);

    UniformCollector uniform2f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2f> value);

    UniformCollector uniform2i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2i> value);

    UniformCollector uniform3f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3f> value);

    UniformCollector uniform3i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3i> value);

    UniformCollector uniform4f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4f> value);

    UniformCollector uniform4i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4i> value);

    UniformCollector uniformMatrix3(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix3fc> value);

    UniformCollector uniformMatrix(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix4fc> value);
}
