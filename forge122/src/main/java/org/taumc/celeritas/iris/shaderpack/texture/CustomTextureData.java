package org.taumc.celeritas.iris.shaderpack.texture;

/**
 * A parsed-but-not-yet-uploaded custom texture from a {@code texture.<stage>.<sampler>}, {@code texture.noise}, or
 * {@code customTexture.<name>} directive. Port of Iris's {@code shaderpack.texture.CustomTextureData}, minus the raw
 * (typed 1D/2D/3D/rect) variants — those are an Iris-1.6 extension no OptiFine-format 1.12.2 pack uses; the properties
 * parser warns and skips them.
 * <p>
 * Construction is Minecraft-free (bytes and names only); {@code pipeline.CustomTextureManager} turns these into GL
 * textures on the render thread.
 */
public abstract class CustomTextureData {
    private CustomTextureData() {
    }

    /** A PNG file shipped inside the pack, plus its mcmeta filtering flags. */
    public static final class PngData extends CustomTextureData {
        private final TextureFilteringData filteringData;
        private final byte[] content;

        public PngData(TextureFilteringData filteringData, byte[] content) {
            this.filteringData = filteringData;
            this.content = content;
        }

        public TextureFilteringData getFilteringData() {
            return this.filteringData;
        }

        public byte[] getContent() {
            return this.content;
        }
    }

    /**
     * The special {@code minecraft:dynamic/lightmap_1} location: the game's live lightmap texture. Resolved at bind
     * time, since the lightmap object can be recreated.
     */
    public static final class LightmapMarker extends CustomTextureData {
        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == this.getClass();
        }

        @Override
        public int hashCode() {
            return 33;
        }
    }

    /** A {@code namespace:path} resource location resolved through Minecraft's TextureManager at bind time. */
    public static final class ResourceData extends CustomTextureData {
        private final String namespace;
        private final String location;

        public ResourceData(String namespace, String location) {
            this.namespace = namespace;
            this.location = location;
        }

        /** @return the namespace of the texture; the caller is responsible for validating it. */
        public String getNamespace() {
            return this.namespace;
        }

        /** @return the path / location of the texture; the caller is responsible for validating it. */
        public String getLocation() {
            return this.location;
        }
    }
}
