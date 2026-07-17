package com.bdmajora.impetus.iris.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.texture.PngTexture;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.texture.CustomTextureData;
import com.bdmajora.impetus.iris.shaderpack.texture.TextureStage;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;
import com.bdmajora.impetus.lwjgl.GL13;
import com.bdmajora.impetus.lwjgl.GL30;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Owns the GL textures behind the pack's custom-texture directives and the per-stage sampler-unit overrides that make
 * programs read them — the port of Iris's {@code CustomTextureManager}, adapted to this pipeline's fixed-texture-unit
 * architecture.
 * <p>
 * Iris intercepts each program's sampler <em>bindings</em> ({@code CustomTextureSamplerInterceptor}); here every
 * sampler name has a fixed unit instead, so each directive gets a dedicated unit above the pipeline's reserved range,
 * the texture is bound there for the whole frame ({@link #bindAll}), and a program belonging to the directive's stage
 * has its sampler uniform pointed at the custom unit instead of the standard one ({@link #getOverrides}).
 * <p>
 * Directive semantics match Iris:
 * <ul>
 * <li>{@code texture.<stage>.<sampler>} overrides that sampler (and every alias of the same unit, e.g.
 * {@code gaux4}/{@code colortex7}) during the given stage only;</li>
 * <li>{@code customTexture.<name>} defines a named sampler available in every stage;</li>
 * <li>{@code texture.noise} replaces the generated noisetex (see {@link #getNoiseTextureId});</li>
 * <li>a PNG uses the pack's bytes with mcmeta filtering; a {@code namespace:path} location resolves through the
 * TextureManager at bind time (so resource reloads are safe); {@code minecraft:dynamic/lightmap_1} resolves to the
 * live lightmap.</li>
 * </ul>
 */
public class CustomTextureManager {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /** A sampler-unit override: programs of the stage point sampler uniforms at {@code unit}. */
    public static final class Override {
        /** The dedicated texture unit the custom texture is bound to. */
        public final int unit;
        /**
         * The colortex index this override shadows, or {@code -1} when the overridden sampler is not a color target.
         * Used for Iris's flip deactivation: once a composite pass has written (flipped) that buffer, later passes
         * read the pass chain's content, not the custom texture.
         */
        public final int colorTarget;

        Override(int unit, int colorTarget) {
            this.unit = unit;
            this.colorTarget = colorTarget;
        }
    }

    /** One dedicated unit and the texture to bind on it (resolved per frame — resource textures can be recreated). */
    private static final class Binding {
        final int unit;
        final int target;
        final IntSupplier texture;

        Binding(int unit, int target, IntSupplier texture) {
            this.unit = unit;
            this.target = target;
            this.texture = texture;
        }
    }

    private static final class TextureRef {
        final int target;
        final IntSupplier texture;

        TextureRef(int target, IntSupplier texture) {
            this.target = target;
            this.texture = texture;
        }
    }

    private final Map<TextureStage, Map<String, Override>> overrides = new EnumMap<>(TextureStage.class);
    private final List<Binding> bindings = new ArrayList<>();
    private final List<PngTexture> ownedTextures = new ArrayList<>();
    private final List<Integer> ownedRawTextures = new ArrayList<>();
    /** The custom noisetex, or {@code null} to keep the generated noise texture. */
    private final PngTexture noiseTexture;

    private final int firstUnit;
    private final int lastUnit;
    private int nextUnit;

    /** Cached {@code EntityRenderer.lightmapTexture} field, found by type so it works under both MCP and SRG names. */
    private static Field lightmapTextureField;

    /**
     * @param pack         the pack whose resolved custom-texture data to upload.
     * @param samplerUnits the pipeline's standard sampler-name → unit table, used to expand a directive's sampler
     *                     name to every alias of the same unit.
     * @param firstUnit    first dedicated texture unit available for custom textures.
     * @param lastUnit     last usable texture unit (inclusive); directives beyond it are skipped with an error.
     */
    public CustomTextureManager(ShaderPack pack, Map<String, Integer> samplerUnits, int firstUnit, int lastUnit) {
        this.firstUnit = firstUnit;
        this.lastUnit = lastUnit;
        this.nextUnit = firstUnit;

        PngTexture noise = null;
        CustomTextureData noiseData = pack.getCustomNoiseTexture();
        if (noiseData != null) {
            if (noiseData instanceof CustomTextureData.PngData) {
                try {
                    noise = new PngTexture((CustomTextureData.PngData) noiseData);
                    this.ownedTextures.add(noise);
                    LOGGER.info("[Iris] Using the pack's own noise texture ({}x{})", noise.getWidth(), noise.getHeight());
                } catch (IOException e) {
                    LOGGER.error("[Iris] Unable to parse the image data for the custom noise texture: {}", e.getMessage());
                }
            } else {
                LOGGER.warn("[Iris] texture.noise only supports pack PNG paths; keeping the generated noisetex");
            }
        }
        this.noiseTexture = noise;

        pack.getCustomTextureDataMap().forEach((stage, stageTextures) ->
                stageTextures.forEach((samplerName, data) ->
                        addOverride(stage, samplerName, samplerUnits, data)));

        // customTexture.<name> samplers exist in every stage, exactly like Iris passing getIrisCustomTextures()
        // to each stage's sampler setup. One texture/unit is shared by all stages.
        pack.getIrisCustomTextureDataMap().forEach((name, data) -> {
            TextureRef texture = createTexture(name, data);
            if (texture == null) {
                return;
            }
            int unit = allocateUnit(name);
            if (unit < 0) {
                return;
            }
            this.bindings.add(new Binding(unit, texture.target, texture.texture));
            Override override = new Override(unit, -1);
            for (TextureStage stage : TextureStage.values()) {
                this.overrides.computeIfAbsent(stage, s -> new LinkedHashMap<>()).put(name, override);
            }
            LOGGER.info("[Iris] Custom texture '{}' on unit {} (all stages)", name, unit);
        });
    }

    private void addOverride(TextureStage stage, String samplerName, Map<String, Integer> samplerUnits,
                             CustomTextureData data) {
        TextureRef texture = createTexture(stage + "." + samplerName, data);
        if (texture == null) {
            return;
        }
        int unit = allocateUnit(samplerName);
        if (unit < 0) {
            return;
        }
        this.bindings.add(new Binding(unit, texture.target, texture.texture));

        Map<String, Override> stageOverrides = this.overrides.computeIfAbsent(stage, s -> new LinkedHashMap<>());
        Integer standardUnit = samplerUnits.get(samplerName);
        if (standardUnit == null) {
            // Not a standard sampler name: override by name only (a pack-declared sampler of that exact name).
            stageOverrides.put(samplerName, new Override(unit, -1));
        } else {
            // Override the name and every alias sharing its unit (gaux4 <-> colortex7, composite <-> colortex3, ...),
            // matching Iris's getOverride(names...) which checks all alias names of a binding.
            int colorTarget = standardUnit < 16 ? standardUnit : -1;
            Override override = new Override(unit, colorTarget);
            for (Map.Entry<String, Integer> entry : samplerUnits.entrySet()) {
                if (entry.getValue().intValue() == standardUnit.intValue()) {
                    stageOverrides.put(entry.getKey(), override);
                }
            }
        }
        LOGGER.info("[Iris] Custom texture for sampler '{}' on unit {} during {}", samplerName, unit, stage);
    }

    private int allocateUnit(String samplerName) {
        if (this.nextUnit > this.lastUnit) {
            LOGGER.error("[Iris] Out of texture units for custom texture '{}' (units {}..{} exhausted); ignoring it",
                    samplerName, this.firstUnit, this.lastUnit);
            return -1;
        }
        return this.nextUnit++;
    }

    /** First texture unit still free after all custom-texture directives have been assigned. */
    public int getNextAvailableUnit() {
        return this.nextUnit;
    }

    /** Turns texture data into a per-frame texture-id supplier, creating/owning a GL texture for PNG data. */
    private TextureRef createTexture(String name, CustomTextureData data) {
        if (data instanceof CustomTextureData.PngData) {
            try {
                PngTexture texture = new PngTexture((CustomTextureData.PngData) data);
                this.ownedTextures.add(texture);
                int id = texture.getTextureId();
                return new TextureRef(GL11.GL_TEXTURE_2D, () -> id);
            } catch (IOException e) {
                LOGGER.error("[Iris] Unable to parse the image data for the custom texture '{}': {}", name, e.getMessage());
                return null;
            }
        }
        if (data instanceof CustomTextureData.LightmapMarker) {
            return new TextureRef(GL11.GL_TEXTURE_2D, CustomTextureManager::resolveLightmap);
        }
        if (data instanceof CustomTextureData.ResourceData) {
            CustomTextureData.ResourceData resource = (CustomTextureData.ResourceData) data;
            return new TextureRef(GL11.GL_TEXTURE_2D,
                    () -> resolveResource(resource.getNamespace(), resource.getLocation()));
        }
        if (data instanceof CustomTextureData.RawData) {
            return createRawTexture(name, (CustomTextureData.RawData) data);
        }
        LOGGER.warn("[Iris] Unsupported custom texture type for '{}'; ignoring it", name);
        return null;
    }

    private TextureRef createRawTexture(String name, CustomTextureData.RawData data) {
        try {
            int target = textureTarget(data.getTextureType());
            int internalFormat = internalFormat(data.getInternalFormat());
            int pixelFormat = pixelFormat(data.getPixelFormat());
            int pixelType = pixelType(data.getPixelType());
            int texture = LWJGL.glGenTextures();

            ByteBuffer pixels = ByteBuffer.allocateDirect(data.getContent().length).order(ByteOrder.nativeOrder());
            pixels.put(data.getContent());
            pixels.flip();

            LWJGL.glBindTexture(target, texture);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            if (target == GL12.GL_TEXTURE_3D) {
                LWJGL.glTexParameteri(target, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            }
            LWJGL.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            if (target == GL12.GL_TEXTURE_3D) {
                LWJGL.glTexImage3D(target, 0, internalFormat, data.getWidth(), data.getHeight(), data.getDepth(),
                        0, pixelFormat, pixelType, pixels);
            } else {
                LWJGL.glTexImage2D(target, 0, internalFormat, data.getWidth(), data.getHeight(),
                        0, pixelFormat, pixelType, pixels);
            }
            LWJGL.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            LWJGL.glBindTexture(target, 0);

            this.ownedRawTextures.add(texture);
            LOGGER.info("[Iris] Raw custom texture '{}' on GL texture {} ({} {}x{}x{})",
                    name, texture, data.getTextureType(), data.getWidth(), data.getHeight(), data.getDepth());
            return new TextureRef(target, () -> texture);
        } catch (IllegalArgumentException e) {
            LOGGER.error("[Iris] Unsupported raw custom texture '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private static int textureTarget(String value) {
        switch (value.toUpperCase(Locale.ROOT)) {
            case "TEXTURE_2D":
                return GL11.GL_TEXTURE_2D;
            case "TEXTURE_3D":
                return GL12.GL_TEXTURE_3D;
            default:
                throw new IllegalArgumentException("target " + value);
        }
    }

    private static int internalFormat(String value) {
        switch (value.toUpperCase(Locale.ROOT)) {
            case "R8": return GL30.GL_R8;
            case "RG8": return GL30.GL_RG8;
            case "RGB8": return GL11.GL_RGB8;
            case "RGBA8": return GL11.GL_RGBA8;
            case "R16F": return GL30.GL_R16F;
            case "RGB16F": return GL30.GL_RGB16F;
            case "RGBA16F": return GL30.GL_RGBA16F;
            case "R32F": return GL30.GL_R32F;
            case "RGBA32F": return GL30.GL_RGBA32F;
            default:
                throw new IllegalArgumentException("internal format " + value);
        }
    }

    private static int pixelFormat(String value) {
        switch (value.toUpperCase(Locale.ROOT)) {
            case "RED": return GL11.GL_RED;
            case "RG": return GL30.GL_RG;
            case "RGB": return GL11.GL_RGB;
            case "RGBA": return GL11.GL_RGBA;
            default:
                throw new IllegalArgumentException("pixel format " + value);
        }
    }

    private static int pixelType(String value) {
        switch (value.toUpperCase(Locale.ROOT)) {
            case "BYTE": return GL11.GL_BYTE;
            case "UNSIGNED_BYTE": return GL11.GL_UNSIGNED_BYTE;
            case "SHORT": return GL11.GL_SHORT;
            case "UNSIGNED_SHORT": return GL11.GL_UNSIGNED_SHORT;
            case "FLOAT": return GL11.GL_FLOAT;
            case "HALF_FLOAT": return GL30.GL_HALF_FLOAT;
            default:
                throw new IllegalArgumentException("pixel type " + value);
        }
    }

    /**
     * Resolves a {@code namespace:path} texture through the TextureManager, re-queried every frame like Iris does
     * (the texture object can be replaced on resource reloads). 1.12.2 registers its atlases without the {@code .png}
     * extension modern packs write ({@code textures/atlas/blocks.png}), so that spelling is retried without it.
     */
    private static int resolveResource(String namespace, String location) {
        ITextureObject texture = Minecraft.getMinecraft().getTextureManager()
                .getTexture(new ResourceLocation(namespace, location));
        if (texture == null && location.endsWith(".png")) {
            texture = Minecraft.getMinecraft().getTextureManager()
                    .getTexture(new ResourceLocation(namespace, location.substring(0, location.length() - 4)));
        }
        return texture != null ? texture.getGlTextureId() : TextureUtil.MISSING_TEXTURE.getGlTextureId();
    }

    /**
     * The live lightmap texture ({@code EntityRenderer.lightmapTexture}), found reflectively by field type — the
     * class has exactly one {@link DynamicTexture} field, and a type scan works under both MCP and SRG names.
     */
    private static int resolveLightmap() {
        try {
            if (lightmapTextureField == null) {
                for (Field field : EntityRenderer.class.getDeclaredFields()) {
                    if (field.getType() == DynamicTexture.class) {
                        field.setAccessible(true);
                        lightmapTextureField = field;
                        break;
                    }
                }
            }
            if (lightmapTextureField != null) {
                DynamicTexture lightmap = (DynamicTexture) lightmapTextureField.get(Minecraft.getMinecraft().entityRenderer);
                if (lightmap != null) {
                    return lightmap.getGlTextureId();
                }
            }
        } catch (ReflectiveOperationException e) {
            // fall through to the missing texture
        }
        return TextureUtil.MISSING_TEXTURE.getGlTextureId();
    }

    /** Sampler-unit overrides for one stage: sampler name (directive name + unit aliases) → override. */
    public Map<String, Override> getOverrides(TextureStage stage) {
        return this.overrides.containsKey(stage)
                ? Collections.unmodifiableMap(this.overrides.get(stage))
                : Collections.emptyMap();
    }

    /** The pack's {@code texture.noise} GL id, or {@code -1} to use the generated noise texture. */
    public int getNoiseTextureId() {
        return this.noiseTexture != null ? this.noiseTexture.getTextureId() : -1;
    }

    public boolean isEmpty() {
        return this.bindings.isEmpty();
    }

    /**
     * Binds every custom texture on its dedicated unit for the frame. All units are above the vanilla-tracked range,
     * so raw binds are correct (GlStateManager's 8-slot cache cannot address them). Leaves unit 0 active.
     */
    public void bindAll() {
        for (Binding binding : this.bindings) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + binding.unit);
            LWJGL.glBindTexture(binding.target, binding.texture.getAsInt());
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Unbinds the custom-texture units (frame teardown, mirrors the pipeline's other unit restores). */
    public void unbindAll() {
        for (Binding binding : this.bindings) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + binding.unit);
            LWJGL.glBindTexture(binding.target, 0);
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void destroy() {
        for (PngTexture texture : this.ownedTextures) {
            texture.destroy();
        }
        for (Integer texture : this.ownedRawTextures) {
            LWJGL.glDeleteTextures(texture);
        }
        this.ownedTextures.clear();
        this.ownedRawTextures.clear();
        this.bindings.clear();
        this.overrides.clear();
    }
}
