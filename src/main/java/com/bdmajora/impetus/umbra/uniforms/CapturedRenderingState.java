package com.bdmajora.impetus.umbra.uniforms;

import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

// A singleton snapshot of per-frame render state captured out of the vanilla render loop
// It exists for the uniforms that cannot be derived from world state at all: the camera matrices and position,
// partial ticks, the render stage, the alpha-test threshold, and which entity or block entity is being submitted
// right now
// A singleton rather than pipeline state because the mixins that capture these are scattered across the render
// loop and have no pipeline reference to write into
public final class CapturedRenderingState {
    public static final CapturedRenderingState INSTANCE = new CapturedRenderingState();

    private final Matrix4f gbufferModelView = new Matrix4f();
    private final Matrix4f gbufferProjection = new Matrix4f();
    private final Vector3d cameraPosition = new Vector3d();
    private final Vector3f fogColor = new Vector3f();
    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();
    private final Vector2i atlasSize = new Vector2i();
    private final Vector4f colorModulator = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private final Vector4f entityColor = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    private float tickDelta;
    // The renderStage uniform: the current WorldRenderingPhase ordinal, matching the MC_RENDER_STAGE_* macros
    // Packs switch on it to tell terrain from entities from the hand within one gbuffer program
    private int renderStage;
    private float currentAlphaTest;
    private int currentRenderedBlockEntity = -1;
    private int currentRenderedEntity = -1;
    private int currentRenderedItem = -1;
    private int textureReloadCount;

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

    public Vector4f getColorModulator() {
        return this.colorModulator;
    }

    public void setColorModulator(float red, float green, float blue, float alpha) {
        this.colorModulator.set(red, green, blue, alpha);
    }

    // OptiFine's entityColor: rgb is the tint, a is the blend factor, so a pack finishes the overlay itself with
    // mix(color.rgb, entityColor.rgb, entityColor.a)
    // It has to travel as a uniform because vanilla paints the hurt flash and the creeper charge-up with
    // fixed-function texture combiners, which a bound shader program ignores entirely — without this the red flash
    // and the white creeper simply never appear under shaders
    public Vector4f getEntityColor() {
        return this.entityColor;
    }

    public void setEntityColor(float red, float green, float blue, float alpha) {
        this.entityColor.set(red, green, blue, alpha);
    }

    public void resetEntityColor() {
        this.entityColor.set(0.0f, 0.0f, 0.0f, 0.0f);
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

    public int getCurrentRenderedItem() {
        return this.currentRenderedItem;
    }

    public void setCurrentRenderedItem(int id) {
        this.currentRenderedItem = id;
    }

    public int getTextureReloadCount() {
        return this.textureReloadCount;
    }

    public void incrementTextureReloadCount() {
        this.textureReloadCount++;
    }
}
