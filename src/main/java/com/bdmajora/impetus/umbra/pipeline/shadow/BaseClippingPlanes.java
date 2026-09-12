package com.bdmajora.impetus.umbra.pipeline.shadow;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

// The six clipping planes of the player's view frustum, pulled out of a model-view-projection matrix
// Verbatim port of Iris's shadows.frustum.advanced.BaseClippingPlanes
// A plane ax + by + cz = d is stored as the 4-vector (a, b, c, -d), which makes testing a point a plain dot
// product against (x, y, z, 1): positive is inside, negative is outside
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

    // Multiplying the transposed MVP by an axis-aligned unit vector extracts that clip plane in world space; the
    // standard Gribb-Hartmann row combination, expressed as one multiply because the matrix is already transposed
    // Normalised so the dot product yields a true signed DISTANCE, which the extrusion maths downstream relies on
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
