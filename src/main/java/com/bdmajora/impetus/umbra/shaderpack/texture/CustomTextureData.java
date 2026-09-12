package com.bdmajora.impetus.umbra.shaderpack.texture;

// A custom texture parsed but not yet uploaded, from texture.<stage>.<sampler>, texture.noise or customTexture.<name> (port of Iris's CustomTextureData including raw typed 3D definitions); touches neither Minecraft nor GL, so parsing is off-thread and CustomTextureManager uploads later
public abstract class CustomTextureData {
    private CustomTextureData() {
    }

    // A PNG shipped inside the pack, carrying its .mcmeta blur/clamp flags alongside the bytes
    public static final class PngData extends CustomTextureData {
        private final TextureFilteringData filteringData;
        private final byte[] content;

        public PngData(TextureFilteringData filteringData, byte[] content) {
            this.filteringData = filteringData;
            this.content = content;
        }

        // blur and clamp from the .mcmeta sidecar
        public TextureFilteringData getFilteringData() {
            return this.filteringData;
        }

        // Raw file bytes
        public byte[] getContent() {
            return this.content;
        }
    }

    // The special minecraft:dynamic/lightmap_1 location meaning the live lightmap; a marker rather than a stored id since the lightmap object is recreated on reloads and brightness changes
    public static final class LightmapMarker extends CustomTextureData {
        // By content, so identical textures share one GL object
        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == this.getClass();
        }

        // Consistent with equals
        @Override
        public int hashCode() {
            return 33;
        }
    }

    // A namespace:path resource location resolved through TextureManager at bind time, since the resource pack it comes from can change
    public static final class ResourceData extends CustomTextureData {
        private final String namespace;
        private final String location;

        public ResourceData(String namespace, String location) {
            this.namespace = namespace;
            this.location = location;
        }

        // Unvalidated: a namespace no mod provides is a skipped texture, not a load failure, and the caller that can log the affected sampler decides
        public String getNamespace() {
            return this.namespace;
        }

        // Likewise unvalidated
        public String getLocation() {
            return this.location;
        }
    }

    // A raw binary texture (`image/foo.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT`); every field is explicit since the file is bare pixel data with no container
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

        // 1D, 2D or 3D
        public String getTextureType() {
            return this.textureType;
        }

        // Pack format name
        public String getInternalFormat() {
            return this.internalFormat;
        }

        // Declared width
        public int getWidth() {
            return this.width;
        }

        // Declared height
        public int getHeight() {
            return this.height;
        }

        // Declared depth, for 3D
        public int getDepth() {
            return this.depth;
        }

        // Pack client format name
        public String getPixelFormat() {
            return this.pixelFormat;
        }

        // Pack client type name
        public String getPixelType() {
            return this.pixelType;
        }

        // Raw file bytes
        public byte[] getContent() {
            return this.content;
        }
    }
}
