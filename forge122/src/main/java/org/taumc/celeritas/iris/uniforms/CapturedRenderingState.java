package org.taumc.celeritas.iris.uniforms;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * A singleton snapshot of per-frame render state captured from the vanilla render loop, for uniforms that cannot be
 * derived from world state alone (camera matrices, camera position, partial ticks, the active alpha-test threshold).
 * <p>
 * The Phase 3/4 {@code EntityRenderer.renderWorld} mixin populates these each frame <em>before</em> the shader passes
 * run; the uniform providers read them. Until those hooks exist the values stay at their identity/zero defaults, which
 * is what keeps the GL foundation compilable and side-effect-free ahead of the render integration.
 */
public final class CapturedRenderingState {
    public static final CapturedRenderingState INSTANCE = new CapturedRenderingState();

    private final Matrix4f gbufferModelView = new Matrix4f();
    private final Matrix4f gbufferProjection = new Matrix4f();
    private final Vector3d cameraPosition = new Vector3d();

    private float tickDelta;
    private float currentAlphaTest;
    private int currentRenderedBlockEntity = -1;
    private int currentRenderedEntity = -1;

    private CapturedRenderingState() {
    }

    public Matrix4f getGbufferModelView() {
        return this.gbufferModelView;
    }

    public void setGbufferModelView(Matrix4f modelView) {
        this.gbufferModelView.set(modelView);
    }

    public Matrix4f getGbufferProjection() {
        return this.gbufferProjection;
    }

    public void setGbufferProjection(Matrix4f projection) {
        this.gbufferProjection.set(projection);
    }

    public Vector3d getCameraPosition() {
        return this.cameraPosition;
    }

    public void setCameraPosition(double x, double y, double z) {
        this.cameraPosition.set(x, y, z);
    }

    public float getTickDelta() {
        return this.tickDelta;
    }

    public void setTickDelta(float tickDelta) {
        this.tickDelta = tickDelta;
    }

    public float getCurrentAlphaTest() {
        return this.currentAlphaTest;
    }

    public void setCurrentAlphaTest(float currentAlphaTest) {
        this.currentAlphaTest = currentAlphaTest;
    }

    public int getCurrentRenderedBlockEntity() {
        return this.currentRenderedBlockEntity;
    }

    public void setCurrentRenderedBlockEntity(int id) {
        this.currentRenderedBlockEntity = id;
    }

    public int getCurrentRenderedEntity() {
        return this.currentRenderedEntity;
    }

    public void setCurrentRenderedEntity(int id) {
        this.currentRenderedEntity = id;
    }
}
