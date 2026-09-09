package com.bdmajora.impetus.impl.render.terrain.compile.light;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.LightUtil;
import com.bdmajora.impetus.engine.impl.model.light.DiffuseProvider;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;

import static com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing.*;

public enum VintageDiffuseProvider implements DiffuseProvider {
    INSTANCE;

    // True when the loaded pack set oldLighting=false, meaning vanilla's per-face shading must NOT be baked into
    // the vertex colour
    // A pack that derives its own lighting from the face normal (Body Camera's lightBrightness, for instance) would
    // otherwise receive vanilla's 0.5/0.6/0.8 face multiplier on top of its own and shade every face twice
    // Iris suppresses this by forcing the shade lookup to Direction.UP, OptiFine by setting its shade constants
    // to 1.0; both amount to the same "multiply by one" this flag selects below
    private static boolean directionalShadingDisabled() {
        return com.bdmajora.impetus.umbra.material.WorldRenderingSettings.shouldDisableDirectionalShading();
    }

    @Override
    public float getDiffuse(float normalX, float normalY, float normalZ, boolean shade) {
        if (!shade || directionalShadingDisabled()) {
            return 1.0f;
        }
        return LightUtil.diffuseLight(normalX, normalY, normalZ);
    }

    public static EnumFacing toEnumFacing(ModelQuadFacing facing) {
        return switch (facing) {
            case NEG_Y -> EnumFacing.DOWN;
            case POS_Y -> EnumFacing.UP;
            case NEG_Z -> EnumFacing.NORTH;
            case POS_Z -> EnumFacing.SOUTH;
            case NEG_X -> EnumFacing.WEST;
            case POS_X -> EnumFacing.EAST;
            case UNASSIGNED -> throw new IllegalArgumentException();
        };
    }

    public static ModelQuadFacing fromEnumFacing(EnumFacing facing) {
        return switch (facing) {
            case DOWN  -> NEG_Y;
            case UP    -> POS_Y;
            case NORTH -> NEG_Z;
            case SOUTH -> POS_Z;
            case WEST  -> NEG_X;
            case EAST  -> POS_X;
        };
    }

    public static ModelQuadFacing fromEnumFacingOrUnassigned(EnumFacing facing) {
        if (facing == null) {
            return UNASSIGNED;
        }
        return fromEnumFacing(facing);
    }

    @Override
    public float getDiffuse(ModelQuadFacing lightFace, boolean shade) {
        if (!shade || directionalShadingDisabled()) {
            return 1.0f;
        }
        return LightUtil.diffuseLight(toEnumFacing(lightFace));
    }
}
