package com.bdmajora.impetus.umbra.pipeline.shadow;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

// The six clipping planes of the view frustum from a model-view-projection matrix (verbatim port of Iris's BaseClippingPlanes); a plane ax + by + cz = d is stored as (a, b, c, -d), so a dot with (x, y, z, 1) is positive inside
public final class BaseClippingPlanes {
    private final Vector4f[] planes = new Vector4f[6];

    public BaseClippingPlanes(Matrix4fc modelViewProjection) {
        // Transform = Transpose(Projection x View)
        Matrix4f transform = new Matrix4f(modelViewProjection);
        transform.transpose();

        this.planes[0] = transform(transform, -1, 0, 0);
        this.planes[1] = transform(transform, 1, 0, 0);
        this.planes[2] = transform(transform, 0, -1, 0);
        this.planes[3] = transform(transform, 0, 1, 0);
        // FAR clipping plane
        this.planes[4] = transform(transform, 0, 0, -1);
        // NEAR clipping plane
        this.planes[5] = transform(transform, 0, 0, 1);
    }

    // Multiplying the transposed MVP by an axis-aligned unit vector extracts that clip plane (Gribb-Hartmann), normalised so the dot product is a true signed DISTANCE the extrusion maths relies on
    private static Vector4f transform(Matrix4fc transform, float x, float y, float z) {
        Vector4f vector = new Vector4f(x, y, z, 1.0f);
        vector.mul(transform);
        vector.normalize();
        return vector;
    }

    // The six camera frustum planes in world space
    public Vector4f[] getPlanes() {
        return this.planes;
    }
}
