package com.bdmajora.impetus.umbra.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

// A shadow frustum fitted to the view, seen from the light: keeps the view frustum's back planes and extrudes
// edge planes along the light vector. View-direction dependent, so unsafe for voxelising packs; see ShadowFrustums
// Port of Iris's AdvancedShadowCullingFrustum, after L. Spiro
public class AdvancedShadowCullingFrustum implements Frustum {
    private static final int MAX_CLIPPING_PLANES = 13;
    // Values chosen to match JOML's FrustumIntersection constants, so ported code reads the same — this port does
    // not otherwise depend on that class
    public static final int OUTSIDE = -1;
    public static final int INSIDE = -2;
    public static final int INTERSECT = -3;

    protected final ShadowBoxCuller boxCuller;
    // Each plane stored as (a, b, c, -d), the same packing BaseClippingPlanes produces, so testing a point is one
    // dot product against (x, y, z, 1)
    private final float[][] planes = new float[MAX_CLIPPING_PLANES][4];
    private final Vector3f shadowLightVectorFromOrigin;
    private int planeCount;

    public AdvancedShadowCullingFrustum(Matrix4fc modelViewProjection, Vector3f shadowLightVectorFromOrigin,
                                        ShadowBoxCuller boxCuller) {
        this.shadowLightVectorFromOrigin = shadowLightVectorFromOrigin;
        this.boxCuller = boxCuller;

        BaseClippingPlanes baseClippingPlanes = new BaseClippingPlanes(modelViewProjection);
        boolean[] isBack = addBackPlanes(baseClippingPlanes);
        addEdgePlanes(baseClippingPlanes, isBack);
    }

    // Appends one culling plane
    private void addPlane(float[] plane) {
        if (this.planeCount >= MAX_CLIPPING_PLANES) {
            return;
        }
        this.planes[this.planeCount] = plane;
        this.planeCount += 1;
    }

    // Adds the view frustum's back planes as seen from the shadow light
    // "Back" means the plane's normal points in the same general direction as the vector from the origin toward the
    // light, i.e. the dot product is >= 0 — those are the planes light travels through on its way to the scene
    // Returns which planes were kept, because the edge extrusion below needs to know where back meets front
    private boolean[] addBackPlanes(BaseClippingPlanes baseClippingPlanes) {
        Vector4f[] planes = baseClippingPlanes.getPlanes();
        boolean[] isBack = new boolean[planes.length];

        for (int planeIndex = 0; planeIndex < planes.length; planeIndex++) {
            Vector4f plane = planes[planeIndex];
            Vector3f planeNormal = truncate(plane);

            float dot = planeNormal.dot(this.shadowLightVectorFromOrigin);
            boolean back = dot > 0.0f;
            boolean edge = dot == 0.0f;

            isBack[planeIndex] = back;

            if (back || edge) {
                addPlane(new float[]{plane.x, plane.y, plane.z, plane.w});
            }
        }

        return isBack;
    }

    // Closes the volume by extruding a new plane along the light vector wherever a back plane meets a front plane
    // Without this the kept back planes form an open volume that extends forever away from the light, so nothing
    // would ever be culled
    private void addEdgePlanes(BaseClippingPlanes baseClippingPlanes, boolean[] isBack) {
        Vector4f[] planes = baseClippingPlanes.getPlanes();

        for (int planeIndex = 0; planeIndex < planes.length; planeIndex++) {
            if (!isBack[planeIndex]) {
                continue;
            }
            Vector4f plane = planes[planeIndex];
            NeighboringPlaneSet neighbors = NeighboringPlaneSet.forPlane(planeIndex);

            if (!isBack[neighbors.plane0()]) {
                addEdgePlane(plane, planes[neighbors.plane0()]);
            }
            if (!isBack[neighbors.plane1()]) {
                addEdgePlane(plane, planes[neighbors.plane1()]);
            }
            if (!isBack[neighbors.plane2()]) {
                addEdgePlane(plane, planes[neighbors.plane2()]);
            }
            if (!isBack[neighbors.plane3()]) {
                addEdgePlane(plane, planes[neighbors.plane3()]);
            }
        }
    }

    // Drops w
    private static Vector3f truncate(Vector4f base) {
        return new Vector3f(base.x(), base.y(), base.z());
    }

    // Avoids the sqrt when only comparing
    private static float lengthSquared(Vector3f v) {
        return v.x() * v.x() + v.y() * v.y() + v.z() * v.z();
    }

    // Cross product into a fresh vector
    private static Vector3f cross(Vector3f first, Vector3f second) {
        return new Vector3f(first.x(), first.y(), first.z()).cross(second);
    }

    // A plane through a silhouette edge of the camera frustum, extruded along the light
    private void addEdgePlane(Vector4f backPlane4, Vector4f frontPlane4) {
        Vector3f backPlaneNormal = truncate(backPlane4);
        Vector3f frontPlaneNormal = truncate(frontPlane4);

        // Vector along the intersection line of the two planes.
        Vector3f intersection = cross(backPlaneNormal, frontPlaneNormal);

        // The edge plane's normal must be perpendicular to the light vector — that is what makes it an edge plane.
        Vector3f edgePlaneNormal = cross(intersection, this.shadowLightVectorFromOrigin);

        // Pick a point on the intersection line so the plane's distance term can be solved for.
        Vector3f ixb = cross(intersection, backPlaneNormal);
        Vector3f fxi = cross(frontPlaneNormal, intersection);
        ixb.mul(-frontPlane4.w());
        fxi.mul(-backPlane4.w());
        ixb.add(fxi);

        float lengthSq = lengthSquared(intersection);
        if (lengthSq == 0.0f) {
            // Parallel planes have no intersection line; nothing to extrude.
            return;
        }
        Vector3f point = ixb.mul(1.0f / lengthSq);

        // dot(normal, (x,y,z) - point) = 0  ->  d = dot(normal, point), and the stored term is -d.
        float w = -edgePlaneNormal.dot(point);
        addPlane(new float[]{edgePlaneNormal.x(), edgePlaneNormal.y(), edgePlaneNormal.z(), w});
    }

    // Classifies a CAMERA-RELATIVE box against the volume as OUTSIDE, INSIDE or INTERSECT
    // Three-way rather than a boolean because SafeZoneCullingFrustum needs to distinguish fully-inside from
    // straddling, and the engine's own test only needs "not outside"
    protected int checkCornerVisibility(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        boolean inside = true;

        for (int i = 0; i < this.planeCount; ++i) {
            float[] plane = this.planes[i];

            float outsideBoundX = (plane[0] < 0) ? minX : maxX;
            float outsideBoundY = (plane[1] < 0) ? minY : maxY;
            float outsideBoundZ = (plane[2] < 0) ? minZ : maxZ;

            if (plane[0] * outsideBoundX + plane[1] * outsideBoundY + plane[2] * outsideBoundZ >= -plane[3]) {
                inside &= plane[0] * (plane[0] < 0 ? maxX : minX)
                        + plane[1] * (plane[1] < 0 ? maxY : minY)
                        + plane[2] * (plane[2] < 0 ? maxZ : minZ) + plane[3] >= 0;
            } else {
                return OUTSIDE;
            }
        }

        return inside ? INSIDE : INTERSECT;
    }

    // Box against every plane; inside if no plane rejects it
    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (this.boxCuller != null && !this.boxCuller.testAab(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        return checkCornerVisibility(minX, minY, minZ, maxX, maxY, maxZ) != OUTSIDE;
    }
}
