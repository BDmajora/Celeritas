package com.bdmajora.impetus.umbra.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

// Camera-position tracking, matching modern Iris's precision contract
// Packs get a BOUNDED float cameraPosition plus the exact integer and fractional parts of the unshifted position.
// The split exists because a float loses sub-block precision tens of thousands of blocks out, and packs that
// recombine the two halves keep full precision anywhere in the world
public final class CameraUniforms {
    private static final CameraPositionTracker TRACKER = new CameraPositionTracker();

    private CameraUniforms() {
    }

    public static void attach(FrameUpdateNotifier notifier) {
        notifier.addListener(TRACKER::update);
    }

    public static Vector3d getCurrentCameraPosition() {
        return TRACKER.getCurrentCameraPosition();
    }

    public static Vector3d getPreviousCameraPosition() {
        return TRACKER.getPreviousCameraPosition();
    }

    public static Vector3d getCurrentCameraPositionUnshifted() {
        return TRACKER.getCurrentCameraPositionUnshifted();
    }

    public static Vector3d getPreviousCameraPositionUnshifted() {
        return TRACKER.getPreviousCameraPositionUnshifted();
    }

    public static Vector3i getCameraPositionInt(Vector3d originalPos) {
        return new Vector3i(
                (int) Math.floor(originalPos.x),
                (int) Math.floor(originalPos.y),
                (int) Math.floor(originalPos.z));
    }

    public static Vector3f getCameraPositionFract(Vector3d originalPos) {
        return new Vector3f(
                (float) (originalPos.x - Math.floor(originalPos.x)),
                (float) (originalPos.y - Math.floor(originalPos.y)),
                (float) (originalPos.z - Math.floor(originalPos.z)));
    }

    private static Vector3d getUnshiftedCameraPosition() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        if (camera == null) {
            return new Vector3d();
        }
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
        return new Vector3d(
                camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * tickDelta,
                camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * tickDelta,
                camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * tickDelta);
    }

    private static final class CameraPositionTracker {
        // Iris's camera shift policy: the shader-facing float position is kept inside this range so it never
        // grows large enough to lose precision, and the shift is applied to the current AND previous position
        // together so the delta packs use for motion vectors is unaffected by the shift
        private static final double WALK_RANGE = 30000.0;
        private static final double TP_RANGE = 1000.0;

        private final Vector3d shift = new Vector3d();
        private Vector3d previousCameraPosition = new Vector3d();
        private Vector3d currentCameraPosition = new Vector3d();
        private Vector3d previousCameraPositionUnshifted = new Vector3d();
        private Vector3d currentCameraPositionUnshifted = new Vector3d();

        private static double getShift(double value, double prevValue) {
            if (Math.abs(value) > WALK_RANGE || Math.abs(value - prevValue) > TP_RANGE) {
                return -(value - (value % WALK_RANGE));
            }
            return 0.0;
        }

        private void update() {
            this.previousCameraPosition = this.currentCameraPosition;
            this.previousCameraPositionUnshifted = this.currentCameraPositionUnshifted;
            this.currentCameraPositionUnshifted = getUnshiftedCameraPosition();
            this.currentCameraPosition = new Vector3d(this.currentCameraPositionUnshifted).add(this.shift);
            updateShift();
        }

        private void updateShift() {
            double dX = getShift(this.currentCameraPosition.x, this.previousCameraPosition.x);
            double dZ = getShift(this.currentCameraPosition.z, this.previousCameraPosition.z);
            if (dX != 0.0 || dZ != 0.0) {
                applyShift(dX, dZ);
            }
        }

        private void applyShift(double dX, double dZ) {
            this.shift.x += dX;
            this.currentCameraPosition.x += dX;
            this.previousCameraPosition.x += dX;
            this.shift.z += dZ;
            this.currentCameraPosition.z += dZ;
            this.previousCameraPosition.z += dZ;
        }

        private Vector3d getCurrentCameraPosition() {
            return this.currentCameraPosition;
        }

        private Vector3d getPreviousCameraPosition() {
            return this.previousCameraPosition;
        }

        private Vector3d getCurrentCameraPositionUnshifted() {
            return this.currentCameraPositionUnshifted;
        }

        private Vector3d getPreviousCameraPositionUnshifted() {
            return this.previousCameraPositionUnshifted;
        }
    }
}
