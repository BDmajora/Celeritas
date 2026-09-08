package com.bdmajora.impetus.umbra.shaderpack.texture;

/**
 * A parsed-but-not-yet-uploaded custom texture from a {@code texture.<stage>.<sampler>}, {@code texture.noise}, or
 * {@code customTexture.<name>} directive. Port of Umbra's {@code shaderpack.texture.CustomTextureData}; includes the
 * raw typed texture definitions modern Umbra packs use for precomputed 3D data textures.
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

    /** A raw binary texture definition, e.g. {@code image/foo.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT}. */
    public static final class RawData extends CustomTextureData {
        private final String textureType;
        private final String internalFormat;
        private final int width;
        private final int height;
        private final int depth;
        private final String pixelFormat;
        private final String pixelType;
        private final byte[] content;

        public RawData(String textureType, String internalFormat, int width, int height, int depth,
                       String pixelFormat, String pixelType, byte[] content) {
            this.textureType = textureType;
            this.internalFormat = internalFormat;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.pixelFormat = pixelFormat;
            this.pixelType = pixelType;
            this.content = content;
        }

        public String getTextureType() {
            return this.textureType;
        }

        public String getInternalFormat() {
            return this.internalFormat;
        }

        public int getWidth() {
            return this.width;
        }

        public int getHeight() {
            return this.height;
        }

        public int getDepth() {
            return this.depth;
        }

        public String getPixelFormat() {
            return this.pixelFormat;
        }

        public String getPixelType() {
            return this.pixelType;
        }

        public byte[] getContent() {
            return this.content;
        }
    }
}
