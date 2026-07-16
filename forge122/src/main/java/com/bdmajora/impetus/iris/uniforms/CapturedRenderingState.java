package com.bdmajora.impetus.iris.uniforms;

import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;

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
    private final Vector3f fogColor = new Vector3f();
    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();
    private final Vector2i atlasSize = new Vector2i();

    private float tickDelta;
    /** Iris {@code renderStage} uniform value: the current WorldRenderingPhase ordinal (MC_RENDER_STAGE_*). */
    private int renderStage;
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

    public Matrix4f getShadowModelView() {
        return this.shadowModelView;
    }

    public void setShadowModelView(Matrix4f modelView) {
        this.shadowModelView.set(modelView);
    }

    public Matrix4f getShadowProjection() {
        return this.shadowProjection;
    }

    public void setShadowProjection(Matrix4f projection) {
        this.shadowProjection.set(projection);
    }

    public Vector2i getAtlasSize() {
        return this.atlasSize;
    }

    public void setAtlasSize(int width, int height) {
        this.atlasSize.set(width, height);
    }

    public Vector3f getFogColor() {
        return this.fogColor;
    }

    public void setFogColor(float red, float green, float blue) {
        this.fogColor.set(red, green, blue);
    }

    public int getRenderStage() {
        return this.renderStage;
    }

    public void setRenderStage(int renderStage) {
        this.renderStage = renderStage;
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
